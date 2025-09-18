// File: app/src/main/java/com/hag/al_quran/helpers/QuranSupportHelper.kt
package com.hag.al_quran.helpers

import android.app.ProgressDialog
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.text.TextUtils
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AlertDialog
import com.bumptech.glide.Glide
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.R
import com.hag.al_quran.audio.MadaniPageProvider
import com.hag.al_quran.tafsir.TafsirUtils
import com.hag.al_quran.tafsir.TafsirUtils.downloadTafsirIfNeeded
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlin.concurrent.thread

class QuranSupportHelper(
    private val activity: QuranPageActivity,
    private val provider: MadaniPageProvider
) {

    private val quranArr: JSONArray by lazy {
        val jsonStr = activity.assets.open("quran.json").bufferedReader().use { it.readText() }
        JSONArray(jsonStr)
    }
    private val surahsArr: JSONArray by lazy {
        val jsonStr = activity.assets.open("surahs.json").bufferedReader().use { it.readText() }
        JSONArray(jsonStr)
    }
    private val boundsCache = HashMap<Int, List<AyahBounds>>()
    private val boundsRoot: JSONObject by lazy {
        val jsonStr = activity.assets.open("pages/ayah_bounds_all.json").bufferedReader().use { it.readText() }
        JSONObject(jsonStr)
    }

    fun showAyahBanner(surah: Int, ayah: Int) {
        val text = try { getAyahTextFromJson(surah, ayah) } catch (_: Throwable) { "—" }
        showOrUpdateAyahBanner(surah, ayah, text)
    }

    fun showOrUpdateAyahBanner(surah: Int, ayah: Int, text: String) {
        activity.ayahBannerSurah?.text = getSurahNameByNumber(surah).ifEmpty { "سورة $surah" }
        activity.ayahBannerNumber?.text = "آية $ayah"
        activity.ayahTextView?.apply {
            this.text = text
            isSelected = false
            post { isSelected = true }
        }

        val banner = activity.ayahBanner
        if (banner?.visibility != View.VISIBLE) {
            banner?.let {
                it.visibility = View.VISIBLE
                val slideIn = AnimationUtils.loadAnimation(activity, R.anim.slide_in_top)
                it.startAnimation(slideIn)
            }
        }
    }

    fun hideAyahBanner() {
        val out = AnimationUtils.loadAnimation(activity, R.anim.slide_out_top)
        out.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation?) {
                activity.ayahBanner?.let { it.visibility = View.GONE }
            }
        })
        activity.ayahBanner?.startAnimation(out)
    }

    fun showAyahOptionsBar(surah: Int, ayah: Int, ayahText: String) {
        activity.toolbar.visibility = View.VISIBLE
        activity.audioControls.visibility = View.VISIBLE

        activity.ayahPreview?.text = ayahText
        activity.ayahOptionsBar.visibility = View.VISIBLE
        activity.ayahOptionsBar.alpha = 1f

        activity.showBarsThenAutoHide(3000)
    }

    fun showToolbarAndHideAfterDelay() {
        activity.showBarsThenAutoHide(3500)
    }

    fun hideToolbarAndBottomBar() {
        activity.toolbar.visibility = View.GONE

        activity.audioControls.animate()
            .translationY(activity.audioControls.height.toFloat())
            .alpha(0f)
            .setDuration(180)
            .withEndAction { activity.audioControls.visibility = View.GONE }
            .start()
    }

    fun showToolbarAndBottomBar() {
        activity.toolbar.visibility = View.VISIBLE
        activity.audioControls.apply {
            visibility = View.VISIBLE
            alpha = 0f
            animate().translationY(0f).alpha(1f).setDuration(200).start()
        }
    }

    fun getAyahTextFromJson(surah: Int, ayah: Int): String {
        for (i in 0 until quranArr.length()) {
            val sObj = quranArr.getJSONObject(i)
            if (sObj.getInt("id") == surah) {
                val verses = sObj.getJSONArray("verses")
                for (j in 0 until verses.length()) {
                    val v = verses.getJSONObject(j)
                    if (v.getInt("id") == ayah) return v.getString("text")
                }
            }
        }
        return "الآية غير موجودة"
    }

    fun getSurahNameByNumber(surahNumber: Int): String {
        for (i in 0 until surahsArr.length()) {
            val o = surahsArr.getJSONObject(i)
            if (o.getInt("number") == surahNumber) return o.getString("name")
        }
        return ""
    }

    fun getSurahNameForPage(page: Int): String {
        var name = ""
        for (i in 0 until surahsArr.length()) {
            val o = surahsArr.getJSONObject(i)
            if (page >= o.getInt("pageNumber")) name = o.getString("name") else break
        }
        return name
    }

    data class Seg(val x: Int, val y: Int, val w: Int, val h: Int)
    data class AyahBounds(val sura_id: Int, val aya_id: Int, val segs: List<Seg>)

    fun loadAyahBoundsForPage(page: Int): List<AyahBounds> {
        boundsCache[page]?.let { return it }
        val arr = boundsRoot.optJSONArray(page.toString()) ?: return emptyList()
        val res = mutableListOf<AyahBounds>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val segsArr = o.getJSONArray("segs")
            val segs = mutableListOf<Seg>()
            for (j in 0 until segsArr.length()) {
                val s = segsArr.getJSONObject(j)
                segs.add(Seg(s.getInt("x"), s.getInt("y"), s.getInt("w"), s.getInt("h")))
            }
            res.add(AyahBounds(o.getInt("sura_id"), o.getInt("aya_id"), segs))
        }
        boundsCache[page] = res
        return res
    }

    data class QariMini(val id: String, val name: String)
    // استبدل هذه الدالة كاملة
    fun showQariPicker(onPicked: (QariMini) -> Unit) {
        val qaris = provider.getQaris()
        val names = qaris.map { it.name }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("اختر القارئ")
            .setItems(names) { _, which ->
                val q = qaris[which]
                // خزّن دائمًا في المفتاح الموحّد الذي تقرأه QuranPageActivity
                activity.prefs.edit()
                    .putString(QuranPageActivity.KEY_QARI_ID, q.id.trim().lowercase())
                    .apply()

                onPicked(QariMini(q.id, q.name))
                Toast.makeText(activity, "تم اختيار ${q.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }


    fun shareCurrentAyah(surah: Int, ayah: Int) {
        val text = "سورة ${getSurahNameByNumber(surah)} - آية $ayah\n\n${getAyahTextFromJson(surah, ayah)}"
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "مشاركة"))
    }

    private val tafsirList = listOf(
        "تفسير ابن كثير" to "ar-tafsir-ibn-kathir.json",
        "تفسير السعدي"   to "ar-tafsir-as-saadi.json",
        "تفسير القرطبي"  to "ar-tafsir-al-qurtubi.json"
    )
    private var selectedTafsirId = 0
    private lateinit var tafsirAlertDialog: AlertDialog

    fun showTafsirPickerDialog() {
        val names = tafsirList.map { it.first }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("اختر نوع التفسير")
            .setItems(names) { _, which -> selectedTafsirId = which }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun showTafsirDownloadDialog() {
        val names = tafsirList.map { it.first }.toTypedArray()
        val files = tafsirList.map { it.second }
        val links = mapOf(
            "ar-tafsir-ibn-kathir.json" to "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/ar-tafsir-ibn-kathir.json",
            "ar-tafsir-as-saadi.json"   to "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/ar-tafsir-as-saadi.json",
            "ar-tafsir-al-qurtubi.json" to "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/ar-tafsir-al-qurtubi.json"
        )
        AlertDialog.Builder(activity)
            .setTitle("تحميل تفسير")
            .setItems(names) { _, which ->
                val file = files[which]
                val url = links[file] ?: return@setItems
                val pd = ProgressDialog(activity).apply {
                    setMessage("جاري تحميل: ${names[which]}"); setCancelable(false); show()
                }
                downloadTafsirIfNeeded(activity, file, url) { ok, _ ->
                    activity.runOnUiThread {
                        pd.dismiss()
                        Toast.makeText(activity, if (ok) "تم التحميل!" else "فشل التحميل!", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun openTafsir(surah: Int, ayah: Int) {
        val tafsirFile = tafsirList[selectedTafsirId].second
        val url = "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/$tafsirFile"
        downloadTafsirIfNeeded(activity, tafsirFile, url) { success, _ ->
            val text = if (success) TafsirUtils.getAyahTafsir(activity, surah, ayah, tafsirFile)
            else "فشل تحميل التفسير من الإنترنت."
            activity.runOnUiThread {
                showTafsirDialog(
                    "سورة ${getSurahNameByNumber(surah)}  -  آية $ayah",
                    getAyahTextFromJson(surah, ayah),
                    text ?: "لم يتم العثور على التفسير."
                )
            }
        }
    }

    private fun showTafsirDialog(title: String, ayahText: String, tafsirText: String) {
        if (::tafsirAlertDialog.isInitialized && tafsirAlertDialog.isShowing) tafsirAlertDialog.dismiss()
        val v = activity.layoutInflater.inflate(R.layout.dialog_tafsir_ayah, null)
        v.findViewById<TextView>(R.id.tafsirAyahTitle).text = title
        v.findViewById<TextView>(R.id.tafsirAyahText).text = ayahText
        v.findViewById<TextView>(R.id.tafsirText).text = tafsirText
        v.findViewById<View>(R.id.btnCloseTafsir).setOnClickListener { tafsirAlertDialog.dismiss() }
        v.findViewById<View>(R.id.btnShareTafsir).setOnClickListener {
            val share = "$title\n\n$ayahText\n\nالتفسير:\n$tafsirText"
            activity.startActivity(Intent.createChooser(Intent().apply {
                action = Intent.ACTION_SEND; type = "text/plain"; putExtra(Intent.EXTRA_TEXT, share)
            }, "مشاركة التفسير"))
        }
        tafsirAlertDialog = AlertDialog.Builder(activity).create().apply { setView(v); show() }
    }

    private enum class DownloadScope { PAGE, SURAH, JUZ }

    private val JUZ_START_PAGES = intArrayOf(
        1, 22, 42, 62, 82, 102, 121, 141, 162, 182,
        201, 222, 242, 262, 282, 302, 322, 342, 362, 382,
        402, 422, 442, 462, 482, 502, 522, 542, 562, 582
    )

    fun showDownloadScopeDialog(currentPage: Int, currentSurah: Int, currentQariId: String) {
        val choices = arrayOf("الصفحة", "السورة", "الجزء")
        var selected = 0
        AlertDialog.Builder(activity)
            .setTitle("كم التحميل؟")
            .setSingleChoiceItems(choices, selected) { _, which -> selected = which }
            .setPositiveButton("تحميل") { d, _ ->
                val scope = when (selected) {
                    1 -> DownloadScope.SURAH
                    2 -> DownloadScope.JUZ
                    else -> DownloadScope.PAGE
                }
                startBulkDownload(scope, currentPage, currentSurah, currentQariId)
                d.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun pageRangeForCurrentJuz(pageNow: Int): IntRange {
        var start = 1
        var end = 604
        for (i in 0 until 30) {
            val s = JUZ_START_PAGES[i]
            val e = if (i == 29) 604 else JUZ_START_PAGES[i + 1] - 1
            if (pageNow in s..e) { start = s; end = e; break }
        }
        return start..end
    }

    private fun buildUrlsFor(
        scope: DownloadScope,
        pageNow: Int,
        surahNow: Int,
        qariId: String
    ): List<Pair<String, File>> {
        val qari = provider.getQariById(qariId) ?: return emptyList()
        val pairs = mutableListOf<Pair<String, File>>()

        fun addFromBounds(bounds: List<AyahBounds>, surahFilter: Int? = null) {
            for (b in bounds) {
                if (surahFilter != null && b.sura_id != surahFilter) continue
                val url = provider.getAyahUrl(qari, b.sura_id, b.aya_id)
                val out = qariFile(qariId, b.sura_id, b.aya_id)
                pairs.add(url to out)
            }
        }

        when (scope) {
            DownloadScope.PAGE -> addFromBounds(loadAyahBoundsForPage(pageNow))
            DownloadScope.SURAH -> {
                for (p in 1..604) {
                    val bs = loadAyahBoundsForPage(p)
                    if (bs.any { it.sura_id == surahNow }) addFromBounds(bs, surahFilter = surahNow)
                }
            }
            DownloadScope.JUZ -> {
                for (p in pageRangeForCurrentJuz(pageNow)) addFromBounds(loadAyahBoundsForPage(p))
            }
        }
        return pairs.distinctBy { it.second.absolutePath }
    }

    private fun startBulkDownload(scope: DownloadScope, pageNow: Int, surahNow: Int, qariId: String) {
        val qari = provider.getQariById(qariId) ?: run {
            Toast.makeText(activity, "تعذر تحديد القارئ.", Toast.LENGTH_SHORT).show(); return
        }
        val items = buildUrlsFor(scope, pageNow, surahNow, qariId)
        if (items.isEmpty()) {
            Toast.makeText(activity, "لا توجد آيات مطابقة للنطاق المحدد.", Toast.LENGTH_SHORT).show()
            return
        }
        val title = when (scope) {
            DownloadScope.PAGE -> "تحميل الصفحة"
            DownloadScope.SURAH -> "تحميل السورة"
            DownloadScope.JUZ -> "تحميل الجزء"
        }
        showDownloadUiStarting("$title…")
        thread {
            var ok = true
            for (i in items.indices) {
                val (url, out) = items[i]
                val (success, _) = downloadWithProgress(url, out) { percent, known ->
                    activity.runOnUiThread {
                        if (known) {
                            activity.downloadProgress.isIndeterminate = false
                            setProgressPercent(percent)
                            activity.downloadLabel.text = "$title — ${i + 1} / ${items.size} — $percent%"
                        } else {
                            activity.downloadProgress.isIndeterminate = true
                            activity.downloadLabel.text = "$title — ${i + 1} / ${items.size}…"
                        }
                    }
                }
                ok = ok && success
            }
            activity.runOnUiThread {
                hideDownloadUi()
                val msg = if (ok) "$title اكتمل بنجاح في مجلد ${qari.id}" else "حدث خطأ أثناء $title"
                Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    fun downloadOneAsync(message: String, url: String, out: File) {
        showDownloadUiStarting(message)
        thread {
            downloadWithProgress(url, out) { percent, known ->
                activity.runOnUiThread {
                    if (known) {
                        activity.downloadProgress.isIndeterminate = false
                        setProgressPercent(percent)
                        activity.downloadLabel.text = "$message — $percent%"
                    } else {
                        activity.downloadProgress.isIndeterminate = true
                        activity.downloadLabel.text = message
                    }
                }
            }
            activity.runOnUiThread { hideDownloadUi() }
        }
    }

    // تنزيل صامت بدون واجهة
    fun downloadOneSilentInBackground(url: String, out: File) {
        thread {
            try { downloadWithProgress(url, out) { _, _ -> } } catch (_: Exception) {}
        }
    }

    fun showDownloadAllPagesDialogIfNeeded() {
        val already = activity.prefs.getBoolean("pages_downloaded", false)
        if (already) return
        showDownloadAllPagesDialog(activity.prefs)
    }

    private fun showDownloadAllPagesDialog(prefs: SharedPreferences) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_download_pages, null)
        dialogView.findViewById<TextView>(R.id.title)?.apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isFocusable = true
            isFocusableInTouchMode = true
            setHorizontallyScrolling(true)
            isSelected = true
        }
        val progressBar = dialogView.findViewById<ProgressBar>(R.id.progressBar)
        val loadingText = dialogView.findViewById<TextView>(R.id.loadingText)
        val btnDownloadNow = dialogView.findViewById<TextView>(R.id.btnDownloadNow)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnCancel)
        progressBar.max = 604; progressBar.progress = 0
        val alert = AlertDialog.Builder(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            .setView(dialogView).setCancelable(false).create()
        alert.window?.setBackgroundDrawableResource(android.R.color.transparent)
        alert.show()

        var cancelled = false

        btnDownloadNow.setOnClickListener {
            btnDownloadNow.isEnabled = false
            thread {
                for (page in 1..604) {
                    if (cancelled) break
                    try {
                        val url = "https://raw.githubusercontent.com/assadig3/quran-pages/main/pages/page_${page}.webp"
                        Glide.with(activity).downloadOnly().load(url).submit().get()
                    } catch (_: Exception) {}
                    activity.runOnUiThread {
                        progressBar.progress = page
                        loadingText.text = "جاري تحميل الصفحة $page من 604..."
                    }
                }
                activity.runOnUiThread {
                    if (!cancelled) {
                        prefs.edit().putBoolean("pages_downloaded", true).apply()
                        alert.dismiss()
                        Toast.makeText(activity, "تم تحميل جميع الصفحات بنجاح!", Toast.LENGTH_LONG).show()
                    } else {
                        btnDownloadNow.isEnabled = true
                    }
                }
            }
        }

        btnCancel.setOnClickListener {
            cancelled = true
            alert.dismiss()
            Toast.makeText(activity, "تم إلغاء التحميل.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadUiStarting(message: String) {
        activity.downloadRow.visibility = View.VISIBLE
        activity.downloadLabel.visibility = View.VISIBLE
        activity.downloadProgress.visibility = View.VISIBLE
        activity.downloadProgress.isIndeterminate = true
        activity.downloadLabel.text = message
    }

    private fun hideDownloadUi() {
        activity.downloadProgress.isIndeterminate = true
        setProgressPercent(0)
        activity.downloadLabel.visibility = View.GONE
        activity.downloadProgress.visibility = View.GONE
        activity.downloadRow.visibility = View.GONE
    }

    private fun setProgressPercent(percent: Int) {
        if (Build.VERSION.SDK_INT >= 24) {
            activity.downloadProgress.setProgress(percent.coerceIn(0, 100), true)
        } else {
            activity.downloadProgress.progress = percent.coerceIn(0, 100)
        }
    }

    private fun downloadWithProgress(
        url: String,
        out: File,
        onProgress: (Int, Boolean) -> Unit
    ): Pair<Boolean, Long> {
        return try {
            if (out.exists() && out.length() > 1024) return true to out.length()
            val conn = URL(url).openConnection()
            conn.connect()
            val total = conn.contentLengthLong
            onProgress(0, total > 0)
            var downloaded = 0L
            conn.getInputStream().use { input ->
                FileOutputStream(out).use { output ->
                    val buf = ByteArray(8 * 1024)
                    var read: Int
                    var lastEmit = 0L
                    while (true) {
                        read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val pct = ((downloaded * 100) / total).toInt().coerceIn(0, 100)
                            if (downloaded - lastEmit > 128 * 1024) {
                                onProgress(pct, true)
                                lastEmit = downloaded
                            }
                        } else {
                            onProgress(0, false)
                        }
                    }
                    output.flush()
                }
            }
            onProgress(100, total > 0)
            true to (if (total > 0) total else downloaded)
        } catch (_: Exception) {
            false to 0L
        }
    }

    private fun qariDir(qariId: String): File {
        val safe = qariId.lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")
        val dir = File(activity.getExternalFilesDir(null), "recitations/$safe")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun qariFile(qariId: String, surah: Int, ayah: Int): File {
        val name = "%03d%03d.mp3".format(surah, ayah)
        return File(qariDir(qariId), name)
    }
}
