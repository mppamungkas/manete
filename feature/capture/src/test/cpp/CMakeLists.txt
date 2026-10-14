# Host-side tests of libnetcapture's portable C (no JNI, no Android): built with the host compiler and run by the
# Gradle task :feature:capture:nativeHostTest (a dependency of testDebugUnitTest).
cmake_minimum_required(VERSION 3.22.1)
project(netcapture_host_tests C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(SRC ${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp)
set(SANITIZE -fsanitize=address,undefined -fno-sanitize-recover=all)

add_library(zdtun_host STATIC ${SRC}/third_party/zdtun/zdtun.c ${SRC}/third_party/zdtun/utils.c)
target_compile_definitions(zdtun_host PRIVATE _LITTLE_ENDIAN NO_DEBUG)
target_compile_options(zdtun_host PRIVATE -w -g ${SANITIZE})

add_executable(netcapture_host_tests
    netcapture_host_tests.c
    ${SRC}/pcap_writer.c
    ${SRC}/capture_clock.c
)
add_executable(netcapture_vpn_tests
    netcapture_vpn_tests.c
    ${SRC}/capture_vpn.c
    ${SRC}/pcap_writer.c
    ${SRC}/capture_clock.c
)
target_link_libraries(netcapture_vpn_tests PRIVATE zdtun_host pthread)

foreach(t netcapture_host_tests netcapture_vpn_tests)
    target_include_directories(${t} PRIVATE ${SRC})
    target_compile_definitions(${t} PRIVATE _GNU_SOURCE _LITTLE_ENDIAN)
    target_compile_options(${t} PRIVATE -Wall -Wextra -Werror -g ${SANITIZE})
    target_link_options(${t} PRIVATE ${SANITIZE})
endforeach()

enable_testing()
add_test(NAME netcapture_host_tests COMMAND netcapture_host_tests)
add_test(NAME netcapture_vpn_tests COMMAND netcapture_vpn_tests)
