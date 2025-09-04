// File: app/src/main/java/com/hag/al_quran/search/SearchResultAdapter.kt
package com.hag.al_quran.search

import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hag.al_quran.R
import java.text.NumberFormat
import java.util.Locale

class SearchResultAdapter(
    private val listener: Listener
) : ListAdapter<SearchResultItem, SearchResultAdapter.VH>(DIFF) {

    interface Listener { fun onResultClick(item: SearchResultItem) }

    private var query: String = ""
    private var exactTashkeel: Boolean = false

    fun setExactTashkeel(enabled: Boolean) { exactTashkeel = enabled }

    fun submitListWithQuery(items: List<SearchResultItem>, q: String) {
        query = q
        super.submitList(items) {
            if (itemCount > 0) notifyItemRangeChanged(0, itemCount, PAYLOAD_HIGHLIGHT)
        }
    }

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long {
        val it = getItem(position)
        return (it.surah.toLong() shl 20) or (it.ayah.toLong() and 0xFFFF)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_search_result, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bindFull(getItem(position), query, listener, position, exactTashkeel)
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads.any { it == PAYLOAD_HIGHLIGHT }) {
            val it = getItem(position)
            holder.bindHighlightOnly(it.text, query)
            holder.setTitleNumber(position + 1, it)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val title: TextView = v.findViewById(R.id.resultTitle)
        private val snippet: TextView = v.findViewById(R.id.resultSnippet)
        private val page: TextView = v.findViewById(R.id.resultPage)
        private var lastClickTs = 0L

        // تنسيق أرقام حسب Locale الحالي
        private fun fmt(n: Int): String =
            NumberFormat.getInstance(itemView.resources.configuration.locales[0]).format(n.toLong())

        // محاولة قراءة أسماء السور المترجمة من الموارد
        private fun localizedSurahName(surahNumber1Based: Int, fallback: String): String {
            val arr = runCatching {
                itemView.resources.getStringArray(R.array.surah_names)
            }.getOrNull()
            return arr?.getOrNull(surahNumber1Based - 1) ?: fallback
        }

        fun bindFull(item: SearchResultItem, query: String, listener: Listener, position: Int, exactTashkeel: Boolean) {
            val ctx = itemView.context
            val ayahLabel = ctx.getString(R.string.ayah)   // مثال: "节" بالصيني، "آية" بالعربي…
            val pageLabel = ctx.getString(R.string.page)   // مثال: "页面" بالصيني

            val surahDisplay = localizedSurahName(item.surah, item.surahName)

            title.text = "${fmt(position + 1)}. $surahDisplay • $ayahLabel ${fmt(item.ayah)}"
            title.textDirection = View.TEXT_DIRECTION_LOCALE
            snippet.textDirection = View.TEXT_DIRECTION_LOCALE

            bindHighlightOnly(item.text, query)

            page.text = if (item.page > 0) "$pageLabel ${fmt(item.page)}" else ""

            itemView.setOnClickListener {
                val now = SystemClock.uptimeMillis()
                if (now - lastClickTs < 350) return@setOnClickListener
                lastClickTs = now
                listener.onResultClick(item)
            }
        }

        fun setTitleNumber(number: Int, item: SearchResultItem) {
            val ctx = itemView.context
            val ayahLabel = ctx.getString(R.string.ayah)
            val surahDisplay = localizedSurahName(item.surah, item.surahName)
            title.text = "${fmt(number)}. $surahDisplay • $ayahLabel ${fmt(item.ayah)}"
        }

        fun bindHighlightOnly(text: String, query: String) {
            val span = SpannableString(text)
            if (query.isNotBlank()) {
                val ranges = SearchUtils.findWholeWordRangesWithPrefixes(text, query)
                for (r in ranges) {
                    val start = r.first
                    val endExclusive = r.last + 1
                    if (start in 0 until span.length && endExclusive in 1..span.length && start < endExclusive) {
                        span.setSpan(
                            BackgroundColorSpan(0x44FFC107.toInt()),
                            start, endExclusive,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                    }
                }
            }
            snippet.setText(span, TextView.BufferType.SPANNABLE)
            (snippet.text as? Spannable)?.setSpan(Object(), 0, 0, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    companion object {
        private const val PAYLOAD_HIGHLIGHT = "PAYLOAD_HIGHLIGHT"
        private val DIFF = object : DiffUtil.ItemCallback<SearchResultItem>() {
            override fun areItemsTheSame(o: SearchResultItem, n: SearchResultItem) =
                o.surah == n.surah && o.ayah == n.ayah
            override fun areContentsTheSame(o: SearchResultItem, n: SearchResultItem) = o == n
        }
    }
}
