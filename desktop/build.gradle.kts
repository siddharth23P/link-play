import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

plugins {
    id("org.jetbrains.kotlin.jvm")
}

// Link Play for macOS and Windows: a Swing app around the same mGBA core and NetLink code as the
// phone app. It compiles the phone's link-play package (android/.../net) unchanged, so computers and
// phones share codes and protocol and can link with each other.
version = "1.1.0"

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir("../android/app/src/main/java/com/linkplay/app/net")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// ---------------------------------------------------------------------------------------------
// Native library, run, self-test, and packaging (jlink + jpackage, no plugins).
//
//   cd android
//   ./gradlew -PdesktopOnly=true :desktop:run                  start the app from Gradle
//   ./gradlew -PdesktopOnly=true :desktop:selfTest             headless self-test (dev runtime)
//   ./gradlew -PdesktopOnly=true :desktop:packageAppImage      app image for this computer
//   ./gradlew -PdesktopOnly=true :desktop:packageDmg           macOS: build/dist/LinkPlay-<ver>-macos-<arch>.dmg
//   ./gradlew -PdesktopOnly=true :desktop:packageExe :desktop:packageZip      Windows (needs WiX 3)
//
// Handy switches: -PskipNative=true (do not build the JNI library), -PappArgs="--data /tmp/x" for run,
// -PjpackageVerbose=true. The jlink and jpackage in use are the ones of the JDK that runs Gradle
// (JAVA_HOME): use JDK 17 (CI uses Temurin 17).
// ---------------------------------------------------------------------------------------------

val appName = "Link Play"
val appVendor = "Link Play"
val appDescription = "Game Boy Advance emulator with link cable play"
val appCopyright = "Link Play contributors (MIT licence)"
val appVersion = version.toString()
val appMainClass = "com.linkplay.desktop.MainKt"
val appMainJar = "linkplay-desktop.jar"
val macBundleId = "io.gbalink.desktop"
// Pinned for good: Windows uses it to recognise an older Link Play and upgrade it in place.
val winUpgradeUuid = "6bab9a44-94ec-4183-ae73-591380552223"
val nativeLibName = "gbalink"

val osName = System.getProperty("os.name").lowercase(Locale.ROOT)
val isMac = osName.contains("mac")
val isWindows = osName.contains("windows")
val hostArch = when (System.getProperty("os.arch").lowercase(Locale.ROOT)) {
    "aarch64", "arm64" -> "arm64"
    "x86_64", "amd64" -> "x64"
    else -> System.getProperty("os.arch")
}
val platformName = if (isMac) "macos" else if (isWindows) "windows" else "linux"

val jdkHome = File(System.getProperty("java.home"))
val repoDir: File = layout.projectDirectory.dir("..").asFile.canonicalFile
val packagingDir = layout.projectDirectory.dir("packaging").asFile
val nativeSrcDir = layout.projectDirectory.dir("native").asFile
val nativeBuildDir = layout.buildDirectory.dir("native-cmake").get().asFile
val nativeOutDir = File(nativeBuildDir, "out")
val appInputDir = layout.buildDirectory.dir("app-input").get().asFile
val runtimeDir = layout.buildDirectory.dir("jlink-runtime").get().asFile
val appImageRoot = layout.buildDirectory.dir("app-image").get().asFile
val jpackageTmpRoot = layout.buildDirectory.dir("jpackage-tmp").get().asFile
val distDir = layout.buildDirectory.dir("dist").get().asFile

val skipNative = providers.gradleProperty("skipNative").orNull == "true"
val jpackageVerbose = providers.gradleProperty("jpackageVerbose").orNull == "true"

/** The app image jpackage writes: "Link Play.app" on macOS, a "Link Play" folder elsewhere. */
val appImageDir = File(appImageRoot, if (isMac) "$appName.app" else appName)
val nativeLibFile = File(nativeOutDir, System.mapLibraryName(nativeLibName))
val iconFile = when {
    isMac -> File(packagingDir, "macos/LinkPlay.icns")
    isWindows -> File(packagingDir, "windows/LinkPlay.ico")
    else -> File(layout.projectDirectory.asFile, "src/main/resources/icons/linkplay-512.png")
}

fun jdkTool(name: String): File {
    val f = File(jdkHome, "bin/" + name + if (isWindows) ".exe" else "")
    if (!f.isFile) {
        throw GradleException(
            "Cannot find $name in ${jdkHome.path}. Run Gradle with a full JDK 17 (set JAVA_HOME), not a JRE."
        )
    }
    return f
}

fun findOnPath(name: String): File? {
    val suffixes = if (isWindows) listOf(".exe", ".cmd", ".bat") else listOf("")
    return (System.getenv("PATH") ?: "").split(File.pathSeparator)
        .filter { it.isNotBlank() }
        .flatMap { dir -> suffixes.map { File(dir, name + it) } }
        .firstOrNull { it.isFile && it.canExecute() }
}

/** Splits -PappArgs="--data /tmp/x --cli-host 'a b.gba'" on spaces, honouring single and double quotes. */
fun splitArgs(s: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quote = 0.toChar()
    var any = false
    for (c in s) {
        when {
            quote != 0.toChar() -> if (c == quote) quote = 0.toChar() else cur.append(c)
            c == '"' || c == '\'' -> { quote = c; any = true }
            c.isWhitespace() -> if (any || cur.isNotEmpty()) { out += cur.toString(); cur.clear(); any = false }
            else -> cur.append(c)
        }
    }
    if (any || cur.isNotEmpty()) out += cur.toString()
    return out
}

val appArgs: List<String> = providers.gradleProperty("appArgs").map { splitArgs(it) }.getOrElse(emptyList())

// ---- Native library (CMake) -----------------------------------------------------------------

val nativeConfigure = tasks.register<Exec>("nativeConfigure") {
    group = "native"
    description = "Configures the desktop JNI library (desktop/native/CMakeLists.txt) with CMake."
    onlyIf { !skipNative }
    doNotTrackState("CMake keeps its own state")
    val ninja = findOnPath("ninja")
    doFirst {
        if (findOnPath("cmake") == null) {
            throw GradleException("cmake was not found on PATH. Install CMake 3.22 or newer (macOS: brew install cmake; Windows: MSYS2 UCRT64 pacman -S mingw-w64-ucrt-x86_64-cmake).")
        }
        if (!File(nativeSrcDir, "CMakeLists.txt").isFile) {
            throw GradleException("desktop/native/CMakeLists.txt is missing. Pass -PskipNative=true to build without the native library.")
        }
        // CMake refuses to reuse a build folder made with another generator, so start over in that case.
        val cache = File(nativeBuildDir, "CMakeCache.txt")
        if (cache.isFile) {
            val old = cache.readLines().firstOrNull { it.startsWith("CMAKE_GENERATOR:INTERNAL=") }?.substringAfter('=')
            val usesNinja = old == "Ninja"
            if (old != null && usesNinja != (ninja != null)) nativeBuildDir.deleteRecursively()
        }
    }
    val args = mutableListOf("cmake", "-S", nativeSrcDir.path, "-B", nativeBuildDir.path)
    if (ninja != null) args += listOf("-G", "Ninja")
    args += "-DCMAKE_BUILD_TYPE=Release"
    args += "-DJAVA_HOME=" + jdkHome.path.replace('\\', '/')
    if (isMac) args += "-DCMAKE_OSX_DEPLOYMENT_TARGET=11.0"
    commandLine(args)
}

val nativeBuild = tasks.register<Exec>("nativeBuild") {
    group = "native"
    description = "Builds libgbalink (desktop/native) into build/native-cmake/out. -PskipNative=true skips it."
    dependsOn(nativeConfigure)
    onlyIf { !skipNative }
    doNotTrackState("CMake keeps its own state")
    commandLine("cmake", "--build", nativeBuildDir.path, "--parallel", Runtime.getRuntime().availableProcessors().toString())
    doLast {
        if (!nativeLibFile.isFile) {
            throw GradleException("CMake finished but ${nativeLibFile.path} does not exist.")
        }
        logger.lifecycle("Native library: ${nativeLibFile.path}")
    }
}

// ---- Run and self-test from Gradle (dev runtime, not jlinked) ---------------------------------

fun JavaExec.devSetup() {
    dependsOn(nativeBuild)
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(appMainClass)
    jvmArgs("-Djava.library.path=${nativeOutDir.path}", "-Dfile.encoding=UTF-8")
    if (isMac) jvmArgs("-Xdock:name=$appName", "-Xdock:icon=${iconFile.path}")
}

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Starts Link Play. Pass arguments with --args='...' or -PappArgs='...'."
    devSetup()
    args(appArgs)
    standardInput = System.`in`
}

val selfTestReport = layout.buildDirectory.file("selftest/report.txt").get().asFile

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "Runs Main --selftest with the dev runtime and fails on any FAIL line in the report."
    devSetup()
    doFirst { selfTestReport.parentFile.mkdirs(); selfTestReport.delete() }
    args("--selftest", selfTestReport.path)
    doLast {
        if (!selfTestReport.isFile) throw GradleException("The self-test wrote no report (${selfTestReport.path}).")
        val lines = selfTestReport.readLines()
        lines.forEach { logger.lifecycle(it) }
        if (lines.any { it.trimStart().startsWith("FAIL") }) throw GradleException("Self-test reported FAIL lines.")
        if (lines.none { it.trimStart().startsWith("PASS") }) throw GradleException("Self-test reported no PASS lines.")
    }
}

// ---- Packaging --------------------------------------------------------------------------------

// What the app needs from the JDK even if jdeps sees less: Swing, java.util.prefs, logging.
val baseModules = listOf("java.base", "java.desktop", "java.logging", "java.prefs")

val stageAppInput = tasks.register<Sync>("stageAppInput") {
    group = "packaging"
    description = "Collects everything jpackage puts in the app folder: jar, Kotlin runtime, JNI library, licences."
    dependsOn(nativeBuild)
    into(appInputDir)
    from(tasks.jar) { rename { appMainJar } }
    from(configurations.runtimeClasspath)
    from(nativeOutDir) { include(System.mapLibraryName(nativeLibName)) }
    from(File(repoDir, "LICENSE"))
    from(File(repoDir, "THIRD_PARTY_NOTICES.md"))
    from(File(repoDir, "vendor/mgba/LICENSE")) { rename { "LICENSE-mGBA.txt" } }
    from(File(repoDir, "vendor/mgba/src/third-party/inih/LICENSE.txt")) { rename { "LICENSE-inih.txt" } }
    doLast {
        if (!File(appInputDir, System.mapLibraryName(nativeLibName)).isFile) {
            val why = if (skipNative) "-PskipNative=true was given" else "the native build produced none"
            logger.warn("WARNING: no ${System.mapLibraryName(nativeLibName)} in the app folder ($why). The app will start but cannot emulate.")
        }
    }
}

val jlinkRuntime = tasks.register<Exec>("jlinkRuntime") {
    group = "packaging"
    description = "Builds a small Java runtime (jlink) with the modules jdeps finds in the app jar and Kotlin stdlib."
    dependsOn(tasks.jar)
    val jarFile = tasks.jar.flatMap { it.archiveFile }
    val libs = configurations.runtimeClasspath
    inputs.files(jarFile, libs)
    inputs.property("baseModules", baseModules)
    outputs.dir(runtimeDir)
    doFirst {
        val libPath = libs.get().files.joinToString(File.pathSeparator) { it.path }
        val found = providers.exec {
            commandLine(
                jdkTool("jdeps").path, "--print-module-deps", "--ignore-missing-deps", "--multi-release", "17",
                "-cp", libPath, jarFile.get().asFile.path,
            )
        }.standardOutput.asText.get().trim().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val modules = (baseModules + found).distinct().sorted()
        val extra = modules - baseModules.toSet()
        logger.lifecycle("jdeps found: ${found.joinToString(",")}")
        if (extra.isNotEmpty()) logger.lifecycle("jdeps asked for more than the base list: ${extra.joinToString(",")}")
        runtimeDir.deleteRecursively()
        commandLine(
            jdkTool("jlink").path,
            "--add-modules", modules.joinToString(","),
            "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=2",
            "--output", runtimeDir.path,
        )
    }
    doLast {
        val javaBin = File(runtimeDir, "bin/" + if (isWindows) "java.exe" else "java")
        val listed = providers.exec { commandLine(javaBin.path, "--list-modules") }.standardOutput.asText.get()
        logger.lifecycle("Runtime modules: " + listed.lines().filter { it.isNotBlank() }.joinToString(", ") { it.substringBefore('@') })
    }
}

/** Arguments every jpackage run shares. */
fun jpackageBase(type: String, dest: File, tmpName: String): List<String> {
    val tmp = File(jpackageTmpRoot, tmpName)
    tmp.deleteRecursively()
    dest.mkdirs()
    val args = mutableListOf(
        jdkTool("jpackage").path,
        "--type", type,
        "--name", appName,
        "--app-version", appVersion,
        "--vendor", appVendor,
        "--description", appDescription,
        "--copyright", appCopyright,
        "--icon", iconFile.path,
        "--dest", dest.path,
        "--temp", tmp.path,
    )
    if (jpackageVerbose) args += "--verbose"
    return args
}

fun checkAppVersion() {
    // jpackage wants plain dotted numbers (Windows: major and minor at most 255).
    if (!Regex("""[1-9]\d{0,2}\.\d{1,3}\.\d{1,5}""").matches(appVersion)) {
        throw GradleException("Version '$appVersion' is not usable for jpackage; use 1.2.3 style numbers.")
    }
}

val requireMac = tasks.register("requireMac") {
    group = "packaging"
    description = "Fails unless this is a Mac."
    doFirst { if (!isMac) throw GradleException("packageDmg needs macOS (hdiutil and a Mac JDK). Run it on a Mac, or use the GitHub Actions workflow.") }
}

val requireWindows = tasks.register("requireWindows") {
    group = "packaging"
    description = "Fails unless this is Windows."
    doFirst { if (!isWindows) throw GradleException("packageExe and packageZip need Windows (WiX 3 and a Windows JDK). Run them on Windows, or use the GitHub Actions workflow.") }
}

// Wrong-OS requests should fail at once, before any slow work.
val packageAppImage = tasks.register<Exec>("packageAppImage") {
    mustRunAfter(requireMac, requireWindows)
    group = "packaging"
    description = "Builds the app image (macOS: Link Play.app, Windows: Link Play folder) in build/app-image."
    dependsOn(stageAppInput, jlinkRuntime)
    inputs.dir(appInputDir)
    inputs.dir(runtimeDir)
    inputs.dir(File(packagingDir, "macos"))
    inputs.file(iconFile)
    inputs.property("version", appVersion)
    outputs.dir(appImageRoot)
    doFirst {
        checkAppVersion()
        appImageRoot.deleteRecursively()
        val args = jpackageBase("app-image", appImageRoot, "app-image").toMutableList()
        args += listOf(
            "--input", appInputDir.path,
            "--main-jar", appMainJar,
            "--main-class", appMainClass,
            "--runtime-image", runtimeDir.path,
            // jpackage fills in $APPDIR itself: the JNI library sits next to the jar.
            "--java-options", "-Djava.library.path=\$APPDIR",
            "--java-options", "-Dfile.encoding=UTF-8",
        )
        if (isMac) {
            args += listOf(
                "--mac-package-identifier", macBundleId,
                "--mac-package-name", appName,
                // Info.plist there replaces jpackage's own (local network text, 11.0 floor, no microphone key).
                "--resource-dir", File(packagingDir, "macos").path,
            )
        }
        commandLine(args)
    }
    doLast {
        if (!appImageDir.isDirectory) throw GradleException("jpackage did not create ${appImageDir.path}")
        logger.lifecycle("App image: ${appImageDir.path}")
    }
}

val packageDmg = tasks.register<Exec>("packageDmg") {
    group = "packaging"
    description = "macOS: build/dist/LinkPlay-<version>-macos-<arm64|x64>.dmg from the app image."
    dependsOn(requireMac, packageAppImage)
    val out = File(distDir, "LinkPlay-$appVersion-macos-$hostArch.dmg")
    inputs.dir(appImageRoot)
    outputs.file(out)
    doFirst {
        val tmpOut = File(jpackageTmpRoot, "dmg-out")
        tmpOut.deleteRecursively()
        val args = jpackageBase("dmg", tmpOut, "dmg").toMutableList()
        args += listOf(
            "--app-image", appImageDir.path,
            "--mac-package-identifier", macBundleId,
            "--mac-package-name", appName,
            "--resource-dir", File(packagingDir, "macos").path,
        )
        commandLine(args)
    }
    doLast {
        val made = File(jpackageTmpRoot, "dmg-out/$appName-$appVersion.dmg")
        if (!made.isFile) throw GradleException("jpackage did not create ${made.path}")
        distDir.mkdirs()
        Files.move(made.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("DMG: ${out.path}")
    }
}

// The Windows installer's licence page needs RTF; this is the MIT licence plus a pointer to the notices.
val windowsLicenseRtf = tasks.register("windowsLicenseRtf") {
    group = "packaging"
    description = "Writes build/windows/LinkPlay-license.rtf for the installer."
    val src = File(repoDir, "LICENSE")
    val out = layout.buildDirectory.file("windows/LinkPlay-license.rtf").get().asFile
    inputs.file(src)
    outputs.file(out)
    doLast {
        fun esc(s: String) = buildString {
            for (c in s.replace("\r", "")) {
                when {
                    c == '\\' -> append("\\\\")
                    c == '{' -> append("\\{")
                    c == '}' -> append("\\}")
                    c == '\n' -> append("\\par\n")
                    c.code > 126 -> append("\\u${c.code.toShort()}?")
                    else -> append(c)
                }
            }
        }
        val note = "Link Play also contains the mGBA emulator core (MPL-2.0), a Java runtime (OpenJDK, GPLv2 with " +
            "Classpath Exception) and the Kotlin standard library (Apache-2.0). Their notices are in " +
            "THIRD_PARTY_NOTICES.md in the installation folder. No games are included: use ROMs you have the right to use."
        out.parentFile.mkdirs()
        out.writeText("{\\rtf1\\ansi\\deff0{\\fonttbl{\\f0 Segoe UI;}}\\fs20 " + esc(src.readText()) + "\\par\n\\par\n" + esc(note) + "\n}")
    }
}

val packageExe = tasks.register<Exec>("packageExe") {
    group = "packaging"
    description = "Windows: build/dist/LinkPlay-<version>-windows-x64.exe installer (needs WiX 3 on PATH)."
    dependsOn(requireWindows, packageAppImage, windowsLicenseRtf)
    val out = File(distDir, "LinkPlay-$appVersion-windows-x64.exe")
    inputs.dir(appImageRoot)
    inputs.file(windowsLicenseRtf.map { it.outputs.files.singleFile })
    outputs.file(out)
    doFirst {
        // jpackage also looks in "Program Files\\WiX Toolset v3*" by itself, so this is only a heads-up.
        val wixDirs = listOf("ProgramFiles", "ProgramFiles(x86)").mapNotNull { System.getenv(it) }
            .flatMap { File(it).listFiles { f -> f.name.startsWith("WiX Toolset v3") }?.toList().orEmpty() }
        if (findOnPath("candle") == null && wixDirs.none { File(it, "bin/candle.exe").isFile }) {
            logger.warn("WARNING: WiX 3 (candle.exe, light.exe) was not found. JDK 17 jpackage needs WiX 3.x: choco install wixtoolset")
        }
        val tmpOut = File(jpackageTmpRoot, "exe-out")
        tmpOut.deleteRecursively()
        val args = jpackageBase("exe", tmpOut, "exe").toMutableList()
        args += listOf(
            "--app-image", appImageDir.path,
            "--win-menu", "--win-menu-group", appName,
            "--win-shortcut", "--win-dir-chooser", "--win-per-user-install",
            "--win-upgrade-uuid", winUpgradeUuid,
            "--license-file", windowsLicenseRtf.get().outputs.files.singleFile.path,
        )
        commandLine(args)
    }
    doLast {
        val made = File(jpackageTmpRoot, "exe-out/$appName-$appVersion.exe")
        if (!made.isFile) throw GradleException("jpackage did not create ${made.path}")
        distDir.mkdirs()
        Files.move(made.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("Installer: ${out.path}")
    }
}

val packageZip = tasks.register<Zip>("packageZip") {
    group = "packaging"
    description = "Windows: build/dist/LinkPlay-<version>-windows-x64.zip, the portable app folder (no installer, no WiX)."
    dependsOn(requireWindows, packageAppImage)
    archiveFileName.set("LinkPlay-$appVersion-windows-x64.zip")
    destinationDirectory.set(distDir)
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    from(appImageRoot) { include("$appName/**") }
}
