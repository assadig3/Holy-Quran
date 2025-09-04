// File: app/src/main/java/com/hag/al_quran/BaseActivity.kt
package com.hag.al_quran

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.LocaleList
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import com.hag.al_quran.utils.FontScale
import java.util.Locale

open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        // 1) ضبط حجم الخط المخصص
        val withFont = FontScale.wrapContextWithFontScale(newBase)
        // 2) فرض اللغة المختارة من الإعدادات على الـ Context قبل أي inflate
        val withLocale = applyAppLocaleFromPrefs(withFont)
        super.attachBaseContext(withLocale)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // اسمح بالتمدد خلف شريطي الحالة والتنقل
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    /**
     * يقرأ كود اللغة من SharedPreferences (المفتاح "lang")
     * ويعيد Context مكيَّفًا عليها؛ إن لم تكن محددة يعيد السياق كما هو.
     */
    private fun applyAppLocaleFromPrefs(ctx: Context): Context {
        val prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("lang", null) ?: return ctx
        if (lang.isBlank()) return ctx

        val locale = Locale.forLanguageTag(lang)
        Locale.setDefault(locale)

        val baseConfig = ctx.resources.configuration
        val newConfig = Configuration(baseConfig)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            newConfig.setLocale(locale)
            newConfig.setLocales(LocaleList(locale))
        } else {
            @Suppress("DEPRECATION")
            newConfig.setLocale(locale)
        }
        return ctx.createConfigurationContext(newConfig)
    }

    /** الأعلى للـToolbar، والسفلي/الجانبين للجذر */
    protected fun applyEdgeToEdgeSplit(root: View, topBar: View? = null) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val nb = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.updatePadding(left = nb.left, right = nb.right, bottom = nb.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)

        topBar?.let { bar ->
            ViewCompat.setOnApplyWindowInsetsListener(bar) { v, insets ->
                val sb = insets.getInsets(WindowInsetsCompat.Type.statusBars())
                v.updatePadding(top = sb.top)
                insets
            }
            ViewCompat.requestApplyInsets(bar)
        }
    }

    /** يضيف padding سفلي يساوي navigation/IME */
    protected fun applyBottomInsets(target: View, includeIme: Boolean = true) {
        val baseBottom = target.paddingBottom
        val types = WindowInsetsCompat.Type.navigationBars() or
                (if (includeIme) WindowInsetsCompat.Type.ime() else 0)
        ViewCompat.setOnApplyWindowInsetsListener(target) { v, insets ->
            val ins = insets.getInsets(types)
            v.updatePadding(bottom = baseBottom + ins.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(target)
    }

    /** للقوائم: لا تقص آخر عنصر وأضف padding سفلي ديناميكي */
    protected fun applyBottomInsetsForList(rv: RecyclerView, includeIme: Boolean = true) {
        rv.clipToPadding = false
        applyBottomInsets(rv, includeIme)
    }
}
