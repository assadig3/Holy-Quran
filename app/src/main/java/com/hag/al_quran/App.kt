package com.hag.al_quran

import android.app.Application
import com.google.firebase.FirebaseApp
import android.util.Log

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        try {
            // هذا يستدعي تهيئة Firebase باستخدام google-services.json إذا كان موجوداً
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                Log.d("App", "FirebaseApp initialized")
            } else {
                Log.d("App", "FirebaseApp already initialized")
            }
        } catch (e: Exception) {
            // لا نسمح لتحطم التطبيق؛ نرصد المشكلة في Logcat
            Log.w("App", "Failed to initialize Firebase: ${e.message}")
        }
    }
}
