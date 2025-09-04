// File: app/src/main/java/com/hag/al_quran/LocaleUtil.kt
package com.hag.al_quran

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleUtil {

    fun wrap(base: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    fun setAppLocale(activity: Activity, lang: String) {
        val wrapped = wrap(activity, lang)
        // تحديث Resources للنشاط الحالي
        activity.resources.updateConfiguration(
            wrapped.resources.configuration, wrapped.resources.displayMetrics
        )
    }
}
