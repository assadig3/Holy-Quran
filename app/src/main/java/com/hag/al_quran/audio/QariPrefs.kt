// File: app/src/main/java/com/hag/al_quran/audio/QariPrefs.kt
package com.hag.al_quran.audio

import android.content.Context
import android.content.SharedPreferences

object QariPrefs {
    private const val PREFS_NAME = "qari_prefs"
    private const val KEY_SELECTED_QARI_ID = "selected_qari_id"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** حفظ معرّف القارئ المختار */
    fun saveSelectedQariId(ctx: Context, qariId: String?) {
        prefs(ctx).edit().putString(KEY_SELECTED_QARI_ID, qariId).apply()
    }

    /** استرجاع القارئ المختار، إن لم يوجد يُعاد null */
    fun getSelectedQariId(ctx: Context): String? =
        prefs(ctx).getString(KEY_SELECTED_QARI_ID, null)

    /** استرجاع Qari نفسه إن وُجد في المزوّد */
    fun getSelectedQari(ctx: Context, provider: MadaniPageProvider): Qari? {
        val id = getSelectedQariId(ctx) ?: return null
        return provider.getQariById(id)
    }

    /** إعادة التعيين */
    fun clear(ctx: Context) {
        prefs(ctx).edit().remove(KEY_SELECTED_QARI_ID).apply()
    }
}
