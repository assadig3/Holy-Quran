// File: app/src/main/java/com/hag/al_quran/tafsir/TafsirManager.kt
package com.hag.al_quran.tafsir

import android.content.Context
import android.content.Intent
import android.widget.TextView
import android.widget.Toast
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hag.al_quran.R
import com.hag.al_quran.tafsir.TafsirUtils.downloadTafsirIfNeeded
import org.json.JSONArray

class TafsirManager(private val ctx: Context) {

    // ---------- ثوابت ----------
    private companion object {
        private const val PREFS_NAME = "quran_prefs"
        private const val KEY_SELECTED_TAFSIR_INDEX = "selected_tafsir_index"
    }

    // ---------- قائمة ملفات التفاسير ----------
    private val items: List<Pair<String, String>> = listOf(
        "تفسير ابن كثير" to "ar-tafsir-ibn-kathir.json",
        "تفسير السعدي"  to "ar-tafsir-as-saadi.json",
        "تفسير القرطبي" to "ar-tafsir-al-qurtubi.json"
    )

    // ---------- تفضيلات ----------
    private val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // اقرأ الاختيار المحفوظ (افتراضي 0 إن لم يُحفظ شيء)
    private var selectedIndex: Int = prefs.getInt(KEY_SELECTED_TAFSIR_INDEX, 0).coerceIn(items.indices)

    // ---------- وصول عام ----------
    fun names(): Array<String> = items.map { it.first }.toTypedArray()
    fun files(): Array<String> = items.map { it.second }.toTypedArray()
    fun getSelectedIndex(): Int = selectedIndex
    fun getSelectedName(): String = items[selectedIndex].first
    fun getSelectedFile(): String = items[selectedIndex].second

    fun setSelectedIndex(index: Int) {
        if (index in items.indices) {
            selectedIndex = index
            prefs.edit().putInt(KEY_SELECTED_TAFSIR_INDEX, index).apply()
        }
    }

    // ---------- حوار اختيار نوع التفسير (بدون كولباك) ----------
    fun showPickerDialog(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle("اختر نوع التفسير")
            .setSingleChoiceItems(names(), selectedIndex) { dlg, which ->
                // نحدّث الاختيار المحفوظ فورًا
                setSelectedIndex(which)
                Toast.makeText(ctx, "تم اختيار: ${items[which].first}", Toast.LENGTH_SHORT).show()
                dlg.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------- نسخة تسمح بتمرير كولباك ----------
    fun showPickerDialog(
        activity: AppCompatActivity,
        onSelected: (Int) -> Unit
    ) {
        AlertDialog.Builder(activity)
            .setTitle("اختر نوع التفسير")
            .setSingleChoiceItems(names(), selectedIndex) { dlg, which ->
                setSelectedIndex(which)
                Toast.makeText(ctx, "تم اختيار: ${items[which].first}", Toast.LENGTH_SHORT).show()
                dlg.dismiss()
                onSelected(which)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ---------- حوار تنزيل ملف تفسير واحد من الـ CDN ----------
    fun showDownloadDialog(activity: AppCompatActivity) {
        val files = files()
        val names = names()
        val links = mapOf(
            "ar-tafsir-ibn-kathir.json" to "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/ar-tafsir-ibn-kathir.json",
            "ar-tafsir-as-saadi.json"   to "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/ar-tafsir-as-saadi.json",
            "ar-tafsir-al-qurtubi.json" to "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/ar-tafsir-al-qurtubi.json"
        )

        AlertDialog.Builder(activity)
            .setTitle("تحميل تفسير")
            .setItems(names) { _, which ->
                val file = files[which]
                val url  = links[file] ?: return@setItems
                val pd = android.app.ProgressDialog(activity).apply {
                    setCancelable(false)
                    setMessage("جاري التحميل…")
                    show()
                }
                downloadTafsirIfNeeded(ctx, file, url) { ok, _ ->
                    activity.runOnUiThread {
                        pd.dismiss()
                        Toast.makeText(
                            ctx,
                            if (ok) "تم التحميل!" else "فشل التحميل!",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    /**
     * تحميل وقراءة التفسير للآية المختارة من CDN.
     * - إن مرّرت ملفًا محددًا في `fileOverride` سنستخدمه مباشرة.
     * - وإلا نستخدم الاختيار المحفوظ (selectedIndex).
     */
    fun fetchFromCDN(
        surah: Int,
        ayah: Int,
        fileOverride: String? = null,
        cb: (String) -> Unit
    ) {
        val file = fileOverride ?: getSelectedFile()
        val url  = "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/$file"

        downloadTafsirIfNeeded(ctx, file, url) { ok, _ ->
            val text = if (ok) TafsirUtils.getAyahTafsir(ctx, surah, ayah, file) else null
            cb(text ?: "لم يتم العثور على التفسير.")
        }
    }

    // دالة توافقية للاسم القديم؛ الآن تحترم باراميتر الملف إن أرسلته
    fun fetchTafsirFromCDN(
        surah: Int,
        ayah: Int,
        file: String? = null,
        cb: (String) -> Unit
    ) = fetchFromCDN(surah, ayah, file, cb)

    // ---------- حوار عرض التفسير مع مشاركة ----------
    fun showAyahDialog(surah: Int, ayah: Int, ayahText: String, tafsirText: String) {
        val v = LayoutInflater.from(ctx).inflate(R.layout.dialog_tafsir_ayah, null)
        v.findViewById<TextView>(R.id.tafsirAyahTitle).text =
            "سورة ${getSurahNameByNumber(surah)}  -  آية $ayah"
        v.findViewById<TextView>(R.id.tafsirAyahText).text = ayahText
        v.findViewById<TextView>(R.id.tafsirText).text = tafsirText

        val dlg = AlertDialog.Builder(ctx).create()
        dlg.setView(v)

        v.findViewById<View>(R.id.btnCloseTafsir).setOnClickListener { dlg.dismiss() }
        v.findViewById<View>(R.id.btnShareTafsir).setOnClickListener {
            val shareText =
                "${v.findViewById<TextView>(R.id.tafsirAyahTitle).text}\n\n$ayahText\n\nالتفسير:\n$tafsirText"
            val i = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, shareText)
            }
            ctx.startActivity(Intent.createChooser(i, "مشاركة التفسير"))
        }

        dlg.show()
    }

    // ---------- مساعدين للأسماء ----------
    fun getSurahNameByNumber(num: Int): String {
        val js = ctx.assets.open("surahs.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(js)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getInt("number") == num) return o.getString("name")
        }
        return ""
    }

    fun getSurahNameForPage(page: Int): String {
        val js = ctx.assets.open("surahs.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(js)
        var name = ""
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (page >= o.getInt("pageNumber")) name = o.getString("name") else break
        }
        return name
    }
}
