package com.example.videodownloader.parser

import com.example.videodownloader.VideoInfo
import com.google.gson.JsonElement
import com.google.gson.JsonObject

// ============================================================================
// 微博：短链跟随 -> base62 mid 转数字 id -> m.weibo.cn / ajax 三端点兜底取 status
// ============================================================================
internal object WeiboParser : PlatformParser {
    private const val B62 = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val realUrl = ParserSupport.resolveRedirects(ctx.shareUrl, ParserSupport.UA_IPHONE, "https://weibo.com/")
        val id = extractNumericId(realUrl) ?: return null
        val status = fetchStatus(id, realUrl) ?: return null

        val video = findVideoUrl(status)
        val imgs = mutableListOf<String>()
        status.getAsJsonArray("pics")?.let { arr ->
            for (i in 0 until arr.size()) {
                val p = arr[i].asObj()
                val u = p?.obj("large")?.str("url") ?: p?.str("url")
                if (u != null) imgs.add(u)
            }
        }
        val pageInfo = status.obj("page_info")
        val cover = pageInfo?.str("page_pic")
            ?: pageInfo?.obj("media_info")?.let { it.str("cover") ?: it.str("cover_url") }
            ?: imgs.firstOrNull()
        val title = status.str("title")?.takeIf { it.isNotBlank() }
            ?: stripHtml(status.str("text_raw") ?: status.str("text"))
        val author = status.obj("user").str("screen_name")
        return makeVideoInfo(
            platform = "weibo", videoUrl = video, images = imgs,
            title = title, author = author, cover = cover,
        )
    }

    // ---- 视频地址：直播回放 > urls 对象 > media_info 各码率 > playback_list ----
    private fun findVideoUrl(st: JsonObject): String? {
        ParserSupport.deepObj(st, "live_media")?.let { lm ->
            lm.str("replay_origin_url")?.let { if (!ParserSupport.isM3u8(it)) return ParserSupport.unescape(it) }
        }
        listOf<JsonObject?>(st, st.obj("page_info")).forEach { holder ->
            val urls = holder?.obj("urls")
            if (urls != null) {
                for ((_, v) in urls.entrySet()) {
                    if (v.isJsonPrimitive && !v.isJsonNull) {
                        val u = v.asString
                        if (u.startsWith("http") && !ParserSupport.isM3u8(u)) return ParserSupport.unescape(u)
                    }
                }
            }
        }
        listOf(st.obj("page_info")?.obj("media_info"), st.obj("media_info")).forEach { mi ->
            if (mi != null) {
                for (k in listOf("mp4_hd_url", "mp4_sd_url", "stream_url_hd_url", "stream_url")) {
                    val u = mi.str(k)
                    if (u != null && !ParserSupport.isM3u8(u)) return ParserSupport.unescape(u)
                }
                mi.getAsJsonArray("playback_list")?.let { arr ->
                    for (i in 0 until arr.size()) {
                        val u = arr[i].asObj()?.obj("play_info")?.str("url")
                        if (u != null && !ParserSupport.isM3u8(u)) return ParserSupport.unescape(u)
                    }
                }
            }
        }
        return null
    }

    // ---- 取 status 对象，三条匿名路径逐级兜底 ----
    private fun fetchStatus(id: String, realUrl: String): JsonObject? {
        // 1. m.weibo.cn 移动接口（最常用，匿名可用）
        try {
            val r = ParserSupport.get(
                "https://m.weibo.cn/statuses/show?id=$id",
                ua = ParserSupport.UA_IPHONE, referer = "https://m.weibo.cn/detail/$id",
                extra = mapOf(
                    "MWeibo-Pwa" to "1",
                    "X-Requested-With" to "XMLHttpRequest",
                    "Accept" to "application/json, text/plain, */*",
                ),
            )
            val j = ParserSupport.parseJsonObject(r.body)
            if (j?.int("ok") == 1) j.obj("data")?.let { return it }
        } catch (_: Exception) {}
        // 2. m.weibo.cn/detail 页面内 $render_data
        try {
            val html = ParserSupport.get(
                "https://m.weibo.cn/detail/$id", ua = ParserSupport.UA_IPHONE, referer = "https://m.weibo.cn/"
            ).body
            val m = Regex("""${'$'}render_data\s*=\s*(\[[\s\S]*?\])\s*\[\s*0\s*\]\s*\|\|""").find(html)
            if (m != null) {
                val st = ParserSupport.parseJsonElement(m.groupValues[1])?.asArr()?.get(0)?.asObj()?.obj("status")
                if (st != null) return st
            }
        } catch (_: Exception) {}
        // 3. PC ajax 接口
        try {
            val r = ParserSupport.get(
                "https://weibo.com/ajax/statuses/show?id=$id",
                ua = ParserSupport.UA_PC, referer = realUrl,
                extra = mapOf("Accept" to "application/json, text/plain, */*"),
            )
            val j = ParserSupport.parseJsonObject(r.body)
            if (j != null && (j.has("idstr") || j.has("id"))) return j
        } catch (_: Exception) {}
        return null
    }

    // ---- 从各种形态的微博 URL 提取数字 mid（含 base62 短链转换）----
    private fun extractNumericId(url: String): String? {
        // 1034 视频 / 1022 直播：fid=1034:xxxx 或 /show/1034:xxxx
        Regex("""(?:fid=|/show/|wblive/p/show/)(1034|1022):([A-Za-z0-9]+)""").find(url)?.let {
            return it.groupValues[2]
        }
        // weibo.com/{uid}/{b62_or_num}
        Regex("""weibo\.(?:com|cn)/\d+/([A-Za-z0-9]+)""").find(url)?.let {
            val seg = it.groupValues[1]
            return if (seg.all { c -> c.isDigit() }) seg else midToId(seg)
        }
        // /status/xx /detail/xx /statuses/show?id=xx
        Regex("""(?:status|detail|statuses/show)(?:/|\?id=)([A-Za-z0-9]+)""").find(url)?.let {
            val seg = it.groupValues[1]
            return if (seg.all { c -> c.isDigit() }) seg else midToId(seg)
        }
        // 任意 id=xx
        Regex("""[?&]id=([A-Za-z0-9]+)""").find(url)?.let {
            val seg = it.groupValues[1]
            return if (seg.all { c -> c.isDigit() }) seg else midToId(seg)
        }
        // 兜底：路径里 9 位以上字母数字段（base62）
        Regex("""/([A-Za-z0-9]{9,})(?:\?|#|$)""").find(ParserSupport.path(url))?.let {
            val seg = it.groupValues[1]
            return if (seg.all { c -> c.isDigit() }) seg else midToId(seg)
        }
        return null
    }

    private fun base62Decode(s: String): Long {
        var res = 0L
        for (c in s) {
            val idx = B62.indexOf(c)
            if (idx < 0) throw NumberFormatException("非 base62 字符: $c")
            res = res * 62 + idx
        }
        return res
    }

    /** 微博 base62 mid -> 数字 id：每 4 字符一段，高位段补零到 7 位 */
    private fun midToId(mid: String): String {
        val rev = mid.reversed()
        val size = (rev.length + 3) / 4
        val parts = mutableListOf<String>()
        var i = 0
        while (i * 4 < rev.length) {
            val seg = rev.substring(i * 4, minOf((i + 1) * 4, rev.length)).reversed()
            var part = base62Decode(seg).toString()
            if (i != size - 1) part = part.padStart(7, '0')
            parts.add(part)
            i++
        }
        parts.reverse()
        // 等价 Python str(int(joined))：去掉前导 0
        return parts.joinToString("").trimStart('0').ifEmpty { "0" }
    }
}

// ============================================================================
// 知乎：answers / zvideos(videos) / pins / articles 四类内容，App API + 页面兜底
// ============================================================================
internal object ZhihuParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val realUrl = ParserSupport.resolveRedirects(ctx.shareUrl, ParserSupport.UA_IPHONE, "https://www.zhihu.com/")
        val (type, id) = matchContent(realUrl) ?: return null
        val endpoint = when (type) {
            "answer" -> "answers"; "zvideo" -> "videos"; "pin" -> "pins"; else -> "articles"
        }
        val accept = mapOf("Accept" to "application/json, text/plain, */*")
        var data: JsonObject? = null
        try {
            val body = ParserSupport.get(
                "https://api.zhihu.com/$endpoint/$id",
                ua = ParserSupport.UA_IPHONE, referer = "https://www.zhihu.com/", extra = accept
            ).body
            val root = ParserSupport.parseJsonObject(body)
            // 部分端点把内容包在 data 字段里，部分直接返回内容对象
            data = root?.obj("data") ?: root
        } catch (_: Exception) {}

        if (data != null) {
            parseFromApi(type, data)?.let { return it }
        }
        // 兜底：抓 www 页面交给通用提取器（__NEXT_DATA__ / og:video）
        return try {
            val html = ParserSupport.get(realUrl, ua = ParserSupport.UA_IPHONE, referer = "https://www.zhihu.com/").body
            GenericHtmlExtractor.extract(html, realUrl, "zhihu", "https://www.zhihu.com/")
        } catch (_: Exception) { null }
    }

    private fun parseFromApi(type: String, data: JsonObject): VideoInfo? {
        var video: String? = null
        val imgs = mutableListOf<String>()

        when (type) {
            "pin" -> {
                data.getAsJsonArray("content")?.let { arr ->
                    for (i in 0 until arr.size()) {
                        val seg = arr[i].asObj() ?: continue
                        when (seg.str("type")) {
                            "video" -> if (video == null) video = bestPlayUrl(seg.obj("playlist"))
                            "image" -> (seg.str("url") ?: seg.str("image"))?.let { imgs.add(it) }
                            "text" -> imgs.addAll(imgsFromHtml(seg.str("content") ?: seg.str("content_html")))
                        }
                    }
                }
            }
            else -> {
                // 视频回答 / 想法视频：顶层 playlist
                video = bestPlayUrl(data.get("playlist"))
                if (video == null) {
                    // 正文内嵌 lens 视频
                    val html = data.str("content") ?: ""
                    val lensId = Regex("""data-lens-id="(\d+)"""").find(html)?.groupValues?.get(1)
                    if (lensId != null) video = fetchLensVideo(lensId)
                    if (video == null) imgs.addAll(imgsFromHtml(html))
                }
            }
        }
        if (video == null && imgs.isEmpty()) return null
        val title = data.obj("question").str("title")
            ?: data.str("title") ?: data.str("excerpt_title")
        val cover = data.str("thumbnail") ?: data.str("image_url") ?: imgs.firstOrNull()
        val author = data.obj("author").str("name")
        return makeVideoInfo(
            platform = "zhihu", videoUrl = video, images = imgs,
            title = title, author = author, cover = cover,
        )
    }

    /** lens.zhihu.com 正文内嵌视频 */
    private fun fetchLensVideo(lensId: String): String? = try {
        val j = ParserSupport.parseJsonObject(
            ParserSupport.get(
                "https://lens.zhihu.com/api/v4/videos/$lensId",
                ua = ParserSupport.UA_IPHONE, referer = "https://www.zhihu.com/",
                extra = mapOf("Accept" to "application/json, text/plain, */*"),
            ).body
        )
        bestPlayUrl(j?.get("playlist"))
    } catch (_: Exception) { null }

    /** playlist 可能是对象（按清晰度 key）或数组，按码率*分辨率取最高 play_url */
    private fun bestPlayUrl(el: JsonElement?): String? {
        if (el == null) return null
        val items = mutableListOf<JsonObject>()
        when {
            el.isJsonObject -> el.asJsonObject.entrySet().forEach {
                if (it.value.isJsonObject) items.add(it.value.asJsonObject)
            }
            el.isJsonArray -> el.asJsonArray.forEach { if (it.isJsonObject) items.add(it.asJsonObject) }
        }
        val best = items.mapNotNull { o ->
            val u = o.str("play_url") ?: o.str("url")
            if (u != null && u.startsWith("http") && !ParserSupport.isM3u8(u)) o to u else null
        }.maxByOrNull { (o, _) ->
            (o.int("bitrate") ?: 0).toLong() * 10_000_000L +
                (o.int("width") ?: 0).toLong() * (o.int("height") ?: 0)
        }
        return best?.let { ParserSupport.unescape(it.second) }
    }

    private fun imgsFromHtml(html: String?): List<String> {
        if (html.isNullOrBlank()) return emptyList()
        val out = LinkedHashSet<String>()
        Regex("""<img[^>]+>""", RegexOption.IGNORE_CASE).findAll(html).forEach { tag ->
            val u = Regex("""data-original\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag.value)
                ?.groupValues?.get(1)
                ?: Regex("""data-actualsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag.value)
                    ?.groupValues?.get(1)
                ?: Regex("""\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(tag.value)
                    ?.groupValues?.get(1)
            if (u != null && u.startsWith("http") && !u.contains("data:image")) {
                out.add(ParserSupport.unescapeHtml(u).split("?")[0].let { it.ifBlank { u } })
            }
        }
        return out.toList()
    }

    /** 识别内容类型与 id */
    private fun matchContent(url: String): Pair<String, String>? {
        Regex("""question/\d+/answer/(\d+)""").find(url)?.let { return "answer" to it.groupValues[1] }
        Regex("""(?:answer)/(\d+)""").find(url)?.let { return "answer" to it.groupValues[1] }
        Regex("""zvideo/(\d+)""").find(url)?.let { return "zvideo" to it.groupValues[1] }
        Regex("""pin/(\d+)""").find(url)?.let { return "pin" to it.groupValues[1] }
        Regex("""(?:zhuanlan\.zhihu\.com/p/|/(?:p|article)/)(\d+)""").find(url)?.let {
            return "article" to it.groupValues[1]
        }
        return null
    }
}
