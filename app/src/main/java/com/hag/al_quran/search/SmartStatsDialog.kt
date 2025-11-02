// File: app/src/main/java/com/hag/al_quran/search/SmartStatsDialog.kt
package com.hag.al_quran.search

import android.content.Context
import androidx.appcompat.app.AlertDialog

object SmartStatsDialog {

    fun show(context: Context, query: String, breakdown: List<WordIndex.SurahBreakdown>, surahNames: List<String>) {
        if (breakdown.isEmpty()) {
            AlertDialog.Builder(context)
                .setTitle("التوزيع على السور")
                .setMessage("لا توجد بيانات لعرضها.")
                .setPositiveButton("حسناً", null)
                .show()
            return
        }

        val lines = breakdown.map { b ->
            val name = surahNames.getOrNull(b.surah - 1) ?: "سورة ${b.surah}"
            "• $name — مرات الذكر: ${toArabic(b.totalOccurrences)} — في ${toArabic(b.ayahMatches)} آية"
        }.toTypedArray()

        AlertDialog.Builder(context)
            .setTitle("«$query» — التوزيع على السور")
            .setItems(lines, null)
            .setPositiveButton("إغلاق", null)
            .show()
    }

    private fun toArabic(n: Int): String {
        val d = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
        return n.toString().map { d[it - '0'] }.joinToString("")
    }
}
