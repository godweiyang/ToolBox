package com.example.videodownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.videodownloader.databinding.ItemToolBinding

/**
 * 工具列表适配器。
 * 点击卡片调用 [onToolClick]，传入被点击的 [Tool]。
 */
class ToolAdapter(
    private val tools: List<Tool>,
    private val onToolClick: (Tool) -> Unit
) : RecyclerView.Adapter<ToolAdapter.ToolVH>() {

    inner class ToolVH(val binding: ItemToolBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ToolVH {
        val binding = ItemToolBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ToolVH(binding)
    }

    override fun onBindViewHolder(holder: ToolVH, position: Int) {
        val tool = tools[position]
        with(holder.binding) {
            ivToolIcon.setImageResource(tool.iconRes)
            // 给图标加一个圆形彩色背景
            val ctx = root.context
            val tinted = androidx.appcompat.content.res.AppCompatResources.getDrawable(ctx, tool.iconRes)
            ivToolIcon.setImageDrawable(tinted)
            // 用工具的 bgColor 给 ImageView 着色为圆形背景
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(ctx.getColor(tool.bgColorRes))
            }
            ivToolIcon.background = bg
            // 图标本身用白色 tint
            ivToolIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            tvToolTitle.text = tool.title
            tvToolDesc.text = tool.desc
            root.setOnClickListener { onToolClick(tool) }
        }
    }

    override fun getItemCount(): Int = tools.size
}
