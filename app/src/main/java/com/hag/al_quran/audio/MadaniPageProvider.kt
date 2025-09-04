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
            Qari(ids[i], names[i], base)
        }
    }

    /** أعِد القائمة كاملة */
    fun getQaris(): List<Qari> = qariList

    /** ابحث عن قارئ بالـ id */
    fun getQariById(id: String): Qari? = qariList.find { it.id == id }

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

    // ===================== دعم قوائم الصفحة =====================

    /** تمثيل نطاق آيات ضمن الصفحة */
    data class AyahRange(val surah: Int, val start: Int, val end: Int)

    /**
     * قراءة page_ayahs_map.json من assets وإرجاع نطاقات الآيات للصفحة.
     * الصيغة المدعومة لكل عنصر:
     * { "sura":1, "start":1, "end":7 } — أو بدائل أسماء المفاتيح: surah/s, from/a1, to/a2
     */
    private fun loadAyahRangesForPage(page: Int): List<AyahRange> {
        val out = mutableListOf<AyahRange>()
        val jsonStr = readAsset("page_ayahs_map.json") ?: return out
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

    /** قراءة ملف من assets كنص */
    private fun readAsset(name: String): String? = try {
        context.assets.open(name).use { ins ->
            BufferedReader(InputStreamReader(ins)).use { it.readText() }
        }
    } catch (_: Throwable) { null }
}
