package com.hag.al_quran.onboarding

import androidx.annotation.DrawableRes

data class LanguageItem(
    val code: String,            // "ar","en",...
    val label: String,           // الاسم الظاهر
    val nativeName: String,      // الاسم المحلي
    val emojiFlag: String,       // علم إيموجي افتراضي
    @DrawableRes val flagRes: Int = 0 // بديل PNG لاحقًا (اتركه 0 الآن)
)
