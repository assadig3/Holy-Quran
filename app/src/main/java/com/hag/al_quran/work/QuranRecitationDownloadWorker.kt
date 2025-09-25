// File: app/src/main/java/com/hag/al_quran/work/QuranRecitationDownloadWorker.kt
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
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hag.al_quran.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * تنزيل تلاوات MP3 وفق النطاق (صفحة/سورة/جزء/كامل)
 * يدعم التخزين الداخلي أو الخارجي (SAF).
 *
 * المدخلات المطلوبة:
 *  - scope: "PAGE" | "SURAH" | "JUZ" | "ALL"
 *  - pageNow: Int (للصفحة)
 *  - surahNow: Int (للسورة الحالية؛ يستخدم أيضاً كFallback)
 *  - qariId: String
 *  - audioBase: String  (مثال: https://cdn.example.com/qari) بدون لاحقة الملف
 *  - storageTarget: "internal" | "external"
 *  - treeUri: String (عند external)
 *
 * مخرجات التقدّم:
 *  - done, total
 */
class QuranRecitationDownloadWorker(
    private val appCtx: Context,
    params: WorkerParameters
) : CoroutineWorker(appCtx, params) {

    companion object {
        const val UNIQUE_NAME = "recitations_download_unique"

        // Inputs
        private const val K_SCOPE   = "scope"
        private const val K_PAGE    = "pageNow"
        private const val K_SURAH   = "surahNow"
        private const val K_QARI    = "qariId"
        private const val K_AUDIO   = "audioBase"
        private const val K_STORE   = "storageTarget" // internal | external
        private const val K_TREE    = "treeUri"       // when external

        // Progress / Output
        const val K_DONE  = "done"
        const val K_TOTAL = "total"

        // Notif
        private const val CHANNEL_ID = "quran_recitations_dl"
        private const val NOTI_ID = 3345

        // API to enqueue
        fun enqueue(
            context: Context,
            scope: String,
            pageNow: Int,
            surahNow: Int,
            qariId: String,
            audioBase: String,
            storageTarget: String,
            treeUri: String
        ) {
            val data = workDataOf(
                K_SCOPE to scope,
                K_PAGE to pageNow,
                K_SURAH to surahNow,
                K_QARI to qariId,
                K_AUDIO to audioBase,
                K_STORE to storageTarget,
                K_TREE to treeUri
            )

            val req = OneTimeWorkRequestBuilder<QuranRecitationDownloadWorker>()
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

    // ==================== تنفيذ ====================
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        createChannel()

        val scope    = (inputData.getString(K_SCOPE) ?: "PAGE").uppercase()
        val pageNow  = inputData.getInt(K_PAGE, 1)
        val surahNow = inputData.getInt(K_SURAH, 1).coerceIn(1, 114)
        val qariId   = inputData.getString(K_QARI) ?: "fares"
        val audioBaseRaw = inputData.getString(K_AUDIO) ?: ""
        val audioBase = normalizeBase(audioBaseRaw)
        val storageTarget = inputData.getString(K_STORE) ?: "internal"
        val treeUriStr    = inputData.getString(K_TREE) ?: ""

        val pairs: List<Pair<Int, Int>> = when (scope) {
            "SURAH" -> buildPairsForSurah(surahNow)
            "PAGE"  -> buildPairsForPage(pageNow) ?: buildPairsForSurah(surahNow)
            "JUZ"   -> buildPairsForJuzOrFallback(pageNow, surahNow)
            "ALL"   -> buildPairsForAll()
            else    -> buildPairsForPage(pageNow) ?: buildPairsForSurah(surahNow)
        }

        val total = pairs.size
        if (total == 0) return@withContext Result.failure()

        setProgress(workDataOf(K_DONE to 0, K_TOTAL to total))

        val useFgs = canStartFgs(appCtx) && hasPostNotifPermission(appCtx)
        if (useFgs) setForeground(createForeground(0, "بدء تنزيل التلاوات…"))
        else        notifyProgress(0, "بدء تنزيل التلاوات…")

        val saver: Saver = if (storageTarget == "external")
            ExternalSaver(appCtx, qariId, treeUriStr)
        else
            InternalSaver(appCtx, qariId)

        if (!saver.isReady()) {
            return@withContext Result.failure()
        }

        var done = 0
        pairs.forEach { (surah, ayah) ->
            if (isStopped) return@forEach

            val surah3 = String.format("%03d", surah)
            val ayah3  = String.format("%03d", ayah)
            val url    = "${audioBase}${surah3}${ayah3}.mp3"   // مثال: .../001002.mp3
            val ok = saver.downloadToTarget(url, "${surah3}${ayah3}.mp3")
            if (ok) done++ else {
                // محاولة ثانية سريعة
                delay(250)
                if (saver.downloadToTarget(url, "${surah3}${ayah3}.mp3")) done++
            }

            setProgress(workDataOf(K_DONE to done, K_TOTAL to total))
            val pct = (done * 100f / total).toInt().coerceIn(0, 100)
            if (useFgs) setForeground(createForeground(pct, "تنزيل التلاوات — $done / $total"))
            else        notifyProgress(pct, "تنزيل التلاوات — $done / $total")
        }

        if (!useFgs) notifyDone(done, total)
        Result.success(workDataOf(K_DONE to done, K_TOTAL to total))
    }

    // ==================== البناء للنطاقات ====================
    private fun buildPairsForPage(page: Int): List<Pair<Int, Int>>? {
        // يتوقّع ملف: assets/pages/page_ayahs_map.json
        // الصيغة المتوقعة: { "1":[{"sura":1,"start":1,"end":7}, ...], "2":[...], ... }
        return try {
            val s = appCtx.assets.open("pages/page_ayahs_map.json").use { it.readBytes().decodeToString() }
            val root = JSONObject(s)
            val arr = root.optJSONArray(page.toString()) ?: return null
            val pairs = mutableListOf<Pair<Int, Int>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sura  = o.getInt("sura")
                val start = o.getInt("start")
                val end   = o.getInt("end")
                for (a in start..end) pairs.add(sura to a)
            }
            pairs
        } catch (_: Exception) { null }
    }

    private fun buildPairsForSurah(surah: Int): List<Pair<Int, Int>> {
        // يعتمد على quran.json: [{ id:1, verses:[{id:1,text:...}, ...] }, ...]
        return try {
            val s = appCtx.assets.open("quran.json").use { it.readBytes().decodeToString() }
            val arr = JSONArray(s)
            val surahObj = (0 until arr.length())
                .map { arr.getJSONObject(it) }
                .firstOrNull { it.getInt("id") == surah }
                ?: return emptyList()
            val verses = surahObj.getJSONArray("verses")
            val out = ArrayList<Pair<Int, Int>>(verses.length())
            for (i in 0 until verses.length()) {
                val v = verses.getJSONObject(i)
                out.add(surah to v.getInt("id"))
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun buildPairsForAll(): List<Pair<Int, Int>> {
        return try {
            val s = appCtx.assets.open("quran.json").use { it.readBytes().decodeToString() }
            val arr = JSONArray(s)
            val out = mutableListOf<Pair<Int, Int>>()
            for (i in 0 until arr.length()) {
                val surahObj = arr.getJSONObject(i)
                val surahId = surahObj.getInt("id")
                val verses = surahObj.getJSONArray("verses")
                for (j in 0 until verses.length()) {
                    val v = verses.getJSONObject(j)
                    out.add(surahId to v.getInt("id"))
                }
            }
            out
        } catch (_: Exception) { emptyList() }
    }

    private fun buildPairsForJuzOrFallback(pageNow: Int, surahNow: Int): List<Pair<Int, Int>> {
        val pageRanges = tryReadJuzPagesMap() ?: return buildPairsForSurah(surahNow)
        val currentJuz = guessJuzFromPage(pageNow, pageRanges) ?: return buildPairsForSurah(surahNow)
        val (pStart, pEnd) = currentJuz
        val pairs = mutableSetOf<Pair<Int, Int>>()
        for (p in pStart..pEnd) {
            buildPairsForPage(p)?.let { pairs.addAll(it) }
        }
        return if (pairs.isEmpty()) buildPairsForSurah(surahNow) else pairs.toList()
    }

    private fun tryReadJuzPagesMap(): Map<Int, Pair<Int, Int>>? {
        fun readJsonSafe(path: String): JSONObject? = try {
            val s = appCtx.assets.open(path).use { it.readBytes().decodeToString() }
            JSONObject(s)
        } catch (_: Exception) { null }

        val obj = readJsonSafe("pages/juz_pages_map.json") ?: readJsonSafe("pages/juz_map.json") ?: return null
        val res = mutableMapOf<Int, Pair<Int, Int>>()
        for (j in 1..30) {
            val o = obj.optJSONObject(j.toString()) ?: continue
            val start = o.optInt("start", o.optInt("pageStart", -1))
            val end   = o.optInt("end",   o.optInt("pageEnd",  -1))
            if (start > 0 && end > 0) res[j] = start to end
        }
        return if (res.isEmpty()) null else res
    }

    private fun guessJuzFromPage(pageNow: Int, pageRanges: Map<Int, Pair<Int, Int>>): Pair<Int, Int>? {
        pageRanges.values.forEach { (s, e) ->
            if (pageNow in s..e) return s to e
        }
        return null
    }

    // ==================== الحفظ (داخلي/خارجي) ====================
    private interface Saver {
        fun isReady(): Boolean
        fun downloadToTarget(url: String, fileName: String): Boolean
    }

    // ← جعلناها inner لتستطيع استدعاء دوال العضو httpToFile/httpToDoc
    private inner class InternalSaver(private val ctx: Context, qariId: String): Saver {
        private val dir: File = File(
            ctx.getExternalFilesDir(null) ?: ctx.filesDir,
            "recitations/${qariId.lowercase()}"
        ).apply { if (!exists()) mkdirs() }

        override fun isReady() = dir.exists() && dir.canWrite()

        override fun downloadToTarget(url: String, fileName: String): Boolean {
            val out = File(dir, fileName)
            return httpToFile(url, out)
        }
    }

    private inner class ExternalSaver(
        private val ctx: Context,
        private val qariId: String,
        treeUriStr: String
    ): Saver {
        private val treeUri: Uri? = try {
            if (treeUriStr.isBlank()) null else Uri.parse(treeUriStr)
        } catch (_: Exception) { null }

        private var qariDir: DocumentFile? = null

        override fun isReady(): Boolean {
            val root = treeUri?.let { DocumentFile.fromTreeUri(ctx, it) } ?: return false
            val recitations = findOrCreateDir(root, "recitations") ?: return false
            qariDir = findOrCreateDir(recitations, qariId.lowercase())
            return qariDir?.canWrite() == true
        }

        override fun downloadToTarget(url: String, fileName: String): Boolean {
            val parent = qariDir ?: return false
            parent.findFile(fileName)?.let { try { it.delete() } catch (_: Exception) {} }
            val doc = parent.createFile("audio/mpeg", fileName) ?: return false
            return httpToDoc(ctx, url, doc.uri)
        }

        private fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
            parent.listFiles().firstOrNull { it.isDirectory && it.name == name }?.let { return it }
            return if (parent.canWrite()) parent.createDirectory(name) else null
        }
    }

    // ==================== أدوات الشبكة/تنزيل ====================
    private fun httpToFile(urlStr: String, out: File): Boolean {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            conn.connect()
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                }
            }
            out.exists() && out.length() > 1024
        } catch (_: Exception) { false }
    }

    private fun httpToDoc(ctx: Context, urlStr: String, destUri: Uri): Boolean {
        return try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
            }
            conn.connect()
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                ctx.contentResolver.openOutputStream(destUri, "w")?.use { output ->
                    val buf = ByteArray(8 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n == -1) break
                        output.write(buf, 0, n)
                    }
                    output.flush()
                } ?: return false
            }
            true
        } catch (_: Exception) { false }
    }

    // ==================== إشعارات Foreground/Background ====================
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

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID, "تنزيل التلاوات", NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeNotify(id: Int, notif: Notification) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                appCtx, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }
        try { NotificationManagerCompat.from(appCtx).notify(id, notif) } catch (_: SecurityException) {}
    }

    private fun createForeground(progress: Int, text: String): ForegroundInfo {
        val notif = NotificationCompat.Builder(appCtx, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("تنزيل التلاوات")
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
            .setContentTitle("تنزيل التلاوات")
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
            .setContentTitle("تنزيل التلاوات")
            .setContentText("اكتمل: $done / $total")
            .setOngoing(false)
            .setOnlyAlertOnce(true)
            .build()
        safeNotify(NOTI_ID, notif)
    }

    // ==================== أدوات صغيرة ====================
    private fun normalizeBase(base: String): String {
        if (base.isBlank()) return ""
        return if (base.endsWith("/")) base else "$base/"
    }
}
