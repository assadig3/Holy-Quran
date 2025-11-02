package com.hag.al_quran

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

class HomeTabsFragment : Fragment() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabLayout: TabLayout

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_home_tabs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewPager = view.findViewById(R.id.viewPager)
        tabLayout = view.findViewById(R.id.tabLayout)

        val adapter = HomePagerAdapter(this)
        viewPager.adapter = adapter

        // عناوين التبويبات
        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_surahs)
                1 -> getString(R.string.tab_juz)
                2 -> getString(R.string.tab_favorites)
                else -> "📚"
            }
        }.attach()

        // ✅ لا نضيف أي paddingBottom ديناميكي
        // ❗ نسمح للـ ViewPager2 بالتمدد خلف شريط النظام بدون قصّ آخر عنصر
        (viewPager.getChildAt(0) as? RecyclerView)?.clipToPadding = false
    }
}
