package com.example.videodownloader

/**
 * 解析后的视频信息
 */
data class VideoInfo(
    val title: String,
    val author: String,
    val videoUrl: String,        // 无水印视频直链
    val coverUrl: String = "",   // 封面图
    val platform: String = "douyin"
)
