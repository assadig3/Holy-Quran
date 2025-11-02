// File: app/src/main/java/com/hag/al_quran/MainActivity.kt
package com.hag.al_quran

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.GravityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.core.app.TaskStackBuilder
import com.google.android.material.navigation.NavigationView
import com.google.android.material.tabs.TabLayout
import com.hag.al_quran.onboarding.LanguageSelectionActivity
import com.hag.al_quran.utils.FontScale
import com.google.firebase.FirebaseApp
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import com.hag.al_quran.Juz.JuzListFragment
import com.hag.al_quran.Surah.SurahListFragment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== In-App Updates (Play Core) =====
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.firebase.ktx.BuildConfig

class MainActivity : BaseActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navigationView: NavigationView
    private lateinit var toolbar: Toolbar
    private lateinit var appBar: View
    private lateinit var tabs: TabLayout

    // Remote Config
    private lateinit var remoteConfig: FirebaseRemoteConfig

    // ===== In-App Update =====
    private lateinit var appUpdateManager: AppUpdateManager
    private val UPDATE_REQUEST_CODE = 991

    // إشعارات التحديث
    private val UPDATE_CHANNEL_ID = "updates_channel"
    private val UPDATE_NOTIFICATION_ID = 202
    private val EXTRA_TRIGGER_UPDATE = "extra_trigger_update"

    // صلاحية التنبيهات (Android 13+)
    private val REQ_NOTIF_PERMISSION = 7331

    // منع التكرار / الـ Cooldown
    private val updatePrefs by lazy { getSharedPreferences("update_prefs", Context.MODE_PRIVATE) }
    private val KEY_LAST_PROMPTED = "last_prompted_version"
    private val KEY_LAST_DISMISSED = "last_dismissed_version"
    private val KEY_LAST_TIME = "last_prompt_time"
    private val COOLDOWN_HOURS_DEFAULT = 72

    // شريط التقدم السفلي للتحديث المرن
    private var updateBar: View? = null
    private var updateProgress: ProgressBar? = null
    private var updateText: TextView? = null

    // مستمع حالة التثبيت
    private val installStateUpdatedListener = InstallStateUpdatedListener { state ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADING -> {
                showUpdateBar()
                updateText?.text = getString(R.string.update_downloading)
                updateProgress?.visibility = View.VISIBLE
                updateProgress?.isIndeterminate = true
            }
            InstallStatus.DOWNLOADED -> {
                showUpdateBar()
                updateText?.text = getString(R.string.update_downloaded_tap_to_restart)
                updateProgress?.visibility = View.GONE
                updateBar?.setOnClickListener { completeFlexibleUpdate() }
            }
            InstallStatus.INSTALLING -> {
                showUpdateBar()
                updateText?.text = getString(R.string.update_installing)
                updateProgress?.visibility = View.VISIBLE
                updateProgress?.isIndeterminate = true
            }
            InstallStatus.INSTALLED -> hideUpdateBar()
            else -> Unit
        }
    }

    // ===== Launcher لشاشة اللغة =====
    private val languagePickerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                recreate()
                overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
            }
        }

    @SuppressLint("StringFormatInvalid")
    override fun onCreate(savedInstanceState: Bundle?) {
        // ✅ شاشة اختيار اللغة مرة واحدة
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedLang = prefs.getString("lang", null)
        if (savedLang.isNullOrBlank()) {
            startActivity(Intent(this, LanguageSelectionActivity::class.java))
            finish()
            return
        } else {
            val desired = LocaleListCompat.forLanguageTags(savedLang)
            if (AppCompatDelegate.getApplicationLocales() != desired) {
                AppCompatDelegate.setApplicationLocales(desired)
            }
        }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // ------ Firebase init ------
        try {
            if (FirebaseApp.getApps(this).isEmpty()) {
                FirebaseApp.initializeApp(this)
                android.util.Log.d("MainActivity", "FirebaseApp initialized in onCreate")
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Firebase initialize failed: ${e.message}")
        }
        // ---------------------------

        // مراجع الواجهة
        drawerLayout  = findViewById(R.id.drawer_layout)
        navigationView = findViewById(R.id.nav_view)
        toolbar       = findViewById(R.id.toolbar)
        appBar        = findViewById(R.id.appBar)
        tabs          = findViewById(R.id.topTabs)

        // Edge-to-Edge + توحيد ألوان الأشرطة
        setupEdgeToEdge()
        applyBarsPalette()   // <<< مهم

        // إبقاء الشاشة شغالة
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<View>(android.R.id.content)?.keepScreenOn = true

        // Toolbar + Drawer
        setSupportActionBar(toolbar)
        applyBarsPalette()

        // عنوان الشريط
        val onPrimary = ContextCompat.getColor(this, R.color.colorOnPrimary)
        toolbar.setTitleTextColor(onPrimary)

// أيقونة الرجوع + قائمة overflow
        toolbar.navigationIcon?.setTint(onPrimary)
        toolbar.overflowIcon?.setTint(onPrimary)

// لو عندك عناصر قائمة بآيقونات مضافة لاحقًا:
        toolbar.menu?.let { m ->
            for (i in 0 until m.size()) {
                m.getItem(i)?.icon?.setTint(onPrimary)
            }
        }

        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            R.string.navigation_drawer_open, R.string.navigation_drawer_close
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        // رأس القائمة الجانبية
        val header = navigationView.getHeaderView(0)
        header.findViewById<TextView>(R.id.header_developer).text =
            getString(R.string.header_developer_by, "Assadiq Hassan")
        header.findViewById<TextView>(R.id.gregorianDate).text = formatGregorianForAppLocale(this)
        header.findViewById<TextView>(R.id.hijriDate).text = formatHijriForAppLocale(this)

        // Tabs
        setupTabs(savedInstanceState)

        // عناصر NavigationView
        navigationView.setNavigationItemSelectedListener { menuItem ->
            val handled = when (menuItem.itemId) {
                R.id.home -> { tabs.getTabAt(0)?.select(); true }
                R.id.nav_search -> {
                    startActivity(Intent(this, com.hag.al_quran.search.SearchActivity::class.java)); true
                }
                R.id.settings -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.main_content, SettingsFragment())
                        .addToBackStack(null).commit(); true
                }
                R.id.help -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.main_content, HelpFragment())
                        .addToBackStack(null).commit(); true
                }
                R.id.about -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.main_content, AboutFragment())
                        .addToBackStack(null).commit(); true
                }
                R.id.share -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            getString(
                                R.string.share_message,
                                "https://play.google.com/store/apps/details?id=$packageName"
                            )
                        )
                    }
                    startActivity(Intent.createChooser(shareIntent, getString(R.string.share_chooser_title)))
                    true
                }
                R.id.share_qr -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.main_content, QRFragment())
                        .addToBackStack(null).commit(); true
                }
                R.id.rate -> { openAppInPlayStore(); true }
                R.id.other_apps -> {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://play.google.com/store/apps/developer?id=afagamro")
                    }
                    startActivity(intent)
                    true
                }
                // ✅ فتح شاشة اختيار اللغة
                R.id.nav_language -> {
                    val i = Intent(this, LanguageSelectionActivity::class.java)
                        .putExtra(LanguageSelectionActivity.EXTRA_FROM_DRAWER, true)
                    languagePickerLauncher.launch(i)
                    overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left)
                    true
                }
                R.id.action_exit -> { showExitDialog(); true }
                else -> false
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            handled
        }

        // رجوع
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // ===== In-App Update manager =====
        appUpdateManager = AppUpdateManagerFactory.create(this)
        appUpdateManager.registerListener(installStateUpdatedListener)

        // قناة إشعار + صلاحية
        ensureUpdateNotificationChannel()
        requestNotifPermissionIfNeeded()

        // Remote Config + التحقق من التحديث
        initRemoteConfigAndMaybeUpdate()

        // في حال فتح التطبيق من إشعار "تحديث الآن"
        if (intent?.getBooleanExtra(EXTRA_TRIGGER_UPDATE, false) == true) {
            triggerFlexibleUpdateIfAvailable()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_TRIGGER_UPDATE, false)) {
            triggerFlexibleUpdateIfAvailable()
        }
    }

    override fun onResume() {
        super.onResume()
        applyBarsPalette()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) {
                showUpdateBar()
                updateText?.text = getString(R.string.update_downloaded_tap_to_restart)
                updateProgress?.visibility = View.GONE
                updateBar?.setOnClickListener { completeFlexibleUpdate() }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { appUpdateManager.unregisterListener(installStateUpdatedListener) } catch (_: Exception) {}
    }

    // =============== Tabs ===============
    private fun setupTabs(savedInstanceState: Bundle?) {
        val t1 = getString(R.string.tab_surah)
        val t2 = getString(R.string.tab_juz)
        val t3 = getString(R.string.tab_fav)

        if (tabs.tabCount == 0) {
            tabs.addTab(tabs.newTab().setText(t1))
            tabs.addTab(tabs.newTab().setText(t2))
            tabs.addTab(tabs.newTab().setText(t3))
        }
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                when (tab.position) {
                    0 -> { switchTo(SurahListFragment()); supportActionBar?.title = getString(R.string.tab_surah) }
                    1 -> { switchTo(JuzListFragment());   supportActionBar?.title = getString(R.string.tab_juz) }
                    2 -> { switchTo(FavoritesFragment()); supportActionBar?.title = getString(R.string.tab_fav) }
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        if (savedInstanceState == null) {
            switchTo(SurahListFragment())
            tabs.getTabAt(0)?.select()
        }
    }

    private fun switchTo(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_content, fragment)
            .commit()
    }
    private fun setupEdgeToEdge() {
        // لا نجعل المحتوى تحت الأشرطة — أسهل لمطابقة الألوان
        WindowCompat.setDecorFitsSystemWindows(window, true)

        // لوّن أشرطة النظام بنفس لون الشريط العلوي (يفترض وجود قيم ليلية في resources)
        val bg = ContextCompat.getColor(this, R.color.topBarBg)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor     = bg
        window.navigationBarColor = bg

        // 🔥 أهم سطرين: نعكس بحسب الوضع الليلي
        val night = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night        // في الليل false (أيقونات فاتحة على خلفية داكنة)
            isAppearanceLightNavigationBars = !night
        }
    }
    private fun applyBarsPalette() {
        // هل النظام حالياً في الوضع الليلي؟
        val isNight = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        // الألوان من ملف resources المناسب (values أو values-night)
        val bg     = ContextCompat.getColor(this, R.color.topBarBg)
        val txt    = ContextCompat.getColor(this, R.color.tabTextDefaultStrong)
        val txtSel = ContextCompat.getColor(this, R.color.tabTextSelectedStrong)
        val ind    = ContextCompat.getColor(this, R.color.tabIndicatorStrong)

        // تطبيق الخلفيات
        toolbar.setBackgroundColor(bg)
        appBar.setBackgroundColor(bg)
        tabs.setBackgroundColor(bg)

        // لون عنوان التولبار
        toolbar.setTitleTextColor(txtSel)

        // إعداد ألوان التبويبات
        tabs.setSelectedTabIndicatorColor(ind)
        tabs.setTabTextColors(txt, txtSel)
        tabs.tabRippleColor = null

        // 🔥 تحديث أشرطة النظام لتناسب الوضع الليلي فوراً
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }

        // تحديث ألوان الأشرطة نفسها
        window.statusBarColor = bg
        window.navigationBarColor = bg
    }

    // =============== Locale Wrapping ===============
    override fun attachBaseContext(newBase: Context) {
        val withFontScale = FontScale.wrapContextWithFontScale(newBase)
        super.attachBaseContext(withFontScale)
    }

    private fun showExitDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.exit_confirm_title)
            .setMessage(
                getString(R.string.exit_confirm_message) + "\n\n" +
                        getString(R.string.please_rate_app)
            )
            .setPositiveButton(R.string.yes) { d, _ ->
                d.dismiss()
                finishAffinity()
            }
            .setNegativeButton(R.string.rate_app) { d, _ ->
                d.dismiss()
                openAppInPlayStore()
            }
            .setNeutralButton(R.string.cancel) { d, _ -> d.dismiss() }
            .show()
    }

    private fun openAppInPlayStore() {
        val appPackageName = packageName
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${appPackageName}"))
            intent.setPackage("com.android.vending")
            startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=${appPackageName}")
            )
            startActivity(intent)
        }
    }

    // =============== Locale Helpers ===============
    private fun appLocale(ctx: Context): Locale {
        val lang = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getString("lang", "ar") ?: "ar"
        return Locale(lang)
    }

    private fun formatGregorianForAppLocale(ctx: Context): String {
        val locale = appLocale(ctx)
        val pattern = "d MMMM yyyy"
        val raw = if (Build.VERSION.SDK_INT >= 26) {
            java.time.LocalDate.now()
                .format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
        } else {
            SimpleDateFormat(pattern, locale).format(Date())
        }
        return if (locale.language == "ar") toArabicDigits(raw) else raw
    }

    // ✅ التاريخ الهجري الفعلي (API 24+)
    private fun formatHijriForAppLocale(ctx: Context): String {
        val locale = appLocale(ctx)

        return if (Build.VERSION.SDK_INT >= 26) {
            val hijri = java.time.chrono.HijrahDate.now()
            val pattern = "d MMMM yyyy"
            val s = hijri.format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
            val withSuffix = if (locale.language == "ar") "$s هـ" else "$s AH"
            if (locale.language == "ar") toArabicDigits(withSuffix) else withSuffix
        } else {
            val cal = android.icu.util.IslamicCalendar()
            val sdf = android.icu.text.SimpleDateFormat("d MMMM y", locale).apply {
                calendar = cal
            }
            val s = sdf.format(java.util.Date())
            val withSuffix = if (locale.language == "ar") "$s هـ" else "$s AH"
            if (locale.language == "ar") toArabicDigits(withSuffix) else withSuffix
        }
    }

    private fun toArabicDigits(s: String): String {
        val western = charArrayOf('0','1','2','3','4','5','6','7','8','9')
        val arabic  = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
        val out = StringBuilder(s.length)
        for (ch in s) {
            val idx = western.indexOf(ch)
            out.append(if (idx >= 0) arabic[idx] else ch)
        }
        return out.toString()
    }
    private fun getHijriPlaceholderForNow(): String = getString(R.string.hijri_placeholder)

    // =============== Remote Config + In-App Update ===============
    private fun initRemoteConfigAndMaybeUpdate() {
        remoteConfig = Firebase.remoteConfig
        val settings = remoteConfigSettings {
            minimumFetchIntervalInSeconds = if (BuildConfig.DEBUG) 0 else 3600
        }
        remoteConfig.setConfigSettingsAsync(settings)

        val defaultUrl = "https://play.google.com/store/apps/details?id$packageName"
        val current = currentVersionCode()

        remoteConfig.setDefaultsAsync(
            mapOf(
                "latest_version_code" to current.toInt(),
                "min_supported_version_code" to current.toInt(),
                "update_message" to "",
                "update_url" to defaultUrl,
                "update_cooldown_hours" to COOLDOWN_HOURS_DEFAULT
            )
        )

        remoteConfig.fetchAndActivate()
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) return@addOnCompleteListener

                val latest = remoteConfig.getLong("latest_version_code")
                val minSupported = remoteConfig.getLong("min_supported_version_code")
                val cooldownHours = remoteConfig.getLong("update_cooldown_hours")
                    .takeIf { it > 0 }?.toInt() ?: COOLDOWN_HOURS_DEFAULT

                maybePromptInAppUpdate(current, latest, minSupported, cooldownHours)
            }
    }

    private fun currentVersionCode(): Long {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0).versionCode.toLong()
            }
        } catch (_: Exception) { 1L }
    }

    private fun maybePromptInAppUpdate(
        current: Long,
        latest: Long,
        minSupported: Long,
        cooldownHours: Int
    ) {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info: AppUpdateInfo ->
            val isUpdateAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
            if (!isUpdateAvailable) return@addOnSuccessListener

            val updateMsg = remoteConfig.getString("update_message").ifBlank {
                getString(R.string.update_available_message)
            }
            val updateUrl = remoteConfig.getString("update_url")
                .ifBlank { "https://play.google.com/store/apps/details?id=$packageName" }

            // إجباري فوري؟
            if (current < minSupported && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)) {
                appUpdateManager.startUpdateFlowForResult(
                    info, AppUpdateType.IMMEDIATE, this, UPDATE_REQUEST_CODE
                )
                return@addOnSuccessListener
            }

            // مرن مع فترة سماح
            if (current < latest &&
                info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) &&
                canPrompt(latest, cooldownHours)
            ) {
                markPrompted(latest)
                postUpdateAvailableNotification(latest, updateMsg, updateUrl)
                appUpdateManager.startUpdateFlowForResult(
                    info, AppUpdateType.FLEXIBLE, this, UPDATE_REQUEST_CODE
                )
            }
        }
    }

    // ===== Cooldown / No-Annoy Logic =====
    private fun canPrompt(latest: Long, cooldownHours: Int): Boolean {
        val p = updatePrefs
        val lastDismissed = p.getLong(KEY_LAST_DISMISSED, -1)
        if (lastDismissed == latest) return false
        val lastPrompted = p.getLong(KEY_LAST_PROMPTED, -1)
        val lastTime = p.getLong(KEY_LAST_TIME, 0)
        val now = System.currentTimeMillis()
        val cooldownMs = cooldownHours * 60L * 60L * 1000L

        return (lastPrompted != latest) || (now - lastTime >= cooldownMs)
    }

    private fun markPrompted(latest: Long) {
        updatePrefs.edit()
            .putLong(KEY_LAST_PROMPTED, latest)
            .putLong(KEY_LAST_TIME, System.currentTimeMillis())
            .apply()
    }

    private fun markDismissed(latest: Long) {
        updatePrefs.edit().putLong(KEY_LAST_DISMISSED, latest).apply()
    }

    // ===== مرئيات شريط تحديث مرن =====
    private fun showUpdateBar() {
        if (updateBar != null) {
            updateBar?.visibility = View.VISIBLE
            return
        }
        val root = findViewById<ViewGroup>(android.R.id.content)

        val container = FrameLayout(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.black_overlay_70))
            elevation = 8f
            setPadding(
                resources.getDimensionPixelSize(R.dimen._16dp),
                resources.getDimensionPixelSize(R.dimen._12dp),
                resources.getDimensionPixelSize(R.dimen._16dp),
                resources.getDimensionPixelSize(R.dimen._12dp)
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply {
                val margin = resources.getDimensionPixelSize(R.dimen.update_bar_margin_bottom)
                setMargins(
                    resources.getDimensionPixelSize(R.dimen._16dp),
                    0,
                    resources.getDimensionPixelSize(R.dimen._16dp),
                    margin
                )
                gravity = Gravity.BOTTOM
            }
        }

        val text = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, android.R.color.white))
            textSize = 14f
            text = getString(R.string.update_preparing)
        }
        val progress = ProgressBar(this).apply { isIndeterminate = true }

        val inner = FrameLayout(this).apply {
            addView(
                text,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            )
            addView(
                progress,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            )
        }

        container.addView(
            inner,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(container)
        updateBar = container
        updateText = text
        updateProgress = progress
    }

    private fun hideUpdateBar() { updateBar?.visibility = View.GONE }

    private fun completeFlexibleUpdate() { appUpdateManager.completeUpdate() }

    // ===== إشعارات التحديث =====
    private fun ensureUpdateNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.update_channel_name)
            val desc = getString(R.string.update_channel_desc)
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(UPDATE_CHANNEL_ID, name, importance).apply {
                description = desc
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQ_NOTIF_PERMISSION
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun postUpdateAvailableNotification(latestVersionCode: Long, message: String, updateUrl: String) {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_TRIGGER_UPDATE, true)
        }

        val contentPendingIntent: PendingIntent = TaskStackBuilder.create(this).run {
            addNextIntentWithParentStack(openAppIntent)
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            getPendingIntent(202, flags)!!
        }

        val openStoreIntent = Intent(this, UpdateNotifReceiver::class.java).apply {
            action = UpdateNotifReceiver.ACTION_OPEN_STORE
            putExtra(UpdateNotifReceiver.EXTRA_PACKAGE, packageName)
        }
        val openStorePI = PendingIntent.getBroadcast(
            this, 0, openStoreIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val dismissedIntent = Intent(this, UpdateNotifReceiver::class.java).apply {
            action = UpdateNotifReceiver.ACTION_DISMISSED
            putExtra(UpdateNotifReceiver.EXTRA_LATEST, latestVersionCode)
        }
        val dismissedPI = PendingIntent.getBroadcast(
            this, 1, dismissedIntent,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = getString(R.string.update_available_title)
        val text = if (message.isNotBlank()) message else getString(R.string.update_available_message)

        val builder = NotificationCompat.Builder(this, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .addAction(0, getString(R.string.action_open_store), openStorePI)
            .setDeleteIntent(dismissedPI)

        NotificationManagerCompat.from(this).notify(202, builder.build())
    }

    private fun triggerFlexibleUpdateIfAvailable() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            val isUpdateAvailable = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE
            if (isUpdateAvailable && info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)) {
                appUpdateManager.startUpdateFlowForResult(
                    info, AppUpdateType.FLEXIBLE, this, UPDATE_REQUEST_CODE
                )
            } else {
                // فتح المتجر كخطة بديلة
                openAppInPlayStore()
            }
        }
    }

    // Back API القديم
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Use onBackPressedDispatcher instead")
    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }
}
