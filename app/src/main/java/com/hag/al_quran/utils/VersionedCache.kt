package com.hag.al_quran.utils

import android.content.Context
import com.bumptech.glide.Glide

/**
 * امسح كاش Glide تلقائياً عند اكتشاف تغيير في REPO_VERSION.
 * نستخدم key محفوظ في SharedPreferences.
 */
object VersionedCache {

    // الحدّث هذا النص ليتطابق مع باقي الملفات:
    private const val REPO_VERSION = "qpages-2025-10-22"

    fun ensureFreshImages(ctx: Context) {
        val p = ctx.getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)
        val last = p.getString("last_repo_version", null)
        if (last == REPO_VERSION) return

        // امسح كاش الذاكرة فوراً
        Glide.get(ctx).clearMemory()
        // امسح كاش القرص في خيط منفصل
        Thread { Glide.get(ctx).clearDiskCache() }.start()

        p.edit().putString("last_repo_version", REPO_VERSION).apply()
    }
}
