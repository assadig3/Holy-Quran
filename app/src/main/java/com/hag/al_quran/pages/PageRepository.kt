package com.hag.al_quran.pages

import android.content.Context
import org.json.JSONObject

// بيانات الآيات على الصفحة
data class Seg(val x: Int, val y: Int, val w: Int, val h: Int)
data class AyahBounds(val sura_id: Int, val aya_id: Int, val segs: List<Seg>)

/**
 * مستودع صفحات المصحف: قراءة ayah_bounds_all.json من مجلد assets.
 */
class PageRepository(private val context: Context) {

    fun loadAyahBoundsForPage(page: Int): List<AyahBounds> {
        val jsonStr = context.assets
            .open("pages/ayah_bounds_all.json")
            .bufferedReader().use { it.readText() }

        val json = JSONObject(jsonStr)
        val arr = json.optJSONArray(page.toString()) ?: return emptyList()

        val out = mutableListOf<AyahBounds>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val segs = o.getJSONArray("segs")
            val segList = mutableListOf<Seg>()
            for (j in 0 until segs.length()) {
                val s = segs.getJSONObject(j)
                segList.add(
                    Seg(
                        s.getInt("x"),
                        s.getInt("y"),
                        s.getInt("w"),
                        s.getInt("h")
                    )
                )
            }
            out.add(
                AyahBounds(
                    o.getInt("sura_id"),
                    o.getInt("aya_id"),
                    segList
                )
            )
        }
        return out
    }
}
