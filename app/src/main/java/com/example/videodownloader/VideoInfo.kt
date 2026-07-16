package com.example.videodownloader

/**
 * 解析后的视频/图片信息。
 *
 * - 抖音 / 快手 / 小红书：单 mp4 直链，只填 [videoUrl]
 * - 抖音图文笔记：无视频，只有图片，填 [isImage]=true 和 [imageUrls]
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
    val qualityLabel: String = "",
    /** 是否为图文笔记（无视频，只有图片） */
    val isImage: Boolean = false,
    /** 图文笔记的图片直链列表（仅 isImage=true 时有效） */
    val imageUrls: List<String> = emptyList(),
    /** 图文笔记的背景音乐 mp3 直链（用于合成视频） */
    val musicUrl: String = "",
    /** 图文笔记的音乐时长（秒），用于计算每张图显示时间 */
    val musicDuration: Int = 0
)
