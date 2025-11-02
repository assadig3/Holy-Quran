// File: app/src/main/java/com/hag/al_quran/download/PagesDownloadService.kt
package com.hag.al_quran.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hag.al_quran.R
import com.hag.al_quran.audio.MadaniPageProvider
import com.hag.al_quran.audio.Qari
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToLong

class PagesDownloadService : Service() {

    companion object {
        const val ACTION_START  = "com.hag.al_quran.PAGES_DL_START"
        const val ACTION_PAUSE  = "com.hag.al_quran.PAGES_DL_PAUSE"
        const val ACTION_RESUME = "com.hag.al_quran.PAGES_DL_RESUME"
        const val ACTION_CANCEL = "com.hag.al_quran.PAGES_DL_CANCEL"

        const val EXTRA_SCOPE  = "extra_scope"   // "PAGE", "SURAH", "JUZ", "QURAN"
        const val EXTRA_PAGE   = "extra_page"
        const val EXTRA_SURAH  = "extra_surah"
        const val EXTRA_QARI   = "extra_qari"    // qari id string
        const val EXTRA_PARALLELISM = "extra_parallelism"
        const val EXTRA_TOTAL  = "extra_total"   // optional

        private const val CHANNEL_ID = "pages_download_channel"
        private const val NOTIF_ID   = 77221

        private const val PREF_NETWORK = "pref_network_type" // "WIFI_ONLY" or "ANY"
    }

    private enum class DownloadScope { PAGE, SURAH, JUZ, QURAN }

    // ===== state =====
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    private val pauseLock = Object()

    private var parallelism = 6
    private var totalItems = 0

    // cache for bounds JSON
    private var boundsRoot: JSONObject? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        // إشعار مبدئي ليصبح Foreground
        startForeground(
            NOTIF_ID,
            buildNotification(paused = false, progress = 0, total = 1, etaText = "…")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val scopeStr = intent.getStringExtra(EXTRA_SCOPE) ?: "PAGE"
                val scope = runCatching { DownloadScope.valueOf(scopeStr) }
                    .getOrElse { DownloadScope.PAGE }
                val page = intent.getIntExtra(EXTRA_PAGE, 1)
                val surah = intent.getIntExtra(EXTRA_SURAH, 1)
                val qariId = intent.getStringExtra(EXTRA_QARI) ?: "fares"
                parallelism = intent.getIntExtra(EXTRA_PARALLELISM, 6)

                isPaused.set(false)
                isCancelled.set(false)

                startDownload(scope, page, surah, qariId)
            }
            ACTION_PAUSE -> {
                isPaused.set(true)
                updateNotification(paused = true)
            }
            ACTION_RESUME -> {
                isPaused.set(false)
                synchronized(pauseLock) { pauseLock.notifyAll() }
                updateNotification(paused = false)
            }
            ACTION_CANCEL -> {
                isCancelled.set(true)
                // سيتم إيقاف الخدمة بعد أن تلاحظ الحلقة حالة الإلغاء
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_ID) }
    }

    // ====== التحميل الرئيسي (تلاوات) ======
    private fun startDownload(scope: DownloadScope, pageNow: Int, surahNow: Int, qariId: String) {
        thread(name = "RecitationsDownloadThread") {
            // 1) تفضيلات الشبكة
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val prefNetwork = prefs.getString(PREF_NETWORK, "WIFI_ONLY") ?: "WIFI_ONLY"
            if (prefNetwork == "WIFI_ONLY" && !isOnWifi()) {
                updateNotification(paused = true, progress = 0, total = 1, etaText = "انتظر Wi-Fi")
                SystemClock.sleep(1200)
                stopSelf()
                return@thread
            }

            // 2) مزوّد وروابط القارئ
            val provider = MadaniPageProvider(applicationContext)
            val qari: Qari? = provider.getQariById(qariId)
            if (qari == null) {
                updateNotification(paused = true, progress = 0, total = 1, etaText = "القارئ غير معروف")
                SystemClock.sleep(800)
                stopSelf()
                return@thread
            }

            // 3) بناء قائمة العناصر
            val items = buildUrlsForScope(provider, qari, scope, pageNow, surahNow)
            if (items.isEmpty()) {
                updateNotification(paused = true, progress = 0, total = 1, etaText = "لا ملفات")
                SystemClock.sleep(800)
                stopSelf()
                return@thread
            }

            totalItems = items.size
            updateNotification(paused = false, progress = 0, total = totalItems, etaText = "—")

            // 4) تنزيل متتابع (يمكن لاحقًا تحويله لـ ThreadPool للـ parallelism)
            val startMs = SystemClock.elapsedRealtime()
            var done = 0
            items.forEachIndexed { idx, (url, out) ->
                if (isCancelled.get()) return@forEachIndexed

                // إيقاف مؤقت
                synchronized(pauseLock) {
                    while (isPaused.get() && !isCancelled.get()) {
                        try { pauseLock.wait(500) } catch (_: InterruptedException) {}
                    }
                }
                if (isCancelled.get()) return@forEachIndexed

                val ok = downloadSingle(url, out)
                if (ok) done++

                // ETA
                val elapsedSec = max(1L, ((SystemClock.elapsedRealtime() - startMs) / 1000f).roundToLong())
                val rate = done.toFloat() / elapsedSec.toFloat()
                val remaining = (totalItems - done).coerceAtLeast(0)
                val etaSec = if (rate > 0f) (remaining / rate).roundToLong() else Long.MAX_VALUE
                val etaText = if (etaSec == Long.MAX_VALUE) "…" else formatEta(etaSec)

                updateNotification(paused = isPaused.get(), progress = idx + 1, total = totalItems, etaText = etaText)
            }

            // 5) إنهاء
            val ok = !isCancelled.get()
            updateNotification(paused = false, progress = totalItems, total = totalItems, etaText = if (ok) "تم" else "أُلغي")
            SystemClock.sleep(800)
            stopSelf()
        }
    }

    // ======= بناء روابط التلاوات =======
    private fun buildUrlsForScope(
        provider: MadaniPageProvider,
        qari: Qari,
        scope: DownloadScope,
        pageNow: Int,
        surahNow: Int
    ): List<Pair<String, File>> {
        val qariId = qari.id.trim().lowercase()
        val pairs = ArrayList<Pair<String, File>>(4096)

        fun addAyah(s: Int, a: Int) {
            // ✅ الإصلاح هنا: استخدام المعرّف النصي للقارئ
            val url = provider.getAyahUrl(qari.id, s, a) ?: return
            val out = qariFile(qariId, s, a)
            pairs.add(url to out)
        }

        when (scope) {
            DownloadScope.PAGE -> {
                val bounds = loadBoundsForPage(pageNow)
                for (b in bounds) addAyah(b.sura_id, b.aya_id)
            }
            DownloadScope.SURAH -> {
                val counts = AYAH_COUNTS.getOrNull(surahNow - 1) ?: 0
                for (a in 1..counts) addAyah(surahNow, a)
            }
            DownloadScope.JUZ -> {
                val range = pageRangeForCurrentJuz(pageNow)
                for (p in range) {
                    val bounds = loadBoundsForPage(p)
                    for (b in bounds) addAyah(b.sura_id, b.aya_id)
                }
            }
            DownloadScope.QURAN -> {
                for (s in 1..114) {
                    val c = AYAH_COUNTS.getOrNull(s - 1) ?: continue
                    for (a in 1..c) addAyah(s, a)
                }
            }
        }

        return pairs
            .filter { (_, file) -> !file.exists() || file.length() <= 1024L } // لا تعِد العناصر الموجودة
            .distinctBy { it.second.absolutePath }
    }

    // ====== bounds (من الأصول) ======
    private data class Seg(val x: Int, val y: Int, val w: Int, val h: Int)
    private data class AyahBounds(val sura_id: Int, val aya_id: Int, val segs: List<Seg>)

    private fun loadBoundsForPage(page: Int): List<AyahBounds> {
        return try {
            if (boundsRoot == null) {
                val jsonStr = applicationContext.assets
                    .open("pages/ayah_bounds_all.json")
                    .bufferedReader().use { it.readText() }
                boundsRoot = JSONObject(jsonStr)
            }
            val arr = boundsRoot?.optJSONArray(page.toString()) ?: return emptyList()
            val res = ArrayList<AyahBounds>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val segsArr = o.getJSONArray("segs")
                val segs = ArrayList<Seg>(segsArr.length())
                for (j in 0 until segsArr.length()) {
                    val s = segsArr.getJSONObject(j)
                    segs.add(Seg(s.getInt("x"), s.getInt("y"), s.getInt("w"), s.getInt("h")))
                }
                res.add(AyahBounds(o.getInt("sura_id"), o.getInt("aya_id"), segs))
            }
            res
        } catch (e: Exception) {
            android.util.Log.e("PagesDownloadSvc", "Failed to load bounds", e)
            emptyList()
        }
    }

    // ======= تنزيل ملف واحد (آمن) =======
    private fun downloadSingle(urlStr: String, outFile: File): Boolean {
        return try {
            if (outFile.exists() && outFile.length() > 1024) return true

            val url = URL(urlStr)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "AlQuranApp/1.0 (Android)")
            }

            conn.inputStream.use { input ->
                val tmp = File(outFile.parentFile, outFile.name + ".part")
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        // فحوصات الإيقاف أثناء القراءة الطويلة
                        if (isCancelled.get()) return false
                        synchronized(pauseLock) {
                            while (isPaused.get() && !isCancelled.get()) {
                                try { pauseLock.wait(300) } catch (_: InterruptedException) {}
                            }
                        }

                        val r = input.read(buf)
                        if (r == -1) break
                        output.write(buf, 0, r)
                    }
                    output.flush()
                }

                // تحقّق الحجم قبل الاستبدال
                val ok = runCatching { File(outFile.parentFile, outFile.name + ".part") }
                    .map { it.exists() && it.length() > 1024 }
                    .getOrDefault(false)

                if (!ok) {
                    runCatching { File(outFile.parentFile, outFile.name + ".part").delete() }
                    return false
                }

                if (outFile.exists()) runCatching { outFile.delete() }
                File(outFile.parentFile, outFile.name + ".part").renameTo(outFile)
            }

            true
        } catch (t: Throwable) {
            android.util.Log.e("PagesDownloadSvc", "downloadSingle failed: $urlStr", t)
            false
        }
    }

    // ====== إشعارات ======
    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name) + " • تنزيل التلاوات",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "تقدّم تنزيل تلاوات الآيات"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(
        paused: Boolean,
        progress: Int? = null,
        total: Int = totalItems,
        etaText: String? = null
    ) {
        if (!canPostNotifications()) return
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIF_ID, buildNotification(paused, progress, total, etaText))
        } catch (_: SecurityException) { /* ignore */ }
    }

    private fun buildNotification(
        paused: Boolean,
        progress: Int? = null,
        total: Int = totalItems,
        etaText: String? = null
    ): Notification {
        val title = if (paused) "التنزيل متوقف مؤقتًا" else "تنزيل تلاوة المصحف"
        val p = (progress ?: 0).coerceIn(0, max(1, total))
        val text = buildString {
            append("الآيات: ")
            append("$p / $total")
            if (!etaText.isNullOrBlank()) append(" • الوقت المتبقي: $etaText")
        }

        // Actions
        val pauseIntent  = Intent(this, PagesDownloadService::class.java).setAction(ACTION_PAUSE)
        val resumeIntent = Intent(this, PagesDownloadService::class.java).setAction(ACTION_RESUME)
        val cancelIntent = Intent(this, PagesDownloadService::class.java).setAction(ACTION_CANCEL)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val piPause  = PendingIntent.getService(this, 501, pauseIntent, flags)
        val piResume = PendingIntent.getService(this, 502, resumeIntent, flags)
        val piCancel = PendingIntent.getService(this, 503, cancelIntent, flags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(!isCancelled.get())
            .setPriority(NotificationCompat.PRIORITY_LOW)

        progress?.let {
            builder.setProgress(total.coerceAtLeast(1), it.coerceAtMost(total.coerceAtLeast(1)), false)
        } ?: builder.setProgress(0, 0, true)

        if (paused) builder.addAction(0, "استئناف", piResume)
        else builder.addAction(0, "إيقاف مؤقت", piPause)
        builder.addAction(0, "إلغاء", piCancel)

        return builder.build()
    }

    // ====== أدوات مساعدة ======
    private fun formatEta(sec: Long): String {
        val s = max(0, sec)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss) else String.format("%02d:%02d", m, ss)
    }

    private fun qariDir(qariIdRaw: String): File {
        val safe = qariIdRaw.trim().lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")
        val dir = File(getExternalFilesDir(null), "recitations/$safe")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun qariFile(qariIdRaw: String, surah: Int, ayah: Int): File {
        val qariId = qariIdRaw.trim().lowercase()
        val name = "%03d%03d.mp3".format(surah, ayah)
        return File(qariDir(qariId), name)
    }

    // ===== الشبكة =====
    private fun isOnWifi(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    // ========= بيانات ثابتة =========
    private val JUZ_START_PAGES = intArrayOf(
        1, 22, 42, 62, 82, 102, 121, 141, 162, 182,
        201, 222, 242, 262, 282, 302, 322, 342, 362, 382,
        402, 422, 442, 462, 482, 502, 522, 542, 562, 582
    )

    private fun pageRangeForCurrentJuz(pageNow: Int): IntRange {
        var start = 1
        var end = 604
        for (i in 0 until 30) {
            val s = JUZ_START_PAGES[i]
            val e = if (i == 29) 604 else JUZ_START_PAGES[i + 1] - 1
            if (pageNow in s..e) { start = s; end = e; break }
        }
        return start..end
    }

    private val AYAH_COUNTS = intArrayOf(
        7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,112,78,118,64,77,227,93,88,69,60,
        34,30,73,54,45,83,182,88,75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,14,11,11,18,
        12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,
        11,8,3,9,5,4,5,6,3,5,4,5,4,5,6
    )
}
