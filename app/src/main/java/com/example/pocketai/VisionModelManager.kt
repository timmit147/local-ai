package com.example.pocketai

import android.content.Context
import java.io.File

object VisionModelManager {
    private const val MODEL_URL =
        "https://huggingface.co/"

    private const val MODEL_FILE = "vision-model.gguf"
    private const val PROJECTOR_FILE = "mmproj-model.gguf"

    fun modelFile(context: Context): File =
        File(context.filesDir, MODEL_FILE)

    fun projectorFile(context: Context): File =
        File(context.filesDir, PROJECTOR_FILE)

    fun isReady(context: Context): Boolean =
        modelFile(context).exists() &&
        projectorFile(context).exists() &&
        modelFile(context).length() > 0 &&
        projectorFile(context).length() > 0

    fun describe(
        context: Context,
        imagePath: String,
        prompt: String
    ): String {
        if (!isReady(context)) {
            return "Vision model files are missing. Download a compatible vision GGUF model and its matching mmproj file first."
        }

        return VisionEngine.describeImage(
            modelFile(context).absolutePath,
            projectorFile(context).absolutePath,
            imagePath,
            prompt
        )
    }
}
