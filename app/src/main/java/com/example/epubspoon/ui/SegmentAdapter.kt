package com.example.epubspoon.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.epubspoon.R

class SegmentAdapter(
    private val onItemClick: (Int) -> Unit
) : RecyclerView.Adapter<SegmentAdapter.ViewHolder>() {

    private var segments: List<String> = emptyList()
    private var currentIndex: Int = 0

    fun updateData(segments: List<String>, currentIndex: Int) {
        this.segments = segments
        this.currentIndex = currentIndex
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_segment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val text = segments[position]
        val preview = if (text.length > 50) text.substring(0, 50) + "…" else text

        holder.tvIndex.text = "${position + 1}"
        holder.tvPreview.text = preview

        // 当前段高亮
        if (position == currentIndex) {
            holder.itemView.setBackgroundColor(Color.parseColor("#F0F0F0"))
            holder.tvIndex.setTextColor(Color.parseColor("#000000"))
            holder.tvPreview.setTextColor(Color.parseColor("#000000"))
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.tvIndex.setTextColor(Color.parseColor("#999999"))
            holder.tvPreview.setTextColor(Color.parseColor("#333333"))
        }

        holder.itemView.setOnClickListener { onItemClick(position) }
    }

    override fun getItemCount() = segments.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvIndex)
        val tvPreview: TextView = view.findViewById(R.id.tvPreview)
    }
}
