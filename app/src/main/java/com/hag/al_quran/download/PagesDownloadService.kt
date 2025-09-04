package com.hag.al_quran.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hag.al_quran.R
import kotlin.math.max
import kotlin.math.roundToLong

class PagesDownloadService : Service() {

    companion object {
        // أكشنات للتحكم من الإشعار
        const val ACTION_START  = "com.hag.al_quran.PAGES_DL_START"
        const val ACTION_PAUSE  = "com.hag.al_quran.PAGES_DL_PAUSE"
        const val ACTION_RESUME = "com.hag.al_quran.PAGES_DL_RESUME"
        const val ACTION_CANCEL = "com.hag.al_quran.PAGES_DL_CANCEL"

        // باراميترات اختيارية للبدء
        const val EXTRA_PARALLELISM = "extra_parallelism"
        const val EXTRA_TOTAL       = "extra_total"

        private const val CHANNEL_ID = "pages_download_channel"
        private const val NOTIF_ID   = 77221
    }

    // حالة بسيطة
    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false
    private val pauseLock = Object()

    private var totalPages = 604
    private var parallelism = 6

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()

        // إشعار Foreground مبدئي (لا يتطلب POST_NOTIFICATIONS)
        val n = buildNotification(
            paused   = false,
            progress = 0,
            total    = totalPages,
            etaText  = "—"
        )
        startForeground(NOTIF_ID, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                parallelism = intent.getIntExtra(EXTRA_PARALLELISM, 6)
                totalPages  = intent.getIntExtra(EXTRA_TOTAL, 604)
                startDownload()
            }
            ACTION_PAUSE  -> { isPaused = true;  updateNotification(paused = true) }
            ACTION_RESUME -> {
                isPaused = false
                synchronized(pauseLock) { pauseLock.notifyAll() }
                updateNotification(paused = false)
            }
            ACTION_CANCEL -> {
                isCancelled = true
                stopSelf()
            }
            else -> { /* تجاهل */ }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // عند التدمير انتهى التحميل
        try { NotificationManagerCompat.from(this).cancel(NOTIF_ID) } catch (_: Throwable) {}
    }

    // ====== التنزيل الوهمي (مثال) ======
    private fun startDownload() {
        isCancelled = false
        isPaused = false

        Thread {
            val startMs = SystemClock.elapsedRealtime()
            var done = 0
            while (done < totalPages && !isCancelled) {
                // إيقاف مؤقت
                synchronized(pauseLock) {
                    while (isPaused && !isCancelled) pauseLock.wait(200)
                }
                if (isCancelled) break

                // .. حمّل صفحة (ضع منطقك الحقيقي هنا) ..
                SystemClock.sleep(40) // محاكاة

                done++

                // ETA بسيط
                val elapsedSec = max(1L, ((SystemClock.elapsedRealtime() - startMs) / 1000f).roundToLong())
                val rate = done.toFloat() / elapsedSec.toFloat()
                val remaining = (totalPages - done).coerceAtLeast(0)
                val etaSec = if (rate > 0f) (remaining / rate).roundToLong() else Long.MAX_VALUE
                val etaText = if (etaSec == Long.MAX_VALUE) "…" else formatEta(etaSec)

                updateNotification(
                    paused   = isPaused,
                    progress = done,
                    total    = totalPages,
                    etaText  = etaText
                )
            }

            stopSelf()
        }.start()
    }

    // ====== الإشعارات ======
    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name) + " • تنزيل الصفحات",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "تقدّم تنزيل صفحات المصحف"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    // فحص إذن الإشعارات للتحديثات (ليس للـ Foreground الأولي)
    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }
    @SuppressLint("MissingPermission")  // لأننا نفحص الإذن يدويًا
    private fun updateNotification(
        paused: Boolean,
        progress: Int? = null,
        total: Int = totalPages,
        etaText: String? = null
    ) {
        // ملاحظة: إشعار الـ foreground الأوّلي لا يحتاج هذا الإذن، لكن التحديثات هنا تحتاجه على 13+
        val canPost = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (!canPost) return

        val nm = NotificationManagerCompat.from(this)
        try {
            nm.notify(NOTIF_ID, buildNotification(paused, progress, total, etaText))
        } catch (_: SecurityException) {
            // في حال رفض الإذن أثناء التشغيل — نتجاهل بهدوء
        }
    }

    private fun buildNotification(
        paused: Boolean,
        progress: Int? = null,
        total: Int = totalPages,
        etaText: String? = null
    ): Notification {
        val title = if (paused) "التنزيل متوقف مؤقتًا" else "تنزيل صفحات المصحف"
        val text  = buildString {
            append("الصفحات: ")
            val p = (progress ?: 0).coerceAtLeast(0).coerceAtMost(total)
            append("$p / $total")
            if (!etaText.isNullOrBlank()) append(" • الوقت المتبقي: $etaText")
        }

        val pauseIntent  = Intent(this, PagesDownloadService::class.java).setAction(ACTION_PAUSE)
        val resumeIntent = Intent(this, PagesDownloadService::class.java).setAction(ACTION_RESUME)
        val cancelIntent = Intent(this, PagesDownloadService::class.java).setAction(ACTION_CANCEL)

        val piPause  = androidx.core.app.PendingIntentCompat.getService(
            this, 501, pauseIntent, 0, true
        )
        val piResume = androidx.core.app.PendingIntentCompat.getService(
            this, 502, resumeIntent, 0, true
        )
        val piCancel = androidx.core.app.PendingIntentCompat.getService(
            this, 503, cancelIntent, 0, true
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download) // ضع أيقونة مناسبة لديك
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(!paused)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        // شريط التقدّم (إن توفر progress)
        progress?.let {
            builder.setProgress(total, it.coerceAtMost(total), false)
        }

        // أزرار
        if (paused) {
            builder.addAction(0, "استئناف", piResume)
        } else {
            builder.addAction(0, "إيقاف مؤقت", piPause)
        }
        builder.addAction(0, "إلغاء", piCancel)

        return builder.build()
    }

    private fun formatEta(sec: Long): String {
        val s = max(0, sec)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss)
        else String.format("%02d:%02d", m, ss)
    }
}
