package com.hag.al_quran2.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hag.al_quran2.R

class DownloadNotifier(private val context: Context) {

    companion object {
        private const val CHANNEL_ID   = "quran_recitation_download"
        private const val CHANNEL_NAME = "تنزيل التلاوة"
        private const val NOTIF_ID     = 880604
    }

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var lastPercent: Int = -1

    init { ensureChannel() }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار يوضح تقدم تنزيل التلاوة"
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
            .setSmallIcon(R.drawable.ic_download)        // تأكد من وجود الأيقونة
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    /** إظهار بداية التحميل */
    fun start(total: Int) {
        lastPercent = -1
        val title = "تنزيل التلاوة"
        val text  = "بدء تنزيل التلاوة…"
        val n: Notification = baseBuilder(title, text, true)
            // في البداية نعرضها كمؤشّر غير محدد لو ما عندنا إجمالي موثوق
            .setProgress(if (total > 0) 100 else 0, 0, total <= 0)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** تحديث التقدّم */
    fun update(done: Int, total: Int, eta: String?) {
        val p = if (total > 0) ((done * 100f) / total).toInt().coerceIn(0, 100) else -1
        if (p == lastPercent && p >= 0) return
        lastPercent = p

        val title = "تنزيل التلاوة"
        // نص التقدّم: “الآيات X / Y • الوقت المتبقي: …” (إن توفر)
        val progressText = buildString {
            append("الآيات ")
            append(done)
            append(" / ")
            append(total)
            if (!eta.isNullOrBlank()) {
                append(" • ")
                append("الوقت المتبقي: ")
                append(eta)
            }
        }

        val n: Notification = baseBuilder(title, progressText, true)
            .setProgress(if (total > 0) 100 else 0, if (total > 0) p.coerceAtLeast(0) else 0, total <= 0)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    /** إنهاء بنجاح */
    fun completeSuccess(done: Int? = null, total: Int? = null) {
        val title = "تنزيل التلاوة"
        val text  = if (done != null && total != null)
            "اكتمل تنزيل التلاوة: $done / $total"
        else
            "اكتمل تنزيل التلاوة"
        val n: Notification = baseBuilder(title, text, false).build()
        nm.notify(NOTIF_ID, n)
    }

    /** إلغاء/فشل */
    fun completeFailed(message: String? = null) {
        val title = "تنزيل التلاوة"
        val text  = message ?: "تعذّر تنزيل التلاوة"
        val n: Notification = baseBuilder(title, text, false).build()
        nm.notify(NOTIF_ID, n)
    }

    fun cancel() { nm.cancel(NOTIF_ID) }
}
