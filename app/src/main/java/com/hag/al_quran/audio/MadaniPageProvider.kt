// File: app/src/main/java/com/hag/al_quran/audio/MadaniPageProvider.kt
package com.hag.al_quran.audio

import android.content.Context
import com.hag.al_quran.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

data class Qari(val id: String, val name: String, val url: String)

/**
 * مزوّد روابط everyayah بصيغة {SSS}{AAA}.mp3
 * - يجلب قائمة القرّاء من resources (ids/names/urls)
 * - يبني روابط الآيات
 * - يوفّر getUrlsForPage لصفحة المصحف اعتمادًا على page_ayahs_map.json
 */
class MadaniPageProvider(private val context: Context) {

    /** نحمل القوائم من resources (الأسماء تُترجم حسب اللغة) */
    private val qariList: List<Qari> by lazy {
        val ids   = context.resources.getStringArray(R.array.qari_ids)
        val names = context.resources.getStringArray(R.array.qari_names)
        val urls  = context.resources.getStringArray(R.array.qari_urls)

        require(ids.size == names.size && names.size == urls.size) {
            "qari arrays length mismatch: ids=${ids.size}, names=${names.size}, urls=${urls.size}"
        }

        ids.indices.map { i ->
            val base = urls[i].let { if (it.endsWith("/")) it else "$it/" }
            Qari(ids[i].trim().lowercase(), names[i], base)
        }
    }

    /** أعِد القائمة كاملة */
    fun getQaris(): List<Qari> = qariList

    /** ابحث عن قارئ بالـ id */
    fun getQariById(id: String): Qari? = qariList.find { it.id == id.trim().lowercase() }

    /** المسار الأساسي للقارئ (منتهي بـ /) لاستخدامه في الـ Worker */
    fun getAudioBaseForQari(id: String): String? = getQariById(id)?.url

    /** اسم الملف القياسي بدون الامتداد: SSSAAA (3 أرقام للسورة + 3 أرقام للآية) */
    fun getFileBaseName(surah: Int, ayah: Int): String =
        "%03d%03d".format(surah.coerceAtLeast(1), ayah.coerceAtLeast(1))

    /** رابط آية عبر معرّف القارئ */
    fun getAyahUrl(qariId: String, surah: Int, ayah: Int): String? {
        val q = getQariById(qariId) ?: return null
        return buildAyahUrl(q, surah, ayah)
    }

    /** رابط آية عبر كائن Qari مباشرة */
    fun getAyahUrl(qari: Qari, surah: Int, ayah: Int): String =
        buildAyahUrl(qari, surah, ayah)

    private fun buildAyahUrl(q: Qari, surah: Int, ayah: Int): String {
        val s = surah.coerceAtLeast(1).toString().padStart(3, '0')
        val a = ayah.coerceAtLeast(1).toString().padStart(3, '0')
        return "${q.url}$s$a.mp3"
    }

    // ===================== دعم قوائم الصفحة/السورة =====================

    /** تمثيل نطاق آيات ضمن الصفحة */
    data class AyahRange(val surah: Int, val start: Int, val end: Int)

    /**
     * قراءة page_ayahs_map.json من assets وإرجاع نطاقات الآيات للصفحة.
     * الصيغة المدعومة لكل عنصر:
     * { "sura":1, "start":1, "end":7 } — أو بدائل أسماء المفاتيح: surah/s, from/a1, to/a2
     * يدعم المسارين:
     *  - assets/pages/page_ayahs_map.json
     *  - assets/page_ayahs_map.json (رجوع تلقائي)
     */
    private fun loadAyahRangesForPage(page: Int): List<AyahRange> {
        val out = mutableListOf<AyahRange>()
        val jsonStr = readAsset("pages/page_ayahs_map.json")
            ?: readAsset("page_ayahs_map.json")
            ?: return out
        try {
            val root = JSONObject(jsonStr)
            val arr: JSONArray = root.optJSONArray(page.coerceAtLeast(1).toString()) ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)

                val s = when {
                    o.has("sura")  -> o.getInt("sura")
                    o.has("surah") -> o.getInt("surah")
                    o.has("s")     -> o.getInt("s")
                    else -> 1
                }
                val start = when {
                    o.has("start") -> o.getInt("start")
                    o.has("from")  -> o.getInt("from")
                    o.has("a1")    -> o.getInt("a1")
                    else -> 1
                }
                val end = when {
                    o.has("end") -> o.getInt("end")
                    o.has("to")  -> o.getInt("to")
                    o.has("a2")  -> o.getInt("a2")
                    else -> start
                }
                out += AyahRange(s, start, end)
            }
        } catch (_: Throwable) { /* تجاهل الخطأ لفردية صفحة */ }
        return out
    }

    /**
     * تُعيد قائمة روابط MP3 لجميع آيات الصفحة (مرتبّة) للمقرئ المحدد.
     * تُستخدم داخل AudioPlaybackManager أو أي مُشغّل لتكوين الطابور.
     */
    fun getUrlsForPage(qariId: String, page: Int): List<String> {
        val qari = getQariById(qariId) ?: return emptyList()
        val ranges = loadAyahRangesForPage(page)
        if (ranges.isEmpty()) return emptyList()

        val urls = ArrayList<String>(64)
        for (r in ranges) {
            val start = r.start.coerceAtLeast(1)
            val end = if (r.end >= start) r.end else start
            for (a in start..end) {
                urls += buildAyahUrl(qari, r.surah, a)
            }
        }
        return urls
    }

    /** ترجع الأزواج (سورة، آية) للصفحة — مفيدة إن كنت تريد تمريـرها للـ Worker مباشرة */
    fun getPairsForPage(page: Int): List<Pair<Int, Int>> {
        val ranges = loadAyahRangesForPage(page)
        if (ranges.isEmpty()) return emptyList()
        val pairs = ArrayList<Pair<Int, Int>>(64)
        for (r in ranges) {
            val start = r.start.coerceAtLeast(1)
            val end = if (r.end >= start) r.end else start
            for (a in start..end) pairs += r.surah to a
        }
        return pairs
    }

    /** روابط سورة كاملة للمقرئ المحدد */
    fun getUrlsForSurah(qariId: String, surah: Int): List<String> {
        val qari = getQariById(qariId) ?: return emptyList()
        val total = getAyahCountInSurah(surah)
        if (total <= 0) return emptyList()
        val urls = ArrayList<String>(total)
        for (a in 1..total) {
            urls += buildAyahUrl(qari, surah, a)
        }
        return urls
    }

    /** عدد آيات السورة من quran.json */
    private fun getAyahCountInSurah(surah: Int): Int {
        return try {
            val txt = readAsset("quran.json") ?: return 0
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val sObj = arr.getJSONObject(i)
                if (sObj.optInt("id") == surah) {
                    return sObj.optJSONArray("verses")?.length() ?: 0
                }
            }
            0
        } catch (_: Throwable) { 0 }
    }
    fun getBaseUrlForQari(id: String): String {
        // نحصل على القارئ حسب الـ id
        val qari = getQariById(id)
        // لو مش موجود نرجع فاضي (عشان ما ينهار الكود)
        return qari?.url ?: ""
    }

    /** قراءة ملف من assets كنص */
    private fun readAsset(name: String): String? = try {
        context.assets.open(name).use { ins ->
            BufferedReader(InputStreamReader(ins)).use { it.readText() }
        }
    } catch (_: Throwable) { null }
}
