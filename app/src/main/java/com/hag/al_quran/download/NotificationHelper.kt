package com.hag.al_quran.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hag.al_quran.R

object NotificationHelper {
    const val CHANNEL_ID = "quran_pages_dl"
    private const val CHANNEL_NAME = "تنزيل صفحات المصحف"
    private const val CHANNEL_DESC = "إشعار تقدم تنزيل صفحات المصحف"

    const val NOTIF_ID = 786

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = CHANNEL_DESC
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
                lightColor = Color.GREEN
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun baseBuilder(ctx: Context, title: String, text: String): NotificationCompat.Builder {
        ensureChannel(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download) // وفّر هذا الأيقون
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    fun buildProgress(
        ctx: Context,
        title: String,
        progress: Int,
        max: Int,
        actions: List<NotificationCompat.Action>,
        subText: String? = null
    ): Notification {
        val b = baseBuilder(ctx, title, "$progress / $max")
            .setProgress(max, progress, false)
        subText?.let { b.setSubText(it) }
        actions.forEach { b.addAction(it) }
        return b.build()
    }
}
