package com.example.pocketai

import android.content.Context
import java.io.File

object VisionModelManager {
    private const val MODEL_FILE = "vision-model.gguf"
    private const val PROJECTOR_FILE = "mmproj-model.gguf"

    fun modelFile(context: Context): File =
        File(context.filesDir, MODEL_FILE)

    fun projectorFile(context: Context): File =
        File(context.filesDir, PROJECTOR_FILE)

    fun isReady(context: Context): Boolean {
        val model = modelFile(context)
        val projector = projectorFile(context)

        return model.isFile &&
            projector.isFile &&
            model.length() > 0 &&
            projector.length() > 0
    }

    fun describe(
        context: Context,
        imagePath: String,
        prompt: String
    ): String {
        require(imagePath.isNotBlank()) {
            "Select an image first."
        }

        check(isReady(context)) {
            "Vision model and matching mmproj files are not installed."
        }

        return VisionEngine.describeImage(
            modelFile(context).absolutePath,
            projectorFile(context).absolutePath,
            imagePath,
            prompt
        )
    }
}
