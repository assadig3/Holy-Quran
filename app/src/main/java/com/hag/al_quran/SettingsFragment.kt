// File: app/src/main/java/com/hag/al_quran/SettingsFragment.kt
package com.hag.al_quran

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.hag.al_quran.utils.FontScale

class SettingsFragment : Fragment() {

    private lateinit var prefs: SharedPreferences

    // ===== مفاتيح حجم الخط =====
    private val idxToKey = arrayOf("xs","small","medium","large","xl","xxl")
    private val keyToIdx = mapOf("xs" to 0, "small" to 1, "medium" to 2, "large" to 3, "xl" to 4, "xxl" to 5)
    private val DEFAULT_FONT_KEY = "large"

    // ===== مفاتيح التخزين =====
    private val KEY_STORAGE_MODE = "recitation_storage"          // "internal" | "external"
    private val KEY_STORAGE_TREE_URI = "recitation_tree_uri"     // Uri للمجلد الخارجي

    // عناصر الواجهة الخاصة بالتخزين
    private var storageGroup: RadioGroup? = null
    private var pickFolderBtn: Button? = null
    private var storageSummaryTv: TextView? = null

    // مُنتقي مجلد SAF
    private val pickDirectoryLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri != null) {
                try {
                    // خزن صلاحية دائمة (هام جداً للكتابة لاحقاً من خدمة/عامل)
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                    requireContext().contentResolver.takePersistableUriPermission(uri, flags)

                    // خزّن الـ Uri
                    prefs.edit().putString(KEY_STORAGE_TREE_URI, uri.toString()).apply()

                    // حدّث الملخص
                    updateStorageSummary()

                    Toast.makeText(requireContext(), "تم اختيار مجلد التنزيلات الخارجي بنجاح.", Toast.LENGTH_SHORT).show()
                } catch (e: SecurityException) {
                    Toast.makeText(requireContext(), "فشل حفظ صلاحية الوصول للمجلد.", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(requireContext(), "لم يتم اختيار مجلد.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? =
        inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.fragment_toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }

        prefs = requireContext().getSharedPreferences("settings", Context.MODE_PRIVATE)

        // عناصر موجودة مسبقاً
        val themeGroup = view.findViewById<RadioGroup>(R.id.themeGroup)
            ?: error("themeGroup not found in fragment_settings.xml")
        val fontSlider = view.findViewById<Slider>(R.id.fontSlider)
            ?: error("fontSlider not found in fragment_settings.xml")
        val resetBtn   = view.findViewById<Button>(R.id.resetOnboardingButton)
            ?: error("resetOnboardingButton not found in fragment_settings.xml")
        val qrImage    = view.findViewById<ImageView>(R.id.qrImage)

        // عناصر التخزين الجديدة
        storageGroup     = view.findViewById(R.id.storageGroup)
        pickFolderBtn    = view.findViewById(R.id.pickExternalFolderButton)
        storageSummaryTv = view.findViewById(R.id.storageSummary)

        // ===== ضبط الثيم =====
        when (prefs.getString("theme", "system")) {
            "light" -> themeGroup.check(R.id.theme_light)
            "dark"  -> themeGroup.check(R.id.theme_dark)
            else    -> themeGroup.check(R.id.theme_system)
        }
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

        // ===== حجم الخط (مع افتراضي أكبر) =====
        val savedKey = prefs.getString("font", null) ?: run {
            prefs.edit().putString("font", DEFAULT_FONT_KEY).apply()
            FontScale.saveChoice(requireContext(), DEFAULT_FONT_KEY)
            DEFAULT_FONT_KEY
        }
        val defaultIdx = keyToIdx[savedKey] ?: keyToIdx[DEFAULT_FONT_KEY] ?: 3
        fontSlider.value = defaultIdx.toFloat()
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

        // ===== إعادة ضبط =====
        resetBtn.setOnClickListener {
            prefs.edit()
                .putString("theme", "system")
                .putString("font", DEFAULT_FONT_KEY)
                .putBoolean("keep_screen_on", false)
                // إعادة التخزين إلى الداخلي بشكل آمن
                .putString(KEY_STORAGE_MODE, "internal")
                .remove(KEY_STORAGE_TREE_URI)
                .apply()
            FontScale.saveChoice(requireContext(), DEFAULT_FONT_KEY)
            themeGroup.check(R.id.theme_system)
            fontSlider.value = (keyToIdx[DEFAULT_FONT_KEY] ?: 3).toFloat()
            updateStorageUIVisibility()
            updateStorageSummary()
            Toast.makeText(requireContext(), getString(R.string.settings_reset), Toast.LENGTH_SHORT).show()
            requireActivity().recreate()
        }

        // ===== إعداد التخزين =====
        val currentStorage = prefs.getString(KEY_STORAGE_MODE, "internal") ?: "internal"
        when (currentStorage) {
            "external" -> storageGroup?.check(R.id.storage_external)
            else       -> storageGroup?.check(R.id.storage_internal)
        }

        storageGroup?.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.storage_external) "external" else "internal"
            prefs.edit().putString(KEY_STORAGE_MODE, mode).apply()
            updateStorageUIVisibility()
            updateStorageSummary()

            if (mode == "external") {
                // إن لم يكن هناك مجلد محفوظ مسبقاً، اطلب من المستخدم اختياره الآن
                val tree = prefs.getString(KEY_STORAGE_TREE_URI, null)
                if (tree.isNullOrEmpty()) {
                    openFolderPicker()
                }
            }
        }

        pickFolderBtn?.setOnClickListener {
            openFolderPicker()
        }

        updateStorageUIVisibility()
        updateStorageSummary()

        // ===== شاشة QR (إن وجدت) =====
        qrImage?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.main_content, QRFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    // إظهار/إخفاء زر اختيار المجلد حسب الوضع المختار
    private fun updateStorageUIVisibility() {
        val mode = prefs.getString(KEY_STORAGE_MODE, "internal") ?: "internal"
        val showExternalControls = mode == "external"
        pickFolderBtn?.visibility = if (showExternalControls) View.VISIBLE else View.GONE
    }

    // تحديث ملخص المكان الحالي
    private fun updateStorageSummary() {
        val mode = prefs.getString(KEY_STORAGE_MODE, "internal") ?: "internal"
        val sb = StringBuilder()
        if (mode == "internal") {
            sb.append("المكان الحالي: الذاكرة الداخلية للتطبيق (مجلد خاص).")
        } else {
            val treeStr = prefs.getString(KEY_STORAGE_TREE_URI, null)
            if (treeStr.isNullOrEmpty()) {
                sb.append("المكان الحالي: تخزين خارجي (لم يتم اختيار مجلد بعد).")
            } else {
                val uri = Uri.parse(treeStr)
                val name = getFolderDisplayName(uri) ?: "مجلد خارجي"
                sb.append("المكان الحالي: $name")
            }
        }
        storageSummaryTv?.text = sb.toString()
    }

    // فتح مُنتقي المجلد عبر SAF
    private fun openFolderPicker() {
        // تلميح: لا توجد صلاحيات RUN-TIME تقليدية هنا؛ SAF يُدير الوصول
        try {
            // starting dir hint على Android 11+ (اختياري)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                pickDirectoryLauncher.launch(null)
            } else {
                pickDirectoryLauncher.launch(null)
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "تعذّر فتح مُنتقي المجلد.", Toast.LENGTH_SHORT).show()
        }
    }

    // اسم مجلد للعرض
    private fun getFolderDisplayName(treeUri: Uri): String? {
        return try {
            val doc = DocumentFile.fromTreeUri(requireContext(), treeUri)
            doc?.name
        } catch (_: Exception) {
            null
        }
    }
}
