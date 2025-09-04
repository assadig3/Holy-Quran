package com.hag.al_quran.search

/** خيارات البحث */
data class SearchOptions(
    val mode: Mode = Mode.EXACT_WORD,
    val exactTashkeel: Boolean = false, // يعمل فقط مع EXACT_WORD
    val debounceMs: Long = 250L
) {
    enum class Mode {
        /** نفس الكلمة فقط مع حدود عربية صحيحة (يمكن تفعيل exactTashkeel) */
        EXACT_WORD,
        /** بحث جزئي داخل النص المطبّع */
        PARTIAL
    }
}