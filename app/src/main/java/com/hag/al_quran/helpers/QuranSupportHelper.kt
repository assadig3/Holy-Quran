// File: app/src/main/java/com/hag/al_quran/helpers/QuranSupportHelper.kt
package com.hag.al_quran.helpers

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.R
import com.hag.al_quran.audio.MadaniPageProvider
import com.hag.al_quran.download.PagesDownloadService
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

    // ===== Utils بسيطة للتعامل مع الواجهات =====
    private fun View?.show() { this?.visibility = View.VISIBLE }
    private fun View?.hide() { this?.visibility = View.GONE }
    private fun TextView?.setSafeText(s: CharSequence?) { this?.text = s ?: "" }
    var onAyahChanged: ((surah: Int, ayah: Int) -> Unit)? = null
    var onPageFinished: (() -> Unit)? = null

    // ======================= تفضيل نوع الشبكة =======================
    private companion object {
        private const val PREF_NETWORK = "pref_network_type"
    }
    private enum class NetworkPref { WIFI_ONLY, MOBILE_ONLY, ANY }

    private fun getNetworkPref(): NetworkPref {
        return when (activity.prefs.getString(PREF_NETWORK, "WIFI_ONLY")) {
            "MOBILE_ONLY" -> NetworkPref.MOBILE_ONLY
            "ANY"         -> NetworkPref.ANY
            else          -> NetworkPref.WIFI_ONLY
        }
    }
    var onAyahPlaybackStarted: ((surah: Int, ayah: Int, ayahText: String) -> Unit)? = null

    private fun isNetworkPrefSet(): Boolean = activity.prefs.contains(PREF_NETWORK)

    /** حوار يطلب نوع الشبكة ويحفظه، ثم ينفّذ onDone مباشرة */
    private fun pickNetworkThen(onDone: () -> Unit) {
        val items = arrayOf("واي-فاي فقط", "البيانات فقط", "أي شبكة (واي-فاي أو بيانات)")
        var sel = when (getNetworkPref()) {
            NetworkPref.WIFI_ONLY   -> 0
            NetworkPref.MOBILE_ONLY -> 1
            NetworkPref.ANY         -> 2
        }
        AlertDialog.Builder(activity)
            .setTitle("نوع الشبكة للتحميل")
            .setSingleChoiceItems(items, sel) { _, which -> sel = which }
            .setPositiveButton("حفظ") { d, _ ->
                val value = when (sel) {
                    0 -> "WIFI_ONLY"
                    1 -> "MOBILE_ONLY"
                    else -> "ANY"
                }
                activity.prefs.edit().putString(PREF_NETWORK, value).apply()
                Toast.makeText(activity, "تم حفظ التفضيل: ${items[sel]}", Toast.LENGTH_SHORT).show()
                d.dismiss()
                onDone()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    /** حوار مستقل لتغيير نوع الشبكة من الإعدادات */
    fun showNetworkTypeDialog() {
        val items = arrayOf("واي-فاي فقط", "البيانات فقط", "أي شبكة (واي-فاي أو بيانات)")
        var sel = when (getNetworkPref()) {
            NetworkPref.WIFI_ONLY   -> 0
            NetworkPref.MOBILE_ONLY -> 1
            NetworkPref.ANY         -> 2
        }
        AlertDialog.Builder(activity)
            .setTitle("نوع الشبكة للتحميل")
            .setSingleChoiceItems(items, sel) { _, which -> sel = which }
            .setPositiveButton("حفظ") { d, _ ->
                val value = when (sel) {
                    0 -> "WIFI_ONLY"
                    1 -> "MOBILE_ONLY"
                    else -> "ANY"
                }
                activity.prefs.edit().putString(PREF_NETWORK, value).apply()
                Toast.makeText(activity, "تم حفظ التفضيل: ${items[sel]}", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ======================= فحص الاتصال ونوع الشبكة =======================
    private fun isConnected(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isOnWifi(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun isOnMobileData(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /** هل نسمح بالتحميل وفق تفضيل الشبكة الحالي؟ */
    private fun shouldAllowDownload(showToast: Boolean = true): Boolean {
        if (!isConnected()) {
            if (showToast) Toast.makeText(activity, "لا يوجد اتصال بالإنترنت.", Toast.LENGTH_LONG).show()
            return false
        }
        return when (getNetworkPref()) {
            NetworkPref.WIFI_ONLY -> {
                val ok = isOnWifi()
                if (!ok && showToast) {
                    Toast.makeText(activity, "التفضيل: واي-فاي فقط. اتصل بواي-فاي أو غيّر الإعداد.", Toast.LENGTH_LONG).show()
                }
                ok
            }
            NetworkPref.MOBILE_ONLY -> {
                val ok = isOnMobileData()
                if (!ok && showToast) {
                    Toast.makeText(activity, "التفضيل: البيانات فقط. فعّل بيانات الهاتف أو غيّر الإعداد.", Toast.LENGTH_LONG).show()
                }
                ok
            }
            NetworkPref.ANY -> true
        }
    }

    // ========= JSON ARRAYS / CACHE =========
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

    // عدد الآيات في كل سورة (1..114)
    private val AYAH_COUNTS = intArrayOf(
        7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,112,78,118,64,77,227,93,88,69,60,
        34,30,73,54,45,83,182,88,75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,14,11,11,18,
        12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,
        11,8,3,9,5,4,5,6,3,5,4,5,4,5,6
    )

    // ======================= بانر "الآن يتلى" =======================
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
            post { isSelected = true } // لتفعيل marquee
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

    // ======================= شريط خيارات الآية =======================
    fun showAyahOptionsBar(surah: Int, ayah: Int, ayahText: String) {
        activity.toolbar.visibility = View.VISIBLE
        activity.audioControls.visibility = View.VISIBLE
        activity.ayahPreview?.text = ayahText
        activity.ayahOptionsBar.visibility = View.VISIBLE
        activity.ayahOptionsBar.alpha = 1f
        activity.showBarsThenAutoHide(3000)
    }

    // ======================= تحكم بعرض الأشرطة =======================
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

    // ======================= نصوص وأسماء السور =======================
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

    // ======================= حدود الآيات على الصفحة =======================
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

    /** أول آية على الصفحة (سورة/آية) أو null إذا لا توجد حدود محفوظة */
    fun firstAyahOnPage(page: Int): Pair<Int, Int>? {
        val b = loadAyahBoundsForPage(page).minByOrNull { it.aya_id } ?: return null
        return b.sura_id to b.aya_id
    }

    /** اختصار: إظهار بانر لأول آية في الصفحة الحالية */
    fun showFirstAyahBannerForPage(page: Int) {
        val fa = firstAyahOnPage(page)
        if (fa != null) showAyahBanner(fa.first, fa.second)
    }

    // ======================= اختيار القارئ =======================
    data class QariMini(val id: String, val name: String)

    fun showQariPicker(onPicked: (QariMini) -> Unit) {
        val qaris = provider.getQaris()
        val names = qaris.map { it.name }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("اختر القارئ")
            .setItems(names) { _, which ->
                val q = qaris[which]
                activity.prefs.edit()
                    .putString(QuranPageActivity.KEY_QARI_ID, q.id.trim().lowercase())
                    .apply()
                onPicked(QariMini(q.id, q.name))
                Toast.makeText(activity, "تم اختيار ${q.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ======================= مشاركة آية =======================
    fun shareCurrentAyah(surah: Int, ayah: Int) {
        val text = "سورة ${getSurahNameByNumber(surah)} - آية $ayah\n\n${getAyahTextFromJson(surah, ayah)}"
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "مشاركة"))
    }

    // ======================= التفسير =======================
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
        v.findViewById<TextView>(R.id.tafsirAyahTitle).setSafeText(title)
        v.findViewById<TextView>(R.id.tafsirAyahText).setSafeText(ayahText)
        v.findViewById<TextView>(R.id.tafsirText).setSafeText(tafsirText)
        v.findViewById<View>(R.id.btnCloseTafsir).setOnClickListener { tafsirAlertDialog.dismiss() }
        v.findViewById<View>(R.id.btnShareTafsir).setOnClickListener {
            val share = "$title\n\n$ayahText\n\nالتفسير:\n$tafsirText"
            activity.startActivity(Intent.createChooser(Intent().apply {
                action = Intent.ACTION_SEND; type = "text/plain"; putExtra(Intent.EXTRA_TEXT, share)
            }, "مشاركة التفسير"))
        }
        tafsirAlertDialog = AlertDialog.Builder(activity).create().apply { setView(v); show() }
    }

    // ======================= تنزيل التلاوات =======================
    private enum class DownloadScope { PAGE, SURAH, JUZ, QURAN }

    private val JUZ_START_PAGES = intArrayOf(
        1, 22, 42, 62, 82, 102, 121, 141, 162, 182,
        201, 222, 242, 262, 282, 302, 322, 342, 362, 382,
        402, 422, 442, 462, 482, 502, 522, 542, 562, 582
    )

    private data class DLItem(val url: String, val out: File, val surah: Int, val ayah: Int)

    fun showDownloadScopeDialog(currentPage: Int, currentSurah: Int, currentQariId: String) {
        val choices = arrayOf("الصفحة", "السورة", "الجزء", "المصحف كامل")
        var selected = 0
        AlertDialog.Builder(activity)
            .setTitle("كم التحميل؟")
            .setSingleChoiceItems(choices, selected) { _, which -> selected = which }
            .setPositiveButton("تحميل") { d, _ ->
                val scope = when (selected) {
                    1 -> DownloadScope.SURAH
                    2 -> DownloadScope.JUZ
                    3 -> DownloadScope.QURAN
                    else -> DownloadScope.PAGE
                }
                val qariId = currentQariId.trim().lowercase()
                val start = {
                    if (scope == DownloadScope.QURAN) {
                        Toast.makeText(activity, "سيتم تنزيل التلاوة للمصحف كاملة. قد يستغرق وقتًا طويلًا.", Toast.LENGTH_LONG).show()
                    }
                    startBulkDownload(scope, currentPage, currentSurah, qariId)
                }
                d.dismiss()
                if (!isNetworkPrefSet()) pickNetworkThen { start() } else start()
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

    private fun buildItemsFor(
        scope: DownloadScope,
        pageNow: Int,
        surahNow: Int,
        qariIdRaw: String
    ): List<DLItem> {
        val qariId = qariIdRaw.trim().lowercase()
        val qari = provider.getQariById(qariId) ?: return emptyList()
        val items = ArrayList<DLItem>(6400)

        fun addAyah(s: Int, a: Int) {
            val url = provider.getAyahUrl(qari, s, a)
            val out = qariFile(qariId, s, a)
            items.add(DLItem(url, out, s, a))
        }

        when (scope) {
            DownloadScope.PAGE -> {
                for (b in loadAyahBoundsForPage(pageNow)) addAyah(b.sura_id, b.aya_id)
            }
            DownloadScope.SURAH -> {
                val count = AYAH_COUNTS.getOrNull(surahNow - 1) ?: 0
                for (a in 1..count) addAyah(surahNow, a)
            }
            DownloadScope.JUZ -> {
                for (p in pageRangeForCurrentJuz(pageNow)) {
                    for (b in loadAyahBoundsForPage(p)) addAyah(b.sura_id, b.aya_id)
                }
            }
            DownloadScope.QURAN -> {
                for (s in 1..114) {
                    val c = AYAH_COUNTS.getOrNull(s - 1) ?: continue
                    for (a in 1..c) addAyah(s, a)
                }
            }
        }
        return items.distinctBy { it.out.absolutePath }
    }

    // ======================= تشغيل الخلفية (بدون أي شريط تحميل سفلي) =======================
    private fun startBulkDownload(
        scope: DownloadScope,
        pageNow: Int,
        surahNow: Int,
        qariId: String
    ) {
        val qari = provider.getQariById(qariId) ?: run {
            Toast.makeText(activity, "تعذر تحديد القارئ.", Toast.LENGTH_SHORT).show()
            return
        }

        // احترام تفضيل الشبكة قبل البدء
        if (!shouldAllowDownload(showToast = true)) return

        val go = {
            val intent = Intent(activity, PagesDownloadService::class.java)
                .setAction(PagesDownloadService.ACTION_START)
                .putExtra(PagesDownloadService.EXTRA_SCOPE, scope.name)
                .putExtra(PagesDownloadService.EXTRA_PAGE, pageNow)
                .putExtra(PagesDownloadService.EXTRA_SURAH, surahNow)
                .putExtra(PagesDownloadService.EXTRA_QARI, qariId)
                .putExtra(PagesDownloadService.EXTRA_PARALLELISM, 6)
                .putExtra(PagesDownloadService.EXTRA_TOTAL, 0)

            ContextCompat.startForegroundService(activity, intent)
        }

        // تنبيه عند استخدام البيانات (في حال السماح بأي شبكة أو البيانات فقط)
        val pref = getNetworkPref()
        if (isOnMobileData() && (pref == NetworkPref.ANY || pref == NetworkPref.MOBILE_ONLY)) {
            AlertDialog.Builder(activity)
                .setTitle("تنبيه")
                .setMessage("أنت تستخدم بيانات الهاتف. قد يستهلك التحميل حجمًا كبيرًا من الباقة.\nهل تريد المتابعة؟")
                .setPositiveButton("متابعة") { _, _ -> go() }
                .setNegativeButton("إلغاء", null)
                .show()
        } else {
            go()
        }
    }

    // ======================= تنزيل ملف واحد =======================
    fun downloadOneAsync(message: String, url: String, out: File) {
        if (!shouldAllowDownload(showToast = true)) return
        thread {
            downloadWithProgress(url, out) { _, _ -> /* اختياري: Toast عند الاكتمال */ }
        }
    }

    // تنزيل صامت بدون واجهة
    fun downloadOneSilentInBackground(url: String, out: File) {
        if (!shouldAllowDownload(showToast = false)) return
        thread {
            try { downloadWithProgress(url, out) { _, _ -> } } catch (_: Exception) {}
        }
    }

    // ======================= تنزيل منخفض المستوى =======================
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
                            if (downloaded - lastEmit > 128 * 1024) { onProgress(pct, true); lastEmit = downloaded }
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

    // ======================= مسارات الحفظ =======================
    private fun qariDir(qariIdRaw: String): File {
        val safe = qariIdRaw.trim().lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")
        val dir = File(activity.getExternalFilesDir(null), "recitations/$safe")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun qariFile(qariIdRaw: String, surah: Int, ayah: Int): File {
        val qariId = qariIdRaw.trim().lowercase()
        val name = "%03d%03d.mp3".format(surah, ayah)
        return File(qariDir(qariId), name)
    }
}
