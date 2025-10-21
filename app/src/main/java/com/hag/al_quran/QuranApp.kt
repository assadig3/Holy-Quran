package com.hag.al_quran2

import android.app.Application
import com.hag.al_quran2.search.SearchEngine

class QuranApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // تهيئة بالخلفية فور تشغيل التطبيق
        Thread { try { SearchEngine.init(this) } catch (_: Throwable) {} }.start()
    }
}
