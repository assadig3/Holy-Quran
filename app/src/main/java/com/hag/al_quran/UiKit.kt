package com.hag.al_quran

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.hag.al_quran.audio.MadaniPageProvider
import com.hag.al_quran.pages.PagesDownloader
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import kotlin.math.max

// ============ أدوات عامة ============
internal fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
internal fun AppCompatActivity.postDelayed(ms: Long, block: () -> Unit) =
    Handler(Looper.getMainLooper()).postDelayed({ block() }, ms)

// ============ شريط الآية ============
class AyahBannerUi(private val activity: AppCompatActivity) {
    private var banner: View? = null
    private var tvSurah: TextView? = null
    private var tvAyahNo: TextView? = null
    private var tvAyahText: TextView? = null

    fun attachTo(root: ViewGroup, onClose: () -> Unit) {
        val v = activity.layoutInflater.inflate(R.layout.ayah_now_playing, root, false)
        banner = v
        tvSurah = v.findViewById(R.id.surahName)
        tvAyahNo = v.findViewById(R.id.ayahNumber)
        tvAyahText = v.findViewById(R.id.ayahText)
        tvAyahText?.apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isSelected = true
            isFocusable = true
            isFocusableInTouchMode = true
            setHorizontallyScrolling(true)
        }
        v.visibility = View.GONE
        root.addView(v)
        v.findViewById<ImageButton>(R.id.btnCloseBanner).setOnClickListener { hide(); onClose() }
    }

    fun update(surahName: String, ayahNumberArabic: String, ayahText: String) {
        tvSurah?.text = surahName
        tvAyahNo?.text = "آية $ayahNumberArabic"
        tvAyahText?.text = ayahText
        tvAyahText?.isSelected = true
    }
    fun show() { banner?.visibility = View.VISIBLE }
    fun hide() { banner?.visibility = View.GONE }
    fun slideIn() {
        val anim = AnimationUtils.loadAnimation(activity, R.anim.slide_in_top)
        banner?.visibility = View.VISIBLE; banner?.startAnimation(anim)
    }
    fun slideOut() {
        val out = AnimationUtils.loadAnimation(activity, R.anim.slide_out_top)
        out.setAnimationListener(object: android.view.animation.Animation.AnimationListener {
            override fun onAnimationStart(animation: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(animation: android.view.animation.Animation?) {}
            override fun onAnimationEnd(animation: android.view.animation.Animation?) { banner?.visibility = View.GONE }
        })
        banner?.startAnimation(out)
    }
}

// ============ حوار القارئ ============
class QariUi(private val activity: AppCompatActivity, private val provider: MadaniPageProvider) {
    fun show(onSelected: (id: String, name: String) -> Unit) {
        val qaris = provider.getQaris()
        val names = qaris.map { it.name }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("اختر القارئ")
            .setItems(names) { _, which ->
                val q = qaris[which]
                onSelected(q.id, q.name)
                Toast.makeText(activity, "تم اختيار ${q.name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null).show()
    }
}

// ============ وضع المطور ============
object DevUi {
    fun setDevMode(context: Context, enable: Boolean) {
        context.getSharedPreferences("quran_prefs", AppCompatActivity.MODE_PRIVATE)
            .edit().putBoolean("dev_mode", enable).apply()
    }
    fun isDevMode(context: Context): Boolean =
        context.getSharedPreferences("quran_prefs", AppCompatActivity.MODE_PRIVATE)
            .getBoolean("dev_mode", false)

    fun setupCalibrateButton(
        activity: AppCompatActivity,
        viewPager: androidx.viewpager2.widget.ViewPager2,
        btn: ImageButton
    ) {
        btn.visibility = if (isDevMode(activity)) ImageButton.VISIBLE else ImageButton.GONE
        btn.post { btn.bringToFront(); btn.elevation = 16f; btn.translationZ = 16f }
        btn.setOnClickListener {
            val pos = viewPager.currentItem
            val rv = (viewPager.getChildAt(0) as? RecyclerView)
            val vh = rv?.findViewHolderForAdapterPosition(pos)
            (vh as? AssetPageAdapter.PageViewHolder)?.photoView?.performLongClick()
                ?: Toast.makeText(activity, "افتح الصفحة ثم أعد المحاولة.", Toast.LENGTH_SHORT).show()
        }
    }
    fun attachEasterEgg(activity: AppCompatActivity, toolbar: View, onToggle: (Boolean)->Unit) {
        var taps = 0
        toolbar.setOnClickListener {
            taps++
            if (taps >= 5) {
                taps = 0
                val newVal = !isDevMode(activity)
                setDevMode(activity, newVal)
                onToggle(newVal)
                Toast.makeText(activity, if (newVal) "تم تفعيل وضع المطوّر" else "تم إيقاف وضع المطوّر", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(activity, "انقر ${5 - taps} مرات لتبديل وضع المطوّر", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

// ============ AyahOptions ============
object AyahOptionsUi {

    fun show(
        activity: AppCompatActivity,
        ayahText: String,
        onCopy: () -> Unit,
        onShare: () -> Unit,
        onTafsir: () -> Unit,
        onPlay: () -> Unit
    ) {
        // الجذر قابل للتمرير لو النص طويل
        val scroll = ScrollView(activity).apply { isFillViewport = true }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(activity.dp(20), activity.dp(20), activity.dp(20), activity.dp(12))
        }

        // نص الآية
        val tvAyah = TextView(activity).apply {
            text = ayahText
            textSize = 18f
            setTextColor(Color.WHITE)
            setLineSpacing(2f, 1f)
        }
        root.addView(
            tvAyah,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = activity.dp(14) }
        )

        // صف الأزرار
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }

        fun makeBtn(@DrawableRes icon: Int, desc: String, onClick: () -> Unit): ImageButton {
            return ImageButton(activity).apply {
                setImageResource(icon)
                contentDescription = desc
                background = null
                setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(8))
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(activity.dp(44), activity.dp(44)).apply {
                    marginStart = activity.dp(4)
                }
            }
        }

        // استخدم نفس الموارد الموجودة عندك للأيقونات
        row.addView(makeBtn(R.drawable.ic_copy,    activity.getString(R.string.copy))   { onCopy()  })
        row.addView(makeBtn(R.drawable.ic_share,   activity.getString(R.string.share))  { onShare() })
        row.addView(makeBtn(R.drawable.ic_tafsir,  activity.getString(R.string.tafsir)) { onTafsir() })
        row.addView(makeBtn(R.drawable.ic_play,    activity.getString(R.string.play))   { onPlay()  })

        root.addView(row)
        scroll.addView(root)

        val dialog = AlertDialog.Builder(activity)
            .setView(scroll)
            .create()

        // خلفية شفافة ناعمة
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.show()
        (dialog.window?.decorView as? View)?.setBackgroundColor(Color.parseColor("#DD1F252B"))
    }
}

// ============ PagesDownload ============
object PagesDownloadUi {
    fun showIfNeeded(
        activity: AppCompatActivity,
        pagesDownloader: PagesDownloader,
        prefs: SharedPreferences,
        keyCached: String,
        keyPromptOnce: String,
        isActivityFinishing: () -> Boolean,
        hasPromptedThisSession: () -> Boolean,
        onUpdatePromptState: (Boolean) -> Unit
    ) {
        if (isActivityFinishing() || hasPromptedThisSession()) return
        val isCached = prefs.getBoolean(keyCached, false) ||
                prefs.getBoolean("pages_downloaded", false) ||
                pagesDownloader.isCached()
        if (isCached) { prefs.edit().putBoolean(keyCached, true).apply(); return }
        if (prefs.getBoolean(keyPromptOnce, false)) return
        onUpdatePromptState(true)

        val titleMarquee = TextView(activity).apply {
            text = "الرجاء الانتظار أثناء تحميل صفحات المصحف — املأ وقتك بالاستغفار 🌿"
            setPadding(32, 24, 32, 12); isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true
            isFocusable = true; isFocusableInTouchMode = true; setHorizontallyScrolling(true)
            textAlignment = TextView.TEXT_ALIGNMENT_VIEW_START
        }
        val progress = ProgressBar(activity).apply { isIndeterminate = false; max = 604 }
        val statusText = TextView(activity).apply { setPadding(32, 8, 32, 16); text = "بدء التحميل..." }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 8)
            addView(statusText, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progress, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        var canceled = false
        val dlg = AlertDialog.Builder(activity)
            .setCustomTitle(titleMarquee).setView(box).setCancelable(false)
            .setNegativeButton("إلغاء") { d, _ -> canceled = true; pagesDownloader.cancel(); d.dismiss() }
            .create()
        dlg.show(); titleMarquee.isSelected = true

        pagesDownloader.start(object : PagesDownloader.Listener {
            override fun onProgress(current: Int, total: Int) {
                activity.runOnUiThread { progress.progress = current; statusText.text = "تحميل الصفحة $current / $total" }
            }
            override fun onComplete(success: Boolean, errorMessage: String?) {
                activity.runOnUiThread {
                    if (dlg.isShowing) dlg.dismiss()
                    if (canceled) { onUpdatePromptState(false); Toast.makeText(activity, "تم الإلغاء.", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                    if (success) {
                        prefs.edit().putBoolean(keyCached, true).putBoolean("pages_downloaded", true).apply()
                        Toast.makeText(activity, "اكتمل التحميل.", Toast.LENGTH_SHORT).show()
                    } else {
                        onUpdatePromptState(false)
                        val msg = errorMessage?.takeIf { it.isNotBlank() } ?: "تعذّر التحميل."
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        })
        dlg.setOnDismissListener { if (!isActivityFinishing() && !canceled) pagesDownloader.cancel() }
        prefs.edit().putBoolean(keyPromptOnce, true).apply()
    }
}

// ============ Repeat ============

// ============ Toolbar ============
object ToolbarUi {
    fun attach(toolbar: View, toolbarSpacer: View, bottomBar: View, autoHideMillis: Long = 3500L): Pair<() -> Unit, () -> Unit> {
        val handler = Handler(Looper.getMainLooper())
        val hideRunnable = Runnable {
            toolbar.visibility = View.GONE
            toolbarSpacer.visibility = View.VISIBLE
            bottomBar.animate().translationY(bottomBar.height.toFloat()).setDuration(200)
                .withEndAction { bottomBar.visibility = View.GONE }
        }
        val show: () -> Unit = {
            toolbar.visibility = View.VISIBLE
            toolbarSpacer.visibility = View.GONE
            toolbar.animate().translationY(0f).setDuration(200).start()
            bottomBar.visibility = View.VISIBLE
            bottomBar.animate().translationY(0f).setDuration(200).start()
            handler.removeCallbacks(hideRunnable); handler.postDelayed(hideRunnable, autoHideMillis)
        }
        val hide: () -> Unit = { handler.removeCallbacks(hideRunnable); hideRunnable.run() }
        return show to hide
    }
}

// ============ AudioBatchDownloader ============
object AudioBatchDownloader {
    fun hook(
        activity: AppCompatActivity,
        button: ImageButton,
        provider: MadaniPageProvider,
        qariId: () -> String,
        currentPage: () -> Int
    ) {
        button.setOnClickListener {
            val qari = provider.getQariById(qariId()) ?: return@setOnClickListener
            val bounds = com.hag.al_quran.utils.loadAyahBoundsForPage(activity, currentPage())
            val urls = bounds.mapNotNull { provider.getAyahUrl(qari.id, it.sura_id, it.aya_id) }
            if (urls.isEmpty()) {
                Toast.makeText(activity, "لا توجد روابط للتحميل.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val dlg = android.app.ProgressDialog(activity).apply { setMessage("جاري تحميل التلاوات..."); setCancelable(false); show() }
            Thread {
                var success = true
                for ((i, url) in urls.withIndex()) {
                    val fn = "s${bounds[i].sura_id}_a${bounds[i].aya_id}_${qari.id}.mp3"
                    com.hag.al_quran.utils.downloadAyahToDownloads(activity, url, fn) { ok -> success = success && ok }
                    activity.runOnUiThread { dlg.setMessage("تحميل ${i + 1} / ${urls.size}") }
                }
                activity.runOnUiThread {
                    dlg.dismiss()
                    Toast.makeText(activity, if (success) "تم حفظ التلاوات!" else "خطأ أثناء التحميل", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
    }
}

// ============ PageIdleWorker ============
object PageIdleWorker {
    fun handle(
        activity: AppCompatActivity,
        page: Int,
        audioManager: com.hag.al_quran.audio.AudioPlaybackManager,
        getQariId: () -> String,
        updateBanner: (surah: Int, ayah: Int, text: String, playing: Boolean) -> Unit,
        hideBanner: () -> Unit
    ) {
        Handler(Looper.getMainLooper()).postDelayed({
            Executors.newSingleThreadExecutor().execute {
                val first = com.hag.al_quran.utils.loadAyahBoundsForPage(activity, page).firstOrNull()
                val text = first?.let { com.hag.al_quran.utils.getAyahTextFromJson(activity, it.sura_id, it.aya_id) }
                audioManager.setQari(getQariId())
                activity.runOnUiThread {
                    if (first != null && text != null)
                        updateBanner(first.sura_id, first.aya_id, text, audioManager.isPlaying())
                    else hideBanner()
                }
            }
        }, 120)
    }
}

// ============ Helpers للمعايرة/الحدود/الصور/الليل ============
data class PageCal(var offX: Float = 0f, var offY: Float = 0f, var scaleXFix: Float = 1f, var scaleYFix: Float = 1f)
data class Seg(val x: Int, val y: Int, val w: Int, val h: Int)
data class AyahBoundsRepo(val sura_id: Int, val aya_id: Int, val segs: List<Seg>)

object CalibrationStore {
    private const val PREF = "ayah_cal"
    fun loadGlobal(ctx: Context) = PageCal(
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("g_offX", 0f),
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("g_offY", 0f),
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("g_sx", 1f),
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getFloat("g_sy", 1f),
    )
    fun saveGlobal(ctx: Context, cal: PageCal) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat("g_offX", cal.offX).putFloat("g_offY", cal.offY)
            .putFloat("g_sx", cal.scaleXFix).putFloat("g_sy", cal.scaleYFix).apply()
    }
    fun loadForPage(ctx: Context, page: Int): PageCal {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val has = p.contains("p${page}_sx") || p.contains("p${page}_sy") || p.contains("p${page}_offX") || p.contains("p${page}_offY")
        return if (has) PageCal(p.getFloat("p${page}_offX", 0f), p.getFloat("p${page}_offY", 0f), p.getFloat("p${page}_sx", 1f), p.getFloat("p${page}_sy", 1f))
        else loadGlobal(ctx)
    }
    fun saveForPage(ctx: Context, page: Int, cal: PageCal) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putFloat("p${page}_offX", cal.offX).putFloat("p${page}_offY", cal.offY)
            .putFloat("p${page}_sx", cal.scaleXFix).putFloat("p${page}_sy", cal.scaleYFix).apply()
    }
    fun clearPage(ctx: Context, page: Int) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .remove("p${page}_offX").remove("p${page}_offY").remove("p${page}_sx").remove("p${page}_sy").apply()
    }
    fun applyToAllPages(ctx: Context, cal: PageCal) {
        val ed = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
        for (pg in 1..604) {
            ed.putFloat("p${pg}_offX", cal.offX); ed.putFloat("p${pg}_offY", cal.offY)
            ed.putFloat("p${pg}_sx", cal.scaleXFix); ed.putFloat("p${pg}_sy", cal.scaleYFix)
        }
        ed.apply()
    }
}

object CalibrationSheet {
    fun show(activity: AppCompatActivity, page: Int, cal: PageCal, onChange: () -> Unit) {
        val ctx = activity
        val bs = BottomSheetDialog(ctx)
        val box = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(ctx.dp(24), ctx.dp(18), ctx.dp(24), ctx.dp(12)) }

        fun sliderRow(title: String, min: Float, max: Float, step: Float, getter: () -> Float, setter: (Float)->Unit): View {
            val wrap = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, ctx.dp(8), 0, ctx.dp(4)) }
            val tv = TextView(ctx).apply { textSize = 14f; text = "$title: ${"%.4f".format(getter())}" }
            val sb = SeekBar(ctx)
            val count = ((max - min) / step).toInt().coerceAtLeast(1)
            fun valueFromProgress(p: Int) = min + p * step
            fun progressFromValue(x: Float) = ((x - min) / step).toInt().coerceIn(0, count)
            sb.max = count; sb.progress = progressFromValue(getter())
            sb.setOnSeekBarChangeListener(object: SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val v = valueFromProgress(progress); setter(v); tv.text = "$title: ${"%.4f".format(v)}"; onChange()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
            wrap.addView(tv); wrap.addView(sb); return wrap
        }

        box.addView(TextView(ctx).apply { text = "معايرة الصفحة $page"; textSize = 16f; paint.isFakeBoldText = true; setPadding(0,0,0,ctx.dp(6)) })
        box.addView(sliderRow("offX", -40f, 40f, 0.5f, { cal.offX }, { cal.offX = it }))
        box.addView(sliderRow("offY", -40f, 40f, 0.5f, { cal.offY }, { cal.offY = it }))
        box.addView(sliderRow("scaleXFix", 0.990f, 1.010f, 0.001f, { cal.scaleXFix }, { cal.scaleXFix = it }))
        box.addView(sliderRow("scaleYFix", 0.990f, 1.010f, 0.001f, { cal.scaleYFix }, { cal.scaleYFix = it }))

        val btnSaveGlobal = Button(ctx).apply { text = "حفظ كافتراضي لكل الصفحات"; setOnClickListener { CalibrationStore.saveGlobal(ctx, cal); Toast.makeText(ctx, "تم حفظ الافتراضي العام.", Toast.LENGTH_SHORT).show(); onChange() } }
        val btnApplyAll  = Button(ctx).apply { text = "تطبيق على جميع الصفحات (1..604)"; setOnClickListener { CalibrationStore.applyToAllPages(ctx, cal); Toast.makeText(ctx, "تم التطبيق على جميع الصفحات.", Toast.LENGTH_LONG).show(); onChange() } }
        val btnClearPage = Button(ctx).apply { text = "إزالة ضبط هذه الصفحة (الرجوع للافتراضي)"; setOnClickListener { CalibrationStore.clearPage(ctx, page); Toast.makeText(ctx, "تمت إزالة ضبط الصفحة.", Toast.LENGTH_SHORT).show(); onChange() } }

        box.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, ctx.dp(6)) })
        box.addView(btnSaveGlobal); box.addView(btnApplyAll); box.addView(btnClearPage)
        bs.setContentView(box); bs.show()
    }
}

object BoundsRepo {
    private var cacheBounds: Map<Int, List<AyahBoundsRepo>>? = null
    private var cacheQuranTextObj: JSONObject? = null
    private var cacheQuranTextArr: JSONArray? = null

    fun loadBoundsMap(context: Context): Map<Int, List<AyahBoundsRepo>> {
        cacheBounds?.let { return it }
        val out = try {
            val jsonStr = context.assets.open("pages/ayah_bounds_all.json").bufferedReader().use { it.readText() }
            val root = JSONObject(jsonStr)
            val map = HashMap<Int, MutableList<AyahBoundsRepo>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val pageKey = keys.next()
                val page = pageKey.toIntOrNull() ?: continue
                val arr = root.getJSONArray(pageKey)
                val list = mutableListOf<AyahBoundsRepo>()
                val counters = HashMap<Int, Int>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val suraId = obj.getInt("sura_id")
                    val ayaId = if (obj.has("aya_id")) obj.getInt("aya_id") else {
                        val n = (counters[suraId] ?: 0) + 1; counters[suraId] = n; n
                    }
                    val segsArr = obj.getJSONArray("segs")
                    val segs = ArrayList<Seg>()
                    for (j in 0 until segsArr.length()) {
                        val s = segsArr.getJSONObject(j)
                        segs.add(Seg(s.getDouble("x").toInt(), s.getDouble("y").toInt(), s.getDouble("w").toInt(), s.getDouble("h").toInt()))
                    }
                    list.add(AyahBoundsRepo(suraId, ayaId, segs))
                }
                map[page] = list
            }
            map
        } catch (_: Exception) { emptyMap() }
        cacheBounds = out; return out
    }

    fun getAyahText(context: Context, surah: Int, ayah: Int): String {
        if (cacheQuranTextObj == null) {
            cacheQuranTextObj = try {
                val jsonStr = context.assets.open("pages/quran.json").bufferedReader().use { it.readText() }
                JSONObject(jsonStr)
            } catch (_: Exception) { null }
        }
        cacheQuranTextObj?.let { obj ->
            val key = surah.toString()
            if (obj.has(key)) {
                val verses = obj.getJSONArray(key)
                for (i in 0 until verses.length()) {
                    val v = verses.getJSONObject(i)
                    if (v.optInt("id") == ayah) return v.getString("text")
                }
            }
        }
        if (cacheQuranTextArr == null) {
            cacheQuranTextArr = try {
                val jsonStr = try { context.assets.open("pages/quran.json").bufferedReader().use { it.readText() } }
                catch (_: Exception) { context.assets.open("quran.json").bufferedReader().use { it.readText() } }
                JSONArray(jsonStr)
            } catch (_: Exception) { null }
        }
        cacheQuranTextArr?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                if (s.optInt("id") == surah) {
                    val verses = s.getJSONArray("verses")
                    for (j in 0 until verses.length()) {
                        val v = verses.getJSONObject(j)
                        if (v.optInt("id") == ayah) return v.getString("text")
                    }
                }
            }
        }
        return "نص الآية غير متاح"
    }
}

object NightMode {
    fun applyInvert(imageView: ImageView, context: Context) {
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (isNight) {
            val cm = ColorMatrix(
                floatArrayOf(
                    -1f,0f,0f,0f,255f,
                    0f,-1f,0f,0f,255f,
                    0f,0f,-1f,0f,255f,
                    0f,0f,0f,1f,0f
                )
            )
            val bright = ColorMatrix().apply { setScale(0.92f, 0.92f, 0.92f, 1f) }
            cm.postConcat(bright)
            imageView.colorFilter = ColorMatrixColorFilter(cm)
            imageView.setBackgroundColor(Color.BLACK)
        } else {
            imageView.colorFilter = null; imageView.setBackgroundColor(Color.TRANSPARENT)
        }
    }
}

object PageImageLoader {
    fun load(context: Context, pageNumber: Int, into: ImageView) {
        val pageFile = "page_$pageNumber.webp"
        if (pageNumber <= 3) {
            try {
                context.assets.open("pages/$pageFile").use { input -> into.setImageBitmap(BitmapFactory.decodeStream(input)) }
            } catch (_: Exception) { Toast.makeText(context, "تعذر تحميل: $pageFile", Toast.LENGTH_SHORT).show() }
        } else {
            val url = "https://raw.githubusercontent.com/assadig3/quran-pages/main/pages/$pageFile"
            Glide.with(context).load(url).placeholder(R.drawable.ic_placeholder).error(R.drawable.ic_error).into(into)
        }
    }
    fun baseSizeFor(boundsList: List<AyahBoundsRepo>): Pair<Float, Float> {
        if (boundsList.isEmpty()) return 290f to 413f
        val allSegs = boundsList.flatMap { it.segs }
        val baseW = max(allSegs.maxOf { it.x + it.w }, 290).toFloat()
        val baseH = max(allSegs.maxOf { it.y + it.h }, 413).toFloat()
        return baseW to baseH
    }
}
