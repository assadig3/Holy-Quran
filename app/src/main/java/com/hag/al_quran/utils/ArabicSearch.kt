package com.hag.al_quran.utils

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.StyleSpan
import kotlin.math.min

object ArabicSearch {

    // نطاقات التشكيل والعلامات العربية (حركات، علامات وقف…)
    private val ARABIC_MARKS = Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED]")
    private fun isArabicMark(ch: Char): Boolean =
        (ch in '\u064B'..'\u065F') || ch == '\u0670' || (ch in '\u06D6'..'\u06ED')

    // حرف عربي؟ (يشمل الحروف الأساسية وأشكال الهمزة والتاء المربوطة والألف المقصورة)
    private fun isArabicLetter(ch: Char): Boolean {
        if (ch in '\u0621'..'\u064A') return true // ء..ي
        return ch == 'آ' || ch == 'أ' || ch == 'إ' || ch == 'ٱ' || ch == 'ى' || ch == 'ة' || ch == 'ؤ' || ch == 'ئ'
    }

    /** تطبيع مبسّط للبحث (بدون تشكيل وبدون تطويل) وتوحيد بعض الحروف */
    fun normalizeForSearch(input: String): String {
        val sb = StringBuilder(input.length)
        for (ch in input) {
            // احذف التشكيل والـtatweel
            if (ch == 'ـ' || isArabicMark(ch)) continue
            // توحيد أشكال الألف والهمزات والياء
            val mapped = when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    /** نبني نصًا مُطبَّعًا + خريطة فهارس من الموضع المطبّع إلى موضع النص الأصلي */
    private fun buildNormalizedWithIndexMap(original: String): Pair<String, IntArray> {
        val norm = StringBuilder(original.length)
        val indexMap = ArrayList<Int>(original.length) // normPos -> originalIndex

        for (i in original.indices) {
            val ch = original[i]
            // تخطَّ التشكيل والـtatweel
            if (ch == 'ـ' || isArabicMark(ch)) continue

            val mapped = when (ch) {
                'أ', 'إ', 'آ', 'ٱ' -> 'ا'
                'ى' -> 'ي'
                'ؤ' -> 'و'
                'ئ' -> 'ي'
                else -> ch
            }
            norm.append(mapped)
            indexMap.add(i)
        }
        return norm.toString() to indexMap.toIntArray()
    }

    /** حدود كلمة عربية: ليس قبلها/بعدها حرف عربي */
    private fun isWordBoundary(norm: String, pos: Int): Boolean {
        if (pos <= 0) return true
        return !isArabicLetter(norm[pos - 1])
    }
    private fun isWordBoundaryAfter(norm: String, posExclusive: Int): Boolean {
        if (posExclusive >= norm.length) return true
        return !isArabicLetter(norm[posExclusive])
    }

    /** إيجاد كل التطابقات كـ IntRange داخل النص الأصلي (مع تشكيله) */
    fun findWholeWordMatches(original: String, rawQuery: String): List<IntRange> {
        if (rawQuery.isBlank()) return emptyList()

        val (normText, idxMap) = buildNormalizedWithIndexMap(original)
        val normQuery = normalizeForSearch(rawQuery)
        if (normQuery.isBlank()) return emptyList()

        val escaped = Regex.escape(normQuery)
        val regex = Regex(escaped) // سنفحص الحدود يدويًا

        val matches = ArrayList<IntRange>()
        var start = 0
        while (true) {
            val m = regex.find(normText, start) ?: break
            val s = m.range.first
            val eExclusive = m.range.last + 1
            // حدود كلمة عربية حقيقية
            if (isWordBoundary(normText, s) && isWordBoundaryAfter(normText, eExclusive)) {
                // حوّل الفهارس المطبّعة إلى الأصلية
                val startOrig = idxMap[s]
                var endOrigInclusive = idxMap[eExclusive - 1]

                // وسّع النهاية لتشمل أي حركات بعد آخر حرف
                var j = endOrigInclusive + 1
                while (j < original.length && isArabicMark(original[j])) j++
                // النطاق في Android سبان يكون [start, end)
                val endExclusive = min(j, original.length)
                matches.add(startOrig until endExclusive)
            }
            start = eExclusive
        }
        return matches
    }

    /** نص مطابق؟ (للاستخدام في فلترة النتائج) */
    fun matchesWholeWord(original: String, rawQuery: String): Boolean {
        return findWholeWordMatches(original, rawQuery).isNotEmpty()
    }

    /** تطبيق التمييز على TextView */
    fun highlightIntoSpannable(text: String, matches: List<IntRange>, bgColor: Int, bold: Boolean = true): SpannableString {
        val span = SpannableString(text)
        for (r in matches) {
            span.setSpan(BackgroundColorSpan(bgColor), r.first, r.last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) span.setSpan(StyleSpan(Typeface.BOLD), r.first, r.last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return span
    }
}
