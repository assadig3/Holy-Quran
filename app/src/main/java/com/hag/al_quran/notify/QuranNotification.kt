package com.hag.al_quran.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.hag.al_quran.MainActivity
import com.hag.al_quran.R

object QuranNotification {
    private const val CHANNEL_ID = "quran_audio_channel"
    private const val CHANNEL_NAME = "Quran Audio"
    private const val STOPPED_ID = 1011

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun showStopped(ctx: Context) {
        ensureChannel(ctx)
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val intent = Intent(ctx, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(R.drawable.icon)
            .setContentTitle("تم إيقاف التلاوة")
            .setContentText("يمكنك إعادة التشغيل من التطبيق.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(STOPPED_ID, notif)
    }
}
