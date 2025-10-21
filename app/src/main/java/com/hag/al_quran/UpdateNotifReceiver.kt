package com.hag.al_quran2

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.edit

class UpdateNotifReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_DISMISSED -> {
                val latest = intent.getLongExtra(EXTRA_LATEST, -1L)
                if (latest > 0) {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                        putLong(KEY_LAST_DISMISSED, latest)
                    }
                }
            }
            ACTION_OPEN_STORE -> {
                val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: context.packageName
                val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                    setPackage("com.android.vending")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try {
                    context.startActivity(market)
                } catch (_: Exception) {
                    val web = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$pkg")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                    context.startActivity(web)
                }
            }
        }
    }

    companion object {
        const val ACTION_DISMISSED  = "com.hag.al_quran2.UPDATE_DISMISSED"
        const val ACTION_OPEN_STORE = "com.hag.al_quran2.OPEN_STORE"

        const val EXTRA_LATEST  = "extra_latest_version_code"
        const val EXTRA_PACKAGE = "extra_package"

        // نفس مفاتيح MainActivity
        private const val PREFS = "update_prefs"
        private const val KEY_LAST_DISMISSED = "last_dismissed_version"
    }
}
