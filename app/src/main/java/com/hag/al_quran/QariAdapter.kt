package com.hag.al_quran

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class QariAdapter(
    private val qariList: List<Pair<String, String>>,
    private val onItemClick: (name: String, id: String) -> Unit
) : RecyclerView.Adapter<QariAdapter.QariViewHolder>() {

    inner class QariViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val qariName: TextView = itemView.findViewById(R.id.qariName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QariViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_qari, parent, false)
        return QariViewHolder(view)
    }

    override fun onBindViewHolder(holder: QariViewHolder, position: Int) {
        val (name, id) = qariList[position]
        holder.qariName.text = name

        holder.itemView.setOnClickListener {
            onItemClick(name, id)
        }
    }

    override fun getItemCount(): Int = qariList.size
}
