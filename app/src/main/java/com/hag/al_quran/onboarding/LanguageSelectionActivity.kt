package com.hag.al_quran.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.hag.al_quran.BaseActivity
import com.hag.al_quran.MainActivity
import com.hag.al_quran.R

class LanguageSelectionActivity : BaseActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var btnContinue: MaterialButton
    private lateinit var adapter: LanguageAdapter

    private var selectedIndex = 0
    private var layoutManagerState: Parcelable? = null

    private val cameFromSettings by lazy {
        intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // لا تُظهر شاشة اختيار اللغة إن كان سبق اختيارها (إلا لو المستخدم جاء من الإعدادات)
        if (!cameFromSettings) {
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val savedLang = prefs.getString(KEY_LANG, null)
            val onboarded = prefs.getBoolean(KEY_ONBOARDED, false)
            if (!savedLang.isNullOrBlank() && onboarded) {
                startMainAndFinish()
                return
            }
        }

        setContentView(R.layout.activity_language_selection)

        // تلوين الأشرطة (اختياري)
        window.statusBarColor = ContextCompat.getColor(this, R.color.skyBlue)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
        window.navigationBarColor = ContextCompat.getColor(this, R.color.skyBlue)

        recycler = findViewById(R.id.recycler)
        btnContinue = findViewById(R.id.btnContinue)

        // رفع المحتوى فوق شريط التنقل / الكيبورد
        findViewById<View>(R.id.root_container)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val sys = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()
                )
                v.updatePadding(bottom = sys.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(root)
        }

        // جهّز العناصر + حدّد المختار الحالي
        val items = buildLanguagesWithDevice()
        selectedIndex = resolveInitialSelectionIndex(items)

        adapter = LanguageAdapter(
            items = items,
            initiallySelected = selectedIndex
        ) { _, pos -> selectedIndex = pos }

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnContinue.setOnClickListener {
            val index = selectedIndex.coerceIn(0, items.lastIndex)
            val chosen = items[index]
            // code == null => لغة الجهاز
            saveLangAndRestart(chosen.code)
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        // استرجاع حالة التمرير والاختيار (بعد التهيئة)
        if (savedInstanceState != null) {
            selectedIndex = savedInstanceState.getInt(STATE_SELECTED_INDEX, selectedIndex)
            layoutManagerState = savedInstanceState.getParcelable(STATE_LAYOUT_MANAGER)
            layoutManagerState?.let { lmState ->
                recycler.layoutManager?.onRestoreInstanceState(lmState)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_INDEX, selectedIndex)
        outState.putParcelable(STATE_LAYOUT_MANAGER, recycler.layoutManager?.onSaveInstanceState())
    }

    private fun handleBack() {
        if (cameFromSettings || !isTaskRoot) {
            finish()
            return
        }
        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    private fun saveLangAndRestart(langCode: String?) {
        // null => استخدم لغة الجهاز (System default)
        val locales = if (langCode.isNullOrBlank()) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(langCode)
        }

        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putString(KEY_LANG, langCode ?: "")
            putBoolean(KEY_ONBOARDED, true)
        }
        AppCompatDelegate.setApplicationLocales(locales)

        startActivity(Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
        finish()
    }

    private fun startMainAndFinish() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    /**
     * تُضيف خيار "استخدم لغة الجهاز" أعلى القائمة.
     * ملاحظة: لو رغبت في صيني مبسّط/تقليدي لاحقًا:
     *   - zh-Hans  // Chinese (Simplified)
     *   - zh-Hant  // Chinese (Traditional)
     */
    private fun buildLanguagesWithDevice(): List<LanguageItem> = listOf(
        LanguageItem(null.toString(), getString(R.string.device_language_option), "System Default", "🖥️"),
        LanguageItem("ar", "العربية",         "Arabic",     "🇸🇦"),
        LanguageItem("en", "English",          "English",    "🇺🇸"),
        LanguageItem("tr", "Türkçe",           "Turkish",    "🇹🇷"),
        LanguageItem("id", "Bahasa Indonesia", "Indonesian", "🇮🇩"),
        LanguageItem("ur", "اردو",             "Urdu",       "🇵🇰"),
        LanguageItem("fa", "فارسی",            "Persian",    "🇮🇷"),
        LanguageItem("bn", "বাংলা",            "Bengali",    "🇧🇩"),
        LanguageItem("ru", "Русский",          "Russian",    "🇷🇺"),
        LanguageItem("hi", "हिन्दी",           "Hindi",      "🇮🇳"),
        LanguageItem("es", "Español",          "Spanish",    "🇪🇸"),
        LanguageItem("fr", "Français",         "French",     "🇫🇷"),
        LanguageItem("de", "Deutsch",          "German",     "🇩🇪"),
        LanguageItem("zh", "中文",             "Chinese",    "🇨🇳"),
        LanguageItem("ja", "日本語",           "Japanese",   "🇯🇵"),
        LanguageItem("ko", "한국어",           "Korean",     "🇰🇷"),
        LanguageItem("pt", "Português",        "Portuguese", "🇵🇹"),
        LanguageItem("it", "Italiano",         "Italian",    "🇮🇹"),
        LanguageItem("sw", "Kiswahili",        "Swahili",    "🇹🇿"),
        LanguageItem("th", "ไทย",              "Thai",       "🇹🇭")
    )

    /**
     * يحدد السطر المختار أول مرة:
     * - إن كانت هناك لغة مُطبّقة عبر AppCompatDelegate يتم مطابقتها.
     * - وإلا يُستخدم ما في الـ prefs إن وجد.
     * - وإلا يكون الاختيار على "لغة الجهاز".
     */
    private fun resolveInitialSelectionIndex(items: List<LanguageItem>): Int {
        val applied = AppCompatDelegate.getApplicationLocales()
        val appliedTag = if (applied.isEmpty) null else applied.toLanguageTags().trim().ifEmpty { null }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_LANG, null)?.trim()?.ifEmpty { null }

        val target = appliedTag ?: saved  // أولوية للفعلي المطبق

        // null => لغة الجهاز => أوّل عنصر
        if (target == null) return 0

        val idx = items.indexOfFirst { it.code?.equals(target, ignoreCase = true) == true }
        return if (idx >= 0) idx else 0
    }

    companion object {
        const val EXTRA_FROM_SETTINGS = "from_settings"
        private const val PREFS = "settings"
        private const val KEY_LANG = "lang"
        private const val KEY_ONBOARDED = "onboarded"

        private const val STATE_SELECTED_INDEX = "state_selected_index"
        private const val STATE_LAYOUT_MANAGER = "state_layout_manager"
    }
}
