cmake_minimum_required(VERSION 3.22)

# The JNI bridge between Box3D and MineBox3D's Java API. Box3D is a git submodule
# (native/box3d) linked statically into one shared library per platform, so a player installs
# exactly one native file per OS/architecture and nothing else.
project(minebox3d_native VERSION 0.1.0 LANGUAGES C CXX)

# JDK headers only: a JNI library calls the VM through JNIEnv and never links against jvm.
set(MB3_JNI_INCLUDE "" CACHE PATH "The JDK's include directory (the one holding jni.h)")
if(NOT EXISTS "${MB3_JNI_INCLUDE}/jni.h")
    message(FATAL_ERROR "MB3_JNI_INCLUDE must point at a JDK include directory, got '${MB3_JNI_INCLUDE}'")
endif()
if(WIN32)
    set(MB3_JNI_PLATFORM_INCLUDE "${MB3_JNI_INCLUDE}/win32")
elseif(APPLE)
    set(MB3_JNI_PLATFORM_INCLUDE "${MB3_JNI_INCLUDE}/darwin")
else()
    set(MB3_JNI_PLATFORM_INCLUDE "${MB3_JNI_INCLUDE}/linux")
endif()

set(MB3_OUTPUT_DIR "" CACHE PATH "Where the built library is placed")
if(MB3_OUTPUT_DIR STREQUAL "")
    set(MB3_OUTPUT_DIR "${CMAKE_BINARY_DIR}/out")
endif()

set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ⚠️ Minecraft worlds are 60 million blocks across. A float position has a 2-block step at the
# border and a 1-block step at the coordinates GameTests use, so the world position boundary is
# double (Box3D's large world mode: only positions are double, everything inside a body stays
# float, docs/large_worlds.md).
set(BOX3D_DOUBLE_PRECISION ON CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# The submodule is our fork's `minebox3d` branch (docs/design/02-box3d-fork.md).
add_subdirectory(box3d box3d EXCLUDE_FROM_ALL)

# One world per level per side plus one per simulation that wants its own (a ragdoll that only
# poses, a preview in an editor): Box3D's default of 128 is a compile-time array size.
target_compile_definitions(box3d PUBLIC B3_MAX_WORLDS=1024)
# Assertions stay on in the shipped library. A failed assertion is routed to the bridge, which
# turns it into a Java exception instead of a debugger break that would take the game down.
target_compile_definitions(box3d PUBLIC B3_ENABLE_ASSERT)

file(GLOB MB3_SOURCES CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/src/*.c")
add_library(minebox3d SHARED ${MB3_SOURCES})
target_compile_features(minebox3d PRIVATE c_std_17)
target_include_directories(minebox3d PRIVATE "${MB3_JNI_INCLUDE}" "${MB3_JNI_PLATFORM_INCLUDE}" "${CMAKE_CURRENT_SOURCE_DIR}/src")
target_link_libraries(minebox3d PRIVATE box3d::box3d)
set_target_properties(minebox3d PROPERTIES
    OUTPUT_NAME "minebox3d"
    C_VISIBILITY_PRESET hidden
    # $<1:> keeps multi-config generators (Visual Studio, Xcode) from appending a config folder
    LIBRARY_OUTPUT_DIRECTORY "$<1:${MB3_OUTPUT_DIR}>"
    RUNTIME_OUTPUT_DIRECTORY "$<1:${MB3_OUTPUT_DIR}>"
)
if(MSVC)
    # One self-contained file: no dependency on the player having the VC++ redistributable.
    set_property(TARGET minebox3d box3d PROPERTY MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    target_compile_options(minebox3d PRIVATE /W4)
else()
    target_compile_options(minebox3d PRIVATE -Wall -Wextra)
endif()
