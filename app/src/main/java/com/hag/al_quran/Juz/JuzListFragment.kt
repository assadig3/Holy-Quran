package com.hag.al_quran.Juz

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.R
import com.hag.al_quran.Surah.SurahAdapter
import com.hag.al_quran.Surah.SurahUtils
import com.hag.al_quran.ui.ThemeManager

class JuzListFragment : Fragment() {

    private lateinit var rvJuz: RecyclerView
    private lateinit var adapter: JuzAdapter
    private lateinit var juzList: MutableList<Juz>
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    // ======= القوائم =======
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_juz, menu)

        // تلوين نص عناصر القائمة
        val color = ContextCompat.getColor(requireContext(), R.color.menu_overflow_text)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val span = SpannableString(item.title)
            span.setSpan(ForegroundColorSpan(color), 0, span.length, 0)
            item.title = span
        }

        updateThemeToggleTitle(menu.findItem(R.id.action_toggle_theme))
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        updateThemeToggleTitle(menu.findItem(R.id.action_toggle_theme))
        super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_jump_to_juz   -> { showJuzPickerDialog(); true }
            R.id.action_jump_to_surah -> { showSurahPickerDialog(); true }
            R.id.nav_last_read -> {
                val lastJuz = getLastReadJuz()
                val page = getFirstPageOfJuz(lastJuz)
                startActivity(Intent(requireContext(), QuranPageActivity::class.java).apply {
                    putExtra("page_number", page)
                    putExtra("page", page)
                })
                true
            }
            R.id.action_toggle_theme -> {
                ThemeManager.toggle(requireContext())
                updateThemeToggleTitle(item)
                requireActivity().recreate()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateThemeToggleTitle(item: MenuItem?) {
        item ?: return
        val night = ThemeManager.isNight(requireContext())
        item.title = if (night) "☀️" else "🌙"
    }

    // ======= واجهة fragment =======
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_juz_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)

        // يدعم كلا الاسمين تحسبًا لنسخ قديمة من التخطيط
        rvJuz = view.findViewById(R.id.rvJuz) ?: view.findViewById(R.id.rvJuz)

        rvJuz.layoutManager = LinearLayoutManager(requireContext())
        rvJuz.setHasFixedSize(true)
        rvJuz.itemAnimator = null

        adapter = JuzAdapter { selectedJuz ->
            prefs.edit().putInt("last_juz", selectedJuz.number).apply()
            startActivity(Intent(requireContext(), QuranPageActivity::class.java).apply {
                putExtra("page_number", selectedJuz.pageNumber)
                putExtra("page", selectedJuz.pageNumber)
            })
        }
        rvJuz.adapter = adapter

        juzList = getAllJuz()
        adapter.updateList(juzList)
    }

    override fun onDestroyView() {
        // تنظيف لمنع تسريبات
        rvJuz.adapter = null
        super.onDestroyView()
    }

    // ======= بيانات الأجزاء =======
    fun getLastReadJuz(): Int = prefs.getInt("last_juz", 30)

    fun getFirstPageOfJuz(juz: Int): Int {
        val pages = intArrayOf(
            1, 22, 42, 62, 82, 102, 121, 142, 162, 182,
            201, 222, 242, 262, 282, 302, 322, 342, 362, 382,
            402, 422, 442, 462, 482, 502, 522, 542, 562, 582
        )
        return pages.getOrElse(juz - 1) { 582 }
    }

    private fun getAllJuz(): MutableList<Juz> {
        val arabicNames = listOf(
            "الجزء الأول","الجزء الثاني","الجزء الثالث","الجزء الرابع","الجزء الخامس",
            "الجزء السادس","الجزء السابع","الجزء الثامن","الجزء التاسع","الجزء العاشر",
            "الجزء الحادي عشر","الجزء الثاني عشر","الجزء الثالث عشر","الجزء الرابع عشر","الجزء الخامس عشر",
            "الجزء السادس عشر","الجزء السابع عشر","الجزء الثامن عشر","الجزء التاسع عشر","الجزء العشرون",
            "الجزء الحادي والعشرون","الجزء الثاني والعشرون","الجزء الثالث والعشرون","الجزء الرابع والعشرون","الجزء الخامس والعشرون",
            "الجزء السادس والعشرون","الجزء السابع والعشرون","الجزء الثامن والعشرون","الجزء التاسع والعشرون","الجزء الثلاثون"
        )
        val englishNames = List(30) { i -> "Juz ${i + 1}" }
        val startPages = listOf(
            1, 22, 42, 62, 82, 102, 121, 142, 162, 182,
            201, 222, 242, 262, 282, 302, 322, 342, 362, 382,
            402, 422, 442, 462, 482, 502, 522, 542, 562, 582
        )
        return MutableList(30) { i ->
            Juz(
                number = i + 1,
                name = arabicNames[i],
                englishName = englishNames[i],
                pageNumber = startPages[i]
            )
        }
    }

    // ======= حوار اختيار جزء =======
    private fun showJuzPickerDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_jump_juz, null)

        val rv = dialogView.findViewById<RecyclerView>(R.id.jumpJuzRecyclerView)
        val searchField = dialogView.findViewById<EditText>(R.id.juzSearchField)
        val cancelButton = dialogView.findViewById<TextView>(R.id.btnCancelJuzDialog)

        val dialogAdapter = JuzAdapter { selectedJuz ->
            prefs.edit().putInt("last_juz", selectedJuz.number).apply()
            startActivity(Intent(requireContext(), QuranPageActivity::class.java).apply {
                putExtra("page_number", selectedJuz.pageNumber)
                putExtra("page", selectedJuz.pageNumber)
            })
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = dialogAdapter
        dialogAdapter.updateList(juzList)

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                val locale = resources.configuration.locales[0]
                val filtered = if (locale.language == "ar") {
                    juzList.filter { it.name.contains(q) || convertToArabicNumber(it.number).contains(q) }
                } else {
                    juzList.filter { it.englishName.contains(q, true) || it.number.toString().contains(q) }
                }
                dialogAdapter.updateList(filtered.toMutableList())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dlg = AlertDialog.Builder(requireContext()).setView(dialogView).create()
        cancelButton.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    // ======= حوار اختيار سورة =======
    private fun showSurahPickerDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_jump_surah, null)

        val rv = dialogView.findViewById<RecyclerView>(R.id.jumpSurahRecyclerView)
        val searchField = dialogView.findViewById<EditText>(R.id.surahSearchField)
        val cancelButton = dialogView.findViewById<TextView>(R.id.btnCancelSurahDialog)

        val surahs = SurahUtils.getAllSurahs(requireContext()).toMutableList()
        val surahAdapter = SurahAdapter { s ->
            startActivity(Intent(requireContext(), QuranPageActivity::class.java).apply {
                putExtra("page_number", s.pageNumber)
                putExtra("page", s.pageNumber)
            })
        }

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = surahAdapter
        surahAdapter.updateList(surahs)

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                val locale = resources.configuration.locales[0]
                val filtered = if (locale.language == "ar") {
                    surahs.filter { it.name.contains(q, true) }
                } else {
                    surahs.filter { it.englishName.contains(q, true) }
                }
                surahAdapter.updateList(filtered.toMutableList())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dlg = AlertDialog.Builder(requireContext()).setView(dialogView).create()
        cancelButton.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun convertToArabicNumber(number: Int): String {
        val map = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
        return number.toString().map { map[it.digitToInt()] }.joinToString("")
    }
}
