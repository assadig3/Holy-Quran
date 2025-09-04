package com.hag.al_quran.Juz

import android.annotation.SuppressLint
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

class JuzListFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: JuzAdapter
    private lateinit var juzList: MutableList<Juz>
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_juz, menu)
        val color = ContextCompat.getColor(requireContext(), R.color.menu_overflow_text)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val span = SpannableString(item.title)
            span.setSpan(ForegroundColorSpan(color), 0, span.length, 0)
            item.title = span
        }
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_jump_to_juz -> { showJuzPickerDialog(); true }
            R.id.action_jump_to_surah -> { showSurahPickerDialog(); true }
            R.id.nav_last_read -> {
                val lastJuz = getLastReadJuz()
                val lastJuzPage = getFirstPageOfJuz(lastJuz)
                val intent = Intent(requireContext(), QuranPageActivity::class.java)
                intent.putExtra("page_number", lastJuzPage)
                startActivity(intent)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun getLastReadJuz(): Int = prefs.getInt("last_juz", 30)

    fun getFirstPageOfJuz(juz: Int): Int {
        val juzPages = intArrayOf(
            1, 22, 42, 62, 82, 102, 121, 142, 162, 182,
            201, 222, 242, 262, 282, 302, 322, 342, 362, 382,
            402, 422, 442, 462, 482, 502, 522, 542, 562, 582
        )
        return juzPages.getOrElse(juz - 1) { 582 }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_juz_list, container, false)

        // SharedPreferences
        prefs = requireContext().getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)

        recyclerView = view.findViewById(R.id.juzRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        // أنشئ الـ adapter بلامبدا فقط
        adapter = JuzAdapter { selectedJuz: Juz ->
            // حفظ آخر جزء
            prefs.edit().putInt("last_juz", selectedJuz.number).apply()
            val intent = Intent(requireContext(), QuranPageActivity::class.java)
            intent.putExtra("page_number", selectedJuz.pageNumber)
            startActivity(intent)
        }
        recyclerView.adapter = adapter

        // حمّل القائمة ومررها للـ adapter
        juzList = getAllJuz()
        adapter.updateList(juzList)

        val searchField = view.findViewById<EditText?>(R.id.juzSearchField)
        searchField?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                val locale = requireContext().resources.configuration.locales[0]
                val filtered = if (locale.language == "ar") {
                    juzList.filter {
                        it.name.contains(query) || convertToArabicNumber(it.number).contains(query)
                    }
                } else {
                    juzList.filter {
                        it.englishName.contains(query, ignoreCase = true)
                                || it.number.toString().contains(query)
                    }
                }
                adapter.updateList(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        return view
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
        val englishNames = List(30) { i -> "Juz ${i + 1}" } // إصلاح النصوص الإنجليزية
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

    private fun showJuzPickerDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_jump_juz, null)

        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.jumpJuzRecyclerView)
        val searchField = dialogView.findViewById<EditText>(R.id.juzSearchField)
        val cancelButton = dialogView.findViewById<TextView>(R.id.btnCancelJuzDialog)

        val dialogAdapter = JuzAdapter { selectedJuz: Juz ->
            prefs.edit().putInt("last_juz", selectedJuz.number).apply()
            val intent = Intent(requireContext(), QuranPageActivity::class.java)
            intent.putExtra("page_number", selectedJuz.pageNumber)
            startActivity(intent)
        }
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = dialogAdapter
        dialogAdapter.updateList(juzList)

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                val locale = requireContext().resources.configuration.locales[0]
                val filtered = if (locale.language == "ar") {
                    juzList.filter { it.name.contains(query) || convertToArabicNumber(it.number).contains(query) }
                } else {
                    juzList.filter { it.englishName.contains(query, ignoreCase = true) || it.number.toString().contains(query) }
                }
                dialogAdapter.updateList(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSurahPickerDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_jump_surah, null)

        val recyclerView = dialogView.findViewById<RecyclerView>(R.id.jumpSurahRecyclerView)
        val searchField = dialogView.findViewById<EditText>(R.id.surahSearchField)
        val cancelButton = dialogView.findViewById<TextView>(R.id.btnCancelSurahDialog)

        val surahList = SurahUtils.getAllSurahs(requireContext()).toMutableList()
        val surahAdapter = SurahAdapter { selectedSurah ->
            val intent = Intent(requireContext(), QuranPageActivity::class.java)
            intent.putExtra("page_number", selectedSurah.pageNumber)
            startActivity(intent)
        }
        recyclerView.adapter = surahAdapter
        surahAdapter.updateList(surahList)

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = surahAdapter

        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s.toString()
                val locale = requireContext().resources.configuration.locales[0]
                val filtered = if (locale.language == "ar") {
                    surahList.filter { it.name.contains(q, ignoreCase = true) }
                } else {
                    surahList.filter { it.englishName.contains(q, ignoreCase = true) }
                }
                surahAdapter.updateList(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun convertToArabicNumber(number: Int): String {
        val arabicNums = arrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
        return number.toString().map { arabicNums[it.digitToInt()] }.joinToString("")
    }
}
