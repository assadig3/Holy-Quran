// File: app/src/main/java/com/hag/al_quran/ui/PageBulkPrefetch.kt
package com.hag.al_quran.ui

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * تنزيل صفحات المصحف إلى كاش Glide من "مصدر واحد فقط" (بدون أي Fallback).
 * اضبط BASE على المستودع الذي تريد الاعتماد عليه (RAW أو jsDelivr) ثم انتهى.
 *
 * ملاحظات:
 * - لا يغيّر هذا الملف أي روابط؛ أنت تختارها هنا يدويًا.
 * - مناسب عندما تريد منع خلط النسخ الآتية من مصادر متعددة.
 * - callbacks اختيارية لمتابعة التقدم.
 */
object PageBulkPrefetch {

    /** عطّل الشبكة إن أردت إيقاف أي تنزيلات (للاختبار). */
    private const val NETWORK_ENABLED = true

    /** اختر واحدًا فقط:
     *  - RAW GitHub:   https://raw.githubusercontent.com/assadig3/quran-pages/main/pages
     *  - jsDelivr:     https://cdn.jsdelivr.net/gh/assadig3/quran-pages@main/pages
     */
    // private const val BASE = "https://raw.githubusercontent.com/assadig3/quran-pages/main/pages"
    private const val BASE = "https://cdn.jsdelivr.net/gh/assadig3/quran-pages@main/pages"

    /** إن أردت كسر الكاش وقت التجربة اجعلها true (يلحق ?v=<minute>). */
    private const val CACHE_BUST = false

    private const val TOTAL_PAGES = 604

    private fun urlFor(page: Int): String {
        val u = "$BASE/page_${page}.webp"
        return if (CACHE_BUST) "$u?v=${System.currentTimeMillis() / 60000}" else u
    }

    /**
     * تنزيل صفحة واحدة إلى كاش Glide من المصدر الواحد فقط.
     * يرمي استثناء عند الفشل (لا توجد أي محاولات بديلة).
     *
     * ملاحظة: استدعها خارج الخيط الرئيسي (أو اتركك كما هو — submit().get() يحجب).
     */
    @Throws(Exception::class)
    fun prefetchPageRetry(
        context: Context,
        page: Int
    ) {
        if (!NETWORK_ENABLED) return
        val url = urlFor(page)
        Glide.with(context)
            .downloadOnly()
            .load(url)
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .submit()
            .get() // نفّذ خارج الـ UI Thread
    }

    /**
     * تنزيل نطاق صفحات بتوازي مضبوط.
     * @param parallelism عدد الاتصالات المتزامنة (مثلاً 4..8)
     */
    fun prefetchRange(
        context: Context,
        fromPage: Int,
        toPage: Int,
        parallelism: Int = 6,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onFinished: (success: Boolean) -> Unit = {}
    ) {
        if (!NETWORK_ENABLED) { onFinished(false); return }

        val start = fromPage.coerceAtLeast(1)
        val end = toPage.coerceAtMost(TOTAL_PAGES)
        if (start > end) { onFinished(false); return }

        val total = end - start + 1
        val done = AtomicInteger(0)
        val sem = Semaphore(parallelism.coerceAtLeast(1))
        val exec = Executors.newCachedThreadPool()

        for (p in start..end) {
            exec.execute {
                sem.acquire()
                try {
                    var ok = true
                    try {
                        prefetchPageRetry(context, p)
                    } catch (_: Throwable) {
                        ok = false
                    } finally {
                        val d = done.incrementAndGet()
                        onProgress(d, total)
                        sem.release()
                    }
                } catch (_: Throwable) {
                    val d = done.incrementAndGet()
                    onProgress(d, total)
                    sem.release()
                }
            }
        }

        exec.shutdown()
        Thread {
            exec.awaitTermination(45, TimeUnit.MINUTES)
            onFinished(done.get() == total)
        }.start()
    }

    /**
     * تنزيل كل الصفحات (1..604) من المصدر الواحد فقط.
     */
    fun prefetchAllPages(
        context: Context,
        parallelism: Int = 6,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onFinished: (success: Boolean) -> Unit = {}
    ) {
        prefetchRange(context, 1, TOTAL_PAGES, parallelism, onProgress, onFinished)
    }

    /**
     * تنزيل سريع حول صفحة معيّنة (مصدر واحد فقط).
     */
    fun prefetchAround(
        context: Context,
        currentPage: Int,
        radius: Int = 2,
        parallelism: Int = 3,
        onFinished: () -> Unit = {}
    ) {
        val from = (currentPage - radius).coerceAtLeast(1)
        val to = (currentPage + radius).coerceAtMost(TOTAL_PAGES)
        prefetchRange(context, from, to, parallelism, onProgress = { _, _ -> }, onFinished = { _ -> onFinished() })
    }

    /**
     * (اختياري) تنظيف كاش Glide:
     * - استدعِ clearDiskCache() على خيط خلفي.
     * - استدعِ clearMemory() على الخيط الرئيسي.
     */
    fun clearGlideCaches(context: Context) {
        Thread { Glide.get(context).clearDiskCache() }.start()
        // يجب على الـ UI Thread:
        // (استدعها من الـ Activity/Fragment على الـ main thread)
        // Glide.get(context).clearMemory()
    }
}
