// app/src/main/java/com/hag/al_quran/App.kt
package com.hag.al_quran

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.MemoryCategory

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Glide.get(this).setMemoryCategory(MemoryCategory.HIGH) // مساحة كاش ذاكرة أكبر

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        val saved = prefs.getString("lang", null)

        // إن لم يختر المستخدم بعد → لا نفرض لغة (نترك لغة النظام)
        val locales = if (saved.isNullOrBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(saved)
        }

        AppCompatDelegate.setApplicationLocales(locales)
    }
}
