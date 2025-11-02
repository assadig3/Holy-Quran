// File: app/src/main/java/com/hag/al_quran/HomePagerAdapter.kt
package com.hag.al_quran

import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.hag.al_quran.Juz.JuzListFragment
import com.hag.al_quran.Surah.SurahListFragment

class HomePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 3

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> SurahListFragment()      // السور
            1 -> JuzListFragment()        // الأجزاء
            2 -> newInstanceOrEmpty("com.hag.al_quran.FavoritesFragment") // المفضلة (اختياري)
            else -> Fragment()
        }
    }

    // يحاول إنشاء المفضلة إن وُجدت، وإلا يرجع Fragment فارغ حتى لا يفشل البناء
    private fun newInstanceOrEmpty(className: String): Fragment =
        runCatching { Class.forName(className).newInstance() as Fragment }
            .getOrElse { Fragment() }
}
