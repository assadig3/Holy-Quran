package com.hag.al_quran.net

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit

@GlideModule
class QuranGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // إعدادات افتراضية: سرعة واستهلاك أقل للذاكرة
        builder.setDefaultRequestOptions(
            RequestOptions()
                .downsample(DownsampleStrategy.AT_MOST)
                .format(DecodeFormat.PREFER_RGB_565)
                .disallowHardwareConfig()
        )
        // كاش Glide على القرص (لصور المعالجة/المحوّلة)
        builder.setDiskCache(
            InternalCacheDiskCacheFactory(
                context,
                "glide-disk-cache",
                200L * 1024 * 1024 // 200MB
            )
        )
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // كاش HTTP مستقل (OkHttp) لسرعة أعلى وتكرار أقل للطلبات
        val httpCacheDir = File(context.cacheDir, "http-img-cache")
        val httpCache = Cache(httpCacheDir, 200L * 1024 * 1024) // 200MB

        val dispatcher = Dispatcher().apply {
            maxRequests = 128             // يسمح بطلبات متوازية أكثر
            maxRequestsPerHost = 16
        }

        val client = OkHttpClient.Builder()
            .cache(httpCache)
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()

        // اربط Glide مع OkHttp
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpUrlLoader.Factory(client)
        )
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
