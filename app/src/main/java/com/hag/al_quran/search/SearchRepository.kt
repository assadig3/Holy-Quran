// File: app/src/main/java/com/hag/al_quran/search/SearchRepository.kt
package com.hag.al_quran.search

import android.content.Context

class SearchRepository(private val context: Context) {

    private val ayat by lazy { SearchUtils.loadAllAyat(context) }
    private val index by lazy { SearchIndex(ayat, true) }
    private val surahNames by lazy { SearchUtils.loadSurahNames(context) }

    fun search(query: String, options: SearchOptions): List<SearchResultItem> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        val hits = when (options.mode) {
            SearchOptions.Mode.EXACT_WORD -> index.searchWholeWord(q)
            SearchOptions.Mode.PARTIAL    -> index.searchPartial(q)
        }

        return hits.map { a ->
            val surahName = surahNames.getOrNull(a.surah - 1) ?: "سورة ${a.surah}"
            SearchResultItem(a.surah, a.ayah, a.page, surahName, a.text)
        }
    }

    /** عدّ النتائج لواجهة المستخدم */
    fun count(query: String, options: SearchOptions): Int {
        val q = query.trim()
        if (q.isEmpty()) return 0
        return when (options.mode) {
            // العدّ السريع الذكي: يشمل اللواصق الأحادية (و/ف/ب/ك/ل/س/ت)
            SearchOptions.Mode.EXACT_WORD -> SearchUtils.countOccurrencesSmart(context, q).first
            // في البحث الجزئي نكتفي بعدّ العناصر المطابقة
            SearchOptions.Mode.PARTIAL    -> index.searchPartial(q).size
        }
    }
}
