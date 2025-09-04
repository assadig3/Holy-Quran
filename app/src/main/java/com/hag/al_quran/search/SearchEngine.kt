package com.hag.al_quran.search

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * محرّك بحث سريع:
 * - تحميل الآيات مع نص مُطبّع norm مرة واحدة.
 * - فهرس عكسي للكلمات للحسابات الذكية (اختياري).
 * - فهرس ثلاثيات (trigram) لتسريع البحث الجزئي contains() بشكل كبير.
 *
 * ملاحظة: عند طول استعلام >= 3 نستخرج ثلاثيات من الاستعلام ونأخذ تقاطع القوائم
 * من الفهرس الثلاثي، ثم نتحقق contains() فقط على المرشّحين (عادة العشرات بدلاً من 6200).
 */
object SearchEngine {

    // بيانات الأساس
    private lateinit var items: List<SearchIndex.AyahItem>
    private lateinit var surahNames: List<String>
    private lateinit var normArray: Array<String>

    // فهرس ثلاثيات: "abc" -> [indices...]
    private lateinit var trigramIndex: HashMap<String, IntArray>

    // (اختياري) فهرس كلمات: token -> [indices...] (مفيد لعدّ الكلمات)
    private lateinit var invertedIndexTokens: HashMap<String, IntArray>
    private lateinit var tokensPerAyah: Array<Array<String>>

    private val ready = AtomicBoolean(false)

    fun isReady(): Boolean = ready.get()

    /** تهيئة آمنة (لن تُعاد لو سبق تنفيذها) */
    fun init(context: Context) {
        if (ready.get()) return
        synchronized(this) {
            if (ready.get()) return

            // تحميل الآيات (AyahItem يحتوي text و norm)
            items = SearchUtils.loadAllAyat(context)
            surahNames = SearchUtils.loadSurahNames(context)

            normArray = Array(items.size) { i -> items[i].norm }

            // كلمات لكل آية (لاستخدامات العدّ الذكي)
            tokensPerAyah = Array(items.size) { i ->
                items[i].norm.split(' ').filter { it.isNotBlank() }.toTypedArray()
            }

            // ---------------------------
            // بناء فهرس ثلاثيات Trigrams
            // ---------------------------
            val tempTri = HashMap<String, MutableList<Int>>(120_000)
            for (i in normArray.indices) {
                val s = normArray[i]
                if (s.length >= 3) {
                    val seen = HashSet<String>()
                    var k = 0
                    val last = s.length - 2
                    while (k < last) {
                        val tri = s.substring(k, k + 3)
                        // نستبعد ثلاثيات كله مسافات
                        if (!tri.isBlank() && tri.any { it != ' ' }) {
                            if (seen.add(tri)) {
                                tempTri.getOrPut(tri) { ArrayList() }.add(i)
                            }
                        }
                        k++
                    }
                } else if (s.isNotBlank()) {
                    // للآيات القصيرة جدًا، نضع النص كله كمفتاح خاص
                    val key = "@LEN<3@$s"
                    tempTri.getOrPut(key) { ArrayList() }.add(i)
                }
            }
            trigramIndex = HashMap(tempTri.size)
            for ((k, list) in tempTri) trigramIndex[k] = list.toIntArray()

            // ---------------------------------
            // (اختياري) بناء فهرس كلمات للعدّ
            // ---------------------------------
            val tempTok = HashMap<String, MutableList<Int>>(50_000)
            for (i in tokensPerAyah.indices) {
                val unique = HashSet<String>()
                for (t in tokensPerAyah[i]) {
                    if (t.isNotBlank() && unique.add(t)) {
                        tempTok.getOrPut(t) { ArrayList() }.add(i)
                    }
                }
            }
            invertedIndexTokens = HashMap(tempTok.size)
            for ((k, list) in tempTok) invertedIndexTokens[k] = list.toIntArray()

            ready.set(true)
        }
    }

    /** تضمن الجاهزية؛ إن لم يكن جاهزًا يحاول التهيئة */
    fun ensureReady(context: Context) {
        if (!isReady()) init(context)
    }

    fun getItems(): List<SearchIndex.AyahItem> = items
    fun getSurahNames(): List<String> = surahNames

    // -----------------------------
    // بحث جزئي سريع للغاية
    // -----------------------------
    fun searchPartialFast(context: Context, queryRaw: String): List<SearchIndex.AyahItem> {
        ensureReady(context)
        if (!isReady()) return emptyList()

        val q = SearchUtils.normalizeArabic(SearchUtils.stripTashkeel(queryRaw))
        if (q.isBlank()) return emptyList()

        // 1) استعلام قصير (1..2): مسح مباشر (محدود)
        if (q.length < 3) {
            // لأداء أفضل: أوقف بعد أول 500 نتيجة مثلاً (يمكن ضبطه)
            val out = ArrayList<SearchIndex.AyahItem>(min(500, normArray.size))
            for (i in normArray.indices) {
                if (normArray[i].contains(q)) {
                    out.add(items[i])
                    if (out.size >= 500) break
                }
            }
            return out
        }

        // 2) >= 3: ثلاثيات + تقاطع
        val tris = extractUniqueTrigrams(q)
        if (tris.isEmpty()) {
            // احتياط
            return fallbackScan(q, limit = 500)
        }

        // نجلب لكل ثلاثية قائمة المرشحين، ثم نأخذ تقاطعها
        var first = true
        var candidates: MutableSet<Int> = hashSetOf()
        for (t in tris) {
            val arr = trigramIndex[t] ?: IntArray(0)
            if (first) {
                // أول مجموعة
                if (arr.isEmpty()) return emptyList()
                candidates = arr.toMutableSet()
                first = false
            } else {
                if (arr.isEmpty()) return emptyList()
                // تقاطع سريع
                candidates.retainAll(arr.asList())
                if (candidates.isEmpty()) return emptyList()
            }
        }

        // تحقُّق نهائي contains() على المرشحين فقط
        val out = ArrayList<SearchIndex.AyahItem>(min(candidates.size, 500))
        for (idx in candidates) {
            if (normArray[idx].contains(q)) {
                out.add(items[idx])
                if (out.size >= 500) break
            }
        }
        return out
    }

    private fun extractUniqueTrigrams(s: String): List<String> {
        val res = ArrayList<String>(s.length)
        val seen = HashSet<String>()
        var i = 0
        val last = s.length - 2
        while (i < last) {
            val tri = s.substring(i, i + 3)
            if (!tri.isBlank() && tri.any { it != ' ' } && seen.add(tri)) {
                res.add(tri)
            }
            i++
        }
        if (res.isEmpty() && s.isNotBlank()) {
            res.add("@LEN<3@$s") // حالة خاصة للنصوص القصيرة جدًا
        }
        return res
    }

    private fun fallbackScan(q: String, limit: Int): List<SearchIndex.AyahItem> {
        val out = ArrayList<SearchIndex.AyahItem>(min(limit, normArray.size))
        for (i in normArray.indices) {
            if (normArray[i].contains(q)) {
                out.add(items[i])
                if (out.size >= limit) break
            }
        }
        return out
    }

    // ------------------------------------
    // عدّ ذكي (إن أردته للعداد/الإحصاء)
    // ------------------------------------
    fun countOccurrencesSmart(context: Context, query: String): Int {
        ensureReady(context)
        if (!isReady()) return 0
        val q = SearchUtils.normalizeArabic(SearchUtils.stripTashkeel(query))
        if (q.isBlank()) return 0

        // عدّ بسيط: احسب كم آية تحتوي q (يمكن تغييره لعدّ التكرارات)
        var c = 0
        for (i in normArray.indices) if (normArray[i].contains(q)) c++
        return c
    }
}
