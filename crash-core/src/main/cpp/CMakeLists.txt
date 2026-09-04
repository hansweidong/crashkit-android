cmake_minimum_required(VERSION 3.18.1)
project(crashkit)

add_library(crashkit SHARED crashkit_native.cpp resource_hook.cpp)

find_library(log-lib log)
target_link_libraries(crashkit ${log-lib})

# Android 15+ / Play 16KB 页。NDK r27 默认仍是 4KB，必须显式对齐。
target_link_options(crashkit PRIVATE "-Wl,-z,max-page-size=16384")
