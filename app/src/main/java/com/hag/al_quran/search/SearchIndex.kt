package com.hag.al_quran.search

import kotlin.math.min

class SearchIndex(baseAyat: List<AyahItem>, includeBasmala: Boolean = true) {

    data class AyahItem(
        val surah: Int,
        val ayah: Int,   // للبسملة نضع 0 (آية افتراضية)
        val page: Int,
        val text: String,
        val norm: String
    )

    private val tokenRe = Regex("[\\p{L}\\p{Nd}]+")
    companion object {
        private const val MAX_PREFIX = 6   // كان 4 -> سبب البطء
        private const val MIN_PREFIX = 2
    }

    // القائمة النهائية بعد إضافة البسملات الافتراضية
    private val ayat: List<AyahItem>

    // فهارس مقلوبة: كلمة/جذر/بادئة -> فهارس الآيات
    private val tokenToAyahs: Map<String, IntArray>
    private val stemToAyahs: Map<String, IntArray>
    private val prefixToAyahs: Map<String, IntArray>

    // توكنات لكل آية + map (سورة،آية) -> index
    private val ayahTokens: Array<List<String>>
    private val idToIdx = HashMap<Int, Int>()

    init {
        // ===== 1) دمج البسملات الافتراضية =====
        val list = ArrayList<AyahItem>(baseAyat.size + 120)
        list.addAll(baseAyat)

        if (includeBasmala) {
            val firstPage = HashMap<Int, Int>() // surah -> page
            for (a in baseAyat) {
                if (a.ayah == 1 && !firstPage.containsKey(a.surah)) {
                    firstPage[a.surah] = a.page
                }
            }
            val basmalaText = "بِسْمِ اللَّهِ الرَّحْمَٰنِ الرَّحِيمِ"
            val basmalaNorm = SearchUtils.normalizeArabic(SearchUtils.stripTashkeel(basmalaText))
            for (s in 2..114) { // الفاتحة موجودة فعليًا، التوبة بلا بسملة
                if (s == 9) continue
                val p = firstPage[s] ?: continue
                list.add(AyahItem(surah = s, ayah = 0, page = p, text = basmalaText, norm = basmalaNorm))
            }
        }
        ayat = list

        // ===== 2) بناء الفهارس =====
        val tokenToSet  = HashMap<String, MutableSet<Int>>()
        val stemToSet   = HashMap<String, MutableSet<Int>>()
        val prefixToSet = HashMap<String, MutableSet<Int>>()

        ayahTokens = Array(ayat.size) { emptyList() }

        for ((idx, a) in ayat.withIndex()) {
            idToIdx[key(a.surah, a.ayah)] = idx

            val tokens = tokenRe.findAll(a.norm).map { it.value }.toList()
            ayahTokens[idx] = tokens

            val unique = tokens.toHashSet()
            for (tok in unique) {
                tokenToSet.getOrPut(tok) { mutableSetOf() }.add(idx)

                val stem = lightStem(tok)
                stemToSet.getOrPut(stem) { mutableSetOf() }.add(idx)

                val maxPref = min(MAX_PREFIX, tok.length)
                for (len in MIN_PREFIX..maxPref) {
                    val pref = tok.substring(0, len)
                    prefixToSet.getOrPut(pref) { mutableSetOf() }.add(idx)
                }
            }
        }

        tokenToAyahs  = tokenToSet .mapValues { it.value.toIntArray().sortedArray() }
        stemToAyahs   = stemToSet  .mapValues { it.value.toIntArray().sortedArray() }
        prefixToAyahs = prefixToSet.mapValues { it.value.toIntArray().sortedArray() }
    }

    fun getAll(): List<AyahItem> = ayat

    /** بحث جزئي سريع بدون مسح شامل */
    fun searchPartial(qRaw: String): List<AyahItem> {
        val q = SearchUtils.normalizeArabic(SearchUtils.stripTashkeel(qRaw))
        if (q.length < MIN_PREFIX) return emptyList()

        // نستخدم أقرب بادئة متاحة (حتى MAX_PREFIX)، وإن لم نجد نقلّص الطول تدريجيًا
        var keyLen = min(q.length, MAX_PREFIX)
        var ids: IntArray? = null
        while (ids == null && keyLen >= MIN_PREFIX) {
            ids = prefixToAyahs[q.substring(0, keyLen)]
            keyLen--
        }
        val out = ArrayList<AyahItem>()
        if (ids != null) {
            for (i in ids) {
                val a = ayat[i]
                if (a.norm.contains(q)) out.add(a)
            }
            return out
        }
        // في أندر الحالات (كلمة كلها رموز…) لا نُجري مسح شامل حفاظًا على الأداء
        return out
    }

    /** بحث كلمة كاملة سريع (مع تجذير خفيف افتراضيًا) */
    fun searchWholeWord(qRaw: String, useStemming: Boolean = true): List<AyahItem> {
        val q = SearchUtils.normalizeArabic(SearchUtils.stripTashkeel(qRaw))
        if (q.isEmpty()) return emptyList()

        val ids = if (useStemming) {
            stemToAyahs[lightStem(q)]
        } else {
            tokenToAyahs[q]
        } ?: IntArray(0)

        val out = ArrayList<AyahItem>(ids.size)
        for (i in ids) out.add(ayat[i])
        return out
    }

    /** عدّ تكرارات كلمة كاملة في آية (باستخدام التوكنات المسبقة) */
    fun countWholeOccurrences(
        surah: Int,
        ayah: Int,
        wordNorm: String,
        useStemming: Boolean = true
    ): Int {
        val idx = idToIdx[key(surah, ayah)] ?: return 0
        val toks = ayahTokens[idx]
        val stemQ = lightStem(wordNorm)
        var c = 0
        for (t in toks) if (t == wordNorm || (useStemming && lightStem(t) == stemQ)) c++
        return c
    }

    private fun key(s: Int, a: Int): Int = (s shl 10) or a

    /** تجذير عربي خفيف */
    private fun lightStem(s0: String): String {
        var s = s0
        s = s.replace('ة','ه').replace('ى','ي')
        if (s.startsWith("ال") && s.length > 3) s = s.substring(2)
        if (s.length > 3 && "وفبكلس".indexOf(s[0]) >= 0) s = s.substring(1)
        fun dropEnd(n: Int){ if (s.length > n + 1) s = s.dropLast(n) }
        when {
            s.endsWith("ات") -> dropEnd(2)
            s.endsWith("ون") || s.endsWith("ين") || s.endsWith("ان") -> dropEnd(2)
        }
        if (s.endsWith("ه") || s.endsWith("ي") || s.endsWith("ا")) dropEnd(1)
        return s
    }
}
