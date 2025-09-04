// File: app/src/main/java/com/hag/al_quran/SettingsFragment.kt
package com.hag.al_quran

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.Toast
import android.widget.RadioGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.hag.al_quran.utils.FontScale

class SettingsFragment : Fragment() {
    private lateinit var prefs: SharedPreferences

    // مفاتيح أحجام الخط
    private val idxToKey = arrayOf("xs","small","medium","large","xl","xxl")
    private val keyToIdx = mapOf("xs" to 0, "small" to 1, "medium" to 2, "large" to 3, "xl" to 4, "xxl" to 5)

    // ✅ الحجم الافتراضي الجديد (يمكن تغييره إلى "xl" إذا رغبت بحجم أكبر)
    private val DEFAULT_FONT_KEY = "large"

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.fragment_toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)

        // عناصر موجودة في التخطيط الحالي
        val themeGroup = view.findViewById<RadioGroup>(R.id.themeGroup)
            ?: error("themeGroup not found in fragment_settings.xml")
        val fontSlider = view.findViewById<Slider>(R.id.fontSlider)
            ?: error("fontSlider not found in fragment_settings.xml")
        val resetBtn   = view.findViewById<Button>(R.id.resetOnboardingButton)
            ?: error("resetOnboardingButton not found in fragment_settings.xml")
        val qrImage    = view.findViewById<ImageView>(R.id.qrImage)

        // اضبط الثيم الحالي
        when (prefs.getString("theme", "system")) {
            "light" -> themeGroup.check(R.id.theme_light)
            "dark"  -> themeGroup.check(R.id.theme_dark)
            else    -> themeGroup.check(R.id.theme_system)
        }

        // ✅ تأكيد وجود قيمة افتراضية أكبر لأول تشغيل
        val savedKey = prefs.getString("font", null) ?: run {
            prefs.edit().putString("font", DEFAULT_FONT_KEY).apply()
            FontScale.saveChoice(requireContext(), DEFAULT_FONT_KEY)
            DEFAULT_FONT_KEY
        }

        // السلايدر -> القيمة المحفوظة (أو الافتراضي الجديد)
        val defaultIdx = keyToIdx[savedKey] ?: keyToIdx[DEFAULT_FONT_KEY] ?: 3
        fontSlider.value = defaultIdx.toFloat()

        // تبديل الثيم
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val theme = when (checkedId) {
                R.id.theme_light -> "light"
                R.id.theme_dark  -> "dark"
                else             -> "system"
            }
            prefs.edit().putString("theme", theme).apply()
            when (theme) {
                "light" -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                "dark"  -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                else    -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
        }

        // حفظ حجم الخط عند الإفلات
        fontSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val idx = slider.value.toInt().coerceIn(0, idxToKey.lastIndex)
                val key = idxToKey[idx]
                prefs.edit().putString("font", key).apply()
                FontScale.saveChoice(requireContext(), key)
                Toast.makeText(requireContext(), getString(R.string.font_changed), Toast.LENGTH_SHORT).show()
                requireActivity().recreate()
            }
        })

        // إعادة ضبط (مع استخدام الافتراضي الجديد)
        resetBtn.setOnClickListener {
            prefs.edit()
                .putString("theme", "system")
                .putString("font", DEFAULT_FONT_KEY) // ✅ يرجع إلى الافتراضي الأكبر
                .putBoolean("keep_screen_on", false)
                .apply()
            FontScale.saveChoice(requireContext(), DEFAULT_FONT_KEY) // ✅ تطبيقه فورًا
            themeGroup.check(R.id.theme_system)
            fontSlider.value = (keyToIdx[DEFAULT_FONT_KEY] ?: 3).toFloat()
            Toast.makeText(requireContext(), getString(R.string.settings_reset), Toast.LENGTH_SHORT).show()
            requireActivity().recreate()
        }

        // فتح شاشة QR (إن كانت موجودة)
        qrImage?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.main_content, QRFragment())
                .addToBackStack(null)
                .commit()
        }
    }
}
