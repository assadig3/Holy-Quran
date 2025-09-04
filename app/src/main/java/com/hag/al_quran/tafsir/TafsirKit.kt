package com.hag.al_quran.tafsir

/**
 * مصدر واحد لقائمة التفاسير وروابط/أسماء الملفات.
 */
object TafsirKit {
    // الاسم المعروض للمستخدم  ↔  اسم ملف JSON على الـ CDN/التخزين
    val items: List<Pair<String, String>> = listOf(
        "تفسير ابن كثير" to "ar-tafsir-ibn-kathir.json",
        "تفسير السعدي"  to "ar-tafsir-as-saadi.json",
        "تفسير القرطبي" to "ar-tafsir-al-qurtubi.json"
    )

    fun names(): Array<String> = items.map { it.first }.toTypedArray()
    fun fileAt(index: Int): String = items[index].second
}
