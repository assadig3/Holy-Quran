// File: app/src/main/java/com/hag/al_quran/QuranPageActivity.kt
package com.hag.al_quran

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
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
import com.hag.al_quran.utils.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.roundToLong

class QuranPageActivity : BaseActivity(), CenterLoaderHost {

    companion object {
        const val EXTRA_TARGET_SURAH = "EXTRA_TARGET_SURAH"
        const val EXTRA_TARGET_AYAH  = "EXTRA_TARGET_AYAH"
        const val EXTRA_TARGET_PAGE  = "EXTRA_TARGET_PAGE"
        const val EXTRA_QUERY        = "EXTRA_QUERY"

        private const val KEY_PAGES_CACHED = "pages_cached"
        private const val TOTAL_PAGES = 604

        const val CHANNEL_ID = "quran_playback_channel"
        private const val NOTIF_ID   = 99111

        private const val ACT_PLAY   = "com.hag.al_quran.NOTIF_PLAY"
        private const val ACT_PAUSE  = "com.hag.al_quran.NOTIF_PAUSE"
        private const val ACT_STOP   = "com.hag.al_quran.NOTIF_STOP"
        private var ayahBarClosedByUser = false

        private const val REQ_POST_NOTIFS = 8807

        // مفتاح موحّد للقارئ
        const val KEY_QARI_ID = "pref_qari_id"

        const val PREF_REPEAT_AYAH = "pref_repeat_ayah_count"
        const val PREF_REPEAT_PAGE = "pref_repeat_page_count"

        // مهلة الإخفاء التلقائي للأشرطة
        const val AUTO_HIDE_DELAY_MS = 4000
    }
    // أعلى الكلاس:
    private var statusBarScrim: View? = null

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
        val v = when (mode) {
            RepeatMode.OFF  -> 0
            RepeatMode.PAGE -> 1
            RepeatMode.AYAH -> 2
        }
        prefs.edit().putInt(PREF_REPEAT_MODE, v).apply()
    }

    private fun updateRepeatIcon() {
        when (repeatMode) {
            RepeatMode.OFF -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat)
                btnRepeat.alpha = 0.55f
                btnRepeat.contentDescription = getString(R.string.repeat_off)
            }
            RepeatMode.PAGE -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat)
                btnRepeat.alpha = 1f
                btnRepeat.contentDescription = getString(R.string.repeat_page)
            }
            RepeatMode.AYAH -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                btnRepeat.alpha = 1f
                btnRepeat.contentDescription = getString(R.string.repeat_ayah)
            }
        }
        audioHelper.repeatMode = when (repeatMode) {
            RepeatMode.OFF  -> "off"
            RepeatMode.PAGE -> "page"
            RepeatMode.AYAH -> "ayah"
        }
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
    lateinit var btnPlayPause: ImageButton
    lateinit var btnQari: TextView
    lateinit var audioDownload: ImageButton
    lateinit var btnRepeat: ImageButton

    // صف التحميل في شريط التلاوة
    lateinit var downloadRow: LinearLayout
    lateinit var downloadProgress: ProgressBar
    lateinit var downloadLabel: TextView

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

    // خدمات
    lateinit var prefs: SharedPreferences
    lateinit var provider: MadaniPageProvider
    lateinit var audioHelper: QuranAudioHelper
    lateinit var supportHelper: QuranSupportHelper
    lateinit var tafsirManager: TafsirManager

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

    // ==== تكرار النطاق (احتياطي إن احتجته) ====
    private data class RangeRepeatState(
        val surah: Int,
        val startAyah: Int,
        val endAyah: Int,
        var loopsLeft: Int,
        val qariId: String,
        var currentAyah: Int
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

    // قياسات وإغلاق insets
    private var toolbarHeight = 0
    private var bottomOverlaysHeight = 0
    private var topInsetLocked = 0
    private var bottomInsetLocked = 0
    private var insetsLocked = false

    // ========================== IMMERSIVE ==========================
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun enterImmersive() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.isAppearanceLightStatusBars = false
        c.isAppearanceLightNavigationBars = false
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun exitImmersive() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.show(WindowInsetsCompat.Type.systemBars())
    }
    // ===============================================================

    // ===== مستقبل أوامر الإشعار =====
    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACT_PLAY -> {
                    val resumed = audioHelper.resumePagePlayback()
                    if (!resumed) audioHelper.startPagePlayback(currentPage, currentQariId)
                    updateNotification(isPlaying = true)
                    setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
                }
                ACT_PAUSE -> {
                    audioHelper.pausePagePlayback()
                    updateNotification(isPlaying = false)
                    setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
                }
                ACT_STOP -> {
                    audioHelper.pausePagePlayback()
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
            this, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun handleIntentAction(action: String?) {
        when (action) {
            ACT_PLAY -> {
                val resumed = audioHelper.resumePagePlayback()
                if (!resumed) audioHelper.startPagePlayback(currentPage, currentQariId)
                updateNotification(isPlaying = true)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            }
            ACT_PAUSE -> {
                audioHelper.pausePagePlayback()
                updateNotification(isPlaying = false)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            }
            ACT_STOP -> {
                audioHelper.pausePagePlayback()
                NotificationManagerCompat.from(this).cancel(NOTIF_ID)
                setAllBarsVisible(false)
            }
        }
    }

    // ======== مراقِب حالة التشغيل ========
    private var lastPlayingState: Boolean = false
    private val playbackWatcher = object : Runnable {
        override fun run() {
            val nowPlaying = (audioHelper.isPlaying || audioHelper.isAyahPlaying)
            if (lastPlayingState && !nowPlaying) {
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            }
            lastPlayingState = nowPlaying
            uiHandler.postDelayed(this, 250)
        }
    }
    // =====================================================================

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quran_page)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val containerRoot = findViewById<ViewGroup>(R.id.quran_container)

// شريط يغطي منطقة الـ Status Bar بلون التولبار لتفادي الشريط الأبيض
        ViewCompat.setOnApplyWindowInsetsListener(containerRoot) { v, insets ->
            val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

            if (statusBarScrim == null) {
                statusBarScrim = View(this).apply {
                    setBackgroundColor(ContextCompat.getColor(this@QuranPageActivity, R.color.colorPrimaryDark))
                }
                // نضيفه في أعلى الـ container
                containerRoot.addView(
                    statusBarScrim,
                    0,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, topInset)
                )
            } else {
                statusBarScrim?.layoutParams?.height = topInset
                statusBarScrim?.requestLayout()
            }

            // لا نستهلك الـ insets هنا حتى تظل الـ listeners الأخرى تعمل
            insets
        }

        ensureNotificationChannel()
        requestNotifPermissionIfNeeded()

        // خدمات
        prefs         = getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)
        provider      = MadaniPageProvider(this)
        supportHelper = QuranSupportHelper(this, provider)
        audioHelper   = QuranAudioHelper(this, provider, supportHelper, audioBgHandler)
        tafsirManager = TafsirManager(this)

        // قراءة Intent
        val pageFromNew = intent.getIntExtra(EXTRA_TARGET_PAGE, 0)
        val pageFromOld = intent.getIntExtra("page", intent.getIntExtra("page_number", 0))
        val surahFromNew = intent.getIntExtra(EXTRA_TARGET_SURAH, 0)
        val ayahFromNew  = intent.getIntExtra(EXTRA_TARGET_AYAH, 0)
        val surahFromOld = intent.getIntExtra("surah_number", 0)
        val ayahFromOld  = intent.getIntExtra("ayah_number", 0)

        currentSurah = if (surahFromNew > 0) surahFromNew else if (surahFromOld > 0) surahFromOld else 1
        currentAyah  = if (ayahFromNew  > 0) ayahFromNew  else if (ayahFromOld  > 0) ayahFromOld  else 1
        currentPage  = when {
            pageFromNew > 0 -> pageFromNew
            pageFromOld > 0 -> pageFromOld
            else -> try { AyahLocator.getPageFor(this, currentSurah, currentAyah) } catch (_: Throwable) { 1 }
        }.coerceIn(1, TOTAL_PAGES)

        // ربط العناصر
        toolbar           = findViewById(R.id.toolbar)
        viewPager         = findViewById(R.id.pageViewPager)
        bottomOverlays    = findViewById(R.id.bottomOverlays)
        audioControlsCard = findViewById(R.id.audioControlsCard)

        audioControls     = findViewById(R.id.audioControls)
        btnPlayPause      = findViewById(R.id.btnPlayPause)
        btnQari           = findViewById(R.id.btnQari)
        audioDownload     = findViewById(R.id.audio_download)
        btnRepeat         = findViewById(R.id.btnRepeat)

        downloadRow       = findViewById(R.id.downloadRow)
        downloadProgress  = findViewById(R.id.downloadProgress)
        downloadLabel     = findViewById(R.id.downloadLabel)

        ayahOptionsBar    = findViewById(R.id.ayahOptionsBar)
        ayahOptionsBar.viewTreeObserver.addOnPreDrawListener(ayahBarGuard)

        val btnTafsirMenu = findViewById<TextView>(R.id.btnTafsirMenu)
        btnDownloadTafsir = findViewById(R.id.btnDownloadTafsir)

        btnShareAyah      = findViewById(R.id.btnShareAyah)
        btnCopyAyah       = findViewById(R.id.btnCopyAyah)
        btnPlayAyah       = findViewById(R.id.btnPlayAyah)
        btnCloseAyahBar   = findViewById(R.id.btnCloseOptions)
        ayahPreview       = findViewById(R.id.ayahPreview)
        initMarquee(ayahPreview)
        ayahOptionsBar.visibility = View.GONE

        val btnCloseAudioBar = findViewById<ImageButton>(R.id.btnCloseAudioBar)
        btnCloseAudioBar?.setOnClickListener { hideAudioBar() }

        // تحميل حالة القارئ والعدادات
        currentQariId = prefs.getString(KEY_QARI_ID, currentQariId) ?: currentQariId
        repeatMode = loadRepeatMode()
        audioHelper.repeatCount = prefs.getInt(PREF_REPEAT_AYAH, 1).coerceIn(1, 99)
        audioHelper.pageRepeatCount = prefs.getInt(PREF_REPEAT_PAGE, 1).coerceIn(1, 99)
        updateRepeatIcon()
        window.statusBarColor = ContextCompat.getColor(this, R.color.colorPrimaryDark)

        // Insets
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.updatePadding(top = top)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(bottomOverlays) { v, insets ->
            val bottomBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val base = (12 * resources.displayMetrics.density).toInt()
            v.updatePadding(bottom = bottomBars + base)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(viewPager) { v, insets ->
            val navBottom = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom
            val statusTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top

            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                v.setPadding(0, 0, 0, navBottom)
            } else {
                // ملاصق للتولبار مع احتساب شريط الحالة فقط
                v.setPadding(0, statusTop, 0, navBottom)
            }
            (v as ViewGroup).clipToPadding = false
            (v as ViewGroup).clipChildren  = false
            WindowInsetsCompat.CONSUMED
        }

        // Toolbar
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title =
            supportHelper.getSurahNameForPage(currentPage).ifEmpty { getString(R.string.app_name) }

        // ViewPager
        viewPager.setBackgroundColor(ContextCompat.getColor(this, R.color.quran_page_bg))
        viewPager.offscreenPageLimit = 1
        (viewPager.getChildAt(0) as? RecyclerView)?.apply {
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(4)
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        }

        // بانر الآن يتلى
        val root = findViewById<ViewGroup>(android.R.id.content)
        val banner = layoutInflater.inflate(R.layout.ayah_now_playing, root, false)
        ayahBanner       = banner
        ayahTextView     = banner.findViewById(R.id.ayahText)
        ayahBannerSurah  = banner.findViewById(R.id.surahName)
        ayahBannerNumber = banner.findViewById(R.id.ayahNumber)
        initMarquee(ayahTextView)
        banner.findViewById<ImageButton>(R.id.btnCloseBanner)
            .setOnClickListener { supportHelper.hideAyahBanner() }
        banner.visibility = View.GONE
        root.addView(banner)

        // Overlay الوسطي
        val container: ViewGroup = findViewById(R.id.quran_container)
        val overlay = layoutInflater.inflate(R.layout.view_center_loader, container, false)
        container.addView(overlay)
        overlay.bringToFront()
        centerLoader     = overlay
        centerLoaderText = overlay.findViewById(R.id.centerText)
        centerProgress   = overlay.findViewById(R.id.centerProgress)
        centerCount      = overlay.findViewById(R.id.centerCount)
        centerPercent    = overlay.findViewById(R.id.centerPercent)
        centerEta        = overlay.findViewById(R.id.centerEta)
        btnPause         = overlay.findViewById(R.id.btnPause)
        btnResume        = overlay.findViewById(R.id.btnResume)
        btnClose         = overlay.findViewById(R.id.btnClose)
        centerLoader.visibility = View.GONE
        centerLoaderText.isSelected = true

        btnPause.setOnClickListener {
            isPaused = true
            btnPause.isEnabled = false
            btnResume.isEnabled = true
        }
        btnResume.setOnClickListener {
            isPaused = false
            synchronized(pauseLock) { pauseLock.notifyAll() }
            btnPause.isEnabled = true
            btnResume.isEnabled = false
        }
        btnClose.setOnClickListener {
            userClosedOverlay = true
            centerVisibleLocks = 0
            centerLoader.visibility = View.GONE
            Toast.makeText(this, "سيستمر التنزيل في الخلفية.", Toast.LENGTH_SHORT).show()
        }

        // === زر قائمة التفسير (Popup) + زر التحميل ===
        setupTafsirMenuButton(btnTafsirMenu)

        // زر التكرار (قصير/طويل)
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
                        Toast.makeText(this, getString(R.string.repeat_off), Toast.LENGTH_SHORT).show()
                        setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
                        true
                    }
                    R.id.repeat_ayah -> {
                        audioHelper.cancelRangeRepeat()
                        repeatMode = RepeatMode.AYAH
                        saveRepeatMode(repeatMode)
                        updateRepeatIcon()
                        Toast.makeText(this, "تكرار آية × ${audioHelper.repeatCount}", Toast.LENGTH_SHORT).show()
                        setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
                        true
                    }
                    R.id.repeat_page -> {
                        audioHelper.cancelRangeRepeat()
                        repeatMode = RepeatMode.PAGE
                        saveRepeatMode(repeatMode)
                        updateRepeatIcon()
                        Toast.makeText(this, "تكرار الصفحة × ${audioHelper.pageRepeatCount}", Toast.LENGTH_SHORT).show()
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
        // أزرار شريط الآية
        btnPlayAyah.setOnClickListener {
            if (audioHelper.isAyahPlaying) {
                audioHelper.stopSingleAyah()
                updateNotification(isPlaying = false)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            } else {
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

            if (!ayahBarClosedByUser) {
                showAyahOptions(true)
            }
        }

        btnCopyAyah.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = ayahPreview?.text?.toString().orEmpty()
            cm.setPrimaryClip(ClipData.newPlainText("Ayah", text))
            Toast.makeText(this, "تم نسخ الآية!", Toast.LENGTH_SHORT).show()
            setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
        }
        btnShareAyah.setOnClickListener { supportHelper.shareCurrentAyah(currentSurah, currentAyah) }
        btnCloseAyahBar.setOnClickListener {
            ayahBarClosedByUser = true
            showAyahOptions(false)
        }

        // اختيار القارئ
        btnQari.text = provider.getQariById(currentQariId)?.name ?: "فارس عباد"
        btnQari.setOnClickListener {
            supportHelper.showQariPicker { qari ->
                val oldWasPagePlaying = audioHelper.isPlaying
                val oldWasAyahPlaying = audioHelper.isAyahPlaying
                val page = currentPage
                val sura = currentSurah
                val ayah = currentAyah

                currentQariId = qari.id
                btnQari.text  = qari.name
                prefs.edit().putString(KEY_QARI_ID, currentQariId).apply()

                audioHelper.stopAllPlaybackAndClearQueue()
                audioHelper.prepareAudioQueueForPage(page, currentQariId)

                when {
                    oldWasPagePlaying -> {
                        showAudioBar()
                        audioHelper.startPagePlayback(page, currentQariId)
                        updateNotification(isPlaying = true)
                    }
                    oldWasAyahPlaying -> {
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

        audioDownload.setOnClickListener {
            supportHelper.showDownloadScopeDialog(currentPage, currentSurah, currentQariId)
        }

        // زر التشغيل/الإيقاف
        btnPlayPause.setOnClickListener {
            if (audioHelper.isPlaying) {
                audioHelper.pausePagePlayback()
                updateNotification(isPlaying = false)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            } else {
                showAudioBar()
                val resumed = audioHelper.resumePagePlayback()
                if (!resumed) audioHelper.startPagePlayback(currentPage, currentQariId)
                updateNotification(isPlaying = true)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            }
        }

        // تثبيت قياسات الأشرطة
        prepareBarsOverlay()

        // Adapter
        val pageNames = (1..TOTAL_PAGES).map { "page_$it.webp" }
        adapter = AssetPageAdapter(
            context = this,
            pages = pageNames,
            realPageNumber = 0,
            onAyahClick = { s, a, t ->
                currentSurah = s
                currentAyah  = a
                val text = try { supportHelper.getAyahTextFromJson(s, a) } catch (_: Throwable) { t ?: "" }
                ayahPreview?.text = text
                ayahPreview?.isSelected = true

                ayahBarClosedByUser = false
                showAyahOptions(true)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            },
            onImageTap = {
                safeToggleBars()
            },

            onNeedPagesDownload = { },
            loaderHost = this
        )
        viewPager.adapter = adapter

        viewPager.setCurrentItem((currentPage - 1).coerceIn(0, TOTAL_PAGES - 1), false)

        viewPager.post { adapter.highlightAyahOnPage(currentPage, currentSurah, currentAyah) }
        PageImageLoader.prefetchAround(this, currentPage, radius = 1)

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                if (lastPos != -1 && lastPos != position) {
                    adapter.clearHighlightOnPage(lastPos + 1)
                }
                lastPos = position
                currentPage = position + 1

                adapter.clearHighlightOnPage(currentPage)
                showAyahOptions(false)

                val title = supportHelper.getSurahNameForPage(currentPage)
                    .ifEmpty { getString(R.string.app_name) }
                if (supportActionBar?.title != title) supportActionBar?.title = title

                saveLastVisitedPage(this@QuranPageActivity, currentPage)
                invalidateOptionsMenu()
                PageImageLoader.prefetchAround(this@QuranPageActivity, currentPage, radius = 1)
                debouncePrepareQueue(currentPage)
                setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
            }
        })

        hideHandler = Handler(Looper.getMainLooper())

        // إظهار أولي ثم إخفاء تلقائي بعد 4 ثوانٍ
        setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
        debouncePrepareQueue(currentPage, immediate = true)
        if (!arePagesCached()) startBulkPagesPrefetch(parallelism = 6)

        lastPlayingState = (audioHelper.isPlaying || audioHelper.isAyahPlaying)
        uiHandler.post(playbackWatcher)
    }

    /** إظهار شريط الآية يدويًا عند النقر إذا كان المستخدم قد أغلقه */
    private fun requestAyahOptions() {
        ayahBarClosedByUser = false
        showAyahOptions(true)
        setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
    }

    // ======== إظهار/إخفاء شريط التلاوة ========
    private fun showAudioBar() {
        if (audioControlsCard.visibility != View.VISIBLE) {
            val startTY = (audioControlsCard.height.takeIf { it > 0 } ?: bottomOverlaysHeight) +
                    bottomInsetLocked
            audioControlsCard.translationY = startTY.toFloat()
            audioControlsCard.alpha = 0f
            audioControlsCard.visibility = View.VISIBLE
            audioControlsCard.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(160)
                .start()
        }
    }

    private fun hideAudioBar() {
        if (audioControlsCard.visibility == View.VISIBLE) {
            val endTY = (audioControlsCard.height.takeIf { it > 0 } ?: bottomOverlays.height) +
                    bottomInsetLocked
            audioControlsCard.animate()
                .translationY(endTY.toFloat())
                .alpha(0f)
                .setDuration(140)
                .withEndAction {
                    audioControlsCard.visibility = View.GONE
                    audioControlsCard.translationY = 0f
                    audioControlsCard.alpha = 1f
                }
                .start()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // فقط جدّد مؤقت الإخفاء إذا الأشرطة أصلاً ظاهرة
        if (barsVisible) {
            hideHandler?.removeCallbacks(hideRunnable)
            hideHandler?.postDelayed(hideRunnable, AUTO_HIDE_DELAY_MS.toLong())
        }
    }


    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isLandscape() && !barsVisible) enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        if (isLandscape() && !barsVisible) enterImmersive() else exitImmersive()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ACT_PLAY); addAction(ACT_PAUSE); addAction(ACT_STOP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(notifReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(notifReceiver) } catch (_: Exception) {}
    }

    // ============================ NOTIFICATION ============================
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "تشغيل التلاوة", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "إشعار تشغيل/إيقاف تلاوة القرآن" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFS
                )
            }
        }
    }

    fun updateNotification(
        isPlaying: Boolean,
        surah: Int? = null,
        ayah: Int? = null,
        customText: String? = null
    ) {
        ensureNotificationChannel()

        val title = if (surah != null && ayah != null) {
            val sName = supportHelper.getSurahNameByNumber(surah).ifEmpty { "سورة $surah" }
            "$sName • آية $ayah"
        } else {
            if (isPlaying) "جاري تلاوة القرآن" else "التلاوة متوقفة"
        }

        val text = when {
            !customText.isNullOrBlank() -> customText
            surah != null && ayah != null -> try {
                supportHelper.getAyahTextFromJson(surah, ayah)
            } catch (_: Throwable) { "—" }
            else -> "—"
        }

        val contentPI = PendingIntent.getActivity(
            this, 100,
            Intent(this, QuranPageActivity::class.java),
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

        if (isPlaying) {
            builder.addAction(android.R.drawable.ic_media_pause, "إيقاف مؤقت", pausePI)
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "تشغيل", playPI)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPI)

        val canPost = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (canPost) NotificationManagerCompat.from(this).notify(NOTIF_ID, builder.build())
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
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val item = menu.findItem(R.id.action_toggle_page_bookmark)
        item?.setIcon(
            if (isFavoritePage(this, currentPage)) R.drawable.ic_star_filled
            else R.drawable.ic_star_border
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_toggle_page_bookmark -> {
                if (isFavoritePage(this, currentPage)) {
                    removeFavoritePage(this, currentPage)
                    item.setIcon(R.drawable.ic_star_border)
                    Toast.makeText(this, "تم إزالة حفظ الصفحة", Toast.LENGTH_SHORT).show()
                } else {
                    addFavoritePage(this, currentPage)
                    item.setIcon(R.drawable.ic_star_filled)
                    toolbar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.star_click))
                    Toast.makeText(this, "تم حفظ الصفحة في المفضلة", Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isCancelled = true
        exec?.shutdownNow()
        try { audioBgThread.quitSafely() } catch (_: Exception) {}

        // تنظيف مؤقتات/مهام مؤجلة
        hideHandler?.removeCallbacks(hideRunnable)
        prepareQueueRunnable?.let { uiHandler.removeCallbacks(it) }
        uiHandler.removeCallbacks(playbackWatcher)

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

    /** تثبيت الأشرطة كطبقات تطفو عبر translation فقط */
    private fun prepareBarsOverlay() {
        toolbar.post {
            toolbarHeight = toolbar.height
            ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
                if (!insetsLocked) {
                    topInsetLocked = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                }
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

    // ===================== تحكم موحّد في الأشرطة =====================
    private fun setAllBarsVisible(
        visible: Boolean,
        autoHideMs: Int? = null
    ) {
        barsVisible = visible

        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscape()) {
            if (visible) ctrl.show(WindowInsetsCompat.Type.systemBars())
            else ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }

        val dur = 180L

        // الشريط العلوي
        if (visible) toolbar.visibility = View.VISIBLE
        val topH = (if (toolbar.height > 0) toolbar.height else toolbarHeight) + topInsetLocked
        val tYTop = if (visible) 0f else -topH.toFloat()
        toolbar.animate()
            .translationY(tYTop)
            .alpha(if (visible) 1f else 0f)
            .setDuration(dur)
            .withEndAction { if (!visible && isLandscape()) toolbar.visibility = View.GONE }
            .start()

        // الشريط السفلي + شريط التلاوة
        if (visible) {
            bottomOverlays.visibility = View.VISIBLE
            audioControlsCard.visibility = View.VISIBLE
        }
        val bottomH =
            (if (bottomOverlays.height > 0) bottomOverlays.height else bottomOverlaysHeight) + bottomInsetLocked
        val tYBottom = if (visible) 0f else bottomH.toFloat()

        bottomOverlays.animate()
            .translationY(tYBottom)
            .alpha(if (visible) 1f else 0f)
            .setDuration(dur)
            .withEndAction { if (!visible) bottomOverlays.visibility = View.GONE }
            .start()

        audioControlsCard.animate()
            .translationY(tYBottom)
            .alpha(if (visible) 1f else 0f)
            .setDuration(dur)
            .withEndAction { if (!visible) audioControlsCard.visibility = View.GONE }
            .start()

        // ===== الإخفاء التلقائي =====
        hideHandler?.removeCallbacks(hideRunnable)
        val requested = autoHideMs ?: AUTO_HIDE_DELAY_MS
        val effectiveAutoHide = maxOf(requested, AUTO_HIDE_DELAY_MS)
        if (visible) {
            hideHandler?.postDelayed(hideRunnable, effectiveAutoHide.toLong())
        }
    }
    /** إظهار/إخفاء شريط الآية مع احترام قفل المستخدم */
    private fun showAyahOptions(show: Boolean, force: Boolean = false) {
        if (show && ayahBarClosedByUser && !force) return

        ayahOptionsBar.clearAnimation()
        if (show) {
            ayahOptionsBar.alpha = 0f
            ayahOptionsBar.visibility = View.VISIBLE
            ayahOptionsBar.animate().alpha(1f).setDuration(150).start()
        } else {
            ayahOptionsBar.animate()
                .alpha(0f).setDuration(120)
                .withEndAction {
                    ayahOptionsBar.visibility = View.GONE
                    ayahOptionsBar.alpha = 1f
                }.start()
        }
    }

    // ===================== CENTER LOADER =====================
    override fun showCenterLoader(msg: String) { acquireCenterLock(msg) }
    fun showCenterLoader() { acquireCenterLock("جاري تنزيل صفحات المصحف…") }
    override fun hideCenterLoader() { releaseCenterLock() }

    private fun acquireCenterLock(msg: String? = null) {
        if (userClosedOverlay) return
        centerVisibleLocks++
        runOnUiThread {
            msg?.let { if (::centerLoaderText.isInitialized) centerLoaderText.text = it }
            if (::centerLoader.isInitialized) {
                centerLoader.visibility = View.VISIBLE
                centerLoader.bringToFront()
            }
        }
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
        val s = max(0, sec)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
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
        val ok = AtomicInteger(0)
        val doneTasks = AtomicInteger(0)

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
                            doneTasks.incrementAndGet()
                        }
                    } catch (_: Exception) {
                        doneTasks.incrementAndGet()
                    }
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

    fun showBarsThenAutoHide(delayMs: Int = 3500) {
        setAllBarsVisible(true, delayMs)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    // ====================== قائمة التفسير (Popup) ======================
    private fun setupTafsirMenuButton(btnTafsirMenu: TextView) {
        btnTafsirMenu.text = try {
            tafsirManager.getSelectedName()
        } catch (_: Throwable) {
            tafsirManager.names().getOrNull(tafsirManager.getSelectedIndex()) ?: getString(R.string.tafsir)
        }

        btnTafsirMenu.setOnClickListener { v ->
            val names = tafsirManager.names()
            val popup = android.widget.PopupMenu(this, v)
            names.forEachIndexed { idx, name -> popup.menu.add(0, idx, idx, name) }

            popup.setOnMenuItemClickListener { mi ->
                val i = mi.itemId
                tafsirManager.setSelectedIndex(i)
                btnTafsirMenu.text = names[i]

                if (currentSurah > 0 && currentAyah > 0) {
                    val ayahText = try {
                        supportHelper.getAyahTextFromJson(currentSurah, currentAyah)
                    } catch (_: Throwable) {
                        ayahPreview?.text?.toString().orEmpty()
                    }
                    tafsirManager.fetchFromCDN(currentSurah, currentAyah) { tafsirText ->
                        runOnUiThread {
                            tafsirManager.showAyahDialog(currentSurah, currentAyah, ayahText, tafsirText)
                        }
                    }
                }
                true
            }
            popup.show()
        }

        btnDownloadTafsir.setOnClickListener {
            tafsirManager.showDownloadDialog(this)
        }
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
        "72. الجن","73. المزمل","74. المدثر","75. القيامة","76. الإنسان","77. المرسلات","78. النبأ","79. النازعات",
        "80. عبس","81. التكوير","82. الانفطار","83. المطففين","84. الانشقاق","85. البروج","86. الطارق","87. الأعلى",
        "88. الغاشية","89. الفجر","90. البلد","91. الشمس","92. الليل","93. الضحى","94. الشرح","95. التين",
        "96. العلق","97. القدر","98. البينة","99. الزلزلة","100. العاديات","101. القارعة","102. التكاثر","103. العصر",
        "104. الهمزة","105. الفيل","106. قريش","107. الماعون","108. الكوثر","109. الكافرون","110. النصر","111. المسد","112. الإخلاص","113. الفلق","114. الناس"
    )

    private val AYAH_COUNTS = intArrayOf(
        7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,112,78,118,64,77,227,93,88,69,60,34,30,73,54,45,83,182,88,75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,14,11,11,18,12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,11,8,3,9,5,4,5,6,3,5,4,5,4,5,6
    )

    /* تطبيع الأرقام العربية/الفارسية إلى لاتينية */
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

    /* حوار تكرار النطاق (من .. إلى) */
    fun showRepeatRangeDialog() {
        val themedCtx = ContextThemeWrapper(this, R.style.ThemeOverlay_Quran_Dialog)
        val v = layoutInflater.cloneInContext(themedCtx)
            .inflate(R.layout.dialog_repeat_range, null, false)

        val spSurah      = v.findViewById<Spinner>(R.id.spSurah)
        val etFrom       = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etFrom)
        val etTo         = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTo)
        val etTimes      = v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTimes)
        val btnFromCurr  = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFromCurrent)
        val btnToEndPage = v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnToEndPage)
        val cgTimes      = v.findViewById<com.google.android.material.chip.ChipGroup>(R.id.cgTimes)

        val adapter = ArrayAdapter(themedCtx, android.R.layout.simple_spinner_item, SURAH_NAMES)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spSurah.adapter = adapter
        spSurah.setSelection((currentSurah - 1).coerceIn(0, 113))

        etFrom.inputType  = android.text.InputType.TYPE_CLASS_NUMBER
        etTo.inputType    = android.text.InputType.TYPE_CLASS_NUMBER
        etTimes.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        etFrom.transformationMethod  = null
        etTo.transformationMethod    = null
        etTimes.transformationMethod = null

        etFrom.setText("")
        etTo.setText("")
        etTimes.setText("3")

        btnFromCurr.setOnClickListener {
            val selSurah = spSurah.selectedItemPosition + 1
            etFrom.setText(if (selSurah == currentSurah) currentAyah.toString() else "1")
        }

        btnToEndPage.setOnClickListener {
            val selSurah = spSurah.selectedItemPosition + 1
            val lastForSurahOnPage = try {
                val list = supportHelper.loadAyahBoundsForPage(currentPage)
                list.filter { it.sura_id == selSurah }.maxOfOrNull { it.aya_id }
            } catch (_: Throwable) { null }
            val fallback = AYAH_COUNTS.getOrNull(selSurah - 1) ?: 7
            etTo.setText((lastForSurahOnPage ?: fallback).toString())
        }

        cgTimes.setOnCheckedStateChangeListener { _, checked ->
            val times = when (checked.firstOrNull()) {
                R.id.chip1  -> 1
                R.id.chip3  -> 3
                R.id.chip5  -> 5
                R.id.chip10 -> 10
                else        -> null
            }
            times?.let { etTimes.setText(it.toString()) }
        }

        MaterialAlertDialogBuilder(themedCtx)
            .setTitle("تكرار نطاق آيات")
            .setView(v)
            .setPositiveButton("بدء") { d, _ ->
                val surah = spSurah.selectedItemPosition + 1
                val from  = normalizeDigits(etFrom.text?.toString()).toIntOrNull() ?: 1
                var to    = normalizeDigits(etTo.text?.toString()).toIntOrNull() ?: from
                var times = normalizeDigits(etTimes.text?.toString()).toIntOrNull() ?: 3
                times = times.coerceIn(1, 99)

                if (to < from) to = from

                audioHelper.startRangeRepeat(
                    surah    = surah,
                    fromAyah = from,
                    toAyah   = to,
                    times    = times,
                    qariId   = currentQariId
                )
                d.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
    private var lastTapAt = 0L

    private fun toggleBars() {
        if (barsVisible) {
            showAyahOptions(false, force = true)
            setAllBarsVisible(false)
        } else {
            setAllBarsVisible(true, AUTO_HIDE_DELAY_MS)
        }
    }

    private fun safeToggleBars() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt < 180) return   // يمنع تنفيذ نقرتين متتاليتين سريعًا
        lastTapAt = now
        toggleBars()
    }

}
