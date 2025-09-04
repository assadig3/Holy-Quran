package com.hag.al_quran

import android.app.Application
import com.hag.al_quran.search.SearchEngine

class QuranApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // تهيئة بالخلفية فور تشغيل التطبيق
        Thread { try { SearchEngine.init(this) } catch (_: Throwable) {} }.start()
    }
}
