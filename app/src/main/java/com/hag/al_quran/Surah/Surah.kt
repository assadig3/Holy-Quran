// File: app/src/main/java/com/hag/al_quran/surah/Surah.kt
package com.hag.al_quran.surah

data class Surah(
    val number: Int,
    val name: String,
    val englishName: String,
    val type: String,
    val ayahCount: Int,
    val pageNumber: Int
)
