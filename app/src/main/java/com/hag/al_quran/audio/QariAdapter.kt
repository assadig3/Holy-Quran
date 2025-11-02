// File: app/src/main/java/com/hag/al_quran/audio/QariAdapter.kt
package com.hag.al_quran.audio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.hag.al_quran.R

/**
 * Adapter لعرض قائمة القرّاء داخل RecyclerView.
 * - يدعم النقر على العنصر عبر OnQariClickListener
 * - يدعم تمييز القارئ المحدد (selectedQariId)
 * - يحتوي على دالة filter(query) للبحث بالاسم
 */
class QariAdapter(
    private val listener: OnQariClickListener? = null
) : ListAdapter<Qari, QariAdapter.QariVH>(Diff) {

    interface OnQariClickListener {
        fun onQariClick(qari: Qari)
    }

    private var allItems: List<Qari> = emptyList()
    private var selectedQariId: String? = null

    init {
        setHasStableIds(true)
    }

    fun setItems(items: List<Qari>) {
        allItems = items
        submitList(items)
    }

    fun setSelectedQariId(id: String?) {
        selectedQariId = id
        // إعادة رسم القائمة لتحديث التمييز
        notifyDataSetChanged()
    }

    fun getSelectedQariId(): String? = selectedQariId

    /**
     * تصفية بالقيمة المدخلة (بحث باسم القارئ)
     */
    fun filter(query: String?) {
        val q = query?.trim()?.lowercase().orEmpty()
        if (q.isEmpty()) {
            submitList(allItems)
        } else {
            submitList(allItems.filter { it.name.lowercase().contains(q) })
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QariVH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_qari, parent, false)
        return QariVH(v)
    }

    override fun onBindViewHolder(holder: QariVH, position: Int) {
        holder.bind(getItem(position), selectedQariId, listener)
    }

    override fun getItemId(position: Int): Long {
        // جعل المعرّف ثابتًا لتحسين الأداء والأنيميشن
        return getItem(position).id.hashCode().toLong()
    }

    class QariVH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val card: MaterialCardView = itemView as MaterialCardView
        private val tvName: TextView = itemView.findViewById(R.id.qariName)

        fun bind(
            qari: Qari,
            selectedId: String?,
            listener: OnQariClickListener?
        ) {
            tvName.text = qari.name

            // تمييز العنصر المحدد عبر Stroke
            val isSelected = qari.id == selectedId
            val primary = MaterialColors.getColor(card, com.google.android.material.R.attr.colorPrimary)
            val surfaceVariant = MaterialColors.getColor(card, com.google.android.material.R.attr.colorOutline) // لون لطيف للحد الافتراضي

            card.strokeWidth = if (isSelected) dp(2) else dp(0)
            card.strokeColor = if (isSelected) primary else surfaceVariant

            card.setOnClickListener {
                listener?.onQariClick(qari)
            }
        }

        private fun dp(value: Int): Int =
            (value * itemView.resources.displayMetrics.density).toInt()
    }

    private object Diff : DiffUtil.ItemCallback<Qari>() {
        override fun areItemsTheSame(oldItem: Qari, newItem: Qari): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Qari, newItem: Qari): Boolean =
            oldItem == newItem
    }
}
