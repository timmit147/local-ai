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

        val bitmap = context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Cannot open selected image." }
            BitmapFactory.decodeStream(input)
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
}
