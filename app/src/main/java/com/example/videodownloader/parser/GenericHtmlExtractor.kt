package com.example.videodownloader.parser

import com.example.videodownloader.VideoInfo
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 通用网页视频/图集提取器，移植自 ucmao/media-parser 的 utils/html_video_extractor.py。
 *
 * 适用场景：
 *  - 番茄小说 / 红果短剧 / 红果漫剧这类 SSR 页面（window._ROUTER_DATA / _SSR_DATA 内嵌直链）；
 *  - 没有专属 parser 的未知分享页，作为最后兜底。
 *
 * 提取优先级：
 *  1. 各类前端状态变量（__INITIAL_STATE__ / __NUXT__ / __NEXT_DATA__ / _ROUTER_DATA /
 *     _SSR_DATA / RENDER_DATA / __INITIAL_DATA__ / __DATA__ / __PRELOADED_STATE__ /
 *     __APOLLO_STATE__ 等）内嵌 JSON 里递归找 mp4 直链；
 *  2. meta 的 og:video / twitter:player:stream；
 *  3. <video src> / <source src>；
 *  4. 整页正则兜底 mp4；
 *  找不到视频时，若能提取到一组图片则按图文笔记返回。
 */
internal object GenericHtmlExtractor {

    private val STATE_MARKERS = listOf(
        "_ROUTER_DATA", "_SSR_DATA", "__INITIAL_STATE__", "__INITIAL_DATA__",
        "__NUXT__", "__NEXT_DATA__", "RENDER_DATA", "__PRELOADED_STATE__",
        "__APOLLO_STATE__", "__DATA__", "window.__data",
    )

    /** 明确的 mp4 直链（兼容 \/ 与 \u002F 转义，匹配时先反转义） */
    private val MP4_REGEX = Regex("""https?://[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)

    /** 广告 / 统计 / 静态占位资源黑名单，命中即跳过 */
    private val BLOCK_KEYS = listOf(
        "googletagmanager", "google-analytics", "doubleclick", "gtag/js", "analytics",
        "cnzz.com", "umeng", "hm.baidu", "sentry", "gtrace", ".js", ".css", ".ico",
        "favicon", "logo", "placeholder", "loading", "default", "avatar", "icon",
        "sprite", "blank", "sample", "demo", "advert", "banner", "pixel", "beacon",
        "stat?", "/stat/", "collect", "is_=1", "tbzt", "img.alicdn.com/tfs",
    )

    private val IMG_REGEX = Regex("""https?://[^\s"'<>\\]+\.(?:jpe?g|png|webp)[^\s"'<>\\]*""", RegexOption.IGNORE_CASE)

    fun extract(
        html: String,
        pageUrl: String,
        platform: String,
        referer: String = "",
    ): VideoInfo? {
        if (html.isBlank()) return null
        val unescaped = ParserSupport.unescape(html)

        val title = ParserSupport.meta(html, "og:title")
            ?.takeIf { it.isNotBlank() }
            ?: ParserSupport.htmlTitle(html)
            ?: "未知作品"
        val author = ParserSupport.meta(html, "og:site_name") ?: ""
        val cover = ParserSupport.meta(html, "og:image") ?: ""

        // 1) 前端状态 JSON 内递归找视频
        var videoUrl: String? = null
        for (marker in STATE_MARKERS) {
            val obj = ParserSupport.extractJsonAfter(html, marker) ?: continue
            videoUrl = findVideoInJson(obj)
            if (!videoUrl.isNullOrBlank()) break
        }

        // 2) meta og:video
        if (videoUrl.isNullOrBlank()) {
            videoUrl = listOf("og:video:url", "og:video", "twitter:player:stream")
                .firstNotNullOfOrNull { ParserSupport.meta(html, it) }
                ?.let { cleanMediaUrl(it) }
        }

        // 3) <video>/<source>
        if (videoUrl.isNullOrBlank()) {
            videoUrl = ParserSupport.tagVideoSrc(html)?.let { cleanMediaUrl(it) }
        }

        // 4) 整页 mp4 正则
        if (videoUrl.isNullOrBlank()) {
            videoUrl = MP4_REGEX.findAll(unescaped)
                .map { cleanMediaUrl(it.value) }
                .firstOrNull { it != null && !isBlocked(it) }
        }

        if (!videoUrl.isNullOrBlank() && !isBlocked(videoUrl)) {
            return VideoInfo(
                title = title,
                author = author.ifBlank { "未知作者" },
                videoUrl = videoUrl,
                coverUrl = cover,
                platform = platform,
            )
        }

        // 5) 没有视频则尝试图集
        val images = LinkedHashSet<String>()
        for (marker in STATE_MARKERS) {
            val obj = ParserSupport.extractJsonAfter(html, marker) ?: continue
            collectImagesInJson(obj, images)
            if (images.size >= 9) break
        }
        if (images.isEmpty()) {
            IMG_REGEX.findAll(unescaped).forEach { m ->
                val u = cleanMediaUrl(m.value) ?: return@forEach
                if (!isBlocked(u)) images.add(u)
            }
        }
        val coverIfAny = cover.ifBlank { images.firstOrNull() ?: "" }
        val list = images.toList().take(12)
        if (list.isNotEmpty()) {
            return VideoInfo(
                title = title,
                author = author.ifBlank { "未知作者" },
                videoUrl = "",
                coverUrl = coverIfAny,
                platform = platform,
                isImage = true,
                imageUrls = list,
            )
        }
        return null
    }

    /** 深度优先在 JSON 中找第一个未被拉黑的 mp4 直链 */
    private fun findVideoInJson(el: JsonElement?): String? {
        when {
            el == null -> return null
            el.isJsonPrimitive && !el.isJsonNull -> {
                val raw = ParserSupport.unescape(el.asString)
                val m = MP4_REGEX.find(raw) ?: return null
                val u = cleanMediaUrl(m.value) ?: return null
                return if (isBlocked(u)) null else u
            }
            el.isJsonObject -> for ((_, v) in el.asJsonObject.entrySet()) {
                findVideoInJson(v)?.let { return it }
            }
            el.isJsonArray -> for (c in el.asJsonArray) {
                findVideoInJson(c)?.let { return it }
            }
        }
        return null
    }

    private fun collectImagesInJson(el: JsonElement?, out: LinkedHashSet<String>) {
        when {
            el == null -> {}
            el.isJsonPrimitive && !el.isJsonNull -> {
                val raw = ParserSupport.unescape(el.asString)
                val m = IMG_REGEX.find(raw)
                if (m != null) {
                    val u = cleanMediaUrl(m.value)
                    if (u != null && !isBlocked(u)) out.add(u)
                }
            }
            el.isJsonObject -> el.asJsonObject.entrySet().forEach { collectImagesInJson(it.value, out) }
            el.isJsonArray -> el.asJsonArray.forEach { collectImagesInJson(it, out) }
        }
    }

    private fun cleanMediaUrl(u: String): String? {
        var s = ParserSupport.unescape(u).trim().trimEnd('\\', '"', '\'', ')')
        if (s.startsWith("//")) s = "https:$s"
        return if (s.startsWith("http")) s else null
    }

    private fun isBlocked(u: String): Boolean {
        val l = u.lowercase()
        return BLOCK_KEYS.any { l.contains(it.lowercase()) }
    }
}
