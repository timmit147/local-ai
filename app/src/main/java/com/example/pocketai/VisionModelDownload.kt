package com.example.pocketai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object VisionModelDownload {

    suspend fun download(
        context: Context,
        modelUrl: String,
        projectorUrl: String,
        onProgress: (String) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val model = File(context.filesDir, "vision-model.gguf")
            val projector = File(context.filesDir, "mmproj-model.gguf")

            downloadFile(modelUrl, model) { onProgress("Vision model: $it%") }
            downloadFile(projectorUrl, projector) {
                onProgress("Vision projector: $it%")
            }

            require(model.length() > 0) { "Downloaded model is empty." }
            require(projector.length() > 0) { "Downloaded projector is empty." }
        }
    }

    private fun downloadFile(
        address: String,
        destination: File,
        progress: (Int) -> Unit
    ) {
        val connection = URL(address).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 30000

        try {
            connection.instanceFollowRedirects = true
            connection.connect()

            check(connection.responseCode in 200..299) {
                "Download failed: HTTP ${connection.responseCode}"
            }

            val total = connection.contentLengthLong
            val temporary = File(destination.parentFile, destination.name + ".part")

            connection.inputStream.use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var count: Int

                    while (input.read(buffer).also { count = it } != -1) {
                        output.write(buffer, 0, count)
                        received += count

                        if (total > 0) {
                            progress(((received * 100) / total).toInt())
                        }
                    }
                }
            }

            check(temporary.length() > 0) { "Downloaded file is empty." }
            check(temporary.renameTo(destination)) {
                "Could not save downloaded file."
            }
        } finally {
            connection.disconnect()
        }
    }
}
