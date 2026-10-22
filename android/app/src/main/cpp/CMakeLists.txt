cmake_minimum_required(VERSION 3.22)
project(gbalink C)

# Shared sources live in the repo, not copied: the desktop host and the app
# build the very same mGBA and NetLink code.
get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)
set(MGBA_DIR "${REPO_ROOT}/vendor/mgba")

# Minimal static libmgba: GBA core only, no frontends, no optional libraries.
set(LIBMGBA_ONLY ON CACHE BOOL "" FORCE)
set(M_CORE_GB OFF CACHE BOOL "" FORCE)
set(ENABLE_DEBUGGERS OFF CACHE BOOL "" FORCE)
set(ENABLE_GDB_STUB OFF CACHE BOOL "" FORCE)
set(ENABLE_SCRIPTING OFF CACHE BOOL "" FORCE)
set(USE_DEBUGGERS OFF CACHE BOOL "" FORCE)
set(SKIP_GIT ON CACHE BOOL "" FORCE)
foreach(opt USE_FFMPEG USE_ZLIB USE_MINIZIP USE_PNG USE_LIBZIP USE_SQLITE3 USE_ELF USE_LUA USE_JSON_C
            USE_FREETYPE USE_LZMA USE_DISCORD_RPC USE_EDITLINE USE_EPOXY BUILD_GL BUILD_GLES2 BUILD_GLES3)
  set(${opt} OFF CACHE BOOL "" FORCE)
endforeach()
add_subdirectory("${MGBA_DIR}" mgba EXCLUDE_FROM_ALL)

# TouchNav = the core (touchnav.c) + one file per screen area (touchnav_<area>.c).
file(GLOB TOUCHNAV_SOURCES CONFIGURE_DEPENDS "${REPO_ROOT}/native/touchnav*.c")
add_library(gbalink SHARED bridge.c render.c "${REPO_ROOT}/native/netlink.c" ${TOUCHNAV_SOURCES})
target_include_directories(gbalink PRIVATE
  "${MGBA_DIR}/include" "${MGBA_DIR}/src" "${CMAKE_CURRENT_BINARY_DIR}/mgba/include" "${REPO_ROOT}/native")

# Compile with exactly the defines libmgba was built with (mGBA sets most of them
# with directory-level add_definitions), or struct layouts silently differ.
get_directory_property(MGBA_DIR_DEFS DIRECTORY "${MGBA_DIR}" COMPILE_DEFINITIONS)
get_target_property(MGBA_TARGET_DEFS mgba COMPILE_DEFINITIONS)
if(NOT MGBA_TARGET_DEFS)
  set(MGBA_TARGET_DEFS "")
endif()
target_compile_definitions(gbalink PRIVATE ${MGBA_DIR_DEFS} ${MGBA_TARGET_DEFS} NDEBUG)

target_link_libraries(gbalink PRIVATE mgba aaudio GLESv3 EGL android log m)
