// File: app/src/main/java/com/hag/al_quran2/download/NotificationHelper.kt
package com.hag.al_quran2.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.hag.al_quran2.R

object NotificationHelper {

    /** قناة التقدّم الرئيسية للتلاوة */
    const val CHANNEL_ID_PROGRESS = "recitation_download"
    /** قناة إشعار قصير عند المتابعة بالخلفية */
    const val CHANNEL_ID_INFO = "recitation_bg_info"

    /** معرفات افتراضية للإشعارات */
    const val NOTIF_ID_PROGRESS = 786
    const val NOTIF_ID_INFO = 9901

    /** إنشاء القنوات إن لزم */
    fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // قناة التقدّم
            val chProgress = NotificationChannel(
                CHANNEL_ID_PROGRESS,
                ctx.getString(R.string.notif_download_title), // "تنزيل التلاوة"
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.getString(R.string.notif_download_title)
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
                lightColor = Color.GREEN
            }

            // قناة إشعار الخلفية القصير
            val chInfo = NotificationChannel(
                CHANNEL_ID_INFO,
                ctx.getString(R.string.notif_download_title),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = ctx.getString(R.string.background_will_continue)
                enableLights(false)
                enableVibration(false)
                setShowBadge(false)
            }

            nm.createNotificationChannel(chProgress)
            nm.createNotificationChannel(chInfo)
        }
    }

    /** منشئ أساسي لإشعار تقدّم */
    private fun baseProgressBuilder(ctx: Context, title: String, text: String): NotificationCompat.Builder {
        ensureChannels(ctx)
        return NotificationCompat.Builder(ctx, CHANNEL_ID_PROGRESS)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }

    /**
     * إشعار تقدّم: ينسّق النص مثل "الملفات 12 / 120" + سطر ETA اختياري.
     * استعمله عندما تحسب (done/total) أثناء تنزيل التلاوات.
     */
    fun buildProgress(
        ctx: Context,
        done: Int,
        total: Int,
        etaText: String? = null,
        actions: List<NotificationCompat.Action> = emptyList()
    ): Notification {
        val title = ctx.getString(R.string.notif_download_title) // "تنزيل التلاوة"
        val text = ctx.getString(R.string.notif_download_progress, done, total) // "الملفات %1$d / %2$d"
        val b = baseProgressBuilder(ctx, title, text)
            .setProgress(total.coerceAtLeast(1), done.coerceAtLeast(0).coerceAtMost(total.coerceAtLeast(1)), false)

        if (!etaText.isNullOrBlank()) {
            // يظهر كسطر ثانٍ (subText) أو contentText ممتد
            b.setSubText(ctx.getString(R.string.notif_time_left, etaText))
        }

        actions.forEach { b.addAction(it) }
        return b.build()
    }

    /** عرض/تحديث إشعار التقدّم مباشرة */
    @SuppressLint("MissingPermission")
    fun notifyProgress(ctx: Context, done: Int, total: Int, etaText: String? = null, actions: List<NotificationCompat.Action> = emptyList()) {
        val n = buildProgress(ctx, done, total, etaText, actions)
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID_PROGRESS, n)
    }

    /** إنهاء إشعار التقدّم بنجاح */
    @SuppressLint("MissingPermission")
    fun completeSuccess(ctx: Context) {
        val n = baseProgressBuilder(
            ctx,
            ctx.getString(R.string.notif_download_title),
            ctx.getString(R.string.pages_download_done) // "اكتمل التحميل."
        )
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID_PROGRESS, n)
    }

    /** إنهاء إشعار التقدّم بفشل */
    @SuppressLint("MissingPermission")
    fun completeFailed(ctx: Context) {
        val n = baseProgressBuilder(
            ctx,
            ctx.getString(R.string.notif_download_title),
            ctx.getString(R.string.pages_download_failed) // "تعذّر التحميل."
        )
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()
        NotificationManagerCompat.from(ctx).notify(NOTIF_ID_PROGRESS, n)
    }

    /** إلغاء إشعار التقدّم */
    fun cancelProgress(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_ID_PROGRESS)
    }

    /**
     * إشعار قصير: "سيستمر التنزيل في الخلفية."
     * يُلغى تلقائيًا بعد timeoutMs (API 26+) أو بالإلغاء اليدوي للأقدم.
     */
    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun showBackgroundContinueInfo(ctx: Context, timeoutMs: Long = 3500L) {
        ensureChannels(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_ID_INFO)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(ctx.getString(R.string.notif_download_title))      // "تنزيل التلاوة"
            .setContentText(ctx.getString(R.string.background_will_continue))   // "سيستمر التنزيل في الخلفية."
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .apply { if (Build.VERSION.SDK_INT >= 26) setTimeoutAfter(timeoutMs) }
            .build()

        val nm = NotificationManagerCompat.from(ctx)
        nm.notify(NOTIF_ID_INFO, n)

        if (Build.VERSION.SDK_INT < 26) {
            Handler(Looper.getMainLooper()).postDelayed({ nm.cancel(NOTIF_ID_INFO) }, timeoutMs)
        }
    }
}
