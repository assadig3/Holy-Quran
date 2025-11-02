package com.hag.al_quran.utils

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.ContextThemeWrapper

object FontScale {
    private const val PREF_NAME = "settings"
    private const val KEY_FONT = "font"

    // المقاييس (عدّلها حسب ذوقك)
    private const val SCALE_XS = 0.85f
    private const val SCALE_SMALL = 0.95f
    private const val SCALE_MEDIUM = 1.00f
    private const val SCALE_LARGE = 1.15f
    private const val SCALE_XL = 1.30f
    private const val SCALE_XXL = 1.50f

    fun saveChoice(ctx: Context, choice: String) {
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_FONT, choice).apply()
    }

    fun getChoice(ctx: Context): String {
        val p = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return p.getString(KEY_FONT, "medium") ?: "medium"
    }

    fun resolveScale(choice: String): Float = when (choice) {
        "xs" -> SCALE_XS
        "small" -> SCALE_SMALL
        "medium" -> SCALE_MEDIUM
        "large" -> SCALE_LARGE
        "xl" -> SCALE_XL
        "xxl" -> SCALE_XXL
        else -> SCALE_MEDIUM
    }

    /** يلفّ الـ Context بمقياس الخط */
    fun wrapContextWithFontScale(base: Context): Context {
        val scale = resolveScale(getChoice(base))
        val cfg = Configuration(base.resources.configuration)
        cfg.fontScale = scale

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            base.createConfigurationContext(cfg)
        } else {
            object : ContextThemeWrapper(base, base.theme) {
                override fun applyOverrideConfiguration(overrideConfiguration: Configuration?) {
                    super.applyOverrideConfiguration(cfg)
                }
            }
        }
    }
}
