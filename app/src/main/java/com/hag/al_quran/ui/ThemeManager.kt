// File: app/src/main/java/com/hag/al_quran/ui/ThemeManager.kt
package com.hag.al_quran.ui

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeManager {
    private const val PREFS = "app_prefs"
    private const val KEY_NIGHT = "night_enabled"

    fun isNight(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_NIGHT, false)

    fun applySavedTheme(context: Context) {
        setNightMode(isNight(context))
    }

    fun toggle(context: Context): Boolean {
        val next = !isNight(context)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_NIGHT, next).apply()
        setNightMode(next)
        return next
    }

    private fun setNightMode(night: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (night) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
