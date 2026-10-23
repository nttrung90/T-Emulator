cmake_minimum_required(VERSION 3.22.1)

project("mesen-core")

set_property(GLOBAL PROPERTY JOB_POOLS compile_pool=2 link_pool=1)
set(CMAKE_JOB_POOL_COMPILE compile_pool)
set(CMAKE_JOB_POOL_LINK link_pool)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Set ROOT_DIR to the root of the repository (5 levels up from app/src/main/cpp)
get_filename_component(ROOT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../.." ABSOLUTE)

include_directories(
    ${ROOT_DIR}
    ${ROOT_DIR}/Core
    ${ROOT_DIR}/Utilities
)

add_compile_options(-fno-char8_t -O3 -fomit-frame-pointer)

# Mesen core sources (Shared, NES, SNES, Gameboy, GBA, PCE, SMS, WS, Debugger, Netplay)
file(GLOB_RECURSE CORE_SOURCES "${ROOT_DIR}/Core/*.cpp")
file(GLOB_RECURSE UTILITIES_SOURCES "${ROOT_DIR}/Utilities/*.cpp" "${ROOT_DIR}/Utilities/*.c")
file(GLOB_RECURSE SEVENZIP_SOURCES "${ROOT_DIR}/SevenZip/*.c")
file(GLOB_RECURSE LUA_SOURCES "${ROOT_DIR}/Lua/*.c")

add_library(
    mesen-core
    SHARED
    JniBridge.cpp
    ${CORE_SOURCES}
    ${UTILITIES_SOURCES}
    ${SEVENZIP_SOURCES}
    ${LUA_SOURCES}
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)
find_library(android-lib android)
find_library(z-lib z)
find_library(aaudio-lib aaudio)

target_link_libraries(
    mesen-core
    ${log-lib}
    ${jnigraphics-lib}
    ${android-lib}
    ${z-lib}
    ${aaudio-lib}
)

set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

target_link_options(
    mesen-core
    PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384"
)
