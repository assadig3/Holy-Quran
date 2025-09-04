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

        private const val REQ_POST_NOTIFS = 8807

        // مفتاح موحّد للقارئ
        const val KEY_QARI_ID = "pref_qari_id"

        const val PREF_REPEAT_AYAH = "pref_repeat_ayah_count"
        const val PREF_REPEAT_PAGE = "pref_repeat_page_count"
    }

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
        // تمرير الوضع الحالي لمساعد الصوت
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
    var hideRunnable: Runnable? = null

    // عرض الصفحات
    lateinit var adapter: AssetPageAdapter
    var lastPos: Int = -1

    // صوت بالخلفية
    private val audioBgThread = HandlerThread("quran-audio-bg").apply { start() }
    internal val audioBgHandler by lazy { Handler(audioBgThread.looper) }
    private val uiHandler by lazy { Handler(Looper.getMainLooper()) }
    private var prepareQueueRunnable: Runnable? = null

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
                    setAllBarsVisible(true, 3000)
                }
                ACT_PAUSE -> {
                    audioHelper.pausePagePlayback()
                    updateNotification(isPlaying = false)
                    setAllBarsVisible(true, 3000)
                }
                ACT_STOP -> {
                    audioHelper.pausePagePlayback()
                    NotificationManagerCompat.from(this@QuranPageActivity).cancel(NOTIF_ID)
                    setAllBarsVisible(false)
                }
            }
        }
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
                setAllBarsVisible(true, 3000)
            }
            ACT_PAUSE -> {
                audioHelper.pausePagePlayback()
                updateNotification(isPlaying = false)
                setAllBarsVisible(true, 3000)
            }
            ACT_STOP -> {
                audioHelper.pausePagePlayback()
                NotificationManagerCompat.from(this).cancel(NOTIF_ID)
                setAllBarsVisible(false)
            }
        }
    }

    // ============================ LIFECYCLE ============================
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quran_page)

        // إبقاء الشاشة مضاءة طوال فترة هذه الشاشة
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        WindowCompat.setDecorFitsSystemWindows(window, false)

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
        val btnTafsirMenu = findViewById<TextView>(R.id.btnTafsirMenu)
        btnDownloadTafsir = findViewById(R.id.btnDownloadTafsir)

        btnShareAyah      = findViewById(R.id.btnShareAyah)
        btnCopyAyah       = findViewById(R.id.btnCopyAyah)
        btnPlayAyah       = findViewById(R.id.btnPlayAyah)
        btnCloseAyahBar   = findViewById(R.id.btnCloseOptions)
        ayahPreview       = findViewById(R.id.ayahPreview)
        initMarquee(ayahPreview)
        ayahOptionsBar.visibility = View.GONE

        // تحميل حالة القارئ والعدادات
        currentQariId = prefs.getString(KEY_QARI_ID, currentQariId) ?: currentQariId
        repeatMode = loadRepeatMode()
        audioHelper.repeatCount = prefs.getInt(PREF_REPEAT_AYAH, 1).coerceIn(1, 99)
        audioHelper.pageRepeatCount = prefs.getInt(PREF_REPEAT_PAGE, 1).coerceIn(1, 99)
        updateRepeatIcon()

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
            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                v.setPadding(0, 0, 0, 0)
            } else {
                v.setPadding(0, 0, 0, navBottom)
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
        btnRepeat.setOnClickListener {
            repeatMode = when (repeatMode) {
                RepeatMode.OFF  -> RepeatMode.PAGE
                RepeatMode.PAGE -> RepeatMode.AYAH
                RepeatMode.AYAH -> RepeatMode.OFF
            }
            saveRepeatMode(repeatMode)
            updateRepeatIcon()
            setAllBarsVisible(true, 3000)
            val msg = when (repeatMode) {
                RepeatMode.OFF  -> getString(R.string.repeat_off)
                RepeatMode.PAGE -> getString(R.string.repeat_page)
                RepeatMode.AYAH -> getString(R.string.repeat_ayah)
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }
        btnRepeat.setOnLongClickListener {
            showRepeatCountDialog()
            setAllBarsVisible(true, 3000)
            true
        }

        // أزرار شريط الآية
        btnPlayAyah.setOnClickListener {
            if (audioHelper.isAyahPlaying) {
                audioHelper.stopSingleAyah(); updateNotification(isPlaying = false)
            } else {
                audioHelper.playSingleAyah(currentSurah, currentAyah, currentQariId)
                updateNotification(
                    isPlaying = true,
                    surah = currentSurah,
                    ayah  = currentAyah,
                    customText = ayahPreview?.text?.toString()
                )
            }
            showAyahOptions(true)
            setAllBarsVisible(true, 3000)
        }
        btnCopyAyah.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = ayahPreview?.text?.toString().orEmpty()
            cm.setPrimaryClip(ClipData.newPlainText("Ayah", text))
            Toast.makeText(this, "تم نسخ الآية!", Toast.LENGTH_SHORT).show()
            setAllBarsVisible(true, 3000)
        }
        btnShareAyah.setOnClickListener { supportHelper.shareCurrentAyah(currentSurah, currentAyah) }
        btnCloseAyahBar.setOnClickListener { showAyahOptions(false) }

        // اختيار القارئ (مفتاح موحّد KEY_QARI_ID)
        btnQari.text = provider.getQariById(currentQariId)?.name ?: "فارس عباد"
        btnQari.setOnClickListener {
            supportHelper.showQariPicker { qari ->
                // 1) حدّث المعرّف والاسم واحفظ في التفضيلات
                val oldWasPagePlaying = audioHelper.isPlaying
                val oldWasAyahPlaying = audioHelper.isAyahPlaying
                val page = currentPage
                val sura = currentSurah
                val ayah = currentAyah

                currentQariId = qari.id
                btnQari.text  = qari.name
                prefs.edit().putString(KEY_QARI_ID, currentQariId).apply()

                // 2) أوقف أي تشغيل قائم ونظّف الطابور والمشغّل
                audioHelper.stopAllPlaybackAndClearQueue()

                // 3) أعِد التحضير بالقارئ الجديد
                audioHelper.prepareAudioQueueForPage(page, currentQariId)

                // 4) لو كان يشغّل من قبل، أعِد التشغيل بالقارئ الجديد
                when {
                    oldWasPagePlaying -> {
                        audioHelper.startPagePlayback(page, currentQariId)
                        updateNotification(isPlaying = true)
                    }
                    oldWasAyahPlaying -> {
                        audioHelper.playSingleAyah(sura, ayah, currentQariId)
                        updateNotification(
                            isPlaying = true,
                            surah = sura,
                            ayah  = ayah,
                            customText = ayahPreview?.text?.toString()
                        )
                    }
                    else -> {
                        // إن لم يكن هناك تشغيل، فقط ضَمِن التحضير السريع
                        debouncePrepareQueue(page, immediate = true)
                    }
                }

                setAllBarsVisible(true, 2000)
            }
        }

        audioDownload.setOnClickListener {
            supportHelper.showDownloadScopeDialog(currentPage, currentSurah, currentQariId)
        }

        // زر التشغيل/الإيقاف
        btnPlayPause.setOnClickListener {
            setAllBarsVisible(true, 3000)
            if (audioHelper.isPlaying) {
                audioHelper.pausePagePlayback(); updateNotification(isPlaying = false)
            } else {
                val resumed = audioHelper.resumePagePlayback()
                if (!resumed) audioHelper.startPagePlayback(currentPage, currentQariId)
                updateNotification(isPlaying = true)
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
                showAyahOptions(true)
                setAllBarsVisible(true, 3000)
            },
            onImageTap = {
                if (barsVisible) setAllBarsVisible(false) else setAllBarsVisible(true, 3000)
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
                setAllBarsVisible(true, 2000)
            }
        })

        hideHandler = Handler(Looper.getMainLooper())
        hideRunnable = Runnable { setAllBarsVisible(false) }

        setAllBarsVisible(true, 3000)
        debouncePrepareQueue(currentPage, immediate = true)
        if (!arePagesCached()) startBulkPagesPrefetch(parallelism = 6)
    }


    override fun onUserInteraction() {
        super.onUserInteraction()
        if (!barsVisible) setAllBarsVisible(true, 3000)
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
        // القائمة أصبحت تحتوي فقط على زر حفظ الصفحة
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
        hideRunnable?.let { hideHandler?.removeCallbacks(it) }
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
        // قياسات أولية
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

        // إظهار مبدئي
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
    private fun setAllBarsVisible(visible: Boolean, autoHideMs: Int? = null) {
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

        // الشريط السفلي
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

        // إخفاء تلقائي
        hideRunnable?.let { r ->
            hideHandler?.removeCallbacks(r)
            autoHideMs?.let { ms ->
                if (!(audioHelper.isPlaying || audioHelper.isAyahPlaying)) {
                    hideHandler?.postDelayed(r, ms.toLong())
                }
            }
        }
    }

    private fun showAyahOptions(show: Boolean) {
        ayahOptionsBar.clearAnimation()
        ayahOptionsBar.alpha = 1f
        ayahOptionsBar.visibility = if (show) View.VISIBLE else View.GONE
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
        val done = AtomicInteger(0)

        fun updateUI(c: Int) {
            centerProgress.progress = c
            centerCount.text = "$c / $TOTAL_PAGES"
            val pct = ((c * 100f) / TOTAL_PAGES.toFloat()).toInt().coerceIn(0, 100)
            centerPercent.text = "  (${pct}%)"

            val elapsedSec = max(1L, ((SystemClock.elapsedRealtime() - startMs) / 1000f).roundToLong())
            val rate = c.toFloat() / elapsedSec.toFloat()
            val remaining = (TOTAL_PAGES - c).coerceAtLeast(0)
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
                        val model = if (page <= 3)
                            "file:///android_asset/pages/page_${page}.webp"
                        else
                            "https://cdn.jsdelivr.net/gh/assadig3/quran-pages@main/pages/page_${page}.webp"

                        com.bumptech.glide.Glide.with(this)
                            .downloadOnly()
                            .load(model)
                            .submit()
                            .get()
                    } catch (_: Exception) {
                    } finally {
                        val c = done.incrementAndGet()
                        runOnUiThread { updateUI(c) }
                    }
                }
            }

            Thread {
                pool.shutdown()
                try { pool.awaitTermination(45, TimeUnit.MINUTES) } catch (_: InterruptedException) {}
                runOnUiThread {
                    if (!isCancelled) {
                        setPagesCachedDone()
                        Toast.makeText(this, "اكتمل تنزيل صفحات المصحف", Toast.LENGTH_LONG).show()
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

    // ====================== نافذة عدّادات التكرار ======================
    private fun showRepeatCountDialog() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), 0)
        }

        fun label(t: String) = TextView(this).apply {
            text = t
            textSize = 16f
            setPadding(0, dpToPx(8), 0, dpToPx(4))
        }

        val ayahPicker = NumberPicker(this).apply {
            minValue = 1; maxValue = 99
            value = prefs.getInt(PREF_REPEAT_AYAH, audioHelper.repeatCount).coerceIn(1, 99)
            setFormatter { "$it ×" }
        }
        val pagePicker = NumberPicker(this).apply {
            minValue = 1; maxValue = 99
            value = prefs.getInt(PREF_REPEAT_PAGE, audioHelper.pageRepeatCount).coerceIn(1, 99)
            setFormatter { "$it ×" }
        }

        content.addView(label(getString(R.string.repeat_ayah)))
        content.addView(ayahPicker)
        content.addView(label(getString(R.string.repeat_page)))
        content.addView(pagePicker)

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.repeat_settings_title))
            .setView(content)
            .setPositiveButton(getString(R.string.ok)) { _, _ ->
                audioHelper.repeatCount = ayahPicker.value
                audioHelper.pageRepeatCount = pagePicker.value
                prefs.edit()
                    .putInt(PREF_REPEAT_AYAH, ayahPicker.value)
                    .putInt(PREF_REPEAT_PAGE, pagePicker.value)
                    .apply()

                if (repeatMode == RepeatMode.OFF) {
                    repeatMode = RepeatMode.AYAH
                    saveRepeatMode(repeatMode)
                    updateRepeatIcon()
                }
                Toast.makeText(
                    this,
                    "تكرار الآية: ${ayahPicker.value}× • تكرار الصفحة: ${pagePicker.value}×",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
}
