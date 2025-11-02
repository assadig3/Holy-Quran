package com.hag.al_quran.search

import android.content.Context
import org.json.JSONArray
// إن كان لديك core-ktx مضافًا (غالبًا موجود):
import androidx.core.content.edit

object SearchHistory {
    private const val PREF = "search_history_prefs"
    private const val KEY  = "history"
    private const val MAX  = 20

    fun load(ctx: Context): MutableList<String> {
        val json = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        val arr = JSONArray(json)
        val list = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) {
            list.add(arr.optString(i))
        }
        return list
    }

    fun save(ctx: Context, q: String) {
        val t = q.trim()
        if (t.isEmpty()) return

        val list = load(ctx)
        list.remove(t)                  // نقلها إلى المقدمة إن كانت موجودة
        list.add(0, t)
        // ✅ بديل removeLast() المتوافق مع كل الإصدارات
        while (list.size > MAX) list.removeAt(list.lastIndex)

        val arr = JSONArray().apply { list.forEach { put(it) } }

        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

        // إن كان لديك core-ktx سيستخدم الامتداد؛ وإلا يبقى edit().apply() عادي
        try {
            prefs.edit { putString(KEY, arr.toString()) }
        } catch (_: Throwable) {
            prefs.edit().putString(KEY, arr.toString()).apply()
        }
    }
}