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
        const val KEY_DISPLAY_PROMPT = "display_prompt"
        const val KEY_MODEL_PATH = "model_path"
        const val KEY_ANSWER = "answer"
        const val KEY_ERROR = "error"
    }

    override suspend fun doWork(): Result {
        val prompt = inputData.getString(KEY_PROMPT)
            ?: return Result.failure(
                workDataOf(KEY_ERROR to "Missing prompt")
            )

        val displayPrompt = inputData.getString(KEY_DISPLAY_PROMPT)
            ?: prompt.take(2000)

        val modelPath = inputData.getString(KEY_MODEL_PATH)
            ?: return Result.failure(
                workDataOf(
                    KEY_ERROR to "Missing model path",
                    KEY_DISPLAY_PROMPT to displayPrompt
                )
            )

        if (prompt.isBlank()) {
            return Result.failure(
                workDataOf(
                    KEY_ERROR to "Prompt is empty",
                    KEY_DISPLAY_PROMPT to displayPrompt
                )
            )
        }

        if (!File(modelPath).isFile) {
            return Result.failure(
                workDataOf(
                    KEY_ERROR to "AI model file not found",
                    KEY_DISPLAY_PROMPT to displayPrompt
                )
            )
        }

        setProgress(
            workDataOf(KEY_DISPLAY_PROMPT to displayPrompt)
        )

        try {
            setForeground(foreground("Starting your AI task…"))

            val model = Llama.loadModel(
                modelPath = modelPath,
                config = LlamaConfig(
                    contextSize = 2048,
                    threads = 4
                )
            )

            val answer = try {
                Llama.complete(
                    model,
                    prompt = prompt,
                    systemPrompt =
                        "You are Pocket AI, a helpful private assistant. " +
                        "Answer clearly and honestly. " +
                        "When given image labels or extracted file text, " +
                        "use that information and don't claim to see " +
                        "details that were not provided.",
                    maxTokens = 256
                ).text
            } finally {
                Llama.releaseModel(model)
            }

            return Result.success(
                workDataOf(
                    KEY_ANSWER to answer.take(6000),
                    KEY_DISPLAY_PROMPT to displayPrompt
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(
                workDataOf(
                    KEY_ERROR to (
                        e.message ?: "AI generation failed"
                    ).take(1000),
                    KEY_DISPLAY_PROMPT to displayPrompt
                )
            )
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
}
