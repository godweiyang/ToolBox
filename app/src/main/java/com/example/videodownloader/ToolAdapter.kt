package com.example.videodownloader

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.videodownloader.databinding.ItemToolBinding

/**
 * 工具列表适配器。
 *
 * - 点击卡片调用 [onToolClick]，传入被点击的 [Tool]
 * - 支持长按拖拽排序：通过 [moveItem] 移动数据，配合外部 ItemTouchHelper
 * - 拖拽中的卡片会被放大 + 提升阴影，松手后还原
 */
class ToolAdapter(
    private val tools: MutableList<Tool>,
    private val onToolClick: (Tool) -> Unit
) : RecyclerView.Adapter<ToolAdapter.ToolVH>() {

    inner class ToolVH(val binding: ItemToolBinding) : RecyclerView.ViewHolder(binding.root) {
        init {
            // 长按触发拖拽
            binding.root.setOnLongClickListener {
                dragListener?.invoke(this)
                true
            }
        }
    }

    /** 拖拽回调，由 MainActivity 通过 ItemTouchHelper 实现 */
    fun setDragListener(l: (ToolVH) -> Unit) {
        dragListener = l
    }

    private var dragListener: ((ToolVH) -> Unit)? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ToolVH {
        val binding = ItemToolBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ToolVH(binding)
    }

    override fun onBindViewHolder(holder: ToolVH, position: Int) {
        val tool = tools[position]
        with(holder.binding) {
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

    /** 移动一个工具项（拖拽时调用） */
    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        if (from !in tools.indices || to !in tools.indices) return
        val item = tools.removeAt(from)
        tools.add(to, item)
        notifyItemMoved(from, to)
    }

    /** 获取当前顺序的工具 id 列表，用于持久化 */
    fun getOrderIds(): List<String> = tools.map { it.id }
}
