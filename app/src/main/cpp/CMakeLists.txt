
cmake_minimum_required(VERSION 3.22)
project(pocketai_vision LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The prebuilt libraries will be placed in:
# app/src/main/jniLibs/arm64-v8a/
set(JNI_LIBS "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

# Headers will be placed in:
# app/src/main/cpp/include/
set(INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/include")

foreach(lib ggml-base ggml-cpu ggml-opencl ggml-vulkan ggml llama llama-common mtmd)
    add_library(${lib} SHARED IMPORTED)
    set_target_properties(${lib} PROPERTIES
        IMPORTED_LOCATION "${JNI_LIBS}/lib${lib}.so"
    )
endforeach()

add_library(pocketai_vision SHARED vision_bridge.cpp)

target_include_directories(pocketai_vision PRIVATE
    "${INCLUDE_DIR}"
    "${INCLUDE_DIR}/common"
    "${INCLUDE_DIR}/mtmd"
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
