package com.example.videodownloader

/**
 * 解析后的视频信息。
 *
 * - 抖音 / 快手 / 小红书：单 mp4 直链，只填 [videoUrl]
 * - B站 dash：音视频分离，[videoUrl] 填视频流，[audioUrl] 填音频流，
 *   下载后用 [BiliMuxer] 合成 mp4
 */
data class VideoInfo(
    val title: String,
    val author: String,
    val videoUrl: String,            // 视频直链（dash 时是视频 m4s）
    val coverUrl: String = "",       // 封面图
    val platform: String = "douyin",
    /** 是否为 dash 音视频分离流（B站高清） */
    val isDash: Boolean = false,
    /** dash 音频流直链（仅 isDash=true 时有效） */
    val audioUrl: String = "",
    /** 清晰度标签，如 "1080P 高清" / "480P" */
    val qualityLabel: String = ""
)
