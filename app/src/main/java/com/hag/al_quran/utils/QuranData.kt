package com.hag.al_quran.utils

import android.app.Activity
import android.content.Context
import com.hag.al_quran.audio.MadaniPageProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

// ===== نماذج تظليل =====
data class Seg(val x: Int, val y: Int, val w: Int, val h: Int)
data class AyahBounds(val sura_id: Int, val aya_id: Int, val segs: List<Seg>)

// ===== أرقام عربية =====
fun convertToArabicNumber(number: Int): String {
    val arabicNums = arrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
    return number.toString().map { arabicNums[it.digitToInt()] }.joinToString("")
}

// ===== خرائط صفحات/سور/أجزاء =====
fun getPageForSurah(surahId: Int): Int {
    val map = mapOf(
        1 to 1, 2 to 2, 3 to 50, 4 to 77, 5 to 106, 6 to 128, 7 to 151, 8 to 177, 9 to 187,
        10 to 208, 11 to 221, 12 to 235, 13 to 249, 14 to 255, 15 to 262, 16 to 267, 17 to 282,
        18 to 293, 19 to 305, 20 to 312, 21 to 322, 22 to 332, 23 to 342, 24 to 350, 25 to 359,
        26 to 367, 27 to 377, 28 to 385, 29 to 396, 30 to 404, 31 to 411, 32 to 415, 33 to 418,
        34 to 428, 35 to 434, 36 to 440, 37 to 446, 38 to 453, 39 to 458, 40 to 467, 41 to 477,
        42 to 482, 43 to 489, 44 to 496, 45 to 499, 46 to 502, 47 to 507, 48 to 511, 49 to 515,
        50 to 518, 51 to 520, 52 to 523, 53 to 526, 54 to 528, 55 to 531, 56 to 534, 57 to 537,
        58 to 542, 59 to 545, 60 to 548, 61 to 550, 62 to 552, 63 to 554, 64 to 556, 65 to 558,
        66 to 560, 67 to 562, 68 to 564, 69 to 566, 70 to 568, 71 to 570, 72 to 572, 73 to 574,
        74 to 575, 75 to 577, 76 to 578, 77 to 580, 78 to 582, 79 to 583, 80 to 585, 81 to 586,
        82 to 587, 83 to 588, 84 to 590, 85 to 591, 86 to 592, 87 to 593, 88 to 594, 89 to 595,
        90 to 596, 91 to 597, 92 to 598, 93 to 599, 94 to 600, 95 to 601, 96 to 602, 97 to 603,
        98 to 603, 99 to 604, 100 to 604, 101 to 604, 102 to 604, 103 to 604, 104 to 604,
        105 to 604, 106 to 604, 107 to 604, 108 to 604, 109 to 604, 110 to 604, 111 to 604,
        112 to 604, 113 to 604, 114 to 604
    )
    return (map[surahId] ?: 1).coerceIn(1, 604)
}

fun getPageForJuz(juzNumber: Int): Int {
    val pages = listOf(
        1, 22, 42, 62, 82, 102, 121, 142, 162, 182,
        201, 222, 242, 262, 282, 302, 322, 342, 362, 382,
        402, 422, 442, 462, 482, 502, 522, 542, 562, 582
    )
    return pages.getOrNull(juzNumber - 1)?.coerceIn(1, 604) ?: 1
}

fun resolveStartPageFromIntent(activity: Activity): Int {
    val i = activity.intent
    val pStart = i.getIntExtra("startFromPage", -1)
    val p1 = i.getIntExtra("page", -1)
    val p2 = i.getIntExtra("page_number", -1)
    val surah = i.getIntExtra("surah_number", -1)
    val juz   = i.getIntExtra("juz_number", -1)
    val byPage = listOf(pStart, p1, p2).firstOrNull { it in 1..604 }
    if (byPage != null) return byPage
    if (surah in 1..114) return getPageForSurah(surah)
    if (juz in 1..30)    return getPageForJuz(juz)
    return 1
}

// ===== قراءة من الأصول =====
fun getSurahNameByNumber(surahNumber: Int, context: Context): String {
    val json = context.assets.open("surahs.json").bufferedReader().use { it.readText() }
    val arr = JSONArray(json)
    for (i in 0 until arr.length()) {
        val s = arr.getJSONObject(i)
        if (s.getInt("number") == surahNumber) return s.getString("name")
    }
    return ""
}

fun getSurahNameForPage(page: Int, context: Context): String {
    val json = context.assets.open("surahs.json").bufferedReader().use { it.readText() }
    val arr = JSONArray(json)
    var name = ""
    for (i in 0 until arr.length()) {
        val s = arr.getJSONObject(i)
        if (page >= s.getInt("pageNumber")) name = s.getString("name") else break
    }
    return name
}

fun getAyahTextFromJson(context: Context, surah: Int, ayah: Int): String {
    return try {
        val jsonStr = context.assets.open("quran.json").bufferedReader().use { it.readText() }
        val quran = JSONArray(jsonStr)
        for (i in 0 until quran.length()) {
            val s = quran.getJSONObject(i)
            if (s.getInt("id") == surah) {
                val verses = s.getJSONArray("verses")
                for (j in 0 until verses.length()) {
                    val v = verses.getJSONObject(j)
                    if (v.getInt("id") == ayah) return v.getString("text")
                }
            }
        }
        "الآية غير موجودة"
    } catch (_: IOException) { "الآية غير موجودة" }
}

fun loadAyahBoundsForPage(context: Context, pageNumber: Int): List<AyahBounds> {
    val jsonStr = context.assets.open("pages/ayah_bounds_all.json").bufferedReader().use { it.readText() }
    val json = JSONObject(jsonStr)
    val arr = json.optJSONArray(pageNumber.toString()) ?: return emptyList()
    val list = mutableListOf<AyahBounds>()
    for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val segsArr = obj.getJSONArray("segs")
        val segs = mutableListOf<Seg>()
        for (j in 0 until segsArr.length()) {
            val s = segsArr.getJSONObject(j)
            segs.add(Seg(s.getInt("x"), s.getInt("y"), s.getInt("w"), s.getInt("h")))
        }
        list.add(AyahBounds(obj.getInt("sura_id"), obj.getInt("aya_id"), segs))
    }
    return list
}

// ===== صفحات/تحميل =====
fun pageUrl(page: Int) =
    "https://raw.githubusercontent.com/assadig3/quran-pages/main/pages/page_${page}.webp"

fun arePagesCachedLocally(context: Context, minCount: Int = 600): Boolean {
    val dir = File(context.getExternalFilesDir(null), "QuranPages")
    if (!dir.exists()) return false
    val count = dir.listFiles()?.count { it.extension.equals("webp", true) } ?: 0
    return count >= minCount
}

fun downloadToFile(urlStr: String, dst: File): Boolean {
    return try {
        val url = URL(urlStr)
        (url.openConnection() as HttpURLConnection).run {
            connectTimeout = 15000
            readTimeout = 30000
            requestMethod = "GET"
            doInput = true
            connect()
            if (responseCode in 200..299) {
                inputStream.use { input ->
                    FileOutputStream(dst).use { out ->
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r == -1) break
                            out.write(buf, 0, r)
                        }
                        out.flush()
                    }
                }
                true
            } else false
        }
    } catch (_: Exception) { false }
}

fun downloadAyahToDownloads(
    context: Context,
    url: String,
    fileName: String,
    onDone: (Boolean) -> Unit
) {
    Thread {
        try {
            val dir = File(context.getExternalFilesDir(null), "AlQuranAudio")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, fileName)
            if (!file.exists()) {
                val conn = URL(url).openConnection()
                conn.connect()
                conn.getInputStream().use { input ->
                    FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            onDone(true)
        } catch (_: Exception) {
            onDone(false)
        }
    }.start()
}

// ===== إكستنشن: استخراج نص الآية من رابط mp3 =====
fun MadaniPageProvider.getAyahTextFromUrl(url: String, context: Context): String? {
    val pattern = ".*/(\\d{3})(\\d{3})\\.mp3$".toRegex()
    val match = pattern.find(url) ?: return null
    val surah = match.groupValues[1].toInt()
    val ayah = match.groupValues[2].toInt()
    val jsonStr = context.assets.open("quran.json").bufferedReader().use { it.readText() }
    val quranArr = JSONArray(jsonStr)
    for (i in 0 until quranArr.length()) {
        val surahObj = quranArr.getJSONObject(i)
        if (surahObj.getInt("id") == surah) {
            val versesArr = surahObj.getJSONArray("verses")
            for (j in 0 until versesArr.length()) {
                val verseObj = versesArr.getJSONObject(j)
                if (verseObj.getInt("id") == ayah) {
                    return verseObj.getString("text")
                }
            }
        }
    }
    return null
}
