cmake_minimum_required(VERSION 3.22)

# macOS: must come before project(). mGBA's own CMakeLists sets 10.6 inside its directory only
# (harmless, older objects link into a newer dylib). The jlink'ed JDK 17 needs 11.0 on arm64.
if(APPLE AND NOT CMAKE_OSX_DEPLOYMENT_TARGET)
  set(CMAKE_OSX_DEPLOYMENT_TARGET "11.0" CACHE STRING "")
endif()
project(gbalink C)
# gnu11, like scripts/build-native.sh. Spelled out because newer GCC (MSYS2) would otherwise pick gnu23.
set(CMAKE_C_STANDARD 11)
set(CMAKE_C_EXTENSIONS ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# The desktop host app's JNI library: the very same bridge.c / netlink.c / touchnav*.c / mGBA as the
# Android app (android/app/src/main/cpp/CMakeLists.txt), minus the phone-only audio and GL parts.
#   cmake -S desktop/native -B <build> -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME=<a JDK 17 home>
#   cmake --build <build>
# Result: <build>/out/libgbalink.dylib (macOS) or <build>/out/gbalink.dll (Windows, MinGW-w64).
# macOS builds the host's architecture; pass -DCMAKE_OSX_ARCHITECTURES=... for anything else.
if(NOT REPO_ROOT)
  get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../.." ABSOLUTE)
endif()
set(MGBA_DIR "${REPO_ROOT}/vendor/mgba")

# Minimal static libmgba: GBA core only, no frontends, no optional libraries. Same options as the
# Android CMakeLists.txt, so desktop and phone run the same core. (BUILD_QT / BUILD_SDL / BUILD_SHARED
# / BUILD_STATIC are already forced by LIBMGBA_ONLY; spelled out so a stray cache entry cannot change them.)
set(LIBMGBA_ONLY ON CACHE BOOL "" FORCE)
set(M_CORE_GB OFF CACHE BOOL "" FORCE)
set(ENABLE_DEBUGGERS OFF CACHE BOOL "" FORCE)
set(ENABLE_GDB_STUB OFF CACHE BOOL "" FORCE)
set(ENABLE_SCRIPTING OFF CACHE BOOL "" FORCE)
set(USE_DEBUGGERS OFF CACHE BOOL "" FORCE)
set(SKIP_GIT ON CACHE BOOL "" FORCE)
foreach(opt USE_FFMPEG USE_ZLIB USE_MINIZIP USE_PNG USE_LIBZIP USE_SQLITE3 USE_ELF USE_LUA USE_JSON_C
            USE_FREETYPE USE_LZMA USE_DISCORD_RPC USE_EDITLINE USE_EPOXY BUILD_GL BUILD_GLES2 BUILD_GLES3
            BUILD_QT BUILD_SDL BUILD_SHARED)
  set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
set(BUILD_STATIC ON CACHE BOOL "" FORCE)
add_subdirectory("${MGBA_DIR}" mgba EXCLUDE_FROM_ALL)

# TouchNav = the core (touchnav.c) + one file per screen area (touchnav_<area>.c).
file(GLOB TOUCHNAV_SOURCES CONFIGURE_DEPENDS "${REPO_ROOT}/native/touchnav*.c")
add_library(gbalink SHARED "${REPO_ROOT}/native/bridge.c" "${REPO_ROOT}/native/netlink.c" ${TOUCHNAV_SOURCES})

# JNI headers only (every call goes through the JNIEnv table, so libjvm is never linked). Not
# find_package(JNI REQUIRED): that also demands libjvm / libjawt and fails on a Homebrew keg root.
# Windows: give a CMake-style path (C:/..., not C:\... or /c/...), e.g. -DJAVA_HOME=$(cygpath -m "$JAVA_HOME").
if(NOT JAVA_HOME)
  set(JAVA_HOME "$ENV{JAVA_HOME}")
endif()
set(JNI_DIRS "")
if(JAVA_HOME)
  file(TO_CMAKE_PATH "${JAVA_HOME}" JAVA_HOME)
  if(WIN32)
    set(JNI_PLATFORM_DIR "${JAVA_HOME}/include/win32")
  elseif(APPLE)
    set(JNI_PLATFORM_DIR "${JAVA_HOME}/include/darwin")
  else()
    set(JNI_PLATFORM_DIR "${JAVA_HOME}/include/linux")
  endif()
  if(EXISTS "${JAVA_HOME}/include/jni.h")
    list(APPEND JNI_DIRS "${JAVA_HOME}/include")
    if(EXISTS "${JNI_PLATFORM_DIR}/jni_md.h")
      list(APPEND JNI_DIRS "${JNI_PLATFORM_DIR}")
    endif()
  endif()
endif()
if(NOT JNI_DIRS)
  find_package(JNI) # last resort: whatever CMake can find
  list(APPEND JNI_DIRS ${JAVA_INCLUDE_PATH} ${JAVA_INCLUDE_PATH2})
endif()
if(NOT JNI_DIRS)
  message(FATAL_ERROR "jni.h not found: pass -DJAVA_HOME=<a JDK 17 home> (or set JAVA_HOME)")
endif()
target_include_directories(gbalink PRIVATE ${JNI_DIRS}
  "${MGBA_DIR}/include" "${MGBA_DIR}/src" "${CMAKE_CURRENT_BINARY_DIR}/mgba/include" "${REPO_ROOT}/native")

# Compile with exactly the defines libmgba was built with (mGBA sets most of them with
# directory-level add_definitions, the rest on the target), or struct layouts silently differ:
# NetLink holds an mGBA Mutex (pthread_mutex_t with USE_PTHREADS on macOS, CRITICAL_SECTION on
# Windows), and bridge.c / netlink.c / touchnav*.c must all be compiled with the same ones.
get_directory_property(MGBA_DIR_DEFS DIRECTORY "${MGBA_DIR}" COMPILE_DEFINITIONS)
get_target_property(MGBA_TARGET_DEFS mgba COMPILE_DEFINITIONS)
if(NOT MGBA_TARGET_DEFS)
  set(MGBA_TARGET_DEFS "")
endif()
target_compile_definitions(gbalink PRIVATE ${MGBA_DIR_DEFS} ${MGBA_TARGET_DEFS} NDEBUG)

set(THREADS_PREFER_PTHREAD_FLAG ON)
find_package(Threads REQUIRED)
target_link_libraries(gbalink PRIVATE mgba Threads::Threads m)

# Everything lands in <build>/out, the one place the Gradle tasks and the jpackage step look.
set_target_properties(gbalink PROPERTIES
  LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out"
  RUNTIME_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}/out")

if(WIN32)
  # gbalink.dll, not libgbalink.dll (System.loadLibrary("gbalink")).
  set_target_properties(gbalink PROPERTIES PREFIX "")
  # The JVM loads the DLL by path, but Windows finds its dependencies in the exe directory, the system
  # directories and PATH, not next to it: nothing but system DLLs may be needed, so no libgcc_s_seh-1.dll
  # and no libwinpthread-1.dll.
  target_link_options(gbalink PRIVATE -static -static-libgcc)
  # winmm: timeBeginPeriod (bridge.c); shell32 ole32 uuid: mGBA's config directory lookup (config.c,
  # SHGetKnownFolderPath / CoTaskMemFree / FOLDERID_*); ws2_32 shlwapi: mGBA's own OS libraries.
  target_link_libraries(gbalink PRIVATE ws2_32 shlwapi winmm shell32 ole32 uuid)
  # mingw-w64's fesetenv (libmingwex, pulled in by the CRT) needs __mingw_fe_dfl_env, which on x86_64 is
  # only defined in the CRT_fp*.o startup objects that GCC adds to executables, not to a -shared DLL
  # ("undefined reference to `__mingw_fe_dfl_env'"). CRT_fp8.o is Windows' own default (x87 53-bit,
  # MXCSR 0x1f80), so the DLL never moves the JVM thread's floating-point state away from what Windows set.
  execute_process(COMMAND "${CMAKE_C_COMPILER}" -print-file-name=CRT_fp8.o
    OUTPUT_VARIABLE MINGW_CRT_FP8 OUTPUT_STRIP_TRAILING_WHITESPACE)
  if(EXISTS "${MINGW_CRT_FP8}")
    target_link_libraries(gbalink PRIVATE "${MINGW_CRT_FP8}")
  else()
    message(WARNING "CRT_fp8.o not found (${MINGW_CRT_FP8}): the DLL link may miss __mingw_fe_dfl_env")
  endif()
  # winpthreads' <pthread.h> comes before any mGBA header in touchnav.h. If it pulls in <windows.h>, the
  # lean variant keeps winsock.h out so the later <winsock2.h> (mgba-util/common.h) still compiles.
  target_compile_definitions(gbalink PRIVATE WIN32_LEAN_AND_MEAN)
endif()
if(APPLE)
  # Redundant (mGBA pulls in Foundation) but explicit, as scripts/build-native.sh.
  target_link_libraries(gbalink PRIVATE "-framework CoreFoundation")
endif()
