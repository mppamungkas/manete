# libnetcapture: native traffic capture (milestone 11, Phase 3b). See docs/decisions/002-native-capture.md.
cmake_minimum_required(VERSION 3.22.1)
project(netcapture C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_library(netcapture SHARED
    capture_clock.c
    netcapture_jni.c
    pcap_writer.c
)

target_compile_options(netcapture PRIVATE -Wall -Wextra -Werror)
# Android 15+ devices may use 16 KB pages; NDK r27 still aligns to 4 KB by default.
target_link_options(netcapture PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(netcapture PRIVATE log)
