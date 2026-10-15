# libnetcapture: native traffic capture (milestone 11, Phase 3b). See docs/decisions/002-native-capture.md.
cmake_minimum_required(VERSION 3.22.1)
project(netcapture C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

# zdtun (LGPL v3+, unmodified, see third_party/zdtun/README.md): built with its own flags, without -Werror.
add_library(zdtun STATIC
    third_party/zdtun/zdtun.c
    third_party/zdtun/utils.c
)
target_compile_definitions(zdtun PRIVATE ANDROID _LITTLE_ENDIAN)
target_compile_options(zdtun PRIVATE -Wno-unused-variable -Wno-unused-function -ffunction-sections -fdata-sections
    -fvisibility=hidden)
target_link_libraries(zdtun PRIVATE log)

add_library(netcapture SHARED
    capture_clock.c
    capture_vpn.c
    netcapture_jni.c
    pcap_writer.c
)

target_compile_definitions(netcapture PRIVATE _LITTLE_ENDIAN)
# Only the JNIEXPORT functions are exported; everything else can be dropped by --gc-sections.
target_compile_options(netcapture PRIVATE -Wall -Wextra -Werror -fvisibility=hidden -ffunction-sections -fdata-sections)
# Android 15+ devices may use 16 KB pages; NDK r27 still aligns to 4 KB by default.
# --gc-sections drops zdtun's unused helpers (its own command-line tools' socket and shell utilities).
target_link_options(netcapture PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections")
target_link_libraries(netcapture PRIVATE zdtun log)
