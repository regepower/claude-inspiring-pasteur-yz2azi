# 7z extraction: the 7z decoder of the LZMA SDK 26.04 (lzma/, public domain, unchanged) + JNI glue.
cmake_minimum_required(VERSION 3.22.1)
project(sevenz C)

file(GLOB LZMA_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/lzma/*.c)
add_library(sevenz SHARED sevenz.c ${LZMA_SOURCES})
target_compile_definitions(sevenz PRIVATE Z7_PPMD_SUPPORT Z7_EXTRACT_ONLY)
# Small library: size optimisation, hidden symbols (JNIEXPORT stays visible), unused code removed
target_compile_options(sevenz PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(sevenz PRIVATE -Wl,--gc-sections)
