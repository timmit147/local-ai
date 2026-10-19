package com.example.pocketai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import kotlinx.coroutines.CancellationException
import java.io.File

class AiTaskWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    companion object {
        const val TAG = "pocket-ai-ai-task"

        const val KEY_PROMPT = "prompt"
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_ANSWER = "answer"
        const val KEY_ERROR = "error"
    }

    override suspend fun doWork(): Result {
        val prompt = inputData.getString(KEY_PROMPT)
            ?: return Result.failure(
                workDataOf(KEY_ERROR to "Missing prompt")
            )

        val modelPath = inputData.getString(KEY_MODEL_PATH)
            ?: return Result.failure(
                workDataOf(KEY_ERROR to "Missing model path")
            )

        if (prompt.isBlank()) {
            return Result.failure(
                workDataOf(KEY_ERROR to "Prompt is empty")
            )
        }

        if (!File(modelPath).isFile) {
            return Result.failure(
                workDataOf(KEY_ERROR to "AI model file not found")
            )
        }

        var model: Any? = null

        try {
            setForeground(
                foreground("Starting your AI task…")
            )

            val loadedModel = Llama.loadModel(
                modelPath = modelPath,
                config = LlamaConfig(
                    contextSize = 2048,
                    threads = 4
                )
            )

            model = loadedModel

            val answer = Llama.complete(
                loadedModel,
                prompt = prompt,
                systemPrompt =
                    "You are Pocket AI, a helpful private assistant. " +
                    "Answer clearly and honestly.",
                maxTokens = 256
            ).text

            return Result.success(
                workDataOf(
                    KEY_ANSWER to answer.take(6000)
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(
                workDataOf(
                    KEY_ERROR to (
                        e.message ?: "AI generation failed"
                    ).take(1000)
                )
            )
        } finally {
            // The model is released below using the typed model
            // reference maintained by the inference library.
            // See the typed implementation immediately below.
            releaseLoadedModel(model)
        }
    }

    private suspend fun foreground(
        message: String
    ): ForegroundInfo {
        return WorkerNotification.createForegroundInfo(
            applicationContext,
            id,
            "Pocket AI is working",
            message
        )
    }

    private suspend fun releaseLoadedModel(
        model: Any?
    ) {
        if (model != null) {
            @Suppress("UNCHECKED_CAST")
            Llama.releaseModel(
                model as dev.ffmpegkit.llama.LlamaModel
            )
        }
    }
}
