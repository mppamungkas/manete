# Host-side tests of libnetcapture's portable C (no JNI, no Android): built with the host compiler and run by the
# Gradle task :feature:capture:nativeHostTest (a dependency of testDebugUnitTest).
cmake_minimum_required(VERSION 3.22.1)
project(netcapture_host_tests C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)

add_executable(netcapture_host_tests
    netcapture_host_tests.c
    ${SRC}/pcap_writer.c
    ${SRC}/capture_clock.c
)
target_include_directories(netcapture_host_tests PRIVATE ${SRC})
target_compile_definitions(netcapture_host_tests PRIVATE _GNU_SOURCE)
target_compile_options(netcapture_host_tests PRIVATE -Wall -Wextra -Werror -g
    -fsanitize=address,undefined -fno-sanitize-recover=all)
target_link_options(netcapture_host_tests PRIVATE -fsanitize=address,undefined)

enable_testing()
add_test(NAME netcapture_host_tests COMMAND netcapture_host_tests)
