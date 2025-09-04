package com.hag.al_quran.Surah

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hag.al_quran.R
import com.hag.al_quran.surah.Surah

class SurahAdapter(
    private val onClick: (Surah) -> Unit
) : ListAdapter<Surah, SurahAdapter.SurahVH>(DIFF) {

    init { setHasStableIds(true) }
    override fun getItemId(position: Int): Long = getItem(position).number.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SurahVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_surah, parent, false)
        return SurahVH(v, onClick)
    }

    override fun onBindViewHolder(holder: SurahVH, position: Int) = holder.bind(getItem(position))

    fun updateList(items: List<Surah>) = submitList(items.toList())

    class SurahVH(
        itemView: View,
        private val onClick: (Surah) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val title: TextView = itemView.findViewById(R.id.surahTitle)
        private val number: TextView = itemView.findViewById(R.id.surahNumber)
        private val details: TextView = itemView.findViewById(R.id.surahDetails)

        fun bind(item: Surah) {
            val locale = itemView.resources.configuration.locales[0]
            val isArabic = locale.language == "ar"

            title.text = item.name
            number.text = if (isArabic) toArabicDigits(item.number) else item.number.toString()

            val typeText = if (isArabic) {
                when (item.type.trim().lowercase()) {
                    "makki", "meccan"   -> "مكية"
                    "madani", "medinan" -> "مدنية"
                    else -> item.type
                }
            } else {
                when (item.type.trim().lowercase()) {
                    "مكية", "makki"  -> "Meccan"
                    "مدنية", "madani" -> "Medinan"
                    else -> if (item.type.equals("Meccan", true) || item.type.equals("Medinan", true)) item.type else "Meccan"
                }
            }

            val versesText = if (isArabic)
                "عدد الآيات: ${toArabicDigits(item.ayahCount)}"
            else
                "${item.ayahCount} verses"

            val pageText = if (isArabic)
                "الصفحة: ${toArabicDigits(item.pageNumber)}"
            else
                "Page ${item.pageNumber}"

            details.text = if (isArabic) {
                "$typeText • $versesText • $pageText"
            } else {
                "${item.englishName} • $typeText • $versesText • $pageText"
            }

            itemView.setOnClickListener { onClick(item) }
        }

        private fun toArabicDigits(n: Int): String {
            val map = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
            return n.toString().map { map[it.digitToInt()] }.joinToString("")
        }
    }

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<Surah>() {
            override fun areItemsTheSame(oldItem: Surah, newItem: Surah) =
                oldItem.number == newItem.number
            override fun areContentsTheSame(oldItem: Surah, newItem: Surah) =
                oldItem == newItem
        }
    }
}
