// File: app/src/main/java/com/hag/al_quran/ui/AyahOptionsBar.kt
package com.hag.al_quran.ui

import android.app.Activity
import android.content.Context
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import com.hag.al_quran.R
import com.hag.al_quran.helpers.QuranSupportHelper
import com.hag.al_quran.tafsir.TafsirManager

/**
 * مُساعد بسيط للتحكم في شريط خيارات الآية داخل activity_quran_page.xml
 * هذه النسخة تعمل مع زر PopupMenu (btnTafsirMenu) بدلاً من TextInputLayout.
 */
class AyahOptionsBar(
    private val root: android.view.View,
    private val context: Context,
    private val tafsirManager: TafsirManager,
    private val supportHelper: QuranSupportHelper,
) {
    // Views
    private val btnClose: ImageButton       = root.findViewById(R.id.btnCloseOptions)
    private val btnTafsirMenu: TextView     = root.findViewById(R.id.btnTafsirMenu)
    private val btnDownloadTafsir: ImageButton = root.findViewById(R.id.btnDownloadTafsir)
    private val btnPlayAyah: ImageButton    = root.findViewById(R.id.btnPlayAyah)
    private val btnCopyAyah: ImageButton    = root.findViewById(R.id.btnCopyAyah)
    private val btnShareAyah: ImageButton   = root.findViewById(R.id.btnShareAyah)
    private val ayahPreview: TextView       = root.findViewById(R.id.ayahPreview)

    // State
    private var currentSurah: Int = 0
    private var currentAyah: Int  = 0

    fun setAyah(surah: Int, ayah: Int, preview: String?) {
        currentSurah = surah
        currentAyah  = ayah
        ayahPreview.text = preview.orEmpty()
        ayahPreview.isSelected = true
    }

    fun setup(
        onClose: (() -> Unit)? = null,
        onPlay: (() -> Unit)? = null,
        onCopy: (() -> Unit)? = null,
        onShare: (() -> Unit)? = null,
    ) {
        // اسم التفسير الحالي
        btnTafsirMenu.text = tafsirManager.names()
            .getOrNull(tafsirManager.getSelectedIndex())
            ?: context.getString(R.string.tafsir)

        // قائمة التفسير
        btnTafsirMenu.setOnClickListener { v ->
            val names = tafsirManager.names()
            val popup = PopupMenu(context, v)
            names.forEachIndexed { idx, name -> popup.menu.add(0, idx, idx, name) }
            popup.setOnMenuItemClickListener { mi ->
                val i = mi.itemId
                tafsirManager.setSelectedIndex(i)
                btnTafsirMenu.text = names[i]

                if (currentSurah > 0 && currentAyah > 0) {
                    val ayahText = try {
                        supportHelper.getAyahTextFromJson(currentSurah, currentAyah)
                    } catch (_: Throwable) {
                        ayahPreview.text.toString()
                    }
                    tafsirManager.fetchFromCDN(currentSurah, currentAyah) { tafsirText ->
                        (context as? Activity)?.runOnUiThread {
                            tafsirManager.showAyahDialog(currentSurah, currentAyah, ayahText, tafsirText)
                        }
                    }
                }
                true
            }
            popup.show()
        }

        // زر التحميل
        btnDownloadTafsir.setOnClickListener {
            (context as? androidx.appcompat.app.AppCompatActivity)?.let {
                tafsirManager.showDownloadDialog(it)
            }
        }

        // الأزرار الأخرى تُمرر إلى النشاط إن رغبت
        btnPlayAyah.setOnClickListener { onPlay?.invoke() }
        btnCopyAyah.setOnClickListener { onCopy?.invoke() }
        btnShareAyah.setOnClickListener { onShare?.invoke() }
        btnClose.setOnClickListener { onClose?.invoke() }
    }
}
