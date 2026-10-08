cmake_minimum_required(VERSION 3.22.1)

project("termux")

add_library(termux SHARED termux.c)

target_compile_options(termux PRIVATE -std=c11 -Wall -Wextra -Werror -Os -fno-stack-protector)
target_compile_definitions(termux PRIVATE _GNU_SOURCE)
target_link_options(termux PRIVATE -Wl,--gc-sections)
target_link_libraries(termux log)
