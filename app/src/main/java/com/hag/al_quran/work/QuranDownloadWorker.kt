// File: app/src/main/java/com/hag/al_quran/work/QuranDownloadWorker.kt
package com.hag.al_quran.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.*
import com.hag.al_quran.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * تنزيل صفحات المصحف (webp) إلى externalFilesDir/quran_pages
 * يدعم: Wi-Fi فقط / بيانات فقط / أي شبكة
 * تقدّم عبر setProgress + إشعارات Foreground/Background
 */
class QuranDownloadWorker(
    private val appCtx: Context,
    params: WorkerParameters
) : CoroutineWorker(appCtx, params) {

    companion object {
        const val UNIQUE_NAME = "pages_download_unique"

        // Progress keys
        const val K_DONE  = "done"
        const val K_TOTAL = "total"

        // Network pref
        enum class NetPref { WIFI_ONLY, DATA_ONLY, ANY }
        private const val K_NET_PREF = "net_pref"

        // Range
        private const val K_START = "start"
        private const val K_END   = "end"

        // Notif
        private const val CHANNEL_ID = "quran_pages_dl"
        private const val NOTI_ID = 2234

        private const val TOTAL = 604
        private const val BASE_URL =
            "https://raw.githubusercontent.com/assadig3/quran-pages/main/pages"

        fun enqueue(
            context: Context,
            netPref: NetPref = NetPref.ANY,
            startPage: Int = 1,
            endPage: Int = TOTAL
        ) {
            val data = workDataOf(
                K_NET_PREF to netPref.name,
                K_START to startPage.coerceIn(1, TOTAL),
                K_END to endPage.coerceIn(1, TOTAL)
            )
            val req = OneTimeWorkRequestBuilder<QuranDownloadWorker>()
                .setInputData(data)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NAME, ExistingWorkPolicy.REPLACE, req
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        createChannel()

        val netPref = runCatching {
            NetPref.valueOf(inputData.getString(K_NET_PREF) ?: NetPref.ANY.name)
        }.getOrDefault(NetPref.ANY)

        val startIn = inputData.getInt(K_START, 1).coerceIn(1, TOTAL)
        val endIn   = inputData.getInt(K_END, TOTAL).coerceIn(1, TOTAL)
        val startPage = min(startIn, endIn)
        val endPage   = max(startIn, endIn)
        val total = (endPage - startPage + 1)

        setProgressAsync(workDataOf(K_DONE to 0, K_TOTAL to total))

        val useFgs = canStartFgs(appCtx) && hasPostNotifPermission(appCtx)
        if (useFgs) setForeground(createForeground(0, "بدء تنزيل صفحات المصحف…"))
        else        notifyProgress(0, "بدء تنزيل صفحات المصحف…")

        val outDir = File(appCtx.getExternalFilesDir(null), "quran_pages").apply { if (!exists()) mkdirs() }

        var done = 0
        for (page in startPage..endPage) {
            if (isStopped) break

            when (netPref) {
                NetPref.WIFI_ONLY -> if (!isOnWifi()) { delay(3000); return@withContext Result.retry() }
                NetPref.DATA_ONLY -> if (!isOnCellular()) { delay(3000); return@withContext Result.retry() }
                NetPref.ANY -> {}
            }

            val file = File(outDir, "page_${page}.webp")
            if (file.exists() && file.length() > 1024) {
                done++
            } else {
                val url = "$BASE_URL/page_${page}.webp"
                val ok = tryDownload(url, file)
                if (ok) done++ else {
                    delay(400)
                    if (tryDownload(url, file)) done++
                }
            }

            setProgressAsync(workDataOf(K_DONE to done, K_TOTAL to total))
            val pct = (done * 100f / total).toInt().coerceIn(0, 100)
            if (useFgs) setForeground(createForeground(pct, "تنزيل الصفحات — $done / $total"))
            else        notifyProgress(pct, "تنزيل الصفحات — $done / $total")
        }

        if (!useFgs) notifyDone(done, total)
        Result.success(workDataOf(K_DONE to done, K_TOTAL to total))
    }

    // ===== Network helpers =====
    private fun cm(): ConnectivityManager =
        appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun isOnWifi(): Boolean {
        val nc = cm().getNetworkCapabilities(cm().activeNetwork) ?: return false
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isOnCellular(): Boolean {
        val nc = cm().getNetworkCapabilities(cm().activeNetwork) ?: return false
        return nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    // ===== Download helpers =====
    private fun tryDownload(url: String, out: File): Boolean = try {
        download(url, out)
    } catch (_: Exception) { false }

    private fun download(urlStr: String, out: File): Boolean {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 15_000
        }
        conn.connect()
        if (conn.responseCode !in 200..299) return false
        conn.inputStream.use { input ->
            FileOutputStream(out).use { output ->
                val buf = ByteArray(8 * 1024)
                while (true) {
                    val r = input.read(buf)
                    if (r == -1) break
                    output.write(buf, 0, r)
                }
                output.flush()
            }
        }
        return out.exists() && out.length() > 1024
    }

    // ===== Notifications =====
    private fun canStartFgs(ctx: Context): Boolean {
        val state = ProcessLifecycleOwner.get().lifecycle.currentState
        return state.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun hasPostNotifPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
        else true
    }

    @SuppressLint("MissingPermission")
    private fun safeNotify(id: Int, notif: Notification) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(appCtx, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        try { NotificationManagerCompat.from(appCtx).notify(id, notif) } catch (_: SecurityException) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(CHANNEL_ID, "تنزيل صفحات المصحف", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun createForeground(progress: Int, text: String): ForegroundInfo {
        val notif = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("تنزيل صفحات المصحف")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), false)
            .build()
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(NOTI_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTI_ID, notif)
        }
    }

    private fun notifyProgress(pct: Int, text: String) {
        val notif = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("تنزيل صفحات المصحف")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct.coerceIn(0, 100), false)
            .build()
        safeNotify(NOTI_ID, notif)
    }

    private fun notifyDone(done: Int, total: Int) {
        val notif = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("تنزيل صفحات المصحف")
            .setContentText("اكتمل: $done / $total")
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .build()
        safeNotify(NOTI_ID, notif)
    }
}
