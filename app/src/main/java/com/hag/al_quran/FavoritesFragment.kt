package com.hag.al_quran

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.*
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hag.al_quran.ui.ThemeManager
import com.hag.al_quran.utils.getAllFavoritePages
import com.hag.al_quran.utils.getRecentPages
import com.hag.al_quran.utils.removeFavoritePage
import java.util.*

class FavoritesFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: FavoritePagesAdapter
    private lateinit var recentListLayout: LinearLayout
    private lateinit var titleRecent: TextView
    private lateinit var titleFavorites: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_favorites, container, false)
        recyclerView = view.findViewById(R.id.recyclerViewFavorites)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        recentListLayout = view.findViewById(R.id.recentPagesList)
        titleRecent = view.findViewById(R.id.titleRecent)
        titleFavorites = view.findViewById(R.id.titleFavorites)

        adapter = FavoritePagesAdapter(
            mutableListOf(),
            onPageClick = { page ->
                val intent = Intent(requireContext(), QuranPageActivity::class.java)
                intent.putExtra("page_number", page)
                startActivity(intent)
            },
            onRemoveClick = { page ->
                removeFavoritePage(requireContext(), page)
                loadFavoritePages()
            }
        )
        recyclerView.adapter = adapter

        return view
    }

    override fun onResume() {
        super.onResume()
        showRecentPages()
        loadFavoritePages()
    }

    private fun loadFavoritePages() {
        val pages = getAllFavoritePages(requireContext()).toMutableList()
        adapter.updateList(pages)

        titleFavorites.visibility = if (pages.isEmpty()) View.GONE else View.VISIBLE

        val locale = requireContext().resources.configuration.locales[0]
        val noFav = if (locale.language == "ar") "لا توجد صفحات مفضّلة" else "No favorite pages"
        if (pages.isEmpty()) {
            Toast.makeText(requireContext(), noFav, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showRecentPages() {
        recentListLayout.removeAllViews()
        val lastPages = getRecentPages(requireContext())
        val inflater = LayoutInflater.from(requireContext())
        val locale = requireContext().resources.configuration.locales[0]
        val pageLabel = if (locale.language == "ar") "الصفحة" else "Page"
        val hintLabel = if (locale.language == "ar") "اضغط للانتقال إلى الصفحة" else "Tap to open the page"

        if (lastPages.isEmpty()) {
            titleRecent.visibility = View.GONE
        } else {
            titleRecent.visibility = View.VISIBLE
            for (page in lastPages) {
                val card = inflater.inflate(R.layout.item_favorite_page, recentListLayout, false)
                val title = card.findViewById<TextView>(R.id.bookmarkTitle)
                val hint = card.findViewById<TextView>(R.id.bookmarkHint)
                val deleteIcon = card.findViewById<ImageView>(R.id.deleteIcon)

                title.text = "🕓 $pageLabel $page"
                hint.text = hintLabel
                card.setOnClickListener {
                    val intent = Intent(requireContext(), QuranPageActivity::class.java)
                    intent.putExtra("page_number", page)
                    startActivity(intent)
                }
                deleteIcon.visibility = View.GONE
                recentListLayout.addView(card)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_favorites, menu)

        // تحديث لون النصوص
        val color = ContextCompat.getColor(requireContext(), R.color.menu_overflow_text)
        for (i in 0 until menu.size()) {
            val item = menu.getItem(i)
            val spanString = SpannableString(item.title)
            spanString.setSpan(ForegroundColorSpan(color), 0, spanString.length, 0)
            item.title = spanString
        }

        // ✅ تحديث زر التبديل (☀️ / 🌙)
        updateThemeToggleTitle(menu.findItem(R.id.action_toggle_theme))

        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        updateThemeToggleTitle(menu.findItem(R.id.action_toggle_theme))
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear_all_bookmarks -> {
                clearAllBookmarks()
                true
            }
            R.id.action_clear_all_recent -> {
                clearAllRecent()
                true
            }
            R.id.action_toggle_theme -> {
                val isNight = ThemeManager.toggle(requireContext())
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

    override fun onAttach(context: Context) {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("lang", "ar") ?: "ar"
        val locale = Locale(lang)
        val config = context.resources.configuration
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        super.onAttach(context)
    }

    private fun clearAllBookmarks() {
        val prefs = requireContext().getSharedPreferences("favorite_pages", Context.MODE_PRIVATE)
        prefs.edit().remove("pages").apply()
        loadFavoritePages()
        val locale = requireContext().resources.configuration.locales[0]
        val msg = if (locale.language == "ar") "تم حذف كل الصفحات المحفوظة" else "All favorite pages deleted"
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }

    private fun clearAllRecent() {
        val prefs = requireContext().getSharedPreferences("quran_prefs", Context.MODE_PRIVATE)
        prefs.edit().remove("recent_pages_str").apply()
        showRecentPages()
        val locale = requireContext().resources.configuration.locales[0]
        val msg = if (locale.language == "ar") "تم حذف سجل التصفح" else "Recent pages cleared"
        Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
    }
}
