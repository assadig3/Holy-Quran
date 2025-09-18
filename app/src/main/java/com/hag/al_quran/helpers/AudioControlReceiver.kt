package com.hag.al_quran.helpers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * يستقبل ضغطات أزرار إشعار التشغيل ثم يشغّل خدمة الصوت كـ ForegroundService.
 * لا ينشئ إشعارًا جديدًا ولا يلمس الواجهة.
 */
class AudioControlReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // مرّر الصفحة/القارئ الحاليين (إن وُجدا) إلى الخدمة
        val svc = Intent(context, AudioForegroundService::class.java).apply {
            this.action = action
            putExtra(
                AudioActions.EXTRA_PAGE,
                intent.getIntExtra(AudioActions.EXTRA_PAGE, AudioForegroundService.currentPage)
            )
            putExtra(
                AudioActions.EXTRA_QARI_ID,
                intent.getStringExtra(AudioActions.EXTRA_QARI_ID) ?: AudioForegroundService.currentQariId
            )
        }

        // مهم: تشغيل الخدمة كـ ForegroundService (مسموح لأنه نتيجة تفاعل المستخدم مع الإشعار)
        ContextCompat.startForegroundService(context, svc)
    }
}
