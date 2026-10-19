package com.example.pocketai

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ModelDownloadWorker(
    context: Context,
    parameters: WorkerParameters
) : CoroutineWorker(context, parameters) {

    companion object {
        const val TAG = "pocket-ai-model-download"

        const val MODEL_URL =
            "https://huggingface.co/bartowski/Qwen2.5-3B-Instruct-GGUF/resolve/main/Qwen2.5-3B-Instruct-Q4_K_M.gguf?download=true"

        fun modelFile(context: Context): File =
            File(context.filesDir, "models/model.gguf")
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val destination = modelFile(applicationContext)
        val partial = File(destination.parentFile, "model.gguf.part")
        var connection: HttpURLConnection? = null

        try {
            if (destination.exists() &&
                destination.length() > 100_000_000L
            ) {
                return@withContext Result.success(
                    workDataOf("path" to destination.absolutePath)
                )
            }

            destination.parentFile?.mkdirs()

            setForeground(foreground("Preparing model download", 0))

            var offset = partial.length()

            fun openConnection(start: Long): HttpURLConnection {
                return (URL(MODEL_URL).openConnection() as HttpURLConnection)
                    .apply {
                        connectTimeout = 30000
                        readTimeout = 60000
                        instanceFollowRedirects = true

                        if (start > 0L) {
                            setRequestProperty("Range", "bytes=$start-")
                        }
                    }
            }

            var active = openConnection(offset)
            connection = active
            active.connect()

            // If the server does not support resuming, restart cleanly.
            if (offset > 0L &&
                active.responseCode != HttpURLConnection.HTTP_PARTIAL
            ) {
                active.disconnect()
                partial.delete()
                offset = 0L

                active = openConnection(0L)
                connection = active
                active.connect()
            }

            val responseCode = active.responseCode

            if (responseCode !in 200..299) {
                if (responseCode == 429 || responseCode >= 500) {
                    return@withContext Result.retry()
                }

                return@withContext Result.failure(
                    workDataOf(
                        "error" to "Download server returned HTTP $responseCode"
                    )
                )
            }

            val remainingLength = active.contentLengthLong
            val totalLength = if (remainingLength >= 0L) {
                remainingLength + offset
            } else {
                -1L
            }

            var downloaded = offset
            var lastPercent = -1

            active.inputStream.use { input ->
                FileOutputStream(partial, offset > 0L)
                    .buffered()
                    .use { output ->
                        val buffer = ByteArray(256 * 1024)

                        while (true) {
                            if (isStopped) {
                                throw CancellationException(
                                    "Download cancelled"
                                )
                            }

                            val count = input.read(buffer)
                            if (count < 0) break

                            output.write(buffer, 0, count)
                            downloaded += count

                            if (totalLength > 0L) {
                                val percent = (
                                    downloaded * 100L / totalLength
                                ).toInt().coerceIn(0, 100)

                                if (percent != lastPercent) {
                                    lastPercent = percent

                                    setProgress(
                                        workDataOf("progress" to percent)
                                    )

                                    setForeground(
                                        foreground(
                                            "Downloading AI model: $percent%",
                                            percent
                                        )
                                    )
                                }
                            }
                        }
                    }
            }

            if (totalLength > 0L &&
                partial.length() != totalLength
            ) {
                throw IOException("Download incomplete; retrying")
            }

            if (partial.length() < 100_000_000L) {
                return@withContext Result.failure(
                    workDataOf("error" to "Downloaded file is too small")
                )
            }

            if (destination.exists()) destination.delete()

            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }

            setProgress(workDataOf("progress" to 100))

            Result.success(
                workDataOf("path" to destination.absolutePath)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // WorkManager retries network failures and can resume the .part file.
            Result.retry()
        } catch (e: Exception) {
            Result.failure(
                workDataOf(
                    "error" to (e.message ?: "Unknown download error")
                )
            )
        } finally {
            connection?.disconnect()
        }
    }

    private suspend fun foreground(
        message: String,
        progress: Int?
    ): ForegroundInfo {
        return WorkerNotification.createForegroundInfo(
            applicationContext,
            id,
            "Pocket AI model download",
            message,
            progress
        )
    }
}
