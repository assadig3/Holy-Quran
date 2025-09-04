package com.hag.al_quran.player

import android.content.Context
import android.content.Intent
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.helpers.QuranAudioHelper
import java.lang.ref.WeakReference

/**
 * جسر خفيف بين BroadCast/Notification وبين الـHelper.
 * إن كان الـHelper مربوطاً نُنفّذ فوراً، وإلا نفتح الـActivity بنفس الـAction.
 */
object PlayerBus {
    private var helperRef: WeakReference<QuranAudioHelper>? = null
    @Volatile private var lastPage: Int = 1
    @Volatile private var lastQariId: String = "fares"

    fun bind(helper: QuranAudioHelper) { helperRef = WeakReference(helper) }
    fun unbind() { helperRef?.clear(); helperRef = null }

    fun updateState(page: Int, qariId: String) {
        lastPage = page; lastQariId = qariId
    }

    fun toggle(context: Context) {
        val h = helperRef?.get()
        if (h != null) {
            if (h.isPlaying) h.pausePagePlayback() else h.startPagePlayback(lastPage, lastQariId)
        } else {
            context.startActivity(
                Intent(context, QuranPageActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setAction("com.hag.al_quran.NOTIF_TOGGLE")
            )
        }
    }

    fun stop(context: Context) {
        val h = helperRef?.get()
        if (h != null) {
            h.pausePagePlayback()
        } else {
            context.startActivity(
                Intent(context, QuranPageActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .setAction("com.hag.al_quran.NOTIF_STOP")
            )
        }
    }
}
