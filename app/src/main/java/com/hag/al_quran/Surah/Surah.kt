// File: app/src/main/java/com/hag/al_quran2/surah/Surah.kt
package com.hag.al_quran2.surah

data class Surah(
    val number: Int,
    val name: String,
    val englishName: String,
    val type: String,
    val ayahCount: Int,
    val pageNumber: Int
)
