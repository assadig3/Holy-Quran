// File: app/src/main/java/com/hag/al_quran/search/WordIndex.kt
package com.hag.al_quran.search

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * فهرس معكوس سريع لحساب مرات الظهور بدون مسح المصحف كل مرة.
 * يُبنى مرة واحدة عند الإقلاع ثم تُقرأ الإحصاءات فورًا.
 */
object WordIndex {

    data class AyahRef(
        val surah: Int,
        val ayah: Int,
        val page: Int,
        val countInAyah: Int
    )

    // word (normalized, no tashkeel) -> list of refs
    private val index = ConcurrentHashMap<String, MutableList<AyahRef>>()
    @Volatile private var built = false

    /** إبنِ الفهرس مرة واحدة. */
    fun buildIfNeeded(context: Context) {
        if (built) return
        synchronized(this) {
            if (built) return
            val items = SearchUtils.loadAllAyat(context)
            for (it in items) {
                if (it.norm.isBlank()) continue
                val counts = HashMap<String, Int>(16)
                val tokens = it.norm.split(' ')
                for (t in tokens) {
                    if (t.isEmpty()) continue
                    counts[t] = (counts[t] ?: 0) + 1
                }
                for ((w, c) in counts) {
                    val list = index.getOrPut(w) { ArrayList() }
                    list.add(AyahRef(it.surah, it.ayah, it.page, c))
                }
            }
            for (e in index.values) {
                e.sortWith(compareBy({ it.surah }, { it.ayah }))
            }
            built = true
        }
    }

    /** بدائل ذكية مطابقة لمنطق SearchUtils.countOccurrencesSmart. */
    private fun smartAlternatives(query: String): Set<String> {
        val qNorm = SearchUtils.normalizeArabic(SearchUtils.stripTashkeel(query))
        if (qNorm.isBlank()) return emptySet()
        val alts = linkedSetOf(qNorm)
        if (qNorm.endsWith("ه")) {
            val stemNoTa = qNorm.dropLast(1)         // إسقاط الهاء ← (تاء مربوطة أصلاً)
            if (stemNoTa.length >= 3) alts.add(stemNoTa)
            if (stemNoTa.endsWith("ي")) {
                val masc = stemNoTa.dropLast(1) + "ن" // مثال: داني -> دان
                if (masc.length >= 3) alts.add(masc)
            }
        }
        return alts
    }

    data class SmartStats(
        val totalOccurrences: Int,
        val ayahMatches: Int,
        val surahCount: Int
    )

    /**
     * إحصاء ذكي (الكلمة + بدائلها الشائعة) بالرجوع للفهرس فقط.
     */
    fun getSmartStats(query: String): SmartStats {
        if (!built) return SmartStats(0, 0, 0)
        val patterns = smartAlternatives(query)
        if (patterns.isEmpty()) return SmartStats(0, 0, 0)

        var total = 0
        val ayahSet = HashSet<Pair<Int, Int>>(64)
        val surahSet = HashSet<Int>(32)

        for (p in patterns) {
            val list = index[p] ?: continue
            for (ref in list) {
                total += ref.countInAyah
                ayahSet.add(ref.surah to ref.ayah)
                surahSet.add(ref.surah)
            }
        }
        return SmartStats(
            totalOccurrences = total,
            ayahMatches = ayahSet.size,
            surahCount = surahSet.size
        )
    }

    // ===== تفصيل بالسور =====
    data class SurahBreakdown(
        val surah: Int,
        val totalOccurrences: Int,
        val ayahMatches: Int
    )

    /**
     * يعيد قائمة بتوزيع الذِّكر على السور (مجمّعة ومفروزة تنازليًا بحسب مرات الذِّكر).
     */
    fun getSmartBreakdown(query: String): List<SurahBreakdown> {
        if (!built) return emptyList()
        val patterns = smartAlternatives(query)
        if (patterns.isEmpty()) return emptyList()

        // surah -> (total occurrences, set of ayahs)
        val totalPerSurah = HashMap<Int, Int>()
        val ayahSetPerSurah = HashMap<Int, HashSet<Int>>()

        for (p in patterns) {
            val list = index[p] ?: continue
            for (ref in list) {
                totalPerSurah[ref.surah] = (totalPerSurah[ref.surah] ?: 0) + ref.countInAyah
                val s = ayahSetPerSurah.getOrPut(ref.surah) { HashSet() }
                s.add(ref.ayah)
            }
        }

        val out = ArrayList<SurahBreakdown>(totalPerSurah.size)
        for ((surah, tot) in totalPerSurah) {
            val ayCnt = ayahSetPerSurah[surah]?.size ?: 0
            out.add(SurahBreakdown(surah, tot, ayCnt))
        }
        // فرز تنازلي بحسب مرات الذكر، ثم تصاعدي بالسورة
        out.sortWith(compareByDescending<SurahBreakdown> { it.totalOccurrences }.thenBy { it.surah })
        return out
    }
}
