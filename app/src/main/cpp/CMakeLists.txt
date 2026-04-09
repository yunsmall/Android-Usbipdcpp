cmake_minimum_required(VERSION 3.22.1)

# set(CMAKE_ANDROID_RTTI ON CACHE)

set(VCPKG_ROOT $ENV{VCPKG_ROOT})
string(REPLACE "\\" "/" VCPKG_ROOT "${VCPKG_ROOT}")
set(CMAKE_TOOLCHAIN_FILE "${VCPKG_ROOT}/scripts/buildsystems/vcpkg.cmake")

set(ANDROID_NDK_HOME $ENV{ANDROID_NDK_HOME})
string(REPLACE "\\" "/" ANDROID_NDK_HOME "${ANDROID_NDK_HOME}")
set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "${ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")

if (ANDROID_ABI STREQUAL "x86")
    set(VCPKG_TARGET_TRIPLET "x86-android")
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(VCPKG_TARGET_TRIPLET "x64-android")
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    set(VCPKG_TARGET_TRIPLET "arm-android")
elseif (ANDROID_ABI STREQUAL "arm64-v8a")
    set(VCPKG_TARGET_TRIPLET "arm64-android")
endif ()

set(CMAKE_BUILD_TYPE RelWithDebInfo)

project("usbipdcpp_native")

set(CMAKE_CXX_STANDARD 23)

find_package(usbipdcpp CONFIG REQUIRED COMPONENTS libusb)

add_library(usbipdcpp_native SHARED
    usbipd_jni.cpp
    jni_log_callback.cpp
)

target_link_libraries(usbipdcpp_native
    usbipdcpp::usbipdcpp
    usbipdcpp::libusb
    android
    log
)
