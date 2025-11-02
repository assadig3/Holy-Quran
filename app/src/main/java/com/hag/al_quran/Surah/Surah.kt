package com.hag.al_quran.Surah

data class Surah(
    val number: Int,
    val name: String,
    val englishName: String,
    val type: String,       // "مكية" / "مدنية" أو Meccan/Medinan
    val ayahCount: Int,
    val pageNumber: Int
)
