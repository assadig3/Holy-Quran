package com.hag.al_quran2.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hag.al_quran2.player.PlayerBus

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            "com.hag.al_quran2.NOTIF_TOGGLE" -> PlayerBus.toggle(context)
            "com.hag.al_quran2.NOTIF_STOP"   -> PlayerBus.stop(context)
        }
    }
}
