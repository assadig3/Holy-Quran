package com.hag.al_quran.helpers

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.R

/** ثوابت أكشنات التحكم */
object AudioActions {
    const val ACTION_PLAY_PAUSE = "com.hag.al_quran.action.PLAY_PAUSE"
    const val ACTION_STOP       = "com.hag.al_quran.action.STOP"
    const val ACTION_NEXT       = "com.hag.al_quran.action.NEXT"
    const val ACTION_PREV       = "com.hag.al_quran.action.PREV"
    const val ACTION_SYNC       = "com.hag.al_quran.action.SYNC"
    const val EXTRA_QARI_ID     = "extra_qari_id"
    const val EXTRA_PAGE        = "extra_page"
}

/**
 * خدمة التشغيل الأمامية: إشعار واحد، وتنفّذ الأزرار حتى لو كانت العملية مغلقة.
 */
class AudioForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "quran_audio"
        private const val NOTIF_ID   = 2211

        @JvmStatic @Volatile
        var audioHelper: QuranAudioHelper? = null

        @Volatile var isPlaying: Boolean = false
        @Volatile var currentPage: Int = 1
        @Volatile var currentQariId: String = "fares"
    }

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Quran Playback", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.extras?.let {
            currentPage   = it.getInt(AudioActions.EXTRA_PAGE, currentPage)
            currentQariId = it.getString(AudioActions.EXTRA_QARI_ID, currentQariId)
        }

        when (intent?.action) {
            AudioActions.ACTION_PLAY_PAUSE -> invokeWithHelper { h ->
                h.togglePlayPause(currentPage, currentQariId)
            }

            AudioActions.ACTION_NEXT -> invokeWithHelper { h ->
                currentPage = (currentPage + 1).coerceAtMost(604)
                h.startPagePlayback(currentPage, currentQariId, fromStart = true)
                true
            }

            AudioActions.ACTION_PREV -> invokeWithHelper { h ->
                currentPage = (currentPage - 1).coerceAtLeast(1)
                h.startPagePlayback(currentPage, currentQariId, fromStart = true)
                true
            }

            AudioActions.ACTION_STOP -> {
                // ✅ إيقاف شامل لكل الحالات (صفحة / آية مفردة / نطاق)
                audioHelper?.stopAll()
                isPlaying = false
                // الإشعار يبقى لكن غير مستمر؛ يمكنك إزالة السطر التالي لو أردت إبقاءه
                stopForeground(STOP_FOREGROUND_DETACH)
                notifyUpdate()
            }

            AudioActions.ACTION_SYNC, null -> { /* تحديث إشعار فقط */ }
        }

        // يجب الدخول في foreground خلال 5 ثوانٍ من البدء
        startForeground(NOTIF_ID, buildNotification())
        return START_STICKY
    }

    /** نفّذ block فور توفر الهيلبر؛ وإلا افتح الواجهة وانتظر تهيئته ثم نفّذ. */
    private fun invokeWithHelper(block: (QuranAudioHelper) -> Boolean) {
        val h = audioHelper
        if (h != null) {
            isPlaying = runCatching { block(h) }.getOrDefault(false)
            notifyUpdate()
            return
        }

        // العملية كانت مغلقة: افتح شاشة المصحف
        val open = Intent(this, QuranPageActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        startActivity(open)

        // انتظر حتى 1.5 ثانية لتهيئة الهيلبر
        var tries = 0
        fun poll() {
            val hh = audioHelper
            if (hh != null) {
                isPlaying = runCatching { block(hh) }.getOrDefault(false)
                notifyUpdate()
            } else if (tries++ < 15) {
                mainHandler.postDelayed({ poll() }, 100)
            } else {
                notifyUpdate()
            }
        }
        poll()
    }

    private fun notifyUpdate() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        runCatching { NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val contentPi = PendingIntent.getActivity(
            this, 100,
            Intent(this, QuranPageActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // getBroadcast -> AudioControlReceiver
        fun ctrl(action: String, req: Int) = PendingIntent.getBroadcast(
            this, req,
            Intent(this, AudioControlReceiver::class.java)
                .setAction(action)
                .putExtra(AudioActions.EXTRA_PAGE, currentPage)
                .putExtra(AudioActions.EXTRA_QARI_ID, currentQariId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPausePi = ctrl(AudioActions.ACTION_PLAY_PAUSE, 101)
        val prevPi      = ctrl(AudioActions.ACTION_PREV,       102)
        val nextPi      = ctrl(AudioActions.ACTION_NEXT,       103)
        val stopPi      = ctrl(AudioActions.ACTION_STOP,       104)

        val text = if (isPlaying) "جارٍ التشغيل" else "التلاوة متوقفة"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(contentPi)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .addAction(android.R.drawable.ic_media_previous, "السابق",  prevPi)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "إيقاف مؤقت" else "تشغيل",
                playPausePi
            )
            .addAction(android.R.drawable.ic_media_next, "التالي", nextPi)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPi)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle())
            .build()
    }
}
