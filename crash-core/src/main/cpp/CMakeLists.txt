cmake_minimum_required(VERSION 3.18.1)
project(crashkit)

add_library(crashkit SHARED crashkit_native.cpp resource_hook.cpp)

find_library(log-lib log)
target_link_libraries(crashkit ${log-lib})
