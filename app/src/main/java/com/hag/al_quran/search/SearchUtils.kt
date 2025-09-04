// File: app/src/main/java/com/hag/al_quran/search/SearchUtils.kt
package com.hag.al_quran.search

import android.content.Context
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.Normalizer

object SearchUtils {

    // جميع علامات التشكيل + التطويل
    private val TASHKEEL = setOf(
        '\u0610','\u0611','\u0612','\u0613','\u0614','\u0615','\u0616','\u0617','\u0618','\u0619','\u061A',
        '\u064B','\u064C','\u064D','\u064E','\u064F','\u0650','\u0651','\u0652','\u0653','\u0654','\u0655','\u0656','\u0657','\u0658','\u0659','\u065A','\u065B','\u065C','\u065D','\u065E','\u065F',
        '\u0670', // الألف الخنجرية
        '\u06D6','\u06D7','\u06D8','\u06D9','\u06DA','\u06DB','\u06DC','\u06DF','\u06E0','\u06E1','\u06E2','\u06E3','\u06E4','\u06E5','\u06E6','\u06E7','\u06E8','\u06E9','\u06EA','\u06EB','\u06EC','\u06ED',
        '\u0640' // التطويل
    )

    // محارف صفرية/اتجاه يجب حذفها حتى لا تُنتج مطابقات وهمية
    private val ZERO_WIDTH = setOf(
        '\u200C', // ZWNJ
        '\u200D', // ZWJ
        '\u200E', // LRM
        '\u200F', // RLM
        '\u2066', // LRI
        '\u2067', // RLI
        '\u2068', // FSI
        '\u2069'  // PDI
    )

    // حروف يمكن اعتبارها سوابق (و/ف/ب/ك/ل/س/ت)
    private val PREFIXES: Set<Char> = setOf('و','ف','ب','ك','ل','س','ت')

    // ====== أدوات أساسية ======

    // إصلاحات على النص الأصلي + تسوية NFKC + إزالة محارف خفيّة/اتجاه
    private fun preprocessOriginal(s0: String): String {
        val s1 = try {
            Normalizer.normalize(s0, Normalizer.Form.NFKC)
        } catch (_: Throwable) { s0 }

        // بدّل الليغيتشر ﷲ إلى "الله"
        val s2 = s1.replace("\uFDF2", "الله")

        val sb = StringBuilder(s2.length)
        for (ch in s2) if (ch !in ZERO_WIDTH) sb.append(ch)
        return sb.toString()
    }

    /** إزالة التشكيل والتطويل فقط (قد تحتاجها في أماكن خاصة) */
    fun stripTashkeel(s: String): String {
        val out = StringBuilder(s.length)
        for (ch in s) if (ch !in TASHKEEL) out.append(ch)
        return out.toString()
    }

    /** تطبيع عربي للبحث فقط (ليس للعرض) — تتضمن إزالة التشكيل داخليًا */
    fun normalizeArabic(s0: String): String {
        val s = preprocessOriginal(s0)
        val out = StringBuilder(s.length)
        for (ch0 in s) {
            if (ch0 in TASHKEEL) continue
            // إزالة أشكال العرض العربية (Presentation Forms)
            val ch = when (ch0) {
                in '\uFB50'..'\uFDFF', in '\uFE70'..'\uFEFF' -> ' ' // تُفكّ بالتسوية غالبًا، نضمن مسافة إن بقيت
                else -> ch0
            }
            val c = when (ch) {
                'إ','أ','آ','ٱ' -> 'ا'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                // ملاحظة: لا نحول "ة" إلى "ه" للحفاظ على دقة البحث
                'گ' -> 'ك'
                'ٷ' -> 'و'
                else -> ch
            }
            if (Character.isLetterOrDigit(c) || c.isWhitespace()) out.append(c) else out.append(' ')
        }
        // توحيد الفراغات
        return out.toString().trim().replace(Regex("\\s+"), " ")
    }

    // حدود حرف عربي بعد التطبيع
    private fun isArabicLetterNormalized(ch: Char): Boolean = ch in '\u0621'..'\u064A'

    // ====== خرائط فهارس للتظليل الدقيق ======

    /** يبني نصًا مُطبّعًا + خريطة فهارس من المُطبّع إلى الأصلي (للتظليل الدقيق) */
    fun buildNormalizedWithIndexMap(original0: String): Pair<String, IntArray> {
        val original = preprocessOriginal(original0)
        val norm = StringBuilder(original.length)
        val indexMap = ArrayList<Int>(original.length)
        var lastWasSpace = false

        var i = 0
        while (i < original.length) {
            var ch = original[i]
            if (ch in ZERO_WIDTH) { i++; continue }
            if (ch in TASHKEEL) { i++; continue }

            // فكّ/تسطيح أشكال العرض إن بقيت
            if (ch in '\uFB50'..'\uFDFF' || ch in '\uFE70'..'\uFEFF') {
                ch = ' '
            }

            ch = when (ch) {
                'إ','أ','آ','ٱ' -> 'ا'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                // لا نحوّل "ة"
                'گ' -> 'ك'
                'ٷ' -> 'و'
                else -> ch
            }

            val outChar = when {
                ch.isWhitespace() -> ' '
                Character.isLetterOrDigit(ch) -> ch
                else -> ' '
            }

            if (outChar == ' ') {
                if (!lastWasSpace) {
                    norm.append(' ')
                    indexMap.add(i)
                    lastWasSpace = true
                }
            } else {
                norm.append(outChar)
                indexMap.add(i)
                lastWasSpace = false
            }
            i++
        }

        var start = 0
        var end = norm.length
        while (start < end && norm[start] == ' ') start++
        while (end > start && norm[end - 1] == ' ') end--

        val finalNorm = if (start == 0 && end == norm.length) norm.toString() else norm.substring(start, end)
        val finalMap = if (start == 0 && end == indexMap.size) indexMap.toIntArray()
        else indexMap.subList(start, end).toIntArray()
        return finalNorm to finalMap
    }

    // ====== بحث كلمة كاملة ======

    /** بحث عن كلمة كاملة (بدون سوابق) */
    fun findWholeWordRanges(original: String, queryRaw: String): List<IntRange> {
        if (queryRaw.isBlank()) return emptyList()
        val (normText, idxMap) = buildNormalizedWithIndexMap(original)
        val q = normalizeArabic(queryRaw)
        if (q.isBlank()) return emptyList()

        val ranges = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val idx = normText.indexOf(q, from)
            if (idx < 0) break
            val endIdx = idx + q.length
            val beforeOk = (idx == 0) || !isArabicLetterNormalized(normText[idx - 1])
            val afterOk = (endIdx >= normText.length) || !isArabicLetterNormalized(normText[endIdx])
            if (beforeOk && afterOk) {
                val startOrig = idxMap[idx]
                var endOrig = idxMap[endIdx - 1] + 1
                while (endOrig < original.length && (original[endOrig] in TASHKEEL || original[endOrig] in ZERO_WIDTH)) endOrig++
                ranges.add(startOrig until endOrig)
            }
            from = idx + 1
        }
        return ranges
    }

    /** بحث عن كلمة كاملة مع السماح بسابقة حرف (و/ف/ب/ك/ل/س/ت) */
    fun findWholeWordRangesWithPrefixes(original: String, queryRaw: String): List<IntRange> {
        if (queryRaw.isBlank()) return emptyList()
        val (normText, idxMap) = buildNormalizedWithIndexMap(original)
        val q = normalizeArabic(queryRaw)
        if (q.isBlank()) return emptyList()

        val ranges = ArrayList<IntRange>()
        var from = 0
        while (true) {
            val idx = normText.indexOf(q, from)
            if (idx < 0) break
            val endIdx = idx + q.length

            val afterOk = (endIdx >= normText.length) || !isArabicLetterNormalized(normText[endIdx])
            val beforeOk = when {
                idx == 0 -> true
                !isArabicLetterNormalized(normText[idx - 1]) -> true
                PREFIXES.contains(normText[idx - 1]) -> {
                    val j = idx - 2
                    j < 0 || !isArabicLetterNormalized(normText.getOrElse(j) { ' ' })
                }
                else -> false
            }

            if (beforeOk && afterOk) {
                val startOrig = idxMap[idx]
                var endOrig = idxMap[endIdx - 1] + 1
                while (endOrig < original.length && (original[endOrig] in TASHKEEL || original[endOrig] in ZERO_WIDTH)) endOrig++
                ranges.add(startOrig until endOrig)
            }
            from = idx + 1
        }
        return ranges
    }

    // ====== أدوات تحميل/قراءة ======

    /** ضمان رقم الصفحة من نفس خريطة العارض */
    private fun ensurePage(surah: Int, ayah: Int, preset: Int): Int {
        if (preset > 0) return preset
        // 1) AyahLocator (إن وجد)
        try {
            val cls = Class.forName("com.hag.al_quran.search.AyahLocator")
            val m   = cls.getMethod("getPageFor", Int::class.java, Int::class.java)
            val p   = m.invoke(null, surah, ayah) as Int
            if (p > 0) return p
        } catch (_: Throwable) {}
        // 2) PageAyahMapLoader (المشروع الأصلي)
        try {
            val cls = Class.forName("com.hag.al_quran.PageAyahMapLoader")
            val m   = cls.getMethod("getPageForAyah", Int::class.java, Int::class.java)
            val p   = m.invoke(null, surah, ayah) as Int
            if (p > 0) return p
        } catch (_: Throwable) {}
        return 1
    }

    /** تحميل كل الآيات: تُعيد AyahItem مباشرة */
    fun loadAllAyat(context: Context): List<SearchIndex.AyahItem> {
        val json = readAsset(context, "quran.json")
        val arr = JSONArray(json)
        if (arr.length() == 0) return emptyList()
        val first = arr.getJSONObject(0)
        return if (first.has("verses")) loadFromNested(arr) else loadFromFlat(arr)
    }

    private fun loadFromNested(surahsArr: JSONArray): List<SearchIndex.AyahItem> {
        val out = ArrayList<SearchIndex.AyahItem>(6200)
        for (i in 0 until surahsArr.length()) {
            val s = surahsArr.getJSONObject(i)
            val surahId = s.optInt("id", i + 1)
            val surahPage = s.optInt("page", 0)
            val verses = s.optJSONArray("verses") ?: JSONArray()
            for (j in 0 until verses.length()) {
                val v = verses.getJSONObject(j)
                val ayah = v.optInt("id", j + 1)
                val text = v.optString("text", "")
                val page = ensurePage(surahId, ayah, v.optInt("page", surahPage))
                val norm = normalizeArabic(text)
                out.add(SearchIndex.AyahItem(surahId, ayah, page, text, norm))
            }
        }
        return out
    }

    private fun loadFromFlat(arr: JSONArray): List<SearchIndex.AyahItem> {
        val out = ArrayList<SearchIndex.AyahItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val surah = o.optInt("surah", o.optInt("sura", 0))
            val ayah  = o.optInt("ayah",  o.optInt("aya",  0))
            val page  = ensurePage(surah, ayah, o.optInt("page", 0))
            val text  = o.optString("text", o.optString("aya_text", ""))
            val norm  = normalizeArabic(text)
            out.add(SearchIndex.AyahItem(surah, ayah, page, text, norm))
        }
        return out
    }

    /** أسماء السور (يحاول من JSON، ثم fallback عام) */
    fun loadSurahNames(context: Context): List<String> {
        try {
            val json = readAsset(context, "quran.json")
            val arr = JSONArray(json)
            if (arr.length() > 0 && arr.getJSONObject(0).has("verses")) {
                val names = ArrayList<String>(114)
                for (i in 0 until arr.length()) {
                    val s = arr.getJSONObject(i)
                    names.add(s.optString("name", "سورة ${s.optInt("id", i + 1)}"))
                }
                if (names.isNotEmpty()) return names
            }
        } catch (_: Exception) { }
        return (1..114).map { "سورة $it" }
    }

    /** قراءة asset كنص كامل */
    private fun readAsset(context: Context, name: String): String {
        context.assets.open(name).use { ins ->
            BufferedReader(InputStreamReader(ins, Charsets.UTF_8)).use { br ->
                val sb = StringBuilder()
                var line: String?
                while (br.readLine().also { line = it } != null) sb.append(line).append('\n')
                return sb.toString()
            }
        }
    }

    // ----- إحصاء ذكي (اختياري لعداد النتائج) -----
    data class Occurrence(
        val surah: Int,
        val ayah: Int,
        val page: Int,
        val ranges: List<IntRange>
    )

    fun countOccurrencesSmart(context: Context, query: String): Pair<Int, List<Occurrence>> {
        if (query.isBlank()) return 0 to emptyList()
        val items = loadAllAyat(context)
        var total = 0
        val hits = ArrayList<Occurrence>()
        for (it in items) {
            val allRanges = findWholeWordRangesWithPrefixes(it.text, query)
            if (allRanges.isNotEmpty()) {
                total += allRanges.size
                hits.add(Occurrence(it.surah, it.ayah, it.page, allRanges))
            }
        }
        return total to hits
    }
}
