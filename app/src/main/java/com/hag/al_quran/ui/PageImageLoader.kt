package com.hag.al_quran.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.hag.al_quran.AyahBoundsRepo
import com.hag.al_quran.R
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

object PageImageLoader {

    private const val USE_CDN = true
    private const val REMOTE_BASE_CDN =
        "https://cdn.jsdelivr.net/gh/assadig3/quran-pages@main/pages"
    private const val REMOTE_BASE_RAW =
        "https://raw.githubusercontent.com/assadig3/quran-pages/main/pages"

    private fun remoteBase(): String = if (USE_CDN) REMOTE_BASE_CDN else REMOTE_BASE_RAW

    private fun localAssetFor(page: Int) = "file:///android_asset/pages/page_${page}.webp"
    private fun primaryUrlFor(page: Int) = "${remoteBase()}/page_${page}.webp"
    private fun fallbackUrlFor(page: Int) =
        if (remoteBase() == REMOTE_BASE_CDN) "${REMOTE_BASE_RAW}/page_${page}.webp"
        else "${REMOTE_BASE_CDN}/page_${page}.webp"

    /** نستخدم هذا الـ model في العرض: أصول محلية لأول 3 صفحات، ثم الشبكة */
    private fun modelFor(page: Int) = if (page <= 3) localAssetFor(page) else primaryUrlFor(page)

    enum class Mode { NORMAL, FAST_PREVIEW }

    // ---------- عرض بصورة مع محاولات + بديل ----------
    fun load(context: Context, pageNumber: Int, into: ImageView) {
        loadWithCallbacks(context, pageNumber, into, Mode.NORMAL, onStart = {}, onReady = {}, onFail = {})
    }

    fun load(context: Context, pageNumber: Int, into: ImageView, mode: Mode) {
        loadWithCallbacks(context, pageNumber, into, mode, onStart = {}, onReady = {}, onFail = {})
    }

    /**
     * يعرض الصورة مع 3 محاولات: CDN ➜ RAW ➜ CDN مع cache-bust.
     * يستدعي onStart/onReady/onFail لربط شريط التحميل.
     */
    fun loadWithCallbacks(
        context: Context,
        pageNumber: Int,
        into: ImageView,
        mode: Mode = Mode.NORMAL,
        onStart: () -> Unit,
        onReady: () -> Unit,
        onFail: () -> Unit
    ) {
        val isLocal = pageNumber <= 3
        if (isLocal) {
            // الأصول المحلية لا تحتاج محاولات
            request(context, localAssetFor(pageNumber), mode, listener(onReady, onFail))
                .into(into)
            onStart()
            return
        }

        val tries = arrayOf(
            primaryUrlFor(pageNumber),
            fallbackUrlFor(pageNumber),
            primaryUrlFor(pageNumber) + "?v=${System.currentTimeMillis() / 60000}" // cache-bust دقيقة
        )

        onStart()
        tryLoadChain(context, tries, into, mode, onReady, onFail, idx = 0)
    }

    private fun tryLoadChain(
        context: Context,
        urls: Array<String>,
        into: ImageView,
        mode: Mode,
        onReady: () -> Unit,
        onFail: () -> Unit,
        idx: Int
    ) {
        if (idx >= urls.size) { onFail(); return }
        val url = urls[idx]

        request(context, url, mode, object : RequestListener<Drawable> {
            override fun onLoadFailed(
                e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
            ): Boolean {
                // backoff صغير قبل المحاولة التالية
                into.postDelayed({ tryLoadChain(context, urls, into, mode, onReady, onFail, idx + 1) }, 120L)
                return true // منع Glide من استدعاء target بالخطأ
            }

            override fun onResourceReady(
                resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean
            ): Boolean {
                onReady()
                return false // اترك Glide يضع الـ Drawable في ImageView
            }
        }).into(into)
    }

    private fun request(
        context: Context,
        model: String,
        mode: Mode,
        listener: RequestListener<Drawable>
    ) = Glide.with(context)
        .load(model)
        .apply(
            RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .downsample(DownsampleStrategy.AT_MOST)
                .format(DecodeFormat.PREFER_RGB_565)
                .timeout(15_000)
                .placeholder(R.drawable.ic_placeholder)
                .error(R.drawable.ic_error)
        )
        .priority(Priority.HIGH)
        .also { if (mode == Mode.FAST_PREVIEW) it.thumbnail(0.25f) }
        .transition(DrawableTransitionOptions.withCrossFade())
        .listener(listener)

    private fun listener(onReady: () -> Unit, onFail: () -> Unit) = object : RequestListener<Drawable> {
        override fun onLoadFailed(
            e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean
        ): Boolean { onFail(); return false }
        override fun onResourceReady(
            resource: Drawable, model: Any, target: Target<Drawable>?, dataSource: DataSource, isFirstResource: Boolean
        ): Boolean { onReady(); return false }
    }

    // ---------- Prefetch (تنزيل مسبق) مع محاولات ----------
    /**
     * تنزيل صفحة إلى الكاش مع محاولات بديلة. تستدعى callback عند الانتهاء (نجاح/فشل).
     */
    fun prefetchPageRetry(
        context: Context,
        page: Int,
        onDone: (success: Boolean) -> Unit
    ) {
        val isLocal = page <= 3
        if (isLocal) { onDone(true); return }

        val urls = arrayOf(
            primaryUrlFor(page),
            fallbackUrlFor(page),
            primaryUrlFor(page) + "?v=${System.currentTimeMillis() / 60000}"
        )
        prefetchTry(context, urls, 0, onDone)
    }

    private fun prefetchTry(
        context: Context,
        urls: Array<String>,
        idx: Int,
        onDone: (Boolean) -> Unit
    ) {
        if (idx >= urls.size) { onDone(false); return }
        Glide.with(context)
            .downloadOnly()
            .load(urls[idx])
            .diskCacheStrategy(DiskCacheStrategy.DATA)
            .submit()
            .get()
        // لو ما رمى استثناء، نجحت
        onDone(true)
    }

    /** Prefetch حول الصفحة الحالية (سريع وخفيف) */
    fun prefetchAround(context: Context, currentPage: Int, radius: Int = 2) {
        for (p in (currentPage - radius)..(currentPage + radius)) {
            if (p < 1 || p > 604) continue
            Glide.with(context)
                .load(modelFor(p))
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .preload()
        }
    }

    // مساعد قديم
    fun baseSizeFor(boundsList: List<AyahBoundsRepo>): Pair<Float, Float> {
        var maxX = 0f; var maxY = 0f
        for (ab in boundsList) for (s in ab.segs) {
            val ex = (s.x + s.w).toFloat()
            val ey = (s.y + s.h).toFloat()
            if (ex > maxX) maxX = ex
            if (ey > maxY) maxY = ey
        }
        if (maxX <= 0f || maxY <= 0f) return 1080f to 1650f
        return maxX to maxY
    }

    /**
     * تنزيل كل الصفحات بسرعة مع حد أقصى للاتصالات المتزامنة لتقليل الفشل.
     * استعمله إن أردت بدلاً من كودك اليدوي.
     */
    fun prefetchAllPages(
        context: Context,
        parallelism: Int = 6,
        onProgress: (done: Int, total: Int) -> Unit,
        onFinished: () -> Unit
    ) {
        val total = 604
        val done = AtomicInteger(0)
        val sem = Semaphore(parallelism)
        val exec = Executors.newCachedThreadPool()

        for (p in 1..total) {
            exec.execute {
                sem.acquire()
                try {
                    var ok = false
                    try {
                        prefetchPageRetry(context, p) { success -> ok = success }
                    } catch (_: Throwable) { ok = false }
                    finally {
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
            // تباعد صغير لتخفيف الضغط على الخادم
            SystemClock.sleep(10L)
        }

        exec.shutdown()
        Thread {
            exec.awaitTermination(45, TimeUnit.MINUTES)
            onFinished()
        }.start()
    }
}
