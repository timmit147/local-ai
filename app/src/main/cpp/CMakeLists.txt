cmake_minimum_required(VERSION 3.22)
project(pocketai_vision LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt")
set(JNI_LIBS "${PREBUILT_DIR}/jniLibs/${ANDROID_ABI}")

foreach(lib ggml-base ggml-cpu ggml-opencl ggml-vulkan ggml llama llama-common mtmd)
    add_library(${lib} SHARED IMPORTED)
    set_target_properties(${lib} PROPERTIES
        IMPORTED_LOCATION "${JNI_LIBS}/lib${lib}.so"
    )
endforeach()

add_library(pocketai_vision SHARED vision_bridge.cpp)

target_include_directories(pocketai_vision PRIVATE
    "${PREBUILT_DIR}/include"
    "${PREBUILT_DIR}/include/common"
    "${PREBUILT_DIR}/include/mtmd"
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
