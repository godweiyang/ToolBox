package com.example.videodownloader

import android.content.Context
import android.content.Intent

/**
 * 工具百宝箱中的一个工具项。
 *
 * @param id 稳定唯一标识（用于拖拽排序持久化），建议用工具英文名
 * @param title 工具名称（显示在卡片上）
 * @param desc 工具描述
 * @param iconRes 图标资源 id（drawable）
 * @param bgColor 卡片背景色资源 id
 * @param launcher 启动该工具的方式：传入 [Context] 返回 [Intent]
 */
data class Tool(
    val id: String,
    val title: String,
    val desc: String,
    val iconRes: Int,
    val bgColorRes: Int,
    val launcher: (Context) -> Intent
)
