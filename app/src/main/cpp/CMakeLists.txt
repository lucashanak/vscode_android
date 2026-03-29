cmake_minimum_required(VERSION 3.22)
project(pty_helper)

add_library(pty_helper SHARED pty_helper.c)
target_link_libraries(pty_helper log)
