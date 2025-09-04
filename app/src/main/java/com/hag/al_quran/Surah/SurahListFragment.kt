package com.hag.al_quran.Surah

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hag.al_quran.Juz.Juz
import com.hag.al_quran.Juz.JuzAdapter
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.R
import com.hag.al_quran.surah.Surah

class SurahListFragment : Fragment() {

    private lateinit var recycler: RecyclerView
    private lateinit var adapter: SurahAdapter
    private var allSurahs: List<Surah> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val v = inflater.inflate(R.layout.fragment_surah_list, container, false)
        recycler = v.findViewById(R.id.surahRecyclerView)
        return v
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null

        adapter = SurahAdapter { surah ->
            val i = Intent(requireContext(), QuranPageActivity::class.java).apply {
                putExtra("page_number", surah.pageNumber)
                putExtra("page", surah.pageNumber)
                putExtra("surah_name", surah.name) // دائمًا الاسم المترجم
            }
            startActivity(i)
        }
        recycler.adapter = adapter

        // يحمّل قائمة مترجمة من SurahUtils (يستبدل name حسب لغة التطبيق)
        allSurahs = SurahUtils.getAllSurahs(requireContext())
        adapter.submitList(allSurahs)
    }

    // ======= Menu =======
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_sections, menu)
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
            R.id.action_jump_to_juz   -> { showJuzPickerDialog(); true }
            R.id.action_jump_to_surah -> { showSurahPickerDialog(); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun goToPage(page: Int) {
        startActivity(Intent(requireContext(), QuranPageActivity::class.java).apply {
            putExtra("page_number", page)
            putExtra("page", page)
        })
    }

    // ======= Dialogs =======

    private fun showSurahPickerDialog() {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_jump_surah, null)
        val recycler = view.findViewById<RecyclerView>(R.id.jumpSurahRecyclerView)
        val search   = view.findViewById<EditText>(R.id.surahSearchField)
        val cancel   = view.findViewById<TextView>(R.id.btnCancelSurahDialog)

        val dlg = AlertDialog.Builder(requireContext()).setView(view).create()

        val dialogAdapter = SurahAdapter { s ->
            goToPage(s.pageNumber)
            dlg.dismiss()
        }

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        recycler.adapter = dialogAdapter
        dialogAdapter.submitList(allSurahs)

        // البحث: اعتمد الاسم المعروض (name) لأي لغة، مع سقوط للإنجليزي
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val q = s?.toString()?.trim().orEmpty()
                val filtered =
                    if (q.isEmpty()) allSurahs
                    else allSurahs.filter {
                        it.name.contains(q, ignoreCase = true) ||
                                it.englishName.contains(q, ignoreCase = true)
                    }
                dialogAdapter.submitList(filtered)
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        cancel.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun showJuzPickerDialog() {
        val view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_jump_juz, null)
        val recycler = view.findViewById<RecyclerView>(R.id.jumpJuzRecyclerView)
        val search   = view.findViewById<EditText>(R.id.juzSearchField)
        val cancel   = view.findViewById<TextView>(R.id.btnCancelJuzDialog)

        // بيانات الأجزاء
        val startPages = listOf(
            1,22,42,62,82,102,121,142,162,182,
            201,222,242,262,282,302,322,342,362,382,
            402,422,442,462,482,502,522,542,562,582
        )
        val locale = requireContext().resources.configuration.locales[0]
        val namesAr = resources.getStringArray(R.array.juz_names)
        val namesEn = (1..30).map { "Juz $it" }
        val base = (1..30).map { i ->
            Juz(i, if (locale.language=="ar") namesAr[i-1] else namesEn[i-1], namesEn[i-1], startPages[i-1])
        }

        val dlg = AlertDialog.Builder(requireContext()).setView(view).create()

        val juzAdapter = JuzAdapter { j ->
            goToPage(j.pageNumber)
            dlg.dismiss()
        }

        recycler.layoutManager = LinearLayoutManager(requireContext())
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        recycler.adapter = juzAdapter
        juzAdapter.submitList(base)

        search.addTextChangedListener { e ->
            val q = e?.toString()?.trim().orEmpty()
            val filtered = if (q.isEmpty()) base else {
                if (locale.language == "ar")
                    base.filter { it.name.contains(q) || convertToArabicNumber(it.number).contains(q) }
                else
                    base.filter { it.englishName.contains(q, true) || it.number.toString().contains(q) }
            }
            juzAdapter.submitList(filtered)
        }

        cancel.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    private fun convertToArabicNumber(n: Int): String {
        val map = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
        return n.toString().map { map[it.digitToInt()] }.joinToString("")
    }
}
