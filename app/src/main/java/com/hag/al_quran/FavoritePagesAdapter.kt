package com.hag.al_quran

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FavoritePagesAdapter(
    private val pages: MutableList<Int>,
    private val onPageClick: (Int) -> Unit,
    private val onRemoveClick: (Int) -> Unit
) : RecyclerView.Adapter<FavoritePagesAdapter.PageViewHolder>() {

    inner class PageViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val pageNumberText: TextView = view.findViewById(R.id.bookmarkTitle)
        val bookmarkHint: TextView = view.findViewById(R.id.bookmarkHint)
        val deleteIcon: ImageView = view.findViewById(R.id.deleteIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_favorite_page, parent, false)
        return PageViewHolder(view)
    }

    override fun onBindViewHolder(holder: PageViewHolder, position: Int) {
        val page = pages[position]
        val context = holder.itemView.context

        // رقم الصفحة مع النجمة (المورد اللغوي)
        holder.pageNumberText.text = context.getString(R.string.favorite_page_item, page)
        // عبارة التلميح المتبدلة حسب اللغة
        holder.bookmarkHint.text = context.getString(R.string.tap_to_open_page)

        holder.itemView.setOnClickListener { onPageClick(page) }
        holder.deleteIcon.setOnClickListener {
            val pos = holder.adapterPosition
            if (pos != RecyclerView.NO_POSITION && pos < pages.size) {
                val removedPage = pages[pos]
                onRemoveClick(removedPage)
                // *** لا تحذف هنا من القائمة ولا تستخدم removeAt ***
                // الحذف سيتم في Activity/Fragment ثم ستحدث updateList()
            }
        }
    }
    fun updateList(newList: List<Int>) {
        pages.clear()
        pages.addAll(newList)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = pages.size
}
