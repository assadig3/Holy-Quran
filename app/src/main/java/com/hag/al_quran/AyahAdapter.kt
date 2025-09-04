package com.hag.al_quran

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.hag.al_quran.utils.addFavoriteAyah
import com.hag.al_quran.utils.isFavoriteAyah
import com.hag.al_quran.utils.removeFavoriteAyah

class AyahAdapter(
    private val surahNumber: Int,
    private val ayahList: List<Pair<Int, String>>, // (رقم الآية، نص الآية)
    private val onAyahClick: (ayahNumber: Int, ayahText: String) -> Unit
) : RecyclerView.Adapter<AyahAdapter.AyahViewHolder>() {

    // لتعقب الآية المحددة (مثلاً لتغيير لون الخلفية عند التحديد)
    var selectedAyah: Int? = null

    inner class AyahViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ayahText: TextView = view.findViewById(R.id.ayahText)
        val favIcon: ImageView = view.findViewById(R.id.favIcon)
        val rootLayout: View = view
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AyahViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_ayah, parent, false)
        return AyahViewHolder(view)
    }

    override fun onBindViewHolder(holder: AyahViewHolder, position: Int) {
        val (ayahNumber, ayahText) = ayahList[position]
        holder.ayahText.text = ayahText

        // تمييز الآية المحددة إن أحببت
        if (selectedAyah == ayahNumber) {
            holder.rootLayout.setBackgroundResource(R.drawable.bg_selected_ayah)
        } else {
            holder.rootLayout.setBackgroundResource(android.R.color.transparent)
        }

        // شكل النجمة حسب الحفظ
        if (isFavoriteAyah(holder.itemView.context, surahNumber, ayahNumber)) {
            holder.favIcon.setImageResource(R.drawable.ic_star_filled)
        } else {
            holder.favIcon.setImageResource(R.drawable.ic_star_border)
        }

        // الحفظ/الإزالة عند الضغط على النجمة
        holder.favIcon.setOnClickListener {
            val ctx = holder.itemView.context
            if (isFavoriteAyah(ctx, surahNumber, ayahNumber)) {
                removeFavoriteAyah(ctx, surahNumber, ayahNumber)
                holder.favIcon.setImageResource(R.drawable.ic_star_border)
            } else {
                addFavoriteAyah(ctx, surahNumber, ayahNumber)
                holder.favIcon.setImageResource(R.drawable.ic_star_filled)
            }
        }

        // الضغط على العنصر (للتفسير أو أي تفاعل آخر)
        holder.itemView.setOnClickListener {
            // غير مفضل حفظ selectedAyah في الذاكرة لو كان هناك إعادة تحميل كثيرة! هنا بسيط فقط
            selectedAyah = ayahNumber
            notifyDataSetChanged()
            onAyahClick(ayahNumber, ayahText)
        }
    }

    override fun getItemCount() = ayahList.size
}
