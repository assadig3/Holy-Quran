// File: app/src/main/java/com/hag/al_quran/Surah/SurahUtils.kt
package com.hag.al_quran.Surah

import android.content.Context
import com.hag.al_quran.R
import com.hag.al_quran.surah.Surah
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.Charset

object SurahUtils {

    fun getAllSurahs(context: Context): List<Surah> {
        // 1) الأساس من الأصول (يحمل pageNumber/ayahCount/type/englishName)
        val baseArr = readJsonArrayFromAssets(context, "surahs.json")

        // 2) النسخة المحلية من res/raw[-locale]/surahs.json (سيتم اختيار raw-tr تلقائيًا عند tr)
        val localizedArr = readJsonArrayFromRaw(context)

        // 3) خريطة number -> name (المترجَم)
        val localizedNameByNumber = mutableMapOf<Int, String>()
        for (i in 0 until localizedArr.length()) {
            val o = localizedArr.optJSONObject(i) ?: continue
            val num = o.optInt("number", -1)
            val n = o.optString("name", "").ifBlank { null } ?: continue
            if (num > 0) localizedNameByNumber[num] = n
        }

        // 4) دمج نهائي
        val out = ArrayList<Surah>(baseArr.length())
        for (i in 0 until baseArr.length()) {
            val o = baseArr.getJSONObject(i)
            val number      = o.optInt("number", i + 1)
            val englishName = o.optString("englishName", "")
            val defaultName = o.optString("name", englishName.ifBlank { "Surah $number" })
            val type        = o.optString("type", "")
            val ayahCount   = o.optInt("ayahCount", o.optInt("versesCount", 0))
            val pageNumber  = o.optInt("pageNumber", o.optInt("page", 1))

            val displayName = localizedNameByNumber[number] ?: defaultName

            out.add(
                Surah(
                    number = number,
                    name = displayName,           // ← الاسم المعروض محلي
                    englishName = englishName,
                    type = type,
                    ayahCount = ayahCount,
                    pageNumber = pageNumber
                )
            )
        }
        return out
    }

    // ========= Helpers =========

    private fun readJsonArrayFromAssets(context: Context, filename: String): JSONArray {
        val text = context.assets.open(filename).use { it.readBytes().toString(Charset.forName("UTF-8")) }
        return try { JSONArray(text) } catch (_: Exception) {
            val lines = text.split('\n').map { it.trim() }.filter { it.startsWith("{") && it.endsWith("}") }
            JSONArray().apply { lines.forEach { put(JSONObject(it)) } }
        }
    }

    // يفتح R.raw.surahs (Android يختار raw-tr تلقائيًا عند لغة tr)
    private fun readJsonArrayFromRaw(context: Context): JSONArray {
        val text = context.resources.openRawResource(R.raw.surahs)
            .use { it.readBytes().toString(Charset.forName("UTF-8")) }
        return try { JSONArray(text) } catch (_: Exception) {
            val lines = text.split('\n').map { it.trim() }.filter { it.startsWith("{") && it.endsWith("}") }
            JSONArray().apply { lines.forEach { put(JSONObject(it)) } }
        }
    }
}
