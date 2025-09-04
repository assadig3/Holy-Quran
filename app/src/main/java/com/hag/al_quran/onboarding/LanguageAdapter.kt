package com.hag.al_quran.onboarding

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hag.al_quran.R

class LanguageAdapter(
    private val items: List<LanguageItem>,
    initiallySelected: Int = 0,
    private val onItemClick: (item: LanguageItem, position: Int) -> Unit
) : RecyclerView.Adapter<LanguageAdapter.VH>() {

    // ✅ دعم القوائم الفارغة + ضبط فهرس البداية بأمان
    private var selectedPos: Int =
        if (items.isNotEmpty()) initiallySelected.coerceIn(0, items.lastIndex) else -1

    init {
        setHasStableIds(true)
    }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        val title: TextView = v.findViewById(R.id.tvTitle)
        val subtitle: TextView = v.findViewById(R.id.tvSubtitle)
        val flagEmoji: TextView = v.findViewById(R.id.tvFlagEmoji)
        val flagImage: ImageView = v.findViewById(R.id.ivFlagImage)
        val radio: RadioButton = v.findViewById(R.id.rbSelect)

        init {
            v.setOnClickListener { trySelect(bindingAdapterPosition) }
            radio.setOnClickListener { trySelect(bindingAdapterPosition) }
        }
    }

    override fun getItemId(position: Int): Long {
        // معرّف ثابت لمساعدة RecyclerView على تحريك التغييرات بسلاسة
        return items[position].code.hashCode().toLong()
    }

    private fun trySelect(pos: Int) {
        if (pos == RecyclerView.NO_POSITION || pos !in items.indices) return

        // لو نفس العنصر، بلّغ المستمع فقط
        if (pos == selectedPos) {
            onItemClick(items[pos], pos)
            return
        }

        val old = selectedPos
        selectedPos = pos

        // 🎯 حدثي العنصر القديم إن كان صالحًا
        if (old in items.indices) notifyItemChanged(old)
        // 🎯 والحديث
        notifyItemChanged(pos)

        onItemClick(items[pos], pos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_language, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(h: VH, position: Int) {
        val it = items[position]

        // العنوان
        h.title.text = it.label

        // السطر الثاني: يظهر فقط عند اختلاف الاسم المحلي عن الظاهر
        val showNative = it.nativeName.isNotBlank() &&
                !it.nativeName.equals(it.label, ignoreCase = true)
        h.subtitle.text = it.nativeName
        h.subtitle.visibility = if (showNative) View.VISIBLE else View.GONE

        // العلم: Emoji أولًا، وإذا عندك مورد صورة استعمله
        if (it.flagRes != 0) {
            h.flagEmoji.visibility = View.GONE
            h.flagImage.visibility = View.VISIBLE
            h.flagImage.setImageResource(it.flagRes)
        } else {
            h.flagImage.visibility = View.GONE
            h.flagEmoji.visibility = View.VISIBLE
            h.flagEmoji.text = it.emojiFlag
        }

        // اختيار الراديو
        h.radio.isChecked = position == selectedPos
        // اختيارية: اجعل الراديو للعرض فقط وخلّ الكليك على السطر كله
        // h.radio.isClickable = false
        // h.radio.isFocusable = false
    }

    override fun getItemCount(): Int = items.size

    /** يعيد العنصر المختار، أو null إذا القائمة فارغة */
    fun getSelectedOrNull(): LanguageItem? = items.getOrNull(selectedPos)

    /** فهرس العنصر المختار أو -1 لو لا شيء */
    fun getSelectedIndex(): Int = selectedPos
}
