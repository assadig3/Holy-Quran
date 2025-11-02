// File: app/src/main/java/com/hag/al_quran/SettingsFragment.kt
package com.hag.al_quran

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.hag.al_quran.utils.FontScale

class SettingsFragment : Fragment() {

    // ------------------ الثوابت (مفاتيح التخزين + تفضيلات المستخدم) ------------------
    companion object {
        // مكان الحفظ: "internal" أو "external"
        const val KEY_STORAGE_MODE = "recitation_storage"

        // هذا هو الـ URI للمجلد الخارجي اللي يختاره المستخدم
        // (نفسه لازم يقرأه الـ Worker)
        const val PREF_TREE_URI = "pref_tree_uri"

        // مفتاح حجم الخط
        private const val PREF_FONT = "font"

        // المفتاح اللي نستخدمه لعدم إطفاء الشاشة (موجود عندك في الإعدادات)
        private const val PREF_KEEP_SCREEN_ON = "keep_screen_on"

        // SharedPreferences file name
        private const val PREF_FILE = "settings"

        // الإعداد الافتراضي للخط
        private const val DEFAULT_FONT_KEY = "large"
    }

    // ------------------ عناصر الحالة ------------------
    private lateinit var prefs: SharedPreferences

    // للتحكم في اختيار المجلد الخارجي (SAF)
    private lateinit var pickDirectoryLauncher: ActivityResultLauncher<Uri?>

    // للتحكم في واجهة التخزين
    private var storageGroup: RadioGroup? = null
    private var pickFolderBtn: Button? = null
    private var storageSummaryTv: TextView? = null

    // خريطـة حجم الخط
    private val idxToKey = arrayOf("xs", "small", "medium", "large", "xl", "xxl")
    private val keyToIdx = mapOf(
        "xs" to 0,
        "small" to 1,
        "medium" to 2,
        "large" to 3,
        "xl" to 4,
        "xxl" to 5
    )

    // ------------------ دورة الحياة: onCreate ------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // نجهز الـ SharedPreferences
        prefs = requireContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        // نجهز الـ launcher لاختيار مجلد خارجي عبر SAF
        pickDirectoryLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                if (uri == null) {
                    Toast.makeText(requireContext(), "لم يتم اختيار مجلد", Toast.LENGTH_SHORT).show()
                    return@registerForActivityResult
                }

                // جرّب التأكد/إنشاء مجلد recitations بداخل المجلد اللي اختاره المستخدم
                val rootDoc = DocumentFile.fromTreeUri(requireContext(), uri)
                val recitationsDir = ensureRecitationsDir(rootDoc)

                if (recitationsDir == null || !recitationsDir.canWrite()) {
                    Toast.makeText(
                        requireContext(),
                        "المجلد غير قابل للكتابة. اختر بطاقة SD أو مجلد له صلاحية كتابة.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@registerForActivityResult
                }

                // خزّن وضع التخزين كـ external
                // وخزّن الـ URI كسلسلة نصية
                prefs.edit()
                    .putString(KEY_STORAGE_MODE, "external")
                    .putString(PREF_TREE_URI, uri.toString())
                    .apply()

                // حدّث الشاشة
                updateStorageUIVisibility()
                updateStorageSummary()

                Toast.makeText(
                    requireContext(),
                    "تم اختيار مجلد خارجي بنجاح ✅",
                    Toast.LENGTH_SHORT
                ).show()
            }
    }

    // ------------------ دورة الحياة: onCreateView ------------------
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    // ------------------ دورة الحياة: onViewCreated ------------------
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ===== الشريط العلوي (رجوع) =====
        val toolbar = view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.fragment_toolbar)
        toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
        toolbar.setNavigationOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        // ===== عناصر الواجهة الأساسية =====
        val fontSlider = view.findViewById<Slider>(R.id.fontSlider)
            ?: error("fontSlider not found in fragment_settings.xml")
        val resetBtn = view.findViewById<Button>(R.id.resetOnboardingButton)
            ?: error("resetOnboardingButton not found in fragment_settings.xml")
        val qrImage = view.findViewById<ImageView>(R.id.qrImage)

        // ===== عناصر التخزين =====
        storageGroup = view.findViewById(R.id.storageGroup)
        pickFolderBtn = view.findViewById(R.id.pickExternalFolderButton)
        storageSummaryTv = view.findViewById(R.id.storageSummary)

        // ------------------ إعداد حجم الخط ------------------
        val savedFontKey = prefs.getString(PREF_FONT, null) ?: run {
            // أول مرة: نختار الحجم الافتراضي ونحفظه
            prefs.edit().putString(PREF_FONT, DEFAULT_FONT_KEY).apply()
            FontScale.saveChoice(requireContext(), DEFAULT_FONT_KEY)
            DEFAULT_FONT_KEY
        }

        val defaultIdx = keyToIdx[savedFontKey] ?: keyToIdx[DEFAULT_FONT_KEY] ?: 3
        fontSlider.value = defaultIdx.toFloat()

        fontSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val idx = slider.value.toInt().coerceIn(0, idxToKey.lastIndex)
                val key = idxToKey[idx]

                prefs.edit()
                    .putString(PREF_FONT, key)
                    .apply()

                FontScale.saveChoice(requireContext(), key)

                Toast.makeText(
                    requireContext(),
                    getString(R.string.font_changed),
                    Toast.LENGTH_SHORT
                ).show()

                // إعادة تحميل الـ Activity عشان يتطبق الحجم الجديد
                requireActivity().recreate()
            }
        })

        // ------------------ زر إعادة الضبط ------------------
        resetBtn.setOnClickListener {
            prefs.edit()
                .putString(PREF_FONT, DEFAULT_FONT_KEY)
                .putBoolean(PREF_KEEP_SCREEN_ON, false)
                .putString(KEY_STORAGE_MODE, "internal")
                .remove(PREF_TREE_URI)
                .apply()

            FontScale.saveChoice(requireContext(), DEFAULT_FONT_KEY)

            fontSlider.value = (keyToIdx[DEFAULT_FONT_KEY] ?: 3).toFloat()

            updateStorageUIVisibility()
            updateStorageSummary()

            Toast.makeText(
                requireContext(),
                getString(R.string.settings_reset),
                Toast.LENGTH_SHORT
            ).show()

            requireActivity().recreate()
        }

        // ------------------ إعداد وضع التخزين (داخلي / خارجي) ------------------
        val currentStorage = prefs.getString(KEY_STORAGE_MODE, "internal") ?: "internal"
        when (currentStorage) {
            "external" -> storageGroup?.check(R.id.storage_external)
            else       -> storageGroup?.check(R.id.storage_internal)
        }

        storageGroup?.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.storage_external) "external" else "internal"

            prefs.edit()
                .putString(KEY_STORAGE_MODE, mode)
                .apply()

            updateStorageUIVisibility()
            updateStorageSummary()

            if (mode == "external") {
                val treeStr = prefs.getString(PREF_TREE_URI, null)
                // لو لسه ما اخترنا مجلد خارجي، افتح اختيار المجلد الآن
                if (treeStr.isNullOrEmpty()) {
                    openFolderPicker()
                }
            }
        }

        // زر اختيار المجلد الخارجي يدوياً
        pickFolderBtn?.setOnClickListener {
            openFolderPicker()
        }

        // حدّث النصوص/الظهور أول مرة
        updateStorageUIVisibility()
        updateStorageSummary()

        // ------------------ الانتقال لواجهة QR (لو عندك QRFragment) ------------------
        qrImage?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.main_content, QRFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    // =====================================================================
    // دوال مساعدة للتخزين الخارجي
    // =====================================================================

    // استدعاء منتقي المجلد (بدون أعلام/فلاغز معقدة تسبب كراش)
    private fun openFolderPicker() {
        try {
            pickDirectoryLauncher.launch(null)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(
                requireContext(),
                "تعذّر فتح مُنتقي المجلد.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // إظهار/إخفاء زر اختيار المجلد بناءً على الوضع
    private fun updateStorageUIVisibility() {
        val mode = prefs.getString(KEY_STORAGE_MODE, "internal") ?: "internal"
        val showExternalControls = (mode == "external")
        pickFolderBtn?.visibility = if (showExternalControls) View.VISIBLE else View.GONE
    }

    // نص الملخص أسفل الإعدادات لتوضيح مكان الحفظ الحالي
    private fun updateStorageSummary() {
        val mode = prefs.getString(KEY_STORAGE_MODE, "internal") ?: "internal"
        val summaryText = if (mode == "internal") {
            "المكان الحالي: الذاكرة الداخلية للتطبيق (مجلد خاص)."
        } else {
            val treeStr = prefs.getString(PREF_TREE_URI, null)
            if (treeStr.isNullOrEmpty()) {
                "المكان الحالي: تخزين خارجي (لم يتم اختيار مجلد بعد)."
            } else {
                val name = getFolderDisplayName(Uri.parse(treeStr)) ?: "مجلد خارجي"
                "المكان الحالي: $name"
            }
        }
        storageSummaryTv?.text = summaryText
    }

    // محاولة معرفة اسم المجلد الخارجي المختار لعرضه للمستخدم
    private fun getFolderDisplayName(treeUri: Uri): String? {
        return try {
            DocumentFile.fromTreeUri(requireContext(), treeUri)?.name
        } catch (_: Exception) {
            null
        }
    }

    // نتأكد وجود مجلد recitations داخل المجلد اللي يختاره المستخدم، وإذا مش موجود ننشئه
    // لاحقًا الـ Worker بيعمل مجلد لكل قارئ داخل recitations
    private fun ensureRecitationsDir(root: DocumentFile?): DocumentFile? {
        if (root == null || !root.isDirectory) return null

        // لو موجود مجلد recitations خلاص رجّعه
        root.listFiles()
            .firstOrNull { it.isDirectory && it.name == "recitations" }
            ?.let { return it }

        // لو غير موجود نحاول ننشئه
        return if (root.canWrite()) {
            root.createDirectory("recitations")
        } else {
            null
        }
    }
}
