// File: app/src/main/java/com/hag/al_quran/ui/PageBulkPrefetch.kt
package com.hag.al_quran.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import com.bumptech.glide.Glide
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max

/**
 * تنزيل صفحة مع 3 محاولات:
 * 1) عبر CDN
 * 2) عبر RAW من GitHub
 * 3) إعادة محاولة CDN مع cache-bust لمنع الكاش الوسيط
 *
 * تعيد الدالة true فقط إذا تم تنزيل صورة WebP سليمة بأبعاد معقولة.
 */
object PageBulkPrefetch {

    private const val MIN_VALID_BYTES = 8 * 1024      // لتجنب حفظ HTML أو ملفات ناقصة
    private const val MIN_OK_WIDTH = 800              // حد أدنى منطقي لأبعاد الصفحة
    private const val MIN_OK_HEIGHT = 1200

    fun prefetchPageRetry(
        context: Context,
        page: Int
    ): Boolean {
        // أول 3 صفحات محلية أصلًا
        if (page in 1..3) return true

        val cdn = "https://cdn.jsdelivr.net/gh/assadig3/quran-pages@main/pages/page_${page}.webp"
        val raw = "https://raw.githubusercontent.com/assadig3/quran-pages/main/pages/page_${page}.webp"
        val cdnBust = "$cdn?cb=${SystemClock.uptimeMillis() / 60000}" // يتغير كل دقيقة

        val models: List<Any> = listOf(cdn, raw, cdnBust)

        for (m in models) {
            try {
                // Glide سيحفظ الملف في الكاش ويرجع مسارًا مؤقتًا
                val f: File = Glide.with(context)
                    .downloadOnly()
                    .load(m)
                    .submit()
                    .get()

                // تحققات السلامة قبل اعتبارها نجاحًا
                if (isValidWebP(f) && hasSaneDimensions(f)) {
                    return true
                }
                // لو فشل التحقق، جرّب المصدر التالي
            } catch (_: Exception) {
                // جرّب المصدر التالي
            }
        }
        return false
    }

    // ===== Helpers =====

    /** فحص رأس WebP: RIFF....WEBP + حجم RIFF منطقي */
    private fun isValidWebP(file: File): Boolean {
        if (!file.exists() || file.length() < max(12, MIN_VALID_BYTES).toLong()) return false
        val header = file.inputStream().use { inp ->
            val b = ByteArray(12)
            val r = inp.read(b)
            if (r < 12) return false
            b
        }
        val riff = String(header.copyOfRange(0, 4))
        val webp = String(header.copyOfRange(8, 12))
        if (riff != "RIFF" || webp != "WEBP") return false

        // حجم RIFF (ليتل إنديَن) يجب أن يتوافق تقريبًا مع حجم الملف
        val sizeLE = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return file.length() >= (sizeLE.toLong() + 8L)
    }

    /** فحص أبعاد الصورة لتجنّب ملفات صغيرة/مبتورة */
    private fun hasSaneDimensions(file: File): Boolean {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, opts)
        return (opts.outWidth >= MIN_OK_WIDTH && opts.outHeight >= MIN_OK_HEIGHT)
    }
}
