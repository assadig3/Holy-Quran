package com.hag.al_quran2

/** واجهة بسيطة لإظهار/إخفاء شريط التحميل الوسطي */
interface CenterLoaderHost {
    fun showCenterLoader(msg: String = "جاري التحميل...")
    fun hideCenterLoader()
}
