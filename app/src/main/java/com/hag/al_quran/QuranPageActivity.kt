package com.hag.al_quran

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.*
import android.text.TextUtils
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hag.al_quran.audio.MadaniPageProvider
import com.hag.al_quran.helpers.QuranAudioHelper
import com.hag.al_quran.helpers.QuranSupportHelper
import com.hag.al_quran.search.AyahLocator
import com.hag.al_quran.tafsir.TafsirManager
import com.hag.al_quran.ui.PageImageLoader
import com.hag.al_quran.ui.ThemeManager
import com.hag.al_quran.utils.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToLong
import android.widget.ArrayAdapter
import com.hag.al_quran.utils.VersionedCache
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.widget.ImageViewCompat
import com.hag.al_quran.worker.QuranRecitationDownloadWorker

class QuranPageActivity : BaseActivity(), CenterLoaderHost {

    companion object {
        const val EXTRA_TARGET_SURAH = "EXTRA_TARGET_SURAH"
        const val EXTRA_TARGET_AYAH  = "EXTRA_TARGET_AYAH"
        const val EXTRA_TARGET_PAGE  = "EXTRA_TARGET_PAGE"
        const val EXTRA_QUERY        = "EXTRA_QUERY"
        private const val KEY_PAGES_CACHED = "pages_cached"
        private const val TOTAL_PAGES = 604
        private val RC_PICK_FOLDER = 1235
        const val PREF_TREE_URI = "pref_tree_uri"

        // يشير إلى صفحة مطلوب بدء تشغيلها تلقائيًا بعد تغيير الـ ViewPager
        private var pendingAutoStartPage: Int? = null

        const val CHANNEL_ID = "quran_playback_channel"
        private const val NOTIF_ID   = 99111

        private const val ACT_PLAY   = "com.hag.al_quran.NOTIF_PLAY"
        private const val ACT_PAUSE  = "com.hag.al_quran.NOTIF_PAUSE"
        private const val ACT_STOP   = "com.hag.al_quran.NOTIF_STOP"
        private var ayahBarClosedByUser = false

        private const val REQ_POST_NOTIFS = 8807

        const val KEY_QARI_ID = "pref_qari_id"
        const val PREF_REPEAT_AYAH = "pref_repeat_ayah_count"
        const val PREF_REPEAT_PAGE = "pref_repeat_page_count"

        const val AUTO_HIDE_DELAY_MS = 4000
    }

    private var statusBarScrim: View? = null
    // تفضيلات محلية
    private val PREFS_SUPPRESS = "quran_prefs"
    private val KEY_SUPPRESS_AYAH_BANNER = "suppress_ayah_banner" // قديم: إخفاء البانر نهائيًا
    private val KEY_BANNER_TOGGLE_STATE = "banner_toggle_state"    // جديد: حالة الزر (مسموح / مقفول)

    // ===================== Repeat Mode =====================
    private enum class RepeatMode { OFF, PAGE, AYAH }
    private val PREF_REPEAT_MODE = "pref_repeat_mode"
    private var repeatMode: RepeatMode = RepeatMode.OFF
    private fun loadRepeatMode(): RepeatMode =
        when (prefs.getInt(PREF_REPEAT_MODE, 0)) {
            1 -> RepeatMode.PAGE
            2 -> RepeatMode.AYAH
            else -> RepeatMode.OFF
        }
    private fun saveRepeatMode(mode: RepeatMode) {
        val v = when (mode) { RepeatMode.OFF->0; RepeatMode.PAGE->1; RepeatMode.AYAH->2 }
        prefs.edit().putInt(PREF_REPEAT_MODE, v).apply()
    }
    private fun updateRepeatIcon() {
        when (repeatMode) {
            RepeatMode.OFF -> { btnRepeat.setImageResource(R.drawable.ic_repeat); btnRepeat.alpha = .55f; btnRepeat.contentDescription = getString(R.string.repeat_off) }
            RepeatMode.PAGE -> { btnRepeat.setImageResource(R.drawable.ic_repeat); btnRepeat.alpha = 1f; btnRepeat.contentDescription = getString(R.string.repeat_page) }
            RepeatMode.AYAH -> { btnRepeat.setImageResource(R.drawable.ic_repeat_one); btnRepeat.alpha = 1f; btnRepeat.contentDescription = getString(R.string.repeat_ayah) }
        }
        audioHelper.repeatMode = when (repeatMode) { RepeatMode.OFF->"off"; RepeatMode.PAGE->"page"; RepeatMode.AYAH->"ayah" }
    }
    // =======================================================

    // UI
    lateinit var toolbar: MaterialToolbar
    lateinit var viewPager: ViewPager2

    // أسفل الشاشة
    lateinit var bottomOverlays: LinearLayout
    lateinit var audioControlsCard: MaterialCardView

    // شريط التلاوة
    lateinit var audioControls: LinearLayout

    lateinit var audioDownload: ImageButton
    lateinit var btnRepeat: ImageButton
    lateinit var btnQari: TextView
    lateinit var btnPlayPause: ImageButton
// ...

    // شريط خيارات الآية
    lateinit var ayahOptionsBar: MaterialCardView
    lateinit var btnDownloadTafsir: ImageButton

    lateinit var btnShareAyah: ImageButton
    lateinit var btnCopyAyah: ImageButton
    lateinit var btnPlayAyah: ImageButton
    lateinit var btnCloseAyahBar: ImageButton
    var ayahPreview: TextView? = null

    // بانر “الآن يتلى”
    var ayahBanner: View? = null
    var ayahTextView: TextView? = null
    var ayahBannerSurah: TextView? = null
    var ayahBannerNumber: TextView? = null
    private var topPageInfoText: TextView? = null
    // هل مسموح للبانر يظهر ولا لا؟
    private var ayahBannerBlocked: Boolean = false

    private val KEY_BANNER_BLOCKED = "banner_blocked"

    // الشريط الوسطي للتحميل
    private lateinit var centerLoader: View
    private lateinit var centerLoaderText: TextView
    private lateinit var centerProgress: ProgressBar
    private lateinit var centerCount: TextView
    private lateinit var centerPercent: TextView
    private lateinit var centerEta: TextView
    private lateinit var btnPause: Button
    private lateinit var btnResume: Button
    private lateinit var btnClose: Button

    lateinit var prefs: SharedPreferences
    lateinit var provider: MadaniPageProvider
    lateinit var audioHelper: QuranAudioHelper
    lateinit var supportHelper: QuranSupportHelper
    lateinit var tafsirManager: TafsirManager

    private var ayahBannerSuppressedByUser: Boolean = false
    private var ayahBannerVisibleByToggle: Boolean = true   // ✅ جديد


    // حالة
    var currentQariId: String = "fares"
    var currentPage = 1
    var currentSurah = 1
    var currentAyah = 1

    // تحكم بالأشرطة
    private var barsVisible = true
    var hideHandler: Handler? = null
    private val hideRunnable = Runnable { setAllBarsVisible(false) }

    // عرض الصفحات
    lateinit var adapter: AssetPageAdapter
    var lastPos: Int = -1

    // صوت بالخلفية
    private val audioBgThread = HandlerThread("quran-audio-bg").apply { start() }
    internal val audioBgHandler by lazy { Handler(audioBgThread.looper) }
    private val uiHandler by lazy { Handler(Looper.getMainLooper()) }
    private var prepareQueueRunnable: Runnable? = null

    // Gesture
    private lateinit var gestureDetector: GestureDetectorCompat

    // ==== تكرار النطاق (اختياري) ====
    private data class RangeRepeatState(
        val surah: Int, val startAyah: Int, val endAyah: Int,
        var loopsLeft: Int, val qariId: String, var currentAyah: Int
    )
    @Volatile private var rangeState: RangeRepeatState? = null
    private var savedAutoContinueToNextPage: Boolean? = null

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false
    @Volatile private var userClosedOverlay = false
    private val pauseLock = Object()
    private var exec: ExecutorService? = null

    @Volatile private var bulkPrefetchRunning = false
    private var centerVisibleLocks = 0
    private var programmaticPaging = false            // لمنع تفعيل منطق السحب عند التنقل البرمجي
    private var wasPlayingBeforeScroll = false        // هل كانت التلاوة صفحةً شغّالة قبل بداية السحب؟
    private var dragStartPos = -1                     // موضع الصفحة عند بداية السحب

    private var toolbarHeight = 0
    private var bottomOverlaysHeight = 0
    private var topInsetLocked = 0
    private var bottomInsetLocked = 0
    private var insetsLocked = false
    private var wasPlayingBeforeSwipe = false        // هل كانت التلاوة تعمل قبل السحب؟
    private var lastPageStartedForPlayback = -1      // لمنع إعادة البدء لنفس الصفحة
    private var lastSelectedPos = 0
    private var pendingAutoStart: Runnable? = null

    private fun cancelPendingAutoStart() {
        pendingAutoStart?.let { uiHandler.removeCallbacks(it) }
        pendingAutoStart = null
    }

    // ===== التحكم بالاستمرار =====
    private var autoContinuePages: Boolean = true
    private var userPausedByAction: Boolean = false
    private var startingPagePlayback = false   // حماية إضافية من البدء المزدوج
    private var autoPlayOnSwipe: Boolean = true
    private var pageInfoInline: TextView? = null

    // ========================== IMMERSIVE ==========================
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    private fun enterImmersive() { WindowInsetsControllerCompat(window, window.decorView).apply {
        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false
        hide(WindowInsetsCompat.Type.systemBars())
    } }
    private fun exitImmersive() { WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars()) }
    // ===============================================================

    // ===== مستقبل أوامر الإشعار =====
    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACT_PLAY -> {
                    userPausedByAction = false
                    val resumed = audioHelper.resumePagePlayback()
                    if (!resumed) audioHelper.startPagePlayback(currentPage, currentQariId)
                    updateNotification(isPlaying = true)
                    setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
                }
                ACT_PAUSE -> {
                    userPausedByAction = true
                    audioHelper.pausePagePlayback()
                    lastPageStartedForPlayback = -1
                    updateNotification(isPlaying = false)
                    setAllBarsVisible(true, 3000)
                }
                ACT_STOP -> {
                    userPausedByAction = true
                    audioHelper.pausePagePlayback()
                    lastPageStartedForPlayback = -1
                    NotificationManagerCompat.from(this@QuranPageActivity).cancel(NOTIF_ID)
                    setAllBarsVisible(false)
                }
            }
        }
    }

    private val ayahBarGuard = ViewTreeObserver.OnPreDrawListener {
        if (ayahBarClosedByUser && ayahOptionsBar.visibility == View.VISIBLE) {
            ayahOptionsBar.visibility = View.GONE
            return@OnPreDrawListener false
        }
        true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentAction(intent.action)
    }

    private fun pendingSelfBroadcast(action: String, reqCode: Int): PendingIntent {
        val i = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun handleIntentAction(action: String?) {
        when (action) {
            ACT_PLAY -> {
                userPausedByAction = false
                val resumed = audioHelper.resumePagePlayback()
                if (!resumed) audioHelper.startPagePlayback(currentPage, currentQariId)
                updateNotification(isPlaying = true)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            }
            ACT_PAUSE -> {
                userPausedByAction = true
                audioHelper.pausePagePlayback()
                updateNotification(isPlaying = false)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            }
            ACT_STOP -> {
                userPausedByAction = true
                audioHelper.pausePagePlayback()
                NotificationManagerCompat.from(this).cancel(NOTIF_ID)
                setAllBarsVisible(false)
            }
        }
    }
    // =====================================================================
    @SuppressLint("ClickableViewAccessibility", "WrongConstant")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ========== نمط الليل من البداية ==========
        val nightAtStart = ThemeManager.isNight(this)
        AppCompatDelegate.setDefaultNightMode(
            if (nightAtStart) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        )

        setContentView(R.layout.activity_quran_page)

        // ========== إعداد الشفافية والحواف (Edge-to-Edge) ==========
        val barHex        = "#F4ECDC"    // بيج فاتح
        val barHexTrans90 = "#E6F4ECDC"  // بيج شفاف تقريباً
        val barColor      = Color.parseColor(barHex)
        val barColor90    = Color.parseColor(barHexTrans90)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        WindowInsetsControllerCompat(window, window.decorView).apply {
            val isNightNow = ThemeManager.isNight(this@QuranPageActivity)
            isAppearanceLightStatusBars = !isNightNow
            isAppearanceLightNavigationBars = !isNightNow
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_BARS_BY_SWIPE
        }

        // ========== قنوات الإشعار والأذونات ==========
        ensureNotificationChannel()
        requestNotifPermissionIfNeeded()

        // ========== التفضيلات ==========
        prefs = getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)

        // مهم: ما نمسح prefs. لو عندك مفاتيح تخص البانر، استخرجها بتفضيل منفصل:
        val bannerPrefs = getSharedPreferences(PREFS_SUPPRESS, Context.MODE_PRIVATE)
        ayahBannerSuppressedByUser =
            bannerPrefs.getBoolean(KEY_SUPPRESS_AYAH_BANNER, false)
        ayahBannerVisibleByToggle =
            bannerPrefs.getBoolean(KEY_BANNER_TOGGLE_STATE, true)

        // ========== مزود البيانات / الهيلبرز ==========
        provider      = MadaniPageProvider(this)
        supportHelper = QuranSupportHelper(this, provider)
        audioHelper   = QuranAudioHelper(this, provider, supportHelper, audioBgHandler)
        tafsirManager = TafsirManager(this)

        VersionedCache.ensureFreshImages(this)

        // ========== قراءة Intent (الصفحة/السورة/الآية المطلوبة) ==========
        val pageFromNew  = intent.getIntExtra(EXTRA_TARGET_PAGE, 0)
        val pageFromOld  = intent.getIntExtra("page",
            intent.getIntExtra("page_number", 0))

        val surahFromNew = intent.getIntExtra(EXTRA_TARGET_SURAH, 0)
        val ayahFromNew  = intent.getIntExtra(EXTRA_TARGET_AYAH, 0)
        val surahFromOld = intent.getIntExtra("surah_number", 0)
        val ayahFromOld  = intent.getIntExtra("ayah_number", 0)

        currentSurah = if (surahFromNew > 0) surahFromNew
        else if (surahFromOld > 0) surahFromOld
        else 1

        currentAyah = if (ayahFromNew > 0) ayahFromNew
        else if (ayahFromOld > 0) ayahFromOld
        else 1

        currentPage = when {
            pageFromNew > 0 -> pageFromNew
            pageFromOld > 0 -> pageFromOld
            else -> try {
                AyahLocator.getPageFor(this, currentSurah, currentAyah)
            } catch (_: Throwable) {
                1
            }
        }.coerceIn(1, TOTAL_PAGES)

        // ========== ربط عناصر الواجهة (findViewById) ==========
        toolbar           = findViewById(R.id.toolbar)
        viewPager         = findViewById(R.id.pageViewPager)
        bottomOverlays    = findViewById(R.id.bottomOverlays)
        audioControlsCard = findViewById(R.id.audioControlsCard)

        audioControls     = findViewById(R.id.audioControls)
        btnPlayPause      = findViewById(R.id.btnPlayPause)
        btnPlayAyah       = findViewById(R.id.btnPlayAyah)
        btnQari           = findViewById(R.id.btnQari)
        audioDownload     = findViewById(R.id.audio_download)
        btnRepeat         = findViewById(R.id.btnRepeat)

        ayahOptionsBar    = findViewById(R.id.ayahOptionsBar)
        ayahOptionsBar.viewTreeObserver.addOnPreDrawListener(ayahBarGuard)

        val btnTafsirMenu = findViewById<TextView>(R.id.btnTafsirMenu)
        btnDownloadTafsir = findViewById(R.id.btnDownloadTafsir)

        btnShareAyah      = findViewById(R.id.btnShareAyah)
        btnCopyAyah       = findViewById(R.id.btnCopyAyah)
        btnCloseAyahBar   = findViewById(R.id.btnCloseOptions)
        ayahPreview       = findViewById(R.id.ayahPreview)
        initMarquee(ayahPreview)
        ayahOptionsBar.visibility = View.GONE

        findViewById<ImageButton>(R.id.btnCloseAudioBar)
            ?.setOnClickListener { hideAudioBar() }

        // ========== إعداد الـToolbar ==========
        setSupportActionBar(toolbar)
        applyBarsPalette()

        val onPrimary = ContextCompat.getColor(this, R.color.colorOnPrimary)

        toolbar.setTitleTextColor(onPrimary)
        toolbar.navigationIcon?.setTint(onPrimary)
        toolbar.overflowIcon?.setTint(onPrimary)
        toolbar.menu?.let { m ->
            for (i in 0 until m.size()) {
                m.getItem(i)?.icon?.setTint(onPrimary)
            }
        }

        supportActionBar?.setDisplayShowTitleEnabled(true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        updateToolbarTitleForPage(currentPage)

        // ========== تلوين الأشرطة السفلية / البطاقات ==========
        bottomOverlays.setBackgroundColor(barColor90)
        audioControlsCard.setCardBackgroundColor(barColor90)
        ayahOptionsBar.setCardBackgroundColor(barColor90)

        btnQari.setTextColor(onPrimary)
        btnPlayPause.imageTintList =
            ColorStateList.valueOf(onPrimary)
        btnRepeat.imageTintList =
            ColorStateList.valueOf(onPrimary)
        audioDownload.imageTintList =
            ColorStateList.valueOf(onPrimary)

        ImageViewCompat.setImageTintList(btnPlayPause,
            ColorStateList.valueOf(onPrimary))
        ImageViewCompat.setImageTintList(btnRepeat,
            ColorStateList.valueOf(onPrimary))
        ImageViewCompat.setImageTintList(audioDownload,
            ColorStateList.valueOf(onPrimary))

        findViewById<ImageButton?>(R.id.btnCloseAudioBar)?.let {
            ImageViewCompat.setImageTintList(it,
                ColorStateList.valueOf(onPrimary))
        }

        // ========== تحضير الـ Ayah Banner أعلى الشاشة ==========
        val containerRoot: ViewGroup =
            findViewById<ViewGroup?>(R.id.quran_container)
                ?: (findViewById(android.R.id.content) as ViewGroup)

        // scrim أعلى لتلوين مساحة الـstatus bar
        ViewCompat.setOnApplyWindowInsetsListener(containerRoot) { _, insets ->
            val topInset =
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            if (statusBarScrim == null) {
                statusBarScrim = View(this).apply {
                    setBackgroundColor(barColor)
                }
                containerRoot.addView(
                    statusBarScrim,
                    0,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        topInset
                    )
                )
            } else {
                val lp = statusBarScrim!!.layoutParams
                lp.height = topInset
                statusBarScrim!!.layoutParams = lp
                statusBarScrim!!.requestLayout()
            }
            insets
        }

        val topStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padH = (12 * resources.displayMetrics.density).toInt()
            setPadding(padH, 0, padH, 0)
            clipToPadding = false
        }
        val topLp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP }
        containerRoot.addView(topStack, topLp)

        val banner = layoutInflater.inflate(
            R.layout.ayah_now_playing,
            topStack,
            false
        )
        ayahBanner       = banner
        ayahTextView     = banner.findViewById(R.id.ayahText)
        ayahBannerSurah  = banner.findViewById(R.id.surahName)
        ayahBannerNumber = banner.findViewById(R.id.ayahNumber)
        pageInfoInline   = banner.findViewById(R.id.pageInfoInline)
        pageInfoInline?.text = buildPageInfoText(currentPage)
        initMarquee(ayahTextView)

        banner.findViewById<ImageButton>(R.id.btnCloseBanner)
            .apply {
                isClickable = true
                isFocusable = true
                bringToFront()
                setOnClickListener { hideAyahBannerByUser() }
            }

        banner.isClickable = true
        banner.isFocusable = false
        banner.bringToFront()
        (topStack.parent as? ViewGroup)?.bringChildToFront(topStack)

        banner.visibility = View.GONE
        topStack.addView(banner)

        // اجعل topStack تحت الـToolbar + شريط الحالة
        ViewCompat.setOnApplyWindowInsetsListener(topStack) { v, insets ->
            val topInset =
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val tbH = toolbar.height.takeIf { it > 0 }
                ?: toolbar.measuredHeight
            (v.layoutParams as FrameLayout.LayoutParams).topMargin =
                topInset + tbH
            v.requestLayout()
            insets
        }

        // ألوان الخلفية النهائية للأشرطة
        toolbar.setBackgroundColor(barColor)
        bottomOverlays.setBackgroundColor(barColor90)
        audioControls.setBackgroundColor(barColor)
        audioControlsCard.setCardBackgroundColor(barColor90)
        ayahOptionsBar.setCardBackgroundColor(barColor90)

        // Insets padding
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val top =
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setOnApplyWindowInsetsListener(bottomOverlays) { v, insets ->
            val bottomBars =
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val base = (12 * resources.displayMetrics.density).toInt()
            v.updatePadding(bottom = bottomBars + base)
            WindowInsetsCompat.CONSUMED
        }

        ViewCompat.setOnApplyWindowInsetsListener(viewPager) { v, insets ->
            val navBottom =
                insets.getInsetsIgnoringVisibility(
                    WindowInsetsCompat.Type.navigationBars()
                ).bottom
            val statusTop =
                insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

            if (resources.configuration.orientation ==
                Configuration.ORIENTATION_LANDSCAPE
            ) {
                v.setPadding(0, 0, 0, navBottom)
            } else {
                v.setPadding(0, statusTop, 0, navBottom)
            }
            (v as ViewGroup).clipToPadding = false
            (v as ViewGroup).clipChildren  = false
            WindowInsetsCompat.CONSUMED
        }
// تعرّف قيمة افتراضية معقولة للقارئ
        val fallbackQariId = provider.getQaris()
            .firstOrNull()
            ?.id
            ?.trim()
            ?.lowercase()
            ?: "alafasy" // بدلها لو عندك ID مختلف للمنشد/القارئ الافتراضي

        currentQariId = prefs
            .getString(KEY_QARI_ID, null)
            ?.trim()
            ?.lowercase()
            ?: fallbackQariId

// حدِّث نص الزر باسم القارئ الحالي
        btnQari.text = provider.getQariById(currentQariId)?.name
            ?: "القارئ"


        // حدث نص زر القارئ في الواجهة
        btnQari.text = provider.getQariById(currentQariId)?.name
            ?: "القارئ"

        repeatMode = loadRepeatMode()
        audioHelper.repeatCount = prefs
            .getInt(PREF_REPEAT_AYAH, 1)
            .coerceIn(1, 99)
        audioHelper.pageRepeatCount = prefs
            .getInt(PREF_REPEAT_PAGE, 1)
            .coerceIn(1, 99)
        updateRepeatIcon()

        // ========== إعداد الـViewPager ==========
        viewPager.setBackgroundColor(
            ContextCompat.getColor(this, R.color.quran_page_bg)
        )
        viewPager.offscreenPageLimit = 1
        (viewPager.getChildAt(0) as? RecyclerView)?.apply {
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(4)
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        }

        // ========== ماذا يحدث بعد انتهاء الصفحة ==========
        audioHelper.onPagePlaybackComplete = {
            val nextPage = currentPage + 1
            if (audioHelper.autoContinueToNextPage && nextPage <= 604) {
                runOnUiThread {
                    viewPager.setCurrentItem(nextPage - 1, true)
                    uiHandler.postDelayed({
                        lastPageStartedForPlayback = nextPage
                        audioHelper.startPagePlayback(
                            nextPage,
                            currentQariId,
                            true
                        )
                        updateNotification(isPlaying = true)
                    }, 220)
                }
            }
        }

        // ========== أزرار ==========
        setupTafsirMenuButton(btnTafsirMenu)

        btnRepeat.setOnClickListener { view ->
            val popup = androidx.appcompat.widget.PopupMenu(this, view)
            menuInflater.inflate(R.menu.menu_repeat_modes, popup.menu)
            popup.setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    R.id.repeat_off -> {
                        audioHelper.cancelRangeRepeat()
                        repeatMode = RepeatMode.OFF
                        saveRepeatMode(repeatMode)
                        updateRepeatIcon()
                        Toast.makeText(
                            this,
                            getString(R.string.repeat_off),
                            Toast.LENGTH_SHORT
                        ).show()
                        setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
                        true
                    }
                    R.id.repeat_ayah -> {
                        audioHelper.cancelRangeRepeat()
                        repeatMode = RepeatMode.AYAH
                        saveRepeatMode(repeatMode)
                        updateRepeatIcon()
                        Toast.makeText(
                            this,
                            "تكرار آية × ${audioHelper.repeatCount}",
                            Toast.LENGTH_SHORT
                        ).show()
                        setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
                        true
                    }
                    R.id.repeat_page -> {
                        audioHelper.cancelRangeRepeat()
                        repeatMode = RepeatMode.PAGE
                        saveRepeatMode(repeatMode)
                        updateRepeatIcon()
                        Toast.makeText(
                            this,
                            "تكرار الصفحة × ${audioHelper.pageRepeatCount}",
                            Toast.LENGTH_SHORT
                        ).show()
                        setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
                        true
                    }
                    R.id.repeat_range -> {
                        showRepeatRangeDialog()
                        setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        btnPlayAyah.setOnClickListener {
            if (audioHelper.isAyahPlaying) {
                userPausedByAction = true
                audioHelper.stopSingleAyah()
                updateNotification(isPlaying = false)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            } else {
                userPausedByAction = false
                audioHelper.playSingleAyah(currentSurah, currentAyah, currentQariId)
                updateNotification(
                    isPlaying = true,
                    surah = currentSurah,
                    ayah = currentAyah,
                    customText = ayahPreview?.text?.toString()
                )
                showAudioBar()
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            }
            if (!ayahBarClosedByUser) showAyahOptions(true)
        }

        btnCopyAyah.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE)
                    as ClipboardManager
            val text = ayahPreview?.text?.toString().orEmpty()
            cm.setPrimaryClip(
                ClipData.newPlainText("Ayah", text)
            )
            Toast.makeText(this, "تم نسخ الآية!", Toast.LENGTH_SHORT).show()
            setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
        }

        btnShareAyah.setOnClickListener {
            supportHelper.shareCurrentAyah(currentSurah, currentAyah)
        }

        btnCloseAyahBar.setOnClickListener {
            ayahBarClosedByUser = true
            showAyahOptions(false)
        }

        // زر اختيار القارئ
        btnQari.setOnClickListener {
            // نغلق أي حالة pressed قديمة
            it.isPressed = false

            supportHelper.showQariPicker { qari ->
                val oldWasPagePlaying = audioHelper.isPlaying
                val oldWasAyahPlaying = audioHelper.isAyahPlaying

                val page = currentPage
                val sura = currentSurah
                val ayah = currentAyah

                // حدّث القارئ الحالي
                currentQariId = qari.id.trim().lowercase()
                btnQari.text  = qari.name
                prefs.edit().putString(KEY_QARI_ID, currentQariId).apply()

                // حضّر الصوت من جديد
                audioHelper.stopAllPlaybackAndClearQueue()
                audioHelper.prepareAudioQueueForPage(page, currentQariId)

                when {
                    oldWasPagePlaying -> {
                        userPausedByAction = false
                        showAudioBar()
                        audioHelper.startPagePlayback(page, currentQariId, true)
                        updateNotification(isPlaying = true)
                    }
                    oldWasAyahPlaying -> {
                        userPausedByAction = false
                        showAudioBar()
                        audioHelper.playSingleAyah(sura, ayah, currentQariId)
                        updateNotification(
                            isPlaying = true,
                            surah = sura,
                            ayah  = ayah,
                            customText = ayahPreview?.text?.toString()
                        )
                    }
                    else -> {
                        debouncePrepareQueue(page, immediate = true)
                    }
                }

                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            }
        }

        // زر تحميل التلاوات
        audioDownload.setOnClickListener {
            showDownloadScopeDialog(
                currentPage   = currentPage,
                currentSurah  = currentSurah,
                currentQariId = currentQariId
            )
        }

        // زر تشغيل / إيقاف الصفحة
        btnPlayPause.setOnClickListener {
            setAllBarsVisible(true, 3000)
            if (audioHelper.isPlaying) {
                autoPlayOnSwipe = false
                audioHelper.pausePagePlayback()
                lastPageStartedForPlayback = -1
                updateNotification(isPlaying = false)
            } else {
                autoPlayOnSwipe = true
                val resumed = audioHelper.resumePagePlayback()
                if (!resumed) {
                    lastPageStartedForPlayback = currentPage
                    audioHelper.startPagePlayback(
                        currentPage,
                        currentQariId,
                        true
                    )
                }
                updateNotification(isPlaying = true)
            }
        }

        // حضّر الأشرطة
        prepareBarsOverlay()

        // ========== الـ Adapter لعرض صفحات المصحف ==========
        val pageNames = (1..TOTAL_PAGES).map { "page_$it.webp" }
        adapter = AssetPageAdapter(
            context = this,
            pages = pageNames,
            realPageNumber = 0,
            onAyahClick = { s, a, t ->
                currentSurah = s
                currentAyah  = a
                val text = try {
                    supportHelper.getAyahTextFromJson(s, a)
                } catch (_: Throwable) {
                    t ?: ""
                }
                ayahPreview?.text = text
                refreshAyahBannerMarquee()
                ayahPreview?.isSelected = true
                ayahBarClosedByUser = false
                showAyahOptions(true)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            },
            onImageTap = {
                safeToggleBars()
            },
            onNeedPagesDownload = {
                // TODO: تحميل صور الصفحات البعيدة من الإنترنت (إن احتجت)
            },
            loaderHost = this
        )

        viewPager.adapter = adapter
        viewPager.setCurrentItem(
            (currentPage - 1).coerceIn(0, TOTAL_PAGES - 1),
            false
        )

        viewPager.post {
            adapter.highlightAyahOnPage(
                currentPage,
                currentSurah,
                currentAyah
            )
        }

        PageImageLoader.prefetchAround(
            this,
            currentPage,
            radius = 1
        )

        // أول تحديث للمعلومات و البانر
        renderPageInfo()
        showFirstAyahBannerForCurrentPage()

        // ========== التنقّل بين الصفحات داخل الـViewPager ==========
        viewPager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {

                override fun onPageSelected(position: Int) {
                    super.onPageSelected(position)

                    // امسح تظليل الصفحة السابقة
                    if (lastPos != -1 && lastPos != position) {
                        adapter.clearHighlightOnPage(lastPos + 1)
                    }
                    lastPos = position

                    // عدّل الحالة للموقع الجديد
                    currentPage = position + 1
                    renderPageInfo()
                    adapter.clearHighlightOnPage(currentPage)
                    showAyahOptions(false)
                    saveLastVisitedPage(this@QuranPageActivity, currentPage)
                    invalidateOptionsMenu()

                    // تجهيز الصفحة الحالية و الجيران
                    PageImageLoader.prefetchAround(
                        this@QuranPageActivity,
                        currentPage,
                        radius = 1
                    )
                    audioHelper.prepareAudioQueueForPage(
                        currentPage,
                        currentQariId,
                        fromStart = true
                    )

                    setAllBarsVisible(true, 2000)
                }

                override fun onPageScrollStateChanged(state: Int) {
                    super.onPageScrollStateChanged(state)

                    when (state) {
                        ViewPager2.SCROLL_STATE_DRAGGING -> {
                            // بدأ سحب يدوي
                            wasPlayingBeforeScroll =
                                audioHelper.isPlaying &&
                                        !audioHelper.isAyahPlaying
                            dragStartPos = viewPager.currentItem
                        }

                        ViewPager2.SCROLL_STATE_IDLE -> {
                            // انتهى الانزلاق
                            val newPos  = viewPager.currentItem
                            val newPage = newPos + 1

                            if (currentPage != newPage) {
                                currentPage = newPage
                            }

                            supportActionBar?.title =
                                supportHelper
                                    .getSurahNameForPage(currentPage)
                                    .ifEmpty { getString(R.string.app_name) }

                            renderPageInfo()

                            // لو كان الانتقال برمجي (مثلاً بعد نهاية الصفحة)
                            if (programmaticPaging) {
                                programmaticPaging = false
                                return
                            }

                            // لو الصفحة لم تكن تُتلى أساسًا قبل السحب:
                            if (!wasPlayingBeforeScroll) {
                                audioHelper.prepareAudioQueueForPage(
                                    currentPage,
                                    currentQariId,
                                    fromStart = true
                                )
                                setAllBarsVisible(true, 2000)
                                return
                            }

                            // تشغيل تلقائي فقط إذا انتقلنا للأمام
                            if (newPos > dragStartPos) {
                                // أوقف وضع "آية مفردة" فقط
                                audioHelper.stopSingleAyah()

                                // شغل الصفحة من أولها
                                audioHelper.startPagePlayback(
                                    currentPage,
                                    currentQariId,
                                    fromStart = true
                                )
                                updateNotification(isPlaying = true)
                            } else {
                                // سحب للخلف: حضر فقط، ما تشغل
                                audioHelper.prepareAudioQueueForPage(
                                    currentPage,
                                    currentQariId,
                                    fromStart = true
                                )
                            }

                            setAllBarsVisible(true, 2000)
                        }
                    }
                }
            }
        )

        // ========== إظهار الأشرطة أول مرة + تجهيز الصفحات للصوت ==========
        hideHandler = Handler(Looper.getMainLooper())
        setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)

        debouncePrepareQueue(currentPage, immediate = true)

        if (!arePagesCached()) {
            startBulkPagesPrefetch(parallelism = 6)
        }

        audioHelper.setAyahBannerGate(object : QuranAudioHelper.AyahBannerGate {
            override fun requestShowAyahBanner(surah: Int, ayah: Int, text: String) {
                try {
                    ayahBannerSurah?.text  = "سورة $surah"
                    ayahBannerNumber?.text = "آية $ayah"
                    ayahTextView?.text     = text
                    refreshAyahBannerMarquee()

                    if (!ayahBannerSuppressedByUser && ayahBannerVisibleByToggle) {
                        ayahBanner?.visibility = View.VISIBLE
                        ayahBanner?.bringToFront()
                    }
                } catch (_: Exception) { }
            }

            override fun requestHideAyahBanner() {
                try {
                    ayahBanner?.visibility = View.GONE
                } catch (_: Exception) { }
            }
        })


    }

    private fun showAyahBannerInternal(show: Boolean) {
        val v = ayahBanner ?: return

        // حماية قصوى:
        // لو المستخدم قفله (ayahBannerBlocked = true) ما نعرضه أبداً حتى لو حد حاول
        if (show && (ayahBannerBlocked || ayahBannerSuppressedByUser || !ayahBannerVisibleByToggle)) {
            return
        }

        if (show) {
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
                v.bringToFront()
                v.animate()
                    .alpha(1f)
                    .setDuration(160)
                    .start()
            } else {
                v.bringToFront()
            }
        } else {
            if (v.visibility == View.VISIBLE) {
                v.animate()
                    .alpha(0f)
                    .setDuration(120)
                    .withEndAction {
                        v.visibility = View.GONE
                        v.alpha = 1f
                    }
                    .start()
            }
        }
    }

    fun hideAyahBannerByUser() {
        // المستخدم ضغط X = ما نبغى نشوف الشريط مرة ثانية تلقائيًا
        ayahBannerSuppressedByUser = true
        ayahBannerVisibleByToggle  = false
        ayahBannerBlocked          = true  // ✅ قفل صريح

        prefs.edit()
            .putBoolean(KEY_SUPPRESS_AYAH_BANNER, true)
            .putBoolean(KEY_BANNER_TOGGLE_STATE, false)
            .putBoolean(KEY_BANNER_BLOCKED, true) // حفظ القفل
            .apply()

        showAyahBannerInternal(false)

        Toast.makeText(
            this,
            "تم إخفاء شريط الآية. لن يظهر تلقائيًا.",
            Toast.LENGTH_SHORT
        ).show()
    }

    fun allowAyahBannerAgain() {
        ayahBannerSuppressedByUser = false
        ayahBannerVisibleByToggle  = true
        ayahBannerBlocked          = false  // ✅ فك القفل

        prefs.edit()
            .putBoolean(KEY_SUPPRESS_AYAH_BANNER, false)
            .putBoolean(KEY_BANNER_TOGGLE_STATE, true)
            .putBoolean(KEY_BANNER_BLOCKED, false)
            .apply()

        Toast.makeText(
            this,
            "سيظهر شريط الآية مع التلاوة التالية",
            Toast.LENGTH_SHORT
        ).show()

        showFirstAyahBannerForCurrentPage()
    }

    fun pickExternalFolderForRecitations() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            putExtra("android.content.extra.SHOW_ADVANCED", true)
            putExtra("android.content.extra.FANCY", true)
            putExtra("android.content.extra.SHOW_FILESIZE", true)
        }
        startActivityForResult(intent, RC_PICK_FOLDER)
    }

    private fun ensureCenterLoaderInflated() {
        if (::centerLoader.isInitialized) return

        val root = findViewById<ViewGroup?>(R.id.quran_container)
            ?: (findViewById(android.R.id.content) as ViewGroup)

        val v = layoutInflater.inflate(R.layout.view_center_loader, root, false)
        root.addView(v)

        centerLoader   = v
        // لاحظ الاختلاف هنا: centerText بدل centerLoaderText
        centerLoaderText = v.findViewById(R.id.centerText)
        centerProgress   = v.findViewById(R.id.centerProgress)
        centerCount      = v.findViewById(R.id.centerCount)
        centerPercent    = v.findViewById(R.id.centerPercent)
        centerEta        = v.findViewById(R.id.centerEta)
        btnPause         = v.findViewById(R.id.btnPause)
        btnResume        = v.findViewById(R.id.btnResume)
        btnClose         = v.findViewById(R.id.btnClose)

        // الحالة الأولية
        centerLoader.visibility = View.GONE
        btnPause.isEnabled  = true
        btnResume.isEnabled = false

        // تشغيل الماركيه (مهم جدًا)
        centerLoaderText.isSelected = true   // هذا الذي يجعل TextView يبدأ التمرير

        // أزرار التحكم
        btnPause.setOnClickListener {
            isPaused = true
            btnPause.isEnabled  = false
            btnResume.isEnabled = true
            synchronized(pauseLock) { pauseLock.notifyAll() }
        }
        btnResume.setOnClickListener {
            isPaused = false
            btnPause.isEnabled  = true
            btnResume.isEnabled = false
            synchronized(pauseLock) { pauseLock.notifyAll() }
        }
        btnClose.setOnClickListener {
            isCancelled = true
            exec?.shutdownNow()
            userClosedOverlay = true
            centerLoader.visibility = View.GONE
        }
    }
    @Deprecated("old activity result API but still fine here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            val treeUri = data.data
            if (treeUri != null) {
                // مهم جداً: خزن صلاحية الكتابة/القراءة بشكل دائم
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                // خزّن الـURI في SharedPreferences
                prefs.edit()
                    .putString(PREF_TREE_URI, treeUri.toString())
                    .apply()

                Toast.makeText(this, "تم اختيار المجلد الخارجي بنجاح ✅", Toast.LENGTH_SHORT).show()

                // اختياري: نقدر نبدأ التنزيل مباشرة بعد الاختيار
                startRecitationsDownload(storageTargetExternal = true)
            } else {
                Toast.makeText(this, "لم يتم اختيار مجلد صالح", Toast.LENGTH_SHORT).show()
            }
        }
    }
    /** إظهار شريط الآية يدويًا عند النقر إذا كان المستخدم قد أغلقه */
    private fun requestAyahOptions() {
        ayahBarClosedByUser = false
        showAyahOptions(true)
        setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
    }
    private fun applyBarsPalette() {
        val isNight = ThemeManager.isNight(this)

        // لوحة نهار/ليل
        val barBg   = if (isNight) Color.parseColor("#121212") else Color.parseColor("#F4ECDC")
        val barBg90 = if (isNight) Color.parseColor("#CC121212") else Color.parseColor("#E6F4ECDC")
        val onPrim  = if (isNight) Color.parseColor("#FFFFFF")  else ContextCompat.getColor(this, R.color.colorOnPrimary)

        // الخلفيات
        toolbar.setBackgroundColor(barBg)
        bottomOverlays.setBackgroundColor(barBg90)
        audioControls.setBackgroundColor(barBg)
        audioControlsCard.setCardBackgroundColor(barBg90)
        ayahOptionsBar.setCardBackgroundColor(barBg90)

        // الـ scrim العلوي (خلف شريط الحالة)
        statusBarScrim?.setBackgroundColor(barBg)

        // لون عنوان وأيقونات الـToolbar
        toolbar.setTitleTextColor(onPrim)
        toolbar.navigationIcon?.setTint(onPrim)
        toolbar.overflowIcon?.setTint(onPrim)
        toolbar.menu?.let { m ->
            for (i in 0 until m.size()) m.getItem(i)?.icon?.setTint(onPrim)
        }

        // أزرار الشريط السفلي
        btnQari.setTextColor(onPrim)
        btnPlayPause.imageTintList = android.content.res.ColorStateList.valueOf(onPrim)
        btnRepeat.imageTintList    = android.content.res.ColorStateList.valueOf(onPrim)
        audioDownload.imageTintList= android.content.res.ColorStateList.valueOf(onPrim)
        findViewById<ImageButton?>(R.id.btnCloseAudioBar)?.imageTintList =
            android.content.res.ColorStateList.valueOf(onPrim)

        // أي نصوص داخل الكروت السفلية (لو موجودة)
        ayahPreview?.setTextColor(onPrim)
    }
    // لانشر يفتح اختيار مجلد التخزين الخارجي (SD card أو Downloads..)
    private val pickFolderLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val treeUri = result.data?.data
            if (treeUri != null) {
                // خذ صلاحية دائمة للوصول لهذا المجلد
                val flags = (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                contentResolver.takePersistableUriPermission(treeUri, flags)

                // خزّنه في التفضيلات
                prefs.edit()
                    .putString(PREF_TREE_URI, treeUri.toString())
                    .apply()

                Toast.makeText(this, "تم اختيار مجلد الحفظ الخارجي", Toast.LENGTH_SHORT).show()

                // الآن نقدر نبدأ التنزيل فعلياً
                startRecitationsDownload(storageTargetExternal = true)
            }
        } else {
            Toast.makeText(this, "لم يتم اختيار مجلد.", Toast.LENGTH_SHORT).show()
        }
    }
    private fun ensureExternalFolderOrAskUser(): Boolean {
        val savedUri = prefs.getString(PREF_TREE_URI, null)
        return if (savedUri.isNullOrBlank()) {
            // ما عندنا مسار خارجي بعد → افتح اختيار مجلد
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
                )
            }
            pickFolderLauncher.launch(intent)
            false
        } else {
            true // جاهز
        }
    }
    private fun startRecitationsDownload(storageTargetExternal: Boolean) {

        // 1. النطاق اللي هننزله.
        // لاحقًا تقدر تخليه من Dialog: "PAGE" / "SURAH" / "JUZ" / "ALL"
        val scope = "PAGE"

        // 2. القارئ الحالي (من currentQariId أو نفرض "fares" كافتراضي)
        val qariIdNow: String = currentQariId.ifBlank { "fares" }

        // 3. رابط الأساس لصوت القارئ
        //   provider.getQariById(...) بيرجع Qari { id, name, url }
        //   يعني الـ mp3 موجودين تحت هذا الرابط (لازم ينتهي بـ "/")
        val rawBase: String = provider.getQariById(qariIdNow)?.url ?: ""
        val audioBase: String = if (rawBase.endsWith("/")) rawBase else rawBase + "/"

        // 4. هل نخزّن خارجي (SD / SAF) أو داخلي داخل التطبيق؟
        val useExternal: Boolean = storageTargetExternal

        // 5. لو خارجي: نجيب URI المجلد اللي حفظه المستخدم من قبل في SharedPreferences
        //    IMPORTANT: لازم نفس المفتاح اللي تستخدمه فعلاً عند حفظ مجلد SAF
        val PREF_TREE_URI = "pref_tree_uri"

        val treeUriStr: String = if (useExternal) {
            prefs.getString(PREF_TREE_URI, "") ?: ""
        } else {
            ""
        }

        // 6. شغّل الـWorker
        QuranRecitationDownloadWorker.enqueue(
            context       = this,
            scope         = scope,
            pageNow       = currentPage,
            surahNow      = currentSurah,
            qariId        = qariIdNow,
            audioBase     = audioBase,
            storageTarget = if (useExternal) "external" else "internal",
            treeUri       = treeUriStr
        )

        // 7. رسالة تأكيد للمستخدم
        Toast.makeText(
            this,
            if (useExternal)
                "جارٍ تنزيل التلاوات إلى الذاكرة الخارجية…"
            else
                "جارٍ تنزيل التلاوات داخل التطبيق…",
            Toast.LENGTH_LONG
        ).show()
    }


    // ======== إظهار/إخفاء شريط التلاوة ========
    private fun showAudioBar() {
        if (audioControlsCard.visibility != View.VISIBLE) {
            val startTY = (audioControlsCard.height.takeIf { it > 0 } ?: bottomOverlaysHeight) + bottomInsetLocked
            audioControlsCard.translationY = startTY.toFloat()
            audioControlsCard.alpha = 0f
            audioControlsCard.visibility = View.VISIBLE
            audioControlsCard.animate().translationY(0f).alpha(1f).setDuration(160).start()
        }
    }
    private fun hideAudioBar() {
        if (audioControlsCard.visibility == View.VISIBLE) {
            val endTY = (audioControlsCard.height.takeIf { it > 0 } ?: bottomOverlays.height) + bottomInsetLocked
            audioControlsCard.animate()
                .translationY(endTY.toFloat()).alpha(0f).setDuration(140)
                .withEndAction {
                    audioControlsCard.visibility = View.GONE
                    audioControlsCard.translationY = 0f
                    audioControlsCard.alpha = 1f
                }.start()
        }
    }
    // اعرض معلومات الصفحة الحالية داخل شريط الآية المتحرك فقط
    private fun renderPageInfo() {
        val page = currentPage
        val sura = runCatching { supportHelper.getSurahNameForPage(page) }.getOrNull().orEmpty()
        val juz  = getJuzForPage(page)

        val txt = if (sura.isNotBlank())
            "الصفحة $page • الجزء $juz • سورة $sura"
        else
            "الصفحة $page • الجزء $juz"

        pageInfoInline?.apply {
            text = txt
            // إعادة تشغيل الماركيه
            isSelected = false
            isSelected = true
        }
    }

    // حساب الجزء من رقم الصفحة
    private val JUZ_START_PAGES = intArrayOf(
        1,22,42,62,82,102,121,142,162,182,
        201,222,242,262,282,302,322,342,362,382,
        402,422,442,462,482,502,522,542,562,582
    )
    private fun getJuzForPage(page: Int): Int {
        // بدايات الأجزاء في مصحف المدينة (604 صفحة)
        val starts = intArrayOf(
            1,22,42,62,82,102,121,142,162,182,
            201,222,242,262,282,302,322,342,362,382,
            402,422,442,462,482,502,522,542,562,582
        )
        var juz = 1
        for (i in starts.indices) if (page >= starts[i]) juz = i + 1
        return juz
    }


    // بناء نص معلومات الصفحة
    private fun buildPageInfoText(page: Int): String {
        val sura = runCatching { supportHelper.getSurahNameForPage(page) }.getOrNull().orEmpty()
        val juz  = getJuzForPage(page)
        return if (sura.isNotBlank())
            "الصفحة $page • الجزء $juz • سورة $sura"
        else
            "الصفحة $page • الجزء $juz"
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (barsVisible) {
            hideHandler?.removeCallbacks(hideRunnable)
            hideHandler?.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS.toLong())
        }
    }

    private fun showBackgroundContinueNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "recitation_bg_info"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, getString(R.string.notif_download_title),
                NotificationManager.IMPORTANCE_LOW).apply {
                description = "إشعار معلوماتي قصير عند متابعة التنزيل بالخلفية"
                setShowBadge(false); enableLights(false); enableVibration(false)
            }
            nm.createNotificationChannel(ch)
        }
        val n = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.notif_download_title))
            .setContentText(getString(R.string.background_will_continue))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setTimeoutAfter(3500)
            .build()
        val id = 9901
        try {
            nm.notify(id, n)
            if (Build.VERSION.SDK_INT < 26) {
                Handler(Looper.getMainLooper()).postDelayed({ nm.cancel(id) }, 3500)
            }
        } catch (_: SecurityException) { }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isLandscape() && !barsVisible) enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        applyBarsPalette()
        refreshAyahBannerMarquee()   // <-- هذا السطر
        if (isLandscape() && !barsVisible) enterImmersive() else exitImmersive()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply { addAction(ACT_PLAY); addAction(ACT_PAUSE); addAction(ACT_STOP) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION") registerReceiver(notifReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(notifReceiver) } catch (_: Exception) {}
    }

    // ============================ NOTIFICATION ============================
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "تشغيل التلاوة", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "إشعار تشغيل/إيقاف تلاوة القرآن" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFS
                )
            }
        }
    }

    fun updateNotification(isPlaying: Boolean, surah: Int? = null, ayah: Int? = null, customText: String? = null) {
        ensureNotificationChannel()
        val title = if (surah != null && ayah != null) {
            val sName = supportHelper.getSurahNameByNumber(surah).ifEmpty { "سورة $surah" }
            "$sName • آية $ayah"
        } else {
            if (isPlaying) "جاري تلاوة القرآن" else "التلاوة متوقفة"
        }
        val text = when {
            !customText.isNullOrBlank() -> customText
            surah != null && ayah != null -> try { supportHelper.getAyahTextFromJson(surah, ayah) } catch (_: Throwable) { "—" }
            else -> "—"
        }
        val contentPI = PendingIntent.getActivity(
            this, 100, Intent(this, QuranPageActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (isPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setContentIntent(contentPI)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        val playPI  = pendingSelfBroadcast(ACT_PLAY , 201)
        val pausePI = pendingSelfBroadcast(ACT_PAUSE, 202)
        val stopPI  = pendingSelfBroadcast(ACT_STOP , 203)

        if (isPlaying) builder.addAction(android.R.drawable.ic_media_pause, "إيقاف مؤقت", pausePI)
        else builder.addAction(android.R.drawable.ic_media_play, "تشغيل", playPI)
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPI)

        val canPost = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        if (canPost) NotificationManagerCompat.from(this).notify(NOTIF_ID, builder.build())
    }
    /** إظهار/إخفاء شريط الآية مع احترام قفل المستخدم */
    private fun showAyahOptions(show: Boolean, force: Boolean = false) {
        // إذا كان المستخدم أغلق الشريط يدويًا، لا نُظهره إلا عند force=true
        if (show && ayahBarClosedByUser && !force) return

        ayahOptionsBar.clearAnimation()

        if (show) {
            // إظهار بتلاشي بسيط
            ayahOptionsBar.alpha = 0f
            ayahOptionsBar.visibility = View.VISIBLE
            ayahOptionsBar.animate()
                .alpha(1f)
                .setDuration(150)
                .start()
        } else {
            // إخفاء بتلاشي بسيط ثم إخفاء الـ View
            ayahOptionsBar.animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction {
                    ayahOptionsBar.visibility = View.GONE
                    ayahOptionsBar.alpha = 1f
                }
                .start()
        }
    }

    // ============================ MENU ============================
    private fun debouncePrepareQueue(page: Int, immediate: Boolean = false) {
        prepareQueueRunnable?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable { audioHelper.prepareAudioQueueForPage(page, currentQariId) }
        prepareQueueRunnable = r
        uiHandler.postDelayed(r, if (immediate) 0 else 120)
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_page_viewer, menu)
        updateThemeToggleTitle(menu?.findItem(R.id.action_toggle_theme))
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val favItem = menu.findItem(R.id.action_toggle_page_bookmark)
        favItem?.setIcon(if (isFavoritePage(this, currentPage)) R.drawable.ic_star_filled else R.drawable.ic_star_border)
        val themeItem = menu.findItem(R.id.action_toggle_theme)
        updateThemeToggleTitle(themeItem)
        return super.onPrepareOptionsMenu(menu)
    }
    private fun showFirstAyahBannerForCurrentPage() {
        // إذا المستخدم منع ظهور الشريط، لا تحاول حتى
        if (ayahBannerSuppressedByUser || !ayahBannerVisibleByToggle) {
            showAyahBannerInternal(false)
            return
        }

        // نحاول نجيب أول آية موجودة في الصفحة الحالية
        val first = supportHelper
            .loadAyahBoundsForPage(currentPage)
            .minWithOrNull(compareBy({ it.sura_id }, { it.aya_id }))

        if (first != null) {
            currentSurah = first.sura_id
            currentAyah  = first.aya_id

            val ayahText = try {
                supportHelper.getAyahTextFromJson(currentSurah, currentAyah)
            } catch (_: Throwable) {
                ""
            }

            // حدّث النص/الرقم/اسم السورة داخل واجهة البانر
            supportHelper.showOrUpdateAyahBanner(currentSurah, currentAyah, ayahText)

            // رجّع الماركيه يشتغل من البداية
            refreshAyahBannerMarquee()

            // فعلياً اعرض البانر (fade in لو كان مخفي)
            showAyahBannerInternal(true)
        } else {
            // لو الصفحة ما فيها حدود آيات (استثنائي جداً) نخفي البانر
            showAyahBannerInternal(false)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_page_bookmark -> {
                if (isFavoritePage(this, currentPage)) {
                    removeFavoritePage(this, currentPage); item.setIcon(R.drawable.ic_star_border)
                    Toast.makeText(this, "تم إزالة حفظ الصفحة", Toast.LENGTH_SHORT).show()
                } else {
                    addFavoritePage(this, currentPage); item.setIcon(R.drawable.ic_star_filled)
                    toolbar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.star_click))
                    Toast.makeText(this, "تم حفظ الصفحة في المفضلة", Toast.LENGTH_SHORT).show()
                }
                true
            }
            // 🕌 زر إظهار البانر
            R.id.action_show_ayah_banner -> {
                // نعكس الحالة كل ضغطة
                ayahBannerVisibleByToggle = !ayahBannerVisibleByToggle

                if (ayahBannerVisibleByToggle) {
                    // السماح بالظهور
                    ayahBannerSuppressedByUser = false

                    prefs.edit()
                        .putBoolean(KEY_BANNER_TOGGLE_STATE, true)
                        .putBoolean(KEY_SUPPRESS_AYAH_BANNER, false)
                        .apply()

                    showFirstAyahBannerForCurrentPage()

                    Toast.makeText(this, "تم تفعيل شريط الآية", Toast.LENGTH_SHORT).show()
                } else {
                    // منع الظهور نهائياً
                    ayahBannerSuppressedByUser = true

                    prefs.edit()
                        .putBoolean(KEY_BANNER_TOGGLE_STATE, false)
                        .putBoolean(KEY_SUPPRESS_AYAH_BANNER, true)
                        .apply()

                    showAyahBannerInternal(false)

                    Toast.makeText(this, "تم إخفاء شريط الآية تمامًا", Toast.LENGTH_SHORT).show()
                }

                true
            }

            R.id.action_toggle_theme -> {
                // بدّل القيمة واحصل على الوضع الجديد
                val toNight = ThemeManager.toggle(this) // يعيد true إذا أصبح ليلياً

                // طبّق فوراً على الـDelegate (مهم لتفادي "نقرة ثانية")
                AppCompatDelegate.setDefaultNightMode(
                    if (toNight) AppCompatDelegate.MODE_NIGHT_YES
                    else AppCompatDelegate.MODE_NIGHT_NO
                )

                // حدّث مظهر أشرطة النظام ليتطابق مع الوضع الجديد
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !toNight
                    isAppearanceLightNavigationBars = !toNight
                }

                updateThemeToggleTitle(item)

                // أعد إنشاء الشاشة بعد أن أصبح الوضع مفعلًا فعليًا
                window.decorView.post { recreate() }
                return true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateThemeToggleTitle(item: MenuItem?) {
        item ?: return
        val night = ThemeManager.isNight(this)
        item.title = if (night) "☀️" else "🌙"
    }
    override fun onDestroy() {
        super.onDestroy()
        isCancelled = true
        exec?.shutdownNow()
        try { audioBgThread.quitSafely() } catch (_: Exception) {}
        hideHandler?.removeCallbacks(hideRunnable)
        prepareQueueRunnable?.let { uiHandler.removeCallbacks(it) }
        try { ayahOptionsBar.viewTreeObserver.removeOnPreDrawListener(ayahBarGuard) } catch (_: Exception) {}
        prepareQueueRunnable = null
    }
    private fun initMarquee(tv: TextView?) {
        tv?.apply {
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            isFocusable = true
            isFocusableInTouchMode = true
            setHorizontallyScrolling(true)
            isSelected = true
        }
    }

    // عند تحديث نص الآية أو معلومات الصفحة
    private fun refreshAyahBannerMarquee() {
        ayahTextView?.let {
            it.isSelected = false
            it.isSelected = true
        }

        pageInfoInline?.let {
            it.isSelected = false
            it.isSelected = true
        }
    }

    private fun prepareBarsOverlay() {
        toolbar.post {
            toolbarHeight = toolbar.height
            ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
                if (!insetsLocked) topInsetLocked = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                v.updatePadding(top = topInsetLocked)
                WindowInsetsCompat.CONSUMED
            }
        }
        bottomOverlays.post { bottomOverlaysHeight = bottomOverlays.height }

        toolbar.visibility = View.VISIBLE
        bottomOverlays.visibility = View.VISIBLE
        audioControlsCard.visibility = View.VISIBLE
        ayahOptionsBar.visibility = View.GONE

        toolbar.alpha = 1f
        bottomOverlays.alpha = 1f
        audioControlsCard.alpha = 1f

        toolbar.post { insetsLocked = true }
    }

    private fun setAllBarsVisible(visible: Boolean, autoHideMs: Int? = null, allowWhilePlaying: Boolean = false) {
        barsVisible = visible
        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscape()) { if (visible) ctrl.show(WindowInsetsCompat.Type.systemBars()) else ctrl.hide(WindowInsetsCompat.Type.systemBars()) }
        else { ctrl.show(WindowInsetsCompat.Type.systemBars()) }

        val dur = 180L
        if (visible) toolbar.visibility = View.VISIBLE
        val topH = (if (toolbar.height > 0) toolbar.height else toolbarHeight) + topInsetLocked
        val tYTop = if (visible) 0f else -topH.toFloat()
        toolbar.animate().translationY(tYTop).alpha(if (visible) 1f else 0f).setDuration(dur)
            .withEndAction { if (!visible && isLandscape()) toolbar.visibility = View.GONE }.start()

        if (visible) {
            bottomOverlays.visibility = View.VISIBLE
            audioControlsCard.visibility = View.VISIBLE
        }
        val bottomH = (if (bottomOverlays.height > 0) bottomOverlays.height else bottomOverlaysHeight) + bottomInsetLocked
        val tYBottom = if (visible) 0f else bottomH.toFloat()
        bottomOverlays.animate().translationY(tYBottom).alpha(if (visible) 1f else 0f).setDuration(dur)
            .withEndAction { if (!visible) bottomOverlays.visibility = View.GONE }.start()
        audioControlsCard.animate().translationY(tYBottom).alpha(if (visible) 1f else 0f).setDuration(dur)
            .withEndAction { if (!visible) audioControlsCard.visibility = View.GONE }.start()

        hideHandler?.removeCallbacks(hideRunnable)
        val requested = autoHideMs ?: AUTO_HIDE_DELAY_MS
        val effectiveAutoHide = maxOf(requested, AUTO_HIDE_DELAY_MS)
        if (visible) hideHandler?.postDelayed(hideRunnable, effectiveAutoHide.toLong())
    }

    // ===================== CENTER LOADER =====================
    override fun showCenterLoader(msg: String) { acquireCenterLock(msg) }
    fun showCenterLoader() { acquireCenterLock("جاري تنزيل صفحات المصحف…") }
    override fun hideCenterLoader() { releaseCenterLock() }

    private fun acquireCenterLock(msg: String? = null) {
        if (userClosedOverlay) return
        ensureCenterLoaderInflated()  // <── أضف هذا هنا
        centerVisibleLocks++
        runOnUiThread {
            msg?.let { if (::centerLoaderText.isInitialized) centerLoaderText.text = it }
            if (::centerLoader.isInitialized) {
                centerLoader.visibility = View.VISIBLE
                centerLoader.bringToFront()
            }
        }
    }

    private fun updateToolbarTitleForPage(page: Int) {
        val title = supportHelper.getSurahNameForPage(page).ifEmpty { getString(R.string.app_name) }

        toolbar.title = title
        val titleColor = androidx.core.content.ContextCompat.getColor(this, R.color.colorOnPrimary)
        toolbar.setTitleTextColor(titleColor)
    }
    private fun releaseCenterLock() {
        if (centerVisibleLocks > 0) centerVisibleLocks--
        runOnUiThread {
            if (centerVisibleLocks == 0 && !bulkPrefetchRunning && ::centerLoader.isInitialized) {
                centerLoader.visibility = View.GONE
            }
        }
    }

    // ===================== PREFETCH PAGES =====================
    private fun arePagesCached(): Boolean = prefs.getBoolean(KEY_PAGES_CACHED, false)
    private fun setPagesCachedDone() { prefs.edit().putBoolean(KEY_PAGES_CACHED, true).apply() }

    private fun formatEta(sec: Long): String {
        val s = max(0, sec); val h = s / 3600; val m = (s % 3600) / 60; val ss = s % 60
        return if (h > 0) String.format("الوقت المتبقي: %d:%02d:%02d", h, m, ss)
        else String.format("الوقت المتبقي: %02d:%02d", m, ss)
    }

    private fun waitIfPaused() {
        synchronized(pauseLock) {
            while (isPaused && !isCancelled) {
                try { pauseLock.wait(150) } catch (_: InterruptedException) { break }
            }
        }
    }

    private fun startBulkPagesPrefetch(parallelism: Int = 6) {
        ensureCenterLoaderInflated()   // <── أضف هذا أولاً
        val successThreshold = 0.95f
        bulkPrefetchRunning = true
        isPaused = false
        isCancelled = false
        userClosedOverlay = false

        acquireCenterLock("جاري تنزيل صفحات المصحف…")
        centerProgress.isIndeterminate = false
        centerProgress.max = TOTAL_PAGES
        centerProgress.progress = 0
        centerCount.text = "0 / $TOTAL_PAGES"
        centerPercent.text = "  (0%)"
        centerEta.text = "الوقت المتبقي: …"
        btnPause.isEnabled = true
        btnResume.isEnabled = false

        val startMs = SystemClock.elapsedRealtime()
        val ok = java.util.concurrent.atomic.AtomicInteger(0)

        fun updateUI(successCount: Int) {
            centerProgress.progress = successCount
            centerCount.text = "$successCount / $TOTAL_PAGES"
            val pct = ((successCount * 100f) / TOTAL_PAGES.toFloat()).toInt().coerceIn(0, 100)
            centerPercent.text = "  (${pct}%)"
            val elapsedSec = max(1L, ((SystemClock.elapsedRealtime() - startMs) / 1000f).roundToLong())
            val rate = successCount.toFloat() / elapsedSec.toFloat()
            val remaining = (TOTAL_PAGES - successCount).coerceAtLeast(0)
            val etaSec = if (rate > 0f) (remaining / rate).roundToLong() else Long.MAX_VALUE
            centerEta.text = if (etaSec == Long.MAX_VALUE) "الوقت المتبقي: …" else formatEta(etaSec)
        }

        exec = Executors.newFixedThreadPool(parallelism).also { pool ->
            for (page in 1..TOTAL_PAGES) {
                pool.execute {
                    if (isCancelled) return@execute
                    waitIfPaused()
                    if (isCancelled) return@execute

                    try {
                        PageImageLoader.prefetchPageRetry(this@QuranPageActivity, page) { success ->
                            if (success) {
                                val c = ok.incrementAndGet()
                                runOnUiThread { updateUI(c) }
                            }
                        }
                    } catch (_: Exception) { }
                }
            }

            Thread {
                pool.shutdown()
                try { pool.awaitTermination(45, TimeUnit.MINUTES) } catch (_: InterruptedException) {}
                runOnUiThread {
                    val successCount = ok.get()
                    val success = successCount >= (TOTAL_PAGES * successThreshold).toInt()

                    if (!isCancelled && success) {
                        setPagesCachedDone()
                        centerProgress.progress = successCount
                        centerCount.text = "$successCount / $TOTAL_PAGES"
                        centerPercent.text = "  (100%)"
                        Toast.makeText(this, "اكتمل تنزيل صفحات المصحف", Toast.LENGTH_LONG).show()
                    } else if (!isCancelled) {
                        val missing = TOTAL_PAGES - successCount
                        Toast.makeText(this, "تعذّر تنزيل $missing صفحة. حاول لاحقًا.", Toast.LENGTH_LONG).show()
                    }

                    bulkPrefetchRunning = false
                    userClosedOverlay = false
                    releaseCenterLock()
                }
            }.start()
        }
    }

    fun showBarsThenAutoHide(delayMs: Int = 3500) { setAllBarsVisible(true, delayMs) }
    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    // ====================== قائمة التفسير (Popup) ======================
    private fun setupTafsirMenuButton(btnTafsirMenu: TextView) {
        btnTafsirMenu.text = try { tafsirManager.getSelectedName() }
        catch (_: Throwable) { tafsirManager.names().getOrNull(tafsirManager.getSelectedIndex()) ?: getString(R.string.tafsir) }

        btnTafsirMenu.setOnClickListener { v ->
            val names = tafsirManager.names()
            val popup = android.widget.PopupMenu(this, v)
            names.forEachIndexed { idx, name -> popup.menu.add(0, idx, idx, name) }
            popup.setOnMenuItemClickListener { mi ->
                val i = mi.itemId
                tafsirManager.setSelectedIndex(i)
                btnTafsirMenu.text = names[i]

                if (currentSurah > 0 && currentAyah > 0) {
                    val ayahText = try { supportHelper.getAyahTextFromJson(currentSurah, currentAyah) }
                    catch (_: Throwable) { ayahPreview?.text?.toString().orEmpty() }
                    tafsirManager.fetchFromCDN(currentSurah, currentAyah) { tafsirText ->
                        runOnUiThread { tafsirManager.showAyahDialog(currentSurah, currentAyah, ayahText, tafsirText) }
                    }
                }
                true
            }
            popup.show()
        }

        btnDownloadTafsir.setOnClickListener { tafsirManager.showDownloadDialog(this) }
    }

    // ====== بيانات السور ======
    private val SURAH_NAMES = arrayOf(
        "1. الفاتحة","2. البقرة","3. آل عمران","4. النساء","5. المائدة","6. الأنعام","7. الأعراف",
        "8. الأنفال","9. التوبة","10. يونس","11. هود","12. يوسف","13. الرعد","14. إبراهيم","15. الحجر",
        "16. النحل","17. الإسراء","18. الكهف","19. مريم","20. طه","21. الأنبياء","22. الحج","23. المؤمنون",
        "24. النور","25. الفرقان","26. الشعراء","27. النمل","28. القصص","29. العنكبوت","30. الروم","31. لقمان",
        "32. السجدة","33. الأحزاب","34. سبأ","35. فاطر","36. يس","37. الصافات","38. ص","39. الزمر",
        "40. غافر","41. فصلت","42. الشورى","43. الزخرف","44. الدخان","45. الجاثية","46. الأحقاف","47. محمد",
        "48. الفتح","49. الحجرات","50. ق","51. الذاريات","52. الطور","53. النجم","54. القمر","55. الرحمن",
        "56. الواقعة","57. الحديد","58. المجادلة","59. الحشر","60. الممتحنة","61. الصف","62. الجمعة","63. المنافقون",
        "64. التغابن","65. الطلاق","66. التحريم","67. الملك","68. القلم","69. الحاقة","70. المعارج","71. نوح",
        "72. الجن","73. المُزَّمِّل","74. المُدَّثِّر","75. القيامة","76. الإنسان","77. المرسلات","78. النبأ","79. النازعات",
        "80. عبس","81. التكوير","82. الانفطار","83. المطففين","84. الانشقاق","85. البروج","86. الطارق","87. الأعلى",
        "88. الغاشية","89. الفجر","90. البلد","91. الشمس","92. الليل","93. الضحى","94. الشرح","95. التين",
        "96. العلق","97. القدر","98. البينة","99. الزلزلة","100. العاديات","101. القارعة","102. التكاثر","103. العصر",
        "104. الهمزة","105. الفيل","106. قريش","107. الماعون","108. الكوثر","109. الكافرون","110. النصر","111. المسد","112. الإخلاص","113. الفلق","114. الناس"
    )

    private val AYAH_COUNTS = intArrayOf(
        7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,112,78,118,64,77,227,93,88,69,60,34,30,73,54,45,83,182,88,75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,14,11,11,18,12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,11,8,3,9,5,4,5,6,3,5,4,5,4,5,6
    )

    private fun normalizeDigits(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val ar = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
        val fa = charArrayOf('۰','۱','۲','۳','۴','۵','۶','۷','۸','۹')
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val i1 = ar.indexOf(ch); val i2 = fa.indexOf(ch)
            when {
                i1 >= 0 -> sb.append(('0'.code + i1).toChar())
                i2 >= 0 -> sb.append(('0'.code + i2).toChar())
                else    -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private var lastTapAt = 0L
    private fun toggleBars() { if (barsVisible) { showAyahOptions(false, true); setAllBarsVisible(false) } else { setAllBarsVisible(true, AUTO_HIDE_DELAY_MS) } }
    private fun safeToggleBars() { val now = SystemClock.uptimeMillis(); if (now - lastTapAt < 180) return; lastTapAt = now; toggleBars() }

    @SuppressLint("InflateParams")
    private fun showRepeatRangeDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_repeat_range, null, false)
        val spSurah = v.findViewById<Spinner>(R.id.spSurah)
        val etFrom  = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etFrom)
        val etTo    = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTo)
        val tilFrom = v.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilFrom)
        val tilTo   = v.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilTo)
        val btnFromCur = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFromCurrent)
        val btnToEnd   = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnToEndPage)
        val cgTimes    = v.findViewById<com.google.android.material.chip.ChipGroup>(R.id.cgTimes)
        val etTimes    = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTimes)
        val tilTimes   = v.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilTimes)

        val surahNames = (1..114).map { n -> supportHelper.getSurahNameByNumber(n).ifEmpty { "سورة $n" } }
        spSurah.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, surahNames)
        spSurah.setSelection((currentSurah - 1).coerceIn(0, 113))

        btnFromCur.setOnClickListener { etFrom.setText(currentAyah.toString()) }
        btnToEnd.setOnClickListener {
            val selSurah = spSurah.selectedItemPosition + 1
            val bounds = supportHelper.loadAyahBoundsForPage(currentPage)
            val lastOnPage = bounds.filter { it.sura_id == selSurah }.maxOfOrNull { it.aya_id } ?: currentAyah
            etTo.setText(lastOnPage.toString())
        }

        fun updateCustomVisibility(checkedId: Int) {
            val custom = (checkedId == R.id.chipCustom)
            tilTimes.visibility = if (custom) View.VISIBLE else View.GONE
            if (custom) { etTimes.requestFocus(); etTimes.setSelection(etTimes.text?.length ?: 0) }
        }
        cgTimes.setOnCheckedChangeListener(
            com.google.android.material.chip.ChipGroup.OnCheckedChangeListener { _, checkedId -> updateCustomVisibility(checkedId) }
        )
        updateCustomVisibility(cgTimes.checkedChipId)

        fun selectedTimes(): Int {
            val id = cgTimes.checkedChipId
            val txt = when (id) {
                R.id.chip1->"1"; R.id.chip3->"3"; R.id.chip5->"5"; R.id.chip10->"10"
                R.id.chipCustom -> etTimes.text?.toString().orEmpty()
                else -> "1"
            }
            return parseArabicIntLocal(txt).coerceIn(1, 99)
        }

        audioHelper.loadLastRange()?.let { last ->
            spSurah.setSelection((last.surah - 1).coerceIn(0, 113))
            etFrom.setText(last.fromAyah.toString())
            etTo.setText(last.toAyah.toString())
            when (last.times) {
                1->cgTimes.check(R.id.chip1); 3->cgTimes.check(R.id.chip3); 5->cgTimes.check(R.id.chip5); 10->cgTimes.check(R.id.chip10)
                else -> { cgTimes.check(R.id.chipCustom); tilTimes.visibility = View.VISIBLE; etTimes.setText(last.times.toString()) }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("تكرار نطاق آيات")
            .setView(v)
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->
                tilFrom.error = null; tilTo.error = null
                val surah = spSurah.selectedItemPosition + 1
                val from  = parseArabicIntLocal(etFrom.text?.toString())
                val to    = parseArabicIntLocal(etTo.text?.toString())
                val times = selectedTimes()
                var valid = true
                if (from <= 0) { tilFrom.error = "أدخل رقم آية صحيح"; valid = false }
                if (to   <= 0) { tilTo.error   = "أدخل رقم آية صحيح"; valid = false }
                if (!valid) return@setPositiveButton
                audioHelper.startRangeRepeat(surah, from, to, times, currentQariId)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS, allowWhilePlaying = true)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
    // داخل QuranPageActivity
    private fun forceStartPageFromBeginning(page: Int) {
        // تحديد أول آية على الصفحة (ترتيب: سورة ثم آية)
        val first = supportHelper
            .loadAyahBoundsForPage(page)
            .minWithOrNull(compareBy({ it.sura_id }, { it.aya_id })) ?: return

        // تحديت المؤشرات العالمية
        currentSurah = first.sura_id
        currentAyah  = first.aya_id

        // إيقاف أي تشغيل قديم وتجهيز الطابور من البداية ثم البدء
        audioHelper.stopSingleAyah()
        audioHelper.stopPagePlayback()
        audioHelper.prepareAudioQueueForPage(page, currentQariId, fromStart = true)

        lastPageStartedForPlayback = page
        audioHelper.startPagePlayback(page, currentQariId, fromStart = true)

        updateNotification(isPlaying = true, surah = currentSurah, ayah = currentAyah)
    }

    private fun parseArabicInt(src: String?): Int {
        if (src.isNullOrBlank()) return 0
        val mapped = buildString(src.length) {
            for (ch in src.trim()) {
                append(when (ch) { '٠'->'0'; '١'->'1'; '٢'->'2'; '٣'->'3'; '٤'->'4'; '٥'->'5'; '٦'->'6'; '٧'->'7'; '٨'->'8'; '٩'->'9'; else->ch })
            }
        }
        return mapped.toIntOrNull() ?: 0
    }

    private fun parseArabicIntLocal(s: String?) = parseArabicInt(s)

    /**
     * (أ) اسأل المستخدم ماذا يريد تنزيله:
     * - الصفحة الحالية
     * - السورة الكاملة
     * - الجزء الحالي
     * - المصحف كامل
     *
     * بعد الاختيار ننتقل للحوار الثاني (مكان الحفظ).
     */
    private fun showDownloadScopeDialog(
        currentPage: Int,
        currentSurah: Int,
        currentQariId: String
    ) {
        val currentJuz = getJuzForPage(currentPage)

        // لازم نوعها Array<String>
        val options: Array<String> = arrayOf(
            "الصفحة الحالية (${currentPage})",
            "السورة كاملة (سورة رقم $currentSurah)",
            "الجزء الحالي (الجزء $currentJuz)",
            "المصحف كامل (604 صفحة)"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("ماذا تريد تنزيله؟")
            .setItems(options) { dialogInterface, which: Int ->
                // حدد الـ scope بناءً على اختيار الزر
                val scope: String = when (which) {
                    0 -> "PAGE"
                    1 -> "SURAH"
                    2 -> "JUZ"
                    else -> "ALL"
                }

                dialogInterface.dismiss()

                // بعد ما عرفنا scope نروح نحدد مكان الحفظ (داخلي / خارجي)
                showStorageChoiceDialog(
                    scope   = scope,
                    pageNow = currentPage,
                    surahNow = currentSurah,
                    qariId  = currentQariId
                )
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * (ب) حوار "أين تريد الحفظ؟"
     * - داخل التطبيق
     * - في الذاكرة الخارجية / بطاقة SD
     *
     * لو اختار خارجي وما عندنا مسار محفوظ، نطلب منه يختار مجلد (ما نبدأ التنزيل مباشرة).
     */
    private fun showStorageChoiceDialog(
        scope: String,
        pageNow: Int,
        surahNow: Int,
        qariId: String
    ) {
        val items: Array<String> = arrayOf(
            "داخل التطبيق (موصى به)",
            "الذاكرة الخارجية / بطاقة SD"
        )

        MaterialAlertDialogBuilder(this)
            .setTitle("أين تريد حفظ التلاوات؟")
            .setItems(items) { dialogInterface, which: Int ->
                dialogInterface.dismiss()

                when (which) {
                    0 -> {
                        // حفظ داخلي مباشرة
                        startRecitationsDownloadWithScope(
                            scope         = scope,
                            storageTarget = "internal",
                            pageNow       = pageNow,
                            surahNow      = surahNow,
                            qariId        = qariId
                        )
                    }

                    1 -> {
                        // حفظ خارجي (SAF / بطاقة SD)
                        val ready: Boolean = ensureExternalFolderOrAskUser()
                        if (ready) {
                            // عندنا مجلد خارجي معروف, نبدأ التنزيل الآن
                            startRecitationsDownloadWithScope(
                                scope         = scope,
                                storageTarget = "external",
                                pageNow       = pageNow,
                                surahNow      = surahNow,
                                qariId        = qariId
                            )
                        } else {
                            // المستخدم لسه ما اختار مجلد. ما نبدأ التنزيل الآن.
                            Toast.makeText(
                                this,
                                "اختر مجلد خارجي أولاً، ثم أعد محاولة التنزيل.",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * دالة وسيطة فقط لترتيب المعطيات ونداء الـ Worker.
     * storageTarget = "internal" أو "external"
     */
    private fun startRecitationsDownloadWithScope(
        scope: String,
        storageTarget: String,
        pageNow: Int,
        surahNow: Int,
        qariId: String
    ) {
        val useExternal = (storageTarget == "external")

        // هنا نرسل كل شيء لـ startRecitationsDownloadCustom
        startRecitationsDownloadCustom(
            userScope        = scope,
            pageNow          = pageNow,
            surahNow         = surahNow,
            qariIdParam      = qariId,
            storageExternal  = useExternal
        )
    }

    /**
     * النسخة العامة من التنزيل الفعلي.
     * هذه هي التي تستدعي الـ Worker فعليًا.
     * ما فيها Dialogs.
     */
    private fun startRecitationsDownloadCustom(
        userScope: String,          // "PAGE" / "SURAH" / "JUZ" / "ALL"
        pageNow: Int,
        surahNow: Int,
        qariIdParam: String,
        storageExternal: Boolean
    ) {
        // تأكد من الـ qariId
        val qariIdNow: String = if (qariIdParam.isBlank()) "fares" else qariIdParam

        // رابط الأساس من القارئ الحالي
        val rawBase: String = provider.getQariById(qariIdNow)?.url ?: ""
        val audioBase: String = if (rawBase.endsWith("/")) rawBase else (rawBase + "/")

        // مسار المجلد الخارجي لو المستخدم اختار external
        val treeUriStr: String = if (storageExternal) {
            prefs.getString(PREF_TREE_URI, "") ?: ""
        } else {
            ""
        }

        // شغل الـ Worker
        QuranRecitationDownloadWorker.enqueue(
            context       = this,
            scope         = userScope, // مهم جداً
            pageNow       = pageNow,
            surahNow      = surahNow,
            qariId        = qariIdNow,
            audioBase     = audioBase,
            storageTarget = if (storageExternal) "external" else "internal",
            treeUri       = treeUriStr
        )

        // رسالة توضيح للمستخدم
        val msg: String = when {
            storageExternal && userScope == "PAGE"  ->
                "جارٍ تنزيل الصفحة $pageNow إلى الذاكرة الخارجية…"
            storageExternal && userScope == "SURAH" ->
                "جارٍ تنزيل السورة كاملة إلى الذاكرة الخارجية…"
            storageExternal && userScope == "JUZ"   ->
                "جارٍ تنزيل الجزء الحالي إلى الذاكرة الخارجية…"
            storageExternal && userScope == "ALL"   ->
                "جارٍ تنزيل المصحف كاملًا إلى الذاكرة الخارجية…"

            !storageExternal && userScope == "PAGE"  ->
                "جارٍ تنزيل الصفحة $pageNow داخل التطبيق…"
            !storageExternal && userScope == "SURAH" ->
                "جارٍ تنزيل السورة كاملة داخل التطبيق…"
            !storageExternal && userScope == "JUZ"   ->
                "جارٍ تنزيل الجزء الحالي داخل التطبيق…"
            else ->
                "جارٍ تنزيل المصحف كاملًا داخل التطبيق…"
        }

        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ====== NO-UI DOWNLOAD STUBS (اختياري) ======
    fun getDownloadRow(): View? = null
    fun getDownloadProgress(): ProgressBar? = null
    fun getDownloadPercentTv(): TextView? = null
    fun getDownloadSpeedTv(): TextView? = null
    fun getDownloadEtaTv(): TextView? = null
    fun getDownloadTitleTv(): TextView? = null
    fun getDownloadSubtitleTv(): TextView? = null
    fun getDownloadPauseButton(): View? = null
    fun getDownloadCancelButton(): View? = null
}

data class RangePrefs(val surah: Int, val fromAyah: Int, val toAyah: Int, val times: Int)
@Suppress("unused")
fun QuranAudioHelper.cancelRangeRepeat() { /* no-op */ }
@Suppress("unused")
fun QuranAudioHelper.loadLastRange(): RangePrefs? = null
@Suppress("unused")
fun QuranAudioHelper.startRangeRepeat(
    surah: Int, fromAyah: Int, toAyah: Int, times: Int, qariId: String
) { /* no-op */ }
