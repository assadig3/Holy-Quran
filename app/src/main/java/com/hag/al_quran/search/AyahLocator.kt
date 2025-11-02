package com.hag.al_quran.search

import android.content.Context
import com.hag.al_quran.BoundsRepo

/**
 * محدِّد صفحة موحّد يعتمد نفس مصدر التظليل (BoundsRepo) لضمان التطابق 1:1
 * ويستعمل PageAyahMapLoader إن وُجد في المشروع.
 */
object AyahLocator {

    @Volatile
    private var cache: MutableMap<Pair<Int, Int>, Int>? = null

    @JvmStatic
    fun getPageFor(context: Context, surah: Int, ayah: Int): Int {
        // 1) إن وُجدت خريطة رسمية
        try {
            val cls = Class.forName("com.hag.al_quran.PageAyahMapLoader")
            val method = cls.getMethod("getPageForAyah", Int::class.java, Int::class.java)
            val p = method.invoke(null, surah, ayah) as Int
            if (p in 1..604) return p
        } catch (_: Throwable) { /* تجاهل */ }

        // 2) من BoundsRepo (نفس مصدر التظليل)
        val local = cache ?: synchronized(this) {
            cache ?: buildFromBounds(context).also { cache = it }
        }
        return (local[surah to ayah] ?: 1).coerceIn(1, 604)
    }

    private fun buildFromBounds(context: Context): MutableMap<Pair<Int, Int>, Int> {
        val map = mutableMapOf<Pair<Int, Int>, Int>()
        val all = BoundsRepo.loadBoundsMap(context) // page -> List<AyahBounds>
        for ((page, list) in all) {
            for (ab in list) {
                map[ab.sura_id to ab.aya_id] = page
            }
        }
        return map
    }
}
