package com.example.epubspoon.ui

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.epubspoon.R
import com.google.android.material.button.MaterialButton

/**
 * 章节列表适配器：展示每章概要，点击复制整章内容
 */
class ChapterAdapter(
    private val onCopyClick: (index: Int, text: String) -> Unit
) : RecyclerView.Adapter<ChapterAdapter.ViewHolder>() {

    private var chapters: List<String> = emptyList()
    /** 记录最近被复制的章节，用于绿色反馈 */
    private var copiedIndex: Int = -1

    fun updateData(chapters: List<String>) {
        this.chapters = chapters
        notifyDataSetChanged()
    }

    /** 设置已复制高亮状态 */
    fun setCopiedIndex(index: Int) {
        val old = copiedIndex
        copiedIndex = index
        if (old >= 0) notifyItemChanged(old)
        if (index >= 0) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val text = chapters[position]
        val wordCount = text.split(Regex("\\s+")).size
        val preview = if (text.length > 120) text.substring(0, 120) + "…" else text

        holder.tvChapterIndex.text = "第 ${position + 1} 章"
        holder.tvChapterPreview.text = preview
        holder.tvChapterWordCount.text = "约 $wordCount 词"

        // 复制反馈：刚被复制的章节显示绿色背景
        if (position == copiedIndex) {
            holder.itemView.setBackgroundColor(Color.parseColor("#E8F5E9"))
            holder.btnCopy.text = "已复制"
        } else {
            holder.itemView.setBackgroundColor(Color.TRANSPARENT)
            holder.btnCopy.text = "复制"
        }

        holder.btnCopy.setOnClickListener { onCopyClick(position, text) }
        holder.itemView.setOnClickListener { onCopyClick(position, text) }
    }

    override fun getItemCount() = chapters.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvChapterIndex: TextView = view.findViewById(R.id.tvChapterIndex)
        val tvChapterPreview: TextView = view.findViewById(R.id.tvChapterPreview)
        val tvChapterWordCount: TextView = view.findViewById(R.id.tvChapterWordCount)
        val btnCopy: MaterialButton = view.findViewById(R.id.btnCopyChapter)
    }
}
