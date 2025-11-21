cmake_minimum_required(VERSION 3.10)
project(dummy)

# This file exists only to ensure libc++_shared.so is packaged with the app
add_library(dummy SHARED dummy.cpp)
