package com.hag.al_quran.Juz

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.hag.al_quran.R

/**
 * Adapter للأجزاء باستخدام ListAdapter + DiffUtil
 * يعتمد تخطيط item_juz بمعرّفات:
 *  - R.id.juzNumberText
 *  - R.id.juzNameText
 *  - R.id.juzHintText
 */
class JuzAdapter(
    private val onClick: (Juz) -> Unit
) : ListAdapter<Juz, JuzAdapter.JuzViewHolder>(DIFF) {

    init { setHasStableIds(true) }

    override fun getItemId(position: Int): Long = getItem(position).number.toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): JuzViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_juz, parent, false)
        return JuzViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: JuzViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** للتوافق مع أي كود قديم */
    fun updateList(newList: List<Juz>) = submitList(newList)

    class JuzViewHolder(
        itemView: View,
        private val onClick: (Juz) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {

        private val juzNumberText: TextView = itemView.findViewById(R.id.juzNumberText)
        private val juzNameText  : TextView = itemView.findViewById(R.id.juzNameText)
        private val juzHintText  : TextView = itemView.findViewById(R.id.juzHintText)

        private var current: Juz? = null

        init {
            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) current?.let(onClick)
            }
        }

        fun bind(juz: Juz) {
            current = juz
            val ctx = itemView.context
            val locale = ctx.resources.configuration.locales[0]
            val isAr = locale.language == "ar"

            // الاسم من الموارد إن وجد، وإلا fallback
            val names = try { ctx.resources.getStringArray(R.array.juz_names) } catch (_: Exception) { null }
            val displayName = names?.getOrNull(juz.number - 1) ?: (if (isAr) juz.name else "Juz ${juz.number}")
            juzNameText.text = displayName

            val numStr  = if (isAr) toArabicDigits(juz.number) else juz.number.toString()
            val pageStr = if (isAr) toArabicDigits(juz.pageNumber) else juz.pageNumber.toString()
            juzNumberText.text = numStr
            juzHintText.text = if (isAr) "بداية من الصفحة $pageStr" else "Starts at page $pageStr"
        }

        private fun toArabicDigits(n: Int): String {
            val map = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
            return n.toString().map { map[it.digitToInt()] }.joinToString("")
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<Juz>() {
            override fun areItemsTheSame(oldItem: Juz, newItem: Juz) =
                oldItem.number == newItem.number
            override fun areContentsTheSame(oldItem: Juz, newItem: Juz) =
                oldItem == newItem
        }
    }
}
