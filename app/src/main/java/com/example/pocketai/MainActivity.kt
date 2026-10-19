package com.example.pocketai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import java.util.UUID

internal object WorkerNotification {

    private const val CHANNEL_ID = "pocket_ai_background"

    fun createForegroundInfo(
        context: Context,
        workId: UUID,
        title: String,
        message: String,
        progress: Int? = null
    ): ForegroundInfo {
        createChannel(context)

        val notificationId =
            (workId.hashCode() and 0x7fffffff).coerceAtLeast(1)

        val cancelIntent = WorkManager.getInstance(context)
            .createCancelPendingIntent(workId)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_delete,
                "Cancel",
                cancelIntent
            )

        if (progress == null) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(
                100,
                progress.coerceIn(0, 100),
                false
            )
        }

        val notification: Notification = builder.build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pocket AI background tasks",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "AI model downloads and background AI tasks"
        }

        manager.createNotificationChannel(channel)
    }
}
