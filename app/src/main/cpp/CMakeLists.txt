
cmake_minimum_required(VERSION 3.22.1)

project(pocketai_vision LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Prebuilt native libraries for ARM64 Android.
set(PREBUILT_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}"
)

# llama.cpp headers.
set(LLAMA_INCLUDE_DIR
    "${CMAKE_CURRENT_SOURCE_DIR}/include"
)

# Import the native inference libraries.
foreach(lib
    ggml-base
    ggml-cpu
    ggml-opencl
    ggml-vulkan
    ggml
    llama
    llama-common
    mtmd
)
    add_library(${lib} SHARED IMPORTED)

    set_target_properties(${lib} PROPERTIES
        IMPORTED_LOCATION
        "${PREBUILT_DIR}/lib${lib}.so"
    )
endforeach()

# Native bridge for image recognition.
add_library(pocketai_vision SHARED
    vision_bridge.cpp
)

target_include_directories(pocketai_vision PRIVATE
    "${LLAMA_INCLUDE_DIR}"
    "${LLAMA_INCLUDE_DIR}/common"
    "${LLAMA_INCLUDE_DIR}/mtmd"
)

target_link_libraries(pocketai_vision
    llama
    llama-common
    mtmd
    ggml
    ggml-base
    ggml-cpu
    ggml-opencl
    ggml-vulkan
    android
    log
    jnigraphics
)
