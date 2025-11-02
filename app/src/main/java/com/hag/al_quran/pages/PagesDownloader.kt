package com.hag.al_quran.pages

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import okhttp3.Cache
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

class PagesDownloader(
    private val context: Context,
    private val parallelism: Int = 4,          // عدد المهام المتزامنة (جرّب 4..6)
    private val successThreshold: Double = 0.95 // نعتبر النجاح عند ≥ 95%
) {

    interface Listener {
        fun onProgress(current: Int, total: Int)
        fun onComplete(success: Boolean, errorMessage: String? = null)
    }

    companion object {
        private const val TOTAL_PAGES = 604
        private const val DEFAULT_BRANCH = "main"
        private const val MIN_VALID_BYTES = 8 * 1024 // ≥ 8KB لتجنّب حفظ صفحات خطأ HTML

        private fun buildCandidateUrls(pageNumber: Int): List<String> {
            val file = "page_${pageNumber}.webp"
            val repo = "assadig3/quran-pages"
            val path = "pages/$file"
            return listOf(
                // غالباً أسرع
                "https://cdn.jsdelivr.net/gh/$repo@$DEFAULT_BRANCH/$path",
                // احتياطي
                "https://raw.githubusercontent.com/$repo/$DEFAULT_BRANCH/$path"
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var isCanceled = false

    // نحتفظ بالنداءات الجارية لإلغائها فوراً عند cancel()
    private val inFlightCalls = ConcurrentLinkedQueue<Call>()

    private val client: OkHttpClient by lazy {
        val cacheDir = File(context.cacheDir, "http_cache")
        val cacheSize = 50L * 1024 * 1024 // 50MB
        OkHttpClient.Builder()
            .cache(Cache(cacheDir, cacheSize))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .followSslRedirects(true)
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            // مبدئياً: لو السيرفر لم يضع Cache-Control، نساعد الاستفادة من الكاش
            .addNetworkInterceptor { chain ->
                val req = chain.request().newBuilder()
                    .header("User-Agent", "AlQuranApp/1.0 (Android)")
                    .build()
                val resp = chain.proceed(req)
                val hasCacheHdr = resp.header("Cache-Control") != null || resp.header("Expires") != null
                if (!hasCacheHdr) {
                    resp.newBuilder()
                        .header("Cache-Control", "public, max-age=31536000, immutable")
                        .build()
                } else resp
            }
            .build()
    }

    private val pagesDir: File by lazy {
        File(context.filesDir, "pages").apply { if (!exists()) mkdirs() }
    }

    fun isCached(): Boolean {
        val count = pagesDir.listFiles()?.count { it.isFile && it.length() > MIN_VALID_BYTES } ?: 0
        return count >= (TOTAL_PAGES * 0.9).toInt()
    }

    fun cancel() {
        isCanceled = true
        // ألغِ الكوروتينات
        scope.coroutineContext.cancelChildren()
        // ألغِ النداءات الجارية
        while (true) {
            val call = inFlightCalls.poll() ?: break
            runCatching { call.cancel() }
        }
    }

    fun start(listener: Listener) {
        isCanceled = false

        scope.launch {
            try {
                // احسب الصفحات الناقصة فقط لتقليل العمل
                val missing = (1..TOTAL_PAGES).filterNot { pageOk(it) }
                val already = TOTAL_PAGES - missing.size

                val progress = AtomicInteger(already)
                withContext(Dispatchers.Main) {
                    listener.onProgress(progress.get(), TOTAL_PAGES)
                }

                if (missing.isNotEmpty()) {
                    val sem = Semaphore(max(1, min(parallelism, 8))) // سقف واقعي
                    missing.map { page ->
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                if (isCanceled) return@async
                                downloadPageWithFallback(page)
                                // حدّث التقدم فقط عند نجاح الصفحة
                                if (pageOk(page)) {
                                    val cur = progress.incrementAndGet()
                                    withContext(Dispatchers.Main) {
                                        listener.onProgress(cur, TOTAL_PAGES)
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }

                if (isCanceled) {
                    withContext(Dispatchers.Main) { listener.onComplete(false, "Canceled by user.") }
                    return@launch
                }

                val okFiles = pagesDir.listFiles()?.count { it.isFile && it.length() > MIN_VALID_BYTES } ?: 0
                val success = okFiles >= (TOTAL_PAGES * successThreshold).toInt()
                if (success) {
                    withContext(Dispatchers.Main) { listener.onComplete(true) }
                } else {
                    val missingCount = TOTAL_PAGES - okFiles
                    withContext(Dispatchers.Main) {
                        listener.onComplete(false, "Failed to download $missingCount pages. Please try again later.")
                    }
                }
            } catch (e: CancellationException) {
                withContext(Dispatchers.Main) { listener.onComplete(false, "Canceled by user.") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { listener.onComplete(false, e.localizedMessage ?: e.javaClass.simpleName) }
            }
        }
    }

    private fun pageOk(i: Int): Boolean {
        val f = File(pagesDir, "page_${i}.webp")
        return f.exists() && f.length() > MIN_VALID_BYTES
    }

    private suspend fun downloadPageWithFallback(page: Int) {
        if (isCanceled) return
        val outFile = File(pagesDir, "page_${page}.webp")
        val urls = buildCandidateUrls(page)

        // لو الملف موجود وكبير كفاية، لا داعي
        if (outFile.exists() && outFile.length() > MIN_VALID_BYTES) return

        // جرّب كل مرشح مع إعادة محاولات خفيفة
        for (url in urls) {
            var attempt = 0
            while (attempt < 2 && !isCanceled) {
                attempt++
                val ok = runCatching { downloadToFile(url, outFile) }.getOrElse { false }
                if (ok && outFile.length() > MIN_VALID_BYTES) return
                // تنظيف ومحاولة ثانية
                runCatching { if (outFile.exists()) outFile.delete() }
                // backoff بسيط
                delay(attempt * 200L)
            }
        }
    }

    private fun newCall(req: Request): Call {
        val call = client.newCall(req)
        inFlightCalls.add(call)
        // شِيل من القائمة عند الانتهاء
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                inFlightCalls.remove(call)
            }
            override fun onResponse(call: Call, response: Response) {
                inFlightCalls.remove(call)
                response.close()
            }
        })
        // نحتاج تنفيذ متزامن هنا، لذلك سنزيله ونعيد إضافته يدويًا عند التنفيذ الحقيقي
        inFlightCalls.remove(call)
        return call
    }

    private fun downloadToFile(url: String, outFile: File): Boolean {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "AlQuranApp/1.0 (Android)")
            .build()

        val call = client.newCall(req)
        inFlightCalls.add(call)

        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body ?: return false

                val tmp = File(outFile.parentFile, outFile.name + ".part")
                body.byteStream().use { input ->
                    FileOutputStream(tmp).use { output ->
                        copyStream(input, output, 32 * 1024)
                    }
                }
                if (tmp.length() <= MIN_VALID_BYTES) {
                    tmp.delete()
                    return false
                }
                if (outFile.exists()) outFile.delete()
                return tmp.renameTo(outFile)
            }
        } finally {
            inFlightCalls.remove(call)
        }
    }

    private fun copyStream(input: InputStream, output: FileOutputStream, bufferSize: Int = 32 * 1024) {
        val buffer = ByteArray(bufferSize)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }
}
