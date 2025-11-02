// File: app/src/main/java/com/hag/al_quran/work/QuranRecitationDownloadWorker.kt
package com.hag.al_quran.worker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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

/**
 * هذا الـWorker مسؤول عن تنزيل ملفات التلاوة MP3 للقارئ المختار.
 *
 * - يدعم 4 نطاقات:
 *   PAGE  = آيات الصفحة الحالية فقط
 *   SURAH = السورة كاملة
 *   JUZ   = الجزء (تجريب/تقريبي)
 *   ALL   = القرآن كامل
 *
 * - يدعم حفظ الملفات إما داخليًا في مجلد خاص بالتطبيق
 *   أو خارجيًا داخل مجلد SAF اختاره المستخدم (مثل SD card).
 *
 * كل ملف يتم حفظه باسم مثل: 001002.mp3 (سورة 1 آية 2)
 *
 * مدخلات العمل (inputData):
 *   scope          "PAGE" | "SURAH" | "JUZ" | "ALL"
 *   pageNow        Int
 *   surahNow       Int
 *   qariId         String  (مثل "fares")
 *   audioBase      String  (الرابط الأساسي بدون 001002.mp3 في النهاية, مثلاً "https://.../Fares_Abbad_64kbps/")
 *   storageTarget  "internal" | "external"
 *   treeUri        String  (URI للمجلد الخارجي لو اخترناه)
 *
 * الخرج (progress / result):
 *   done           Int
 *   total          Int
 */
class QuranRecitationDownloadWorker(
    private val appCtx: Context,
    params: WorkerParameters
) : CoroutineWorker(appCtx, params) {

    companion object {
        const val UNIQUE_NAME = "recitations_download_unique"

        // مفاتيح الإدخال
        private const val K_SCOPE   = "scope"
        private const val K_PAGE    = "pageNow"
        private const val K_SURAH   = "surahNow"
        private const val K_QARI    = "qariId"
        private const val K_AUDIO   = "audioBase"
        private const val K_STORE   = "storageTarget" // "internal" أو "external"
        private const val K_TREE    = "treeUri"       // لو external

        // مفاتيح المخرجات/التقدم
        const val K_DONE  = "done"
        const val K_TOTAL = "total"

        // قناة الإشعارات
        private const val CHANNEL_ID = "quran_recitations_dl"
        private const val NOTI_ID    = 3345

        /**
         * استدعِ هذه الدالة من الـActivity لبدء التنزيل.
         *
         * مثال:
         * QuranRecitationDownloadWorker.enqueue(
         *     context       = this,
         *     scope         = "PAGE",
         *     pageNow       = currentPage,
         *     surahNow      = currentSurah,
         *     qariId        = currentQariId,
         *     audioBase     = baseUrlFromQari, // مثل "https://cdn.../fares_64kbps/"
         *     storageTarget = "external",      // أو "internal"
         *     treeUri       = treeUriStr       // إذا خارجي، وإلا ""
         * )
         */
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
                UNIQUE_NAME,
                ExistingWorkPolicy.REPLACE,
                req
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }

    // ============================= تنفيذ الـ Worker =============================
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {

        createChannel()

        // 1. اقرأ المدخلات
        val scopeIn        = (inputData.getString(K_SCOPE) ?: "PAGE").uppercase()
        val pageNow        = inputData.getInt(K_PAGE, 1)
        val surahNow       = inputData.getInt(K_SURAH, 1).coerceIn(1, 114)
        val qariId         = inputData.getString(K_QARI) ?: "fares"
        val audioBaseRaw   = inputData.getString(K_AUDIO) ?: ""
        val audioBase      = normalizeBase(audioBaseRaw)
        val storageTarget  = inputData.getString(K_STORE) ?: "internal"
        val treeUriStr     = inputData.getString(K_TREE) ?: ""

        // 2. جهّز قائمة (سورة, آية) المطلوب تنزيلها
        val pairs: List<Pair<Int, Int>> = when (scopeIn) {
            "SURAH" -> buildPairsForSurah(surahNow)
            "PAGE"  -> buildPairsForPage(pageNow) ?: buildPairsForSurah(surahNow)
            "JUZ"   -> buildPairsForJuzOrFallback(pageNow, surahNow)
            "ALL"   -> buildPairsForAll()
            else    -> buildPairsForPage(pageNow) ?: buildPairsForSurah(surahNow)
        }

        val total = pairs.size
        if (total == 0) {
            return@withContext Result.failure()
        }

        setProgressAsync(workDataOf(K_DONE to 0, K_TOTAL to total))

        // 3. إشعار foreground أو background (حسب وضع التطبيق و صلاحية الإشعار)
        val useFgs = canStartFgs(appCtx) && hasPostNotifPermission(appCtx)
        if (useFgs) {
            setForeground(createForeground(0, "بدء تنزيل التلاوات…"))
        } else {
            notifyProgress(0, "بدء تنزيل التلاوات…")
        }

        val saver: Saver = try {
            if (storageTarget == "external") {
                ExternalSaver(appCtx, qariId, treeUriStr)
            } else {
                InternalSaver(appCtx, qariId)
            }
        } catch (e: Exception) {
            // لو في أي مشكلة في إعداد الحفظ، نوقف بدون كراش
            return@withContext Result.failure()
        }

        if (!saver.isReady()) {
            // مثلا URI غير صالح أو ما يقدر يكتب
            return@withContext Result.failure()
        }

        // 5. نزّل ملف ملف
        var done = 0
        for ((suraNum, ayahNum) in pairs) {
            if (isStopped) break

            val surah3 = String.format("%03d", suraNum)
            val ayah3  = String.format("%03d", ayahNum)
            val url    = "${audioBase}${surah3}${ayah3}.mp3" // مثال https://.../001002.mp3
            val fileName = "${surah3}${ayah3}.mp3"

            val ok = saver.downloadToTarget(url, fileName)
            if (ok) {
                done++
            } else {
                // محاولة ثانية سريعة
                delay(250)
                if (saver.downloadToTarget(url, fileName)) {
                    done++
                }
            }

            // تقدّم
            setProgressAsync(workDataOf(K_DONE to done, K_TOTAL to total))
            val pct = (done * 100f / total).toInt().coerceIn(0, 100)
            val msgNow = "تنزيل التلاوات — $done / $total"
            if (useFgs) {
                setForeground(createForeground(pct, msgNow))
            } else {
                notifyProgress(pct, msgNow)
            }
        }

        if (!useFgs) {
            notifyDone(done, total)
        }

        return@withContext Result.success(
            workDataOf(K_DONE to done, K_TOTAL to total)
        )
    }

    // ======================= بناء القوائم (سورة/آية) =======================

    /**
     * قراءة آيات الصفحة من ملف assets/pages/page_ayahs_map.json
     * الصيغة:
     * {
     *   "1":[{"sura":1,"start":1,"end":7}, ...],
     *   "2":[...],
     *   ...
     * }
     */
    private fun buildPairsForPage(page: Int): List<Pair<Int, Int>>? {
        return try {
            val s = appCtx.assets.open("pages/page_ayahs_map.json").use {
                it.readBytes().decodeToString()
            }
            val root = JSONObject(s)
            val arr = root.optJSONArray(page.toString()) ?: return null
            val out = mutableListOf<Pair<Int, Int>>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val sura  = o.getInt("sura")
                val start = o.getInt("start")
                val end   = o.getInt("end")
                for (a in start..end) {
                    out.add(sura to a)
                }
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    /**
     * كل آيات السورة من ملف quran.json
     * الصيغة المتوقعة:
     * [
     *    { "id":1, "verses":[{"id":1,"text":"..."}, ...] },
     *    { "id":2, ... },
     *    ...
     * ]
     */
    private fun buildPairsForSurah(surah: Int): List<Pair<Int, Int>> {
        return try {
            val s = appCtx.assets.open("quran.json").use {
                it.readBytes().decodeToString()
            }
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * كل القرآن كامل
     */
    private fun buildPairsForAll(): List<Pair<Int, Int>> {
        return try {
            val s = appCtx.assets.open("quran.json").use {
                it.readBytes().decodeToString()
            }
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
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * تحميل الجزء: نعتمد على خريطة صفحات الجزء
     * assets/pages/juz_pages_map.json أو pages/juz_map.json
     * ثم نجمّع كل الآيات من هذه الصفحات.
     * لو فشل، نرجع للسورة الحالية.
     */
    private fun buildPairsForJuzOrFallback(pageNow: Int, surahNow: Int): List<Pair<Int, Int>> {
        val pageRanges = tryReadJuzPagesMap() ?: return buildPairsForSurah(surahNow)
        val (pStart, pEnd) = guessJuzPageRange(pageNow, pageRanges)
            ?: return buildPairsForSurah(surahNow)

        val pairs = linkedSetOf<Pair<Int, Int>>() // Linked علشان يظل الترتيب
        for (p in pStart..pEnd) {
            buildPairsForPage(p)?.let { pairs.addAll(it) }
        }
        return if (pairs.isEmpty()) buildPairsForSurah(surahNow) else pairs.toList()
    }

    private fun tryReadJuzPagesMap(): Map<Int, Pair<Int, Int>>? {
        fun readJsonSafe(path: String): JSONObject? = try {
            val s = appCtx.assets.open(path).use { it.readBytes().decodeToString() }
            JSONObject(s)
        } catch (_: Exception) {
            null
        }

        val obj = readJsonSafe("pages/juz_pages_map.json")
            ?: readJsonSafe("pages/juz_map.json")
            ?: return null

        val res = mutableMapOf<Int, Pair<Int, Int>>()
        for (j in 1..30) {
            val o = obj.optJSONObject(j.toString()) ?: continue
            val start = o.optInt("start", o.optInt("pageStart", -1))
            val end   = o.optInt("end",   o.optInt("pageEnd",  -1))
            if (start > 0 && end > 0) {
                res[j] = start to end
            }
        }
        return if (res.isEmpty()) null else res
    }

    private fun guessJuzPageRange(
        pageNow: Int,
        pageRanges: Map<Int, Pair<Int, Int>>
    ): Pair<Int, Int>? {
        pageRanges.values.forEach { (startP, endP) ->
            if (pageNow in startP..endP) return startP to endP
        }
        return null
    }

    // ======================= التخزين الداخلي / الخارجي =======================

    /**
     * واجهة عامة لحفظ الملفات (داخلي أو خارجي).
     */
    private interface Saver {
        fun isReady(): Boolean
        fun downloadToTarget(url: String, fileName: String): Boolean
    }

    /**
     * الحفظ داخل مساحة التطبيق:
     * /Android/data/com.hag.al_quran/files/recitations/{qariId}/001002.mp3
     *
     * هذا المجلد يمكن الوصول له من الكمبيوتر (USB) حتى لو ما ظهر في مدير الملفات.
     */
    private class InternalSaver(ctx: Context, qariId: String) : Saver {
        private val dir: File = File(
            ctx.getExternalFilesDir(null) ?: ctx.filesDir,
            "recitations/${qariId.lowercase()}"
        ).apply {
            if (!exists()) mkdirs()
        }

        override fun isReady(): Boolean {
            return dir.exists() && dir.canWrite()
        }

        override fun downloadToTarget(url: String, fileName: String): Boolean {
            val outFile = File(dir, fileName)
            return httpToFile(url, outFile)
        }

        /**
         * تنزيل من URL إلى ملف عادي
         */
        private fun httpToFile(urlStr: String, out: File): Boolean {
            return try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout    = 15_000
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
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * الحفظ في موقع خارجي يختاره المستخدم (SAF / بطاقة SD مثلاً):
     * مجلد رئيسي (treeUri) ← recitations ← qariId ← ملفات mp3
     */
    private class ExternalSaver(
        private val ctx: Context,
        private val qariId: String,
        treeUriStr: String
    ) : Saver {

        private val treeUri: Uri? = try {
            if (treeUriStr.isBlank()) null else Uri.parse(treeUriStr)
        } catch (_: Exception) {
            null
        }

        private var qariDir: DocumentFile? = null

        override fun isReady(): Boolean {
            val root = treeUri?.let { DocumentFile.fromTreeUri(ctx, it) } ?: return false
            // مجلد recitations
            val recitationsDir = findOrCreateDir(root, "recitations") ?: return false
            // مجلد القارئ
            qariDir = findOrCreateDir(recitationsDir, qariId.lowercase())
            return qariDir?.canWrite() == true
        }

        override fun downloadToTarget(url: String, fileName: String): Boolean {
            val parent = qariDir ?: return false

            // لو الملف موجود بنفس الاسم نحذفه أولاً
            parent.findFile(fileName)?.let { old ->
                try { old.delete() } catch (_: Exception) {}
            }

            // أنشئ ملف جديد
            val doc = parent.createFile("audio/mpeg", fileName) ?: return false
            return httpToDoc(url, doc.uri)
        }

        /**
         * تنزيل من URL إلى Uri باستخدام ContentResolver
         */
        private fun httpToDoc(urlStr: String, destUri: Uri): Boolean {
            return try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000
                    readTimeout    = 15_000
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
            } catch (_: Exception) {
                false
            }
        }

        /**
         * يساعدنا نلقى أو ننشئ مجلد داخل SAF
         */
        private fun findOrCreateDir(parent: DocumentFile, name: String): DocumentFile? {
            // هل المجلد موجود أصلاً؟
            parent.listFiles()
                .firstOrNull { it.isDirectory && it.name == name }
                ?.let { return it }

            // لو ما موجود، جرّب أنشئه
            return if (parent.canWrite()) {
                parent.createDirectory(name)
            } else {
                null
            }
        }
    }

    // ======================= إشعارات التقدّم =======================

    /**
     * هل التطبيق حالياً في الواجهة؟ لو نعم نقدر نعمل ForegroundInfo
     * (ووقتها النظام يسمح لنا نظهر إشعار قيد التشغيل المستمر)
     */
    private fun canStartFgs(ctx: Context): Boolean {
        val state = ProcessLifecycleOwner.get().lifecycle.currentState
        return state.isAtLeast(Lifecycle.State.STARTED)
    }

    private fun hasPostNotifPermission(ctx: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(
                ctx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "تنزيل التلاوات",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(ch)
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeNotify(id: Int, notif: Notification) {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                appCtx,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        try {
            NotificationManagerCompat.from(appCtx).notify(id, notif)
        } catch (_: SecurityException) {
        }
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

        // على أندرويد الحديث لازم نحدد نوع الخدمة (dataSync)
        // وهذا النوع لازم يكون معلن في الـ Manifest (الخطوة 2)
        return ForegroundInfo(
            NOTI_ID,
            notif,
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
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

    /**
     * إشعار بعد الانتهاء
     */
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

    // ======================= أدوات صغيرة =======================

    private fun normalizeBase(base: String): String {
        if (base.isBlank()) return ""
        return if (base.endsWith("/")) base else "$base/"
    }
}
