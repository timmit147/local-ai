package com.example.pocketai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

data class ImageLabelResult(
    val label: String,
    val confidence: Float
)

/**
 * Fully offline image recognition.
 * Uses the ML Kit image labeling model that is bundled inside the APK,
 * so no download and no internet connection are needed.
 */
object ImageRecognizer {

    suspend fun recognize(
        imagePath: String,
        minConfidence: Float = 0.5f,
        maxLabels: Int = 8
    ): List<ImageLabelResult> {
        val (bitmap, rotation) = withContext(Dispatchers.IO) {
            loadBitmap(imagePath)
        }

        val labeler = ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(minConfidence)
                .build()
        )

        return try {
            val image = InputImage.fromBitmap(bitmap, rotation)

            suspendCancellableCoroutine { continuation ->
                labeler.process(image)
                    .addOnSuccessListener { labels ->
                        continuation.resume(
                            labels
                                .sortedByDescending { it.confidence }
                                .take(maxLabels)
                                .map { ImageLabelResult(it.text, it.confidence) }
                        )
                    }
                    .addOnFailureListener { error ->
                        continuation.resumeWithException(error)
                    }
            }
        } finally {
            labeler.close()
            bitmap.recycle()
        }
    }

    /** Text shown directly to the user (also used when the chat model is not installed). */
    fun describe(labels: List<ImageLabelResult>): String {
        if (labels.isEmpty()) {
            return "I could not recognise anything in this image with enough confidence."
        }

        return "Image recognition (on this device):\n" +
            labels.joinToString("\n") {
                "• ${it.label} (${(it.confidence * 100).roundToInt()}%)"
            }
    }

    /** Prompt for the offline chat model. */
    fun buildPrompt(
        userText: String,
        labels: List<ImageLabelResult>
    ): String {
        val question = userText.ifBlank { "What is in this image?" }

        val found = if (labels.isEmpty()) {
            "The image classifier could not recognise anything with enough confidence."
        } else {
            "The on-device image classifier detected these labels: " +
                labels.joinToString(", ") {
                    "${it.label} (${(it.confidence * 100).roundToInt()}%)"
                } + "."
        }

        return "The user attached a photo. $found " +
            "You cannot see the photo itself, only these labels, so do not invent " +
            "other details. Answer in the same language as the user's question.\n\n" +
            "User question: $question"
    }

    private fun loadBitmap(path: String): Pair<Bitmap, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)

        require(bounds.outWidth > 0 && bounds.outHeight > 0) {
            "Invalid image."
        }

        var sample = 1
        while (
            bounds.outWidth / sample > 1280 ||
            bounds.outHeight / sample > 1280
        ) {
            sample *= 2
        }

        val bitmap = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: error("Cannot decode image.")

        val rotation = try {
            when (
                ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        } catch (_: Exception) {
            0
        }

        return bitmap to rotation
    }
}
