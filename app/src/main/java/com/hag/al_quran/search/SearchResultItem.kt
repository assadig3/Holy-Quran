package com.hag.al_quran.search

data class SearchResultItem(
    val surah: Int,
    val ayah: Int,
    val page: Int,
    val surahName: String,
    val text: String
)