package com.hag.al_quran.utils

import android.content.Context
import org.json.JSONObject

// ✅ مفاتيح التخزين العامة
private const val PREF_AYAH = "favorite_ayahs"
private const val PREF_PAGE = "favorite_pages"
private const val KEY_AYAH_LIST = "fav_list"
private const val KEY_PAGE_LIST = "pages"



// -----------------------------
// 📌 دوال الآيات المفضلة
// -----------------------------

fun addFavoriteAyah(context: Context, surah: Int, ayah: Int) {
    val prefs = context.getSharedPreferences(PREF_AYAH, Context.MODE_PRIVATE)
    val set = prefs.getStringSet(KEY_AYAH_LIST, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    set.add("$surah:$ayah")
    prefs.edit().putStringSet(KEY_AYAH_LIST, LinkedHashSet(set)).apply()
}

fun removeFavoriteAyah(context: Context, surah: Int, ayah: Int) {
    val prefs = context.getSharedPreferences(PREF_AYAH, Context.MODE_PRIVATE)
    val set = prefs.getStringSet(KEY_AYAH_LIST, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    set.remove("$surah:$ayah")
    prefs.edit().putStringSet(KEY_AYAH_LIST, LinkedHashSet(set)).apply()
}

fun isFavoriteAyah(context: Context, surah: Int, ayah: Int): Boolean {
    val prefs = context.getSharedPreferences(PREF_AYAH, Context.MODE_PRIVATE)
    val set = prefs.getStringSet(KEY_AYAH_LIST, mutableSetOf()) ?: mutableSetOf()
    return set.contains("$surah:$ayah")
}

fun getAllFavoriteAyahs(context: Context): List<Pair<Int, Int>> {
    val prefs = context.getSharedPreferences(PREF_AYAH, Context.MODE_PRIVATE)
    val set = prefs.getStringSet(KEY_AYAH_LIST, emptySet()) ?: emptySet()
    return set.mapNotNull {
        val parts = it.split(":")
        if (parts.size == 2) {
            val surah = parts[0].toIntOrNull()
            val ayah = parts[1].toIntOrNull()
            if (surah != null && ayah != null) surah to ayah else null
        } else null
    }
}

// -----------------------------
// 📌 دوال الصفحات المفضلة
// -----------------------------

fun addFavoritePage(context: Context, pageNumber: Int) {
    val prefs = context.getSharedPreferences(PREF_PAGE, Context.MODE_PRIVATE)
    val set = prefs.getStringSet(KEY_PAGE_LIST, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    set.add(pageNumber.toString())
    prefs.edit().putStringSet(KEY_PAGE_LIST, LinkedHashSet(set)).apply()
}
fun removeFavoritePage(context: Context, pageNumber: Int) {
    val prefs = context.getSharedPreferences(PREF_PAGE, Context.MODE_PRIVATE)
    val set = prefs.getStringSet(KEY_PAGE_LIST, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
    set.remove(pageNumber.toString())
    prefs.edit().putStringSet(KEY_PAGE_LIST, LinkedHashSet(set)).apply()
}


fun isFavoritePage(context: Context, pageNumber: Int): Boolean {
    val prefs = context.getSharedPreferences(PREF_PAGE, Context.MODE_PRIVATE)
    val set = prefs.getStringSet(KEY_PAGE_LIST, mutableSetOf()) ?: mutableSetOf()
    return set.contains(pageNumber.toString())
}

fun getAllFavoritePages(context: Context): List<Int> {
    val prefs = context.getSharedPreferences(PREF_PAGE, Context.MODE_PRIVATE)
    val set = prefs.getStringSet(KEY_PAGE_LIST, emptySet()) ?: emptySet()
    return set.mapNotNull { it.toIntOrNull() }
}

// -----------------------------
// 📌 حفظ آخر 3 صفحات تمت زيارتها
// -----------------------------

fun saveLastVisitedPage(context: Context, pageNumber: Int) {
    val prefs = context.getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)
    val stored = prefs.getString("recent_pages_str", "") ?: ""
    val history = stored.split(",").filter { it.isNotBlank() }.toMutableList()

    history.remove(pageNumber.toString()) // إزالة التكرار إن وجد
    history.add(0, pageNumber.toString()) // أضف الصفحة في البداية

    val limited = history.take(3).joinToString(",")
    prefs.edit().putString("recent_pages_str", limited).apply()
}

fun getRecentPages(context: Context): List<Int> {
    val prefs = context.getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)
    val stored = prefs.getString("recent_pages_str", "") ?: ""
    return stored.split(",")
        .filter { it.isNotBlank() }
        .mapNotNull { it.toIntOrNull() }
}

// -----------------------------
// 📌 حفظ آخر 3 آيات تمت زيارتها
// -----------------------------

fun saveLastVisitedAyah(context: Context, surah: Int, ayah: Int) {
    val prefs = context.getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)
    val stored = prefs.getString("recent_ayahs_str", "") ?: ""
    val history = stored.split(",").filter { it.isNotBlank() }.toMutableList()

    val key = "$surah:$ayah"
    history.remove(key) // إزالة التكرار إن وجد
    history.add(0, key) // أضف الآية في البداية

    val limited = history.take(3).joinToString(",")
    prefs.edit().putString("recent_ayahs_str", limited).apply()
}

fun getRecentAyahs(context: Context): List<Pair<Int, Int>> {
    val prefs = context.getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)
    val stored = prefs.getString("recent_ayahs_str", "") ?: ""
    return stored.split(",")
        .filter { it.isNotBlank() }
        .mapNotNull {
            val parts = it.split(":")
            if (parts.size == 2) {
                val surah = parts[0].toIntOrNull()
                val ayah = parts[1].toIntOrNull()
                if (surah != null && ayah != null) surah to ayah else null
            } else null
        }
}
fun getAyahsForPage(context: Context, pageNumber: Int): List<Pair<Int, Int>> {
    val inputStream = context.assets.open("page_ayahs_map.json")
    val jsonString = inputStream.bufferedReader().use { it.readText() }
    val json = JSONObject(jsonString)
    val ayahList = mutableListOf<Pair<Int, Int>>()
    if (!json.has(pageNumber.toString())) return ayahList
    val ayahArray = json.getJSONArray(pageNumber.toString())
    for (i in 0 until ayahArray.length()) {
        val item = ayahArray.getJSONArray(i)
        val surah = item.getInt(0)
        val ayah = item.getInt(1)
        ayahList.add(surah to ayah)
    }
    return ayahList
}
