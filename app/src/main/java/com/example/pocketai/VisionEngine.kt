package com.example.pocketai

import android.graphics.Bitmap

object VisionEngine {
    init {
        System.loadLibrary("ggml-base")
        System.loadLibrary("ggml-cpu")
        System.loadLibrary("ggml-opencl")
        System.loadLibrary("ggml-vulkan")
        System.loadLibrary("ggml")
        System.loadLibrary("llama")
        System.loadLibrary("llama-common")
        System.loadLibrary("mtmd")
        System.loadLibrary("pocketai_vision")
    }

    external fun nativeStatus(): String

    external fun describeImage(
        modelPath: String,
        mmprojPath: String,
        imagePath: String,
        prompt: String
    ): String
}
