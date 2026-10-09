package com.example.videodownloader

import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
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
    private var presentation: GridPresentation = GridMetrics.presentation(GridMetrics.DEFAULT_COLUMNS)

    fun setPresentation(value: GridPresentation) {
        if (presentation == value) return
        presentation = value
        notifyItemRangeChanged(0, itemCount, PAYLOAD_PRESENTATION)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ToolVH {
        val binding = ItemToolBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ToolVH(binding)
    }

    override fun onBindViewHolder(holder: ToolVH, position: Int) {
        val tool = tools[position]
        with(holder.binding) {
            // iOS 风格：圆角矩形渐变色块 + 白色图标
            val ctx = root.context
            val tinted = androidx.appcompat.content.res.AppCompatResources.getDrawable(ctx, tool.iconRes)
            ivToolIcon.setImageDrawable(tinted)
            ivToolIcon.setBackgroundResource(tool.iconBgRes)
            ivToolIcon.clipToOutline = true
            ivToolIcon.imageTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
            tvToolTitle.text = tool.title
            tvToolDesc.text = tool.desc
            applyPresentation(this, presentation)
            root.contentDescription = if (presentation.showDescription)
                "${tool.title}，${tool.desc}" else tool.title
            root.setOnClickListener { onToolClick(tool) }
        }
    }

    private fun applyPresentation(binding: ItemToolBinding, value: GridPresentation) = with(binding) {
        val density = root.resources.displayMetrics.density
        fun dp(v: Int) = (v * density + .5f).toInt()
        (root.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.setMargins(dp(value.outerSpacingDp), dp(value.outerSpacingDp),
                dp(value.outerSpacingDp), dp(value.outerSpacingDp))
            root.layoutParams = it
        }
        val content = root.getChildAt(0)
        content.setPadding(dp(maxOf(7, value.outerSpacingDp + 6)), dp(value.verticalPaddingDp),
            dp(maxOf(7, value.outerSpacingDp + 6)), dp(value.verticalPaddingDp))
        ivToolIcon.layoutParams = ivToolIcon.layoutParams.apply {
            width = dp(value.iconDp); height = dp(value.iconDp)
        }
        ivToolIcon.setPadding(dp(value.iconPaddingDp), dp(value.iconPaddingDp),
            dp(value.iconPaddingDp), dp(value.iconPaddingDp))
        tvToolTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, value.titleSp)
        tvToolTitle.maxLines = value.titleMaxLines
        tvToolTitle.ellipsize = android.text.TextUtils.TruncateAt.END
        tvToolTitle.setPadding(0, 0, 0, 0)
        (tvToolTitle.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
            it.topMargin = dp(if (value.columns <= 2) 12 else 7)
            tvToolTitle.layoutParams = it
        }
        tvToolDesc.visibility = if (value.showDescription) View.VISIBLE else View.GONE
        tvToolDesc.maxLines = value.descriptionMaxLines.coerceAtLeast(1)
        tvToolDesc.ellipsize = android.text.TextUtils.TruncateAt.END
        tvToolDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (value.columns == 2) 12f else 10.5f)
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

    private companion object {
        const val PAYLOAD_PRESENTATION = "presentation"
    }
}
