# Desktop packaging

Everything that turns `desktop/` into a macOS `.dmg` and a Windows installer and zip. The tasks live in
`desktop/build.gradle.kts`; CI is `.github/workflows/build.yml`.

| Path | What |
| --- | --- |
| `icons/IconGen.java`, `icons/make-icons.sh` | Draws the launcher icon with Java2D from the Android icon geometry and writes every icon file below. Needs a JDK; the `.icns` step needs a Mac (`iconutil`). Run `sh desktop/packaging/icons/make-icons.sh` and commit the results. |
| `macos/LinkPlay.icns` | App and dmg icon (generated). |
| `macos/Info.plist` | Replaces the `Info.plist` jpackage writes (passed with `--resource-dir`). |
| `windows/LinkPlay.ico` | Installer, exe and shortcut icon (generated, 16 to 256 px). |
| `windows/check-dll-deps.sh` | CI check: `gbalink.dll` may import Windows system DLLs only. |
| `../src/main/resources/icons/linkplay-*.png` | Window and taskbar icons (generated). |

## Info.plist

`macos/Info.plist` starts from jpackage's own template (JDK 17, `Info-lite.plist.template`). The `DEPLOY_*`
words are filled in by jpackage from `--name`, `--app-version`, `--mac-package-identifier` and so on, so
the version always matches `version` in `desktop/build.gradle.kts`. What differs from the default:

- `LSMinimumSystemVersion` is 11.0 (jpackage says 10.11; the arm64 Java runtime needs 11.0 anyway).
- `NSLocalNetworkUsageDescription`: macOS 15 and later ask before an app talks to devices on the home
  network, and this is the text of that question.
- `NSHighResolutionCapable` is a real boolean (jpackage writes the string "true").
- `NSAppSleepDisabled`: a linked game has to keep running while its window is behind other windows.
- The category is Games, and there is no `NSMicrophoneUsageDescription` (the app never records sound).

## Tasks

```bash
cd android
./gradlew -PdesktopOnly=true :desktop:packageAppImage    # build/app-image/Link Play.app (or the Link Play folder on Windows)
./gradlew -PdesktopOnly=true :desktop:packageDmg         # macOS: build/dist/LinkPlay-<version>-macos-<arm64|x64>.dmg
./gradlew -PdesktopOnly=true :desktop:packageExe :desktop:packageZip   # Windows, needs WiX 3
```

The app image is also what `packageDmg` and `packageExe` wrap, so the self-test in CI runs on the exact
files that ship. The jpackage working folders are kept in `desktop/build/jpackage-tmp`.

## Not signed

macOS gets jpackage's ad-hoc signature (needed so arm64 code runs at all), not a Developer ID, and the
Windows files carry no signature. Notarizing or signing later means `--mac-sign` plus entitlements for the
JIT (`com.apple.security.cs.allow-jit`, `allow-unsigned-executable-memory`, `disable-library-validation`), and
a code-signing certificate on Windows.
