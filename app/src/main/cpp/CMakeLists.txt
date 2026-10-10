# Native part of DualFiles: 7z extraction (7z decoder of the LZMA SDK 26.04 in lzma/, public domain,
# unchanged), fast folder listing, EXIF for WebP conversion.
cmake_minimum_required(VERSION 3.22.1)
project(dualfiles C)

file(GLOB LZMA_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/lzma/*.c)
add_library(dualfiles SHARED sevenz.c fsdir.c media.c ${LZMA_SOURCES})
target_compile_definitions(dualfiles PRIVATE Z7_PPMD_SUPPORT Z7_EXTRACT_ONLY)
# Small library: size optimisation, hidden symbols (JNIEXPORT stays visible), unused code removed
target_compile_options(dualfiles PRIVATE -Os -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(dualfiles PRIVATE -Wl,--gc-sections)
