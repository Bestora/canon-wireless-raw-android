cmake_minimum_required(VERSION 3.22)
project(canonraw C)

set(PTP_USE_LIBUSB OFF CACHE BOOL "" FORCE)
set(PTP_NO_USB ON CACHE BOOL "" FORCE)
add_subdirectory(libpict)

add_library(ptpjni SHARED ptp_jni.c)
target_include_directories(ptpjni PRIVATE libpict/src)
target_link_libraries(ptpjni libpict log)
# 16 KB page alignment: Android 15+ devices with 16 KB pages otherwise warn on every launch.
target_link_options(ptpjni PRIVATE "-Wl,-z,max-page-size=16384")
