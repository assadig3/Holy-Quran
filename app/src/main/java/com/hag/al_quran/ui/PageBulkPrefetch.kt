// File: app/src/main/java/com/hag/al_quran/ui/PageBulkPrefetch.kt
package com.hag.al_quran.ui

import android.content.Context
import android.os.SystemClock
import com.bumptech.glide.Glide

/**
 * تنزيل صفحة مع 3 محاولات:
 * 1) عبر CDN
 * 2) عبر RAW من GitHub
 * 3) إعادة محاولة CDN مع cache-bust لمنع الكاش الوسيط
 */
object PageBulkPrefetch {

    fun prefetchPageRetry(
        context: Context,
        page: Int
    ) {
        val cdn = "https://cdn.jsdelivr.net/gh/assadig3/quran-pages@main/pages/page_${page}.webp"
        val raw = "https://raw.githubusercontent.com/assadig3/quran-pages/main/pages/page_${page}.webp"
        val cdnBust = "$cdn?cb=${SystemClock.uptimeMillis()}"

        val models: List<Any> = listOf(cdn, raw, cdnBust)

        var last: Exception? = null
        for (m in models) {
            try {
                Glide.with(context)
                    .downloadOnly()
                    .load(m)
                    .submit()
                    .get() // يتم النداء ضمن خيط المنفذ (ليس خيط الواجهة)
                return
            } catch (e: Exception) {
                last = e
                // جرّب المصدر التالي
            }
        }
        // لو فشلت كل المحاولات، نعيد آخر استثناء
        if (last != null) throw last
    }
}
