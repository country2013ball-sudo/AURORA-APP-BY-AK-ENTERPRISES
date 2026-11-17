cmake_minimum_required(VERSION 3.22.1)
project("aurora_native")

add_library(
    aurora_native
    SHARED
    aurora_native.cpp
)

find_library(log-lib log)

target_link_libraries(
    aurora_native
    ${log-lib}
)
