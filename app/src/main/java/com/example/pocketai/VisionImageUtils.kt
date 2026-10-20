package com.example.pocketai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object VisionImageUtils {

    fun copyImageToInternalStorage(
        context: Context,
        uri: Uri
    ): File {
        val destination = File(
            context.cacheDir,
            "vision_${System.currentTimeMillis()}.jpg"
        )

        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }

        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected image." }
            BitmapFactory.decodeStream(input, null, options)
        }

        require(options.outWidth > 0 && options.outHeight > 0) {
            "Invalid image."
        }

        options.inSampleSize = calculateSampleSize(
            options.outWidth,
            options.outHeight,
            2048
        )
        options.inJustDecodeBounds = false

        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot reopen selected image." }
            BitmapFactory.decodeStream(input, null, options)
                ?: error("Cannot decode selected image.")
        }

        try {
            FileOutputStream(destination).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
                    "Cannot save image."
                }
            }
        } finally {
            bitmap.recycle()
        }

        return destination
    }

    private fun calculateSampleSize(
        width: Int,
        height: Int,
        maxDimension: Int
    ): Int {
        var sample = 1
        while (width / (sample * 2) >= maxDimension &&
            height / (sample * 2) >= maxDimension
        ) {
            sample *= 2
        }
        return sample
    }
}
