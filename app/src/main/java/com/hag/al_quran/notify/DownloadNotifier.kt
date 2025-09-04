package com.hag.al_quran.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hag.al_quran.R

class DownloadNotifier(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "quran_pages_download"
        private const val CHANNEL_NAME = "تنزيل صفحات المصحف"
        private const val NOTIF_ID = 880604
    }

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var lastPercent: Int = -1

    init { ensureChannel() }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار يوضح تقدم تنزيل صفحات المصحف"
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
                lightColor = Color.GREEN
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun baseBuilder(title: String, text: String, ongoing: Boolean): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download) // تأكد من وجود الأيقونة
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    /** إظهار بداية التحميل */
    fun start(total: Int) {
        lastPercent = -1
        val n = baseBuilder(
            context.getString(R.string.app_name),
            context.getString(R.string.pages_download_starting)
            , true)
            .setProgress(total, 0, true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** تحديث التقدّم */
    fun update(done: Int, total: Int, eta: String?) {
        val p = ((done * 100f) / total).toInt().coerceIn(0, 100)
        if (p == lastPercent) return
        lastPercent = p

        val text = context.getString(R.string.of_pages, done, total) + (if (!eta.isNullOrEmpty()) " • $eta" else "")
        val n = baseBuilder(
            context.getString(R.string.app_name),
            text, true
        )
            .setProgress(total, done, false)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** إنهاء بنجاح */
    fun completeSuccess() {
        val n = baseBuilder(
            context.getString(R.string.app_name),
            context.getString(R.string.pages_download_done), false
        ).build()
        nm.notify(NOTIF_ID, n)
    }

    /** إلغاء/فشل */
    fun completeFailed() {
        val n = baseBuilder(
            context.getString(R.string.app_name),
            context.getString(R.string.pages_download_failed), false
        ).build()
        nm.notify(NOTIF_ID, n)
    }

    fun cancel() { nm.cancel(NOTIF_ID) }
}
