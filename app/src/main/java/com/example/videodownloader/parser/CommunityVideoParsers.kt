package com.example.videodownloader.parser

import com.example.videodownloader.VideoInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.net.URLEncoder

/**
 * 统一构造 VideoInfo：视频与图集二选一（有视频优先视频），两者皆无返回 null。
 * 集中处理空值兜底、去重、封面回退、isImage 标记，避免每个 parser 重复样板代码。
 */
internal fun makeVideoInfo(
    platform: String,
    videoUrl: String? = null,
    title: String? = null,
    author: String? = null,
    cover: String? = null,
    images: List<String>? = null,
    musicUrl: String? = null,
): VideoInfo? {
    val video = videoUrl?.let { ParserSupport.unescape(it) }?.takeIf { it.isNotBlank() && it.startsWith("http") }
    val imgs = images.orEmpty().map { ParserSupport.unescape(it) }
        .filter { it.isNotBlank() && it.startsWith("http") }.distinct()
    if (video == null && imgs.isEmpty()) return null
    val isImage = video == null
    return VideoInfo(
        title = title?.let { ParserSupport.unescapeHtml(it) }?.takeIf { s -> s.isNotBlank() } ?: "未知作品",
        author = author?.let { ParserSupport.unescapeHtml(it) }?.takeIf { s -> s.isNotBlank() } ?: "未知作者",
        videoUrl = video ?: "",
        coverUrl = ParserSupport.unescape(cover ?: "")
            .takeIf { it.startsWith("http") } ?: imgs.firstOrNull().orEmpty(),
        platform = platform,
        isImage = isImage,
        imageUrls = if (isImage) imgs else emptyList(),
        musicUrl = musicUrl ?: "",
    )
}

/** 遍历 JsonArray，把每个元素当 object 回调 */
private inline fun JsonArray?.eachObject(block: (JsonObject) -> Unit) {
    if (this == null) return
    for (i in 0 until size()) {
        val e = this[i]
        if (e != null && e.isJsonObject) block(e.asJsonObject)
    }
}

/** 从 url_list 数组取首个 http 直链 */
private fun JsonObject?.firstUrlInList(listKey: String = "url_list"): String? =
    ParserSupport.pickFirstHttp(this?.getAsJsonArray(listKey))

private fun urlEnc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** 去掉字符串里的 HTML 标签 */
internal fun stripHtml(s: String?): String? =
    s?.replace(Regex("<[^>]+>"), "")?.replace(Regex("\\s+"), " ")?.trim()

// ============================================================================
// 微视：window.Vise.initState -> feedsList[0].videoUrl
// ============================================================================
internal object WeishiParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val html = ParserSupport.get(
            ctx.shareUrl, ua = ParserSupport.UA_PC, referer = "https://isee.weishi.qq.com"
        ).body
        val root = ParserSupport.extractJsonAfter(html, "window.Vise.initState") ?: return null
        val feed = root.getAsJsonArray("feedsList")?.get(0)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return null
        return makeVideoInfo(
            platform = "weishi",
            videoUrl = feed.str("videoUrl"),
            title = feed.str("feedDesc"),
            author = feed.obj("poster").str("nick"),
            cover = feed.str("videoCover"),
        )
    }
}

// ============================================================================
// AcFun：window.pageInfo -> currentVideoInfo.ksPlayJson -> representation 里挑 mp4
// ============================================================================
internal object AcfunParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val html = ParserSupport.get(ctx.shareUrl, ua = ParserSupport.UA_PC).body
        val page = ParserSupport.extractJsonAfter(html, "window.pageInfo") ?: return null
        val cur = page.obj("currentVideoInfo")
        val ks = cur.str("ksPlayJson")?.let { ParserSupport.parseJsonObject(it) }
        val reps = ks?.getAsJsonArray("adaptationSet")?.get(0)?.takeIf { it.isJsonObject }
            ?.asJsonObject?.getAsJsonArray("representation")
        val candidates = mutableListOf<String>()
        reps.eachObject { r ->
            r.str("url")?.let { u -> if (!ParserSupport.isM3u8(u)) candidates.add(u) }
        }
        // AcFun 长视频/番剧只有 m3u8（HLS），无法直接存 mp4，返回 null 由上层提示
        val video = candidates.lastOrNull()
        return makeVideoInfo(
            platform = "acfun",
            videoUrl = video,
            title = page.str("title"),
            author = page.obj("user").str("name"),
            cover = page.str("coverUrl"),
        )
    }
}

// ============================================================================
// 皮皮虾：短链 302 取 cell_id -> cell_comment 接口
// ============================================================================
internal object PipixiaParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val first = ParserSupport.get(
            ctx.shareUrl, ua = ParserSupport.UA_PC, referer = "https://h5.pipix.com/", follow = false
        )
        val loc = first.headers["Location"]?.let { ParserSupport.absolutize(it, ctx.shareUrl) }
            ?: ctx.shareUrl
        val cellId = loc.substringBefore("?").trimEnd('/').substringAfterLast('/')
        if (cellId.isEmpty() || !cellId.all { it.isDigit() }) return null
        val api = "https://api.pipix.com/bds/cell/cell_comment/?offset=0&cell_type=1&api_version=1" +
            "&cell_id=$cellId&ac=wifi&channel=huawei_1319_64&aid=1319&app_name=super"
        val j = ParserSupport.parseJsonObject(
            ParserSupport.get(api, ua = ParserSupport.UA_PC, referer = "https://h5.pipix.com/").body
        ) ?: return null
        val item = j.obj("data")?.getAsJsonArray("cell_comments")?.get(0)?.asObj()
            ?.obj("comment_info")?.obj("item") ?: return null

        var video: String? = null
        item.obj("video")?.let { v ->
            for (k in listOf("video_high", "video_mid", "video_low", "video")) {
                val u = v.obj(k).firstUrlInList()
                if (u != null) { video = u; break }
            }
        }
        val imgs = mutableListOf<String>()
        item.obj("note")?.getAsJsonArray("multi_image").eachObject { img ->
            img.firstUrlInList()?.let { imgs.add(it) }
        }
        return makeVideoInfo(
            platform = "pipixia",
            videoUrl = video,
            images = imgs,
            title = item.str("content"),
            author = item.obj("author").str("name"),
            cover = item.obj("cover").firstUrlInList(),
        )
    }
}

// ============================================================================
// 皮皮搞笑：POST ppapi/share/fetch_content -> post.videos[imgs[0].id].url
// ============================================================================
internal object PipigaoxiaoParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val pid = Regex("(\\d{6,})").find(ctx.shareUrl)?.value?.toLongOrNull() ?: return null
        val body = """{"mid":"null","pid":$pid,"type":"post"}"""
        val j = ParserSupport.parseJsonObject(
            ParserSupport.postJson(
                "https://h5.pipigx.com/ppapi/share/fetch_content", body,
                ua = ParserSupport.UA_PC, referer = ctx.shareUrl
            ).body
        ) ?: return null
        val post = j.obj("data")?.obj("post") ?: return null
        val firstId = post.getAsJsonArray("imgs")?.get(0)?.asObj()?.str("id")
        val video = firstId?.let { post.obj("videos")?.obj(it)?.str("url") }
        val imgs = mutableListOf<String>()
        post.getAsJsonArray("imgs").eachObject { im ->
            // 图文帖 imgs 内可能直接带图片地址
            val set = LinkedHashSet<String>()
            ParserSupport.collectStrings(im, set) { s ->
                s.startsWith("http") && Regex("""\.(jpe?g|png|webp)""", RegexOption.IGNORE_CASE).containsMatchIn(s)
            }
            if (set.isNotEmpty()) imgs.addAll(set)
        }
        return makeVideoInfo(
            platform = "pipigaoxiao",
            videoUrl = video,
            images = imgs,
            title = post.str("content"),
            author = j.obj("data")?.obj("user").str("name"),
            cover = firstId?.let { "https://file.ippzone.com/img/view/id/$it" },
        )
    }
}

// ============================================================================
// 好看视频：window.__PRELOADED_STATE__ -> curVideoMeta.clarityUrl（取最高清）
// ============================================================================
internal object HaokanParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val html = ParserSupport.get(
            ctx.shareUrl, ua = ParserSupport.UA_IPHONE, referer = "https://haokan.baidu.com/v"
        ).body
        val meta = ParserSupport.extractJsonAfter(html, "window.__PRELOADED_STATE__")
            ?.obj("curVideoMeta") ?: return null
        val clarity = meta.getAsJsonArray("clarityUrl")
        val video = if (clarity != null && clarity.size() > 0) {
            clarity.get(clarity.size() - 1)?.asObj()?.str("url")
                ?.let { ParserSupport.urlDecode(ParserSupport.unescape(it)) }
        } else null
        return makeVideoInfo(
            platform = "haokan",
            videoUrl = video,
            title = meta.str("title"),
            author = meta.obj("mth").str("author_name"),
            cover = meta.str("poster"),
        )
    }
}

// ============================================================================
// 梨视频：videoStatus.jsp 拿混淆 srcUrl，把时间戳段替换回 cont-{contId}
// ============================================================================
internal object LishipinParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val contId = Regex("(\\d+)").find(ParserSupport.path(ctx.shareUrl))?.value
            ?: Regex("(\\d+)").find(ctx.shareUrl)?.value ?: return null
        val mrd = Math.random()
        val api = "https://www.pearvideo.com/videoStatus.jsp?contId=$contId&mrd=$mrd"
        val j = ParserSupport.parseJsonObject(
            ParserSupport.get(api, ua = ParserSupport.UA_PC, referer = ctx.shareUrl).body
        ) ?: return null
        val info = j.obj("videoInfo")
        val src = info?.obj("videos").str("srcUrl") ?: return null
        val video = src.replace(Regex("(\\d+)-(\\d+-hd\\.mp4)")) { m ->
            "cont-$contId-${m.groupValues[2]}"
        }
        // 标题从视频页 HTML 取 og:title / div.summary
        var title: String? = null
        try {
            val pageHtml = ParserSupport.get(ctx.shareUrl, ua = ParserSupport.UA_PC).body
            title = ParserSupport.meta(pageHtml, "og:title")
                ?: ParserSupport.firstMatch(Regex("""class="summary"[^>]*>([^<]+)<"""), pageHtml)
        } catch (_: Exception) {}
        return makeVideoInfo(
            platform = "lishipin",
            videoUrl = video,
            title = title,
            cover = info.str("video_image"),
        )
    }
}

// ============================================================================
// 美拍：window.PHPDATA -> mediaInfo.video（自实现的切片+base64 解码）-> CDN 302
// ============================================================================
internal object MeipaiParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val mediaId = Regex("\\d{15,}").find(ctx.shareUrl)?.value ?: return null
        val html = ParserSupport.get(
            "http://www.meipai.com/media/$mediaId",
            ua = ParserSupport.UA_IPHONE, referer = "https://www.meipai.com/"
        ).body
        val php = ParserSupport.extractJsonAfter(html, "window.PHPDATA") ?: return null
        val media = php.obj("mediaInfo") ?: return null
        val raw = media.str("video")?.let { decode(it) }
        val video = if (raw != null) {
            // 再走一次 CDN 重定向接口拿真实直链
            try {
                ParserSupport.get(
                    "https://cracl.meitubase.com/resource/get_cdn_url?url=${urlEnc(raw)}",
                    ua = ParserSupport.UA_IPHONE, referer = "https://www.meipai.com/", follow = true
                ).finalUrl.ifBlank { raw }
            } catch (_: Exception) { raw }
        } else null
        var cover = media.str("cover_pic")?.let { c ->
            var x = if (c.startsWith("//")) "https:$c" else c
            if (x.contains("!")) x = x.substringBefore("!")
            x
        }
        return makeVideoInfo(
            platform = "meipai",
            videoUrl = video,
            title = stripHtml(media.str("caption_origin") ?: media.str("caption")),
            author = media.obj("user").str("screen_name"),
            cover = cover,
        )
    }

    /** 移植自 meipai_parser._decode_video_string：前4位反转得 hex 头，按指示切片删除后 base64 解码 */
    private fun decode(encoded: String): String? {
        return try {
            if (encoded.length < 5) return null
            val eDec = encoded.substring(0, 4).reversed().toLong(16).toString()
            if (eDec.length < 4) return null
            val pre = eDec.substring(0, 2).map { it.digitToInt() }
            val tail = eDec.substring(2).map { it.digitToInt() }
            val rest = encoded.substring(4)
            val idx1 = pre[0]; val len1 = pre[1]
            val a1 = rest.substring(idx1, minOf(idx1 + len1, rest.length))
            val str1 = rest.substring(0, idx1) + removeFirst(rest.substring(idx1), a1)
            val tailIdx = str1.length - tail[0] - tail[1]
            val a2 = str1.substring(tailIdx, minOf(tailIdx + tail[1], str1.length))
            val str2 = str1.substring(0, tailIdx) + removeFirst(str1.substring(tailIdx), a2)
            var raw = ParserSupport.base64Decode(str2) ?: return null
            if (raw.startsWith("//")) raw = "https:$raw"
            if (raw.startsWith("http://")) raw = "https://" + raw.substring(7)
            raw
        } catch (_: Exception) { null }
    }

    private fun removeFirst(s: String, sub: String): String {
        val i = s.indexOf(sub)
        return if (i >= 0) s.substring(0, i) + s.substring(i + sub.length) else s
    }
}

// ============================================================================
// 最右：POST planck/share/post/detail_h5 -> post.videos[imgs[0].id].url
// ============================================================================
internal object ZuiyouParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val pid = ParserSupport.query(ctx.shareUrl, "pid")?.toLongOrNull() ?: return null
        val j = ParserSupport.parseJsonObject(
            ParserSupport.postJson(
                "https://share.xiaochuankeji.cn/planck/share/post/detail_h5",
                """{"h_av":"5.2.13.011","pid":$pid}""",
                ua = ParserSupport.UA_PC, referer = "https://share.xiaochuankeji.cn/"
            ).body
        ) ?: return null
        val post = j.obj("data")?.obj("post") ?: return null
        val firstId = post.getAsJsonArray("imgs")?.get(0)?.asObj()?.str("id")
        val video = firstId?.let { post.obj("videos")?.obj(it)?.str("url") }
        val imgs = mutableListOf<String>()
        post.getAsJsonArray("imgs").eachObject { im ->
            val set = LinkedHashSet<String>()
            ParserSupport.collectStrings(im, set) { s ->
                s.startsWith("http") && Regex("""\.(jpe?g|png|webp)""", RegexOption.IGNORE_CASE).containsMatchIn(s)
            }
            if (set.isNotEmpty()) imgs.addAll(set)
        }
        return makeVideoInfo(
            platform = "zuiyou",
            videoUrl = video,
            images = imgs,
            title = post.str("content"),
            author = post.obj("member").str("name"),
        )
    }
}

// ============================================================================
// 绿洲：SSR 页面 <video src> / 图片，通用 HTML 提取器兜底
// ============================================================================
internal object LvzhouParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val html = ParserSupport.get(
            ctx.shareUrl, ua = ParserSupport.UA_IPHONE, referer = "https://oasis.weibo.cn/"
        ).body
        val info = GenericHtmlExtractor.extract(html, ctx.shareUrl, "lvzhou", "https://oasis.weibo.cn/")
        if (info != null) return info
        // 专属兜底：background-image 封面 + .status-text 标题
        val video = ParserSupport.tagVideoSrc(html)
        val title = ParserSupport.firstMatch(
            Regex("""class="status-(?:text|title)"[^>]*>([^<]+)<"""), html
        ) ?: ParserSupport.meta(html, "og:title")
        return makeVideoInfo(platform = "lvzhou", videoUrl = video, title = title)
    }
}

// ============================================================================
// 全民K歌：node/play?s= -> window.__DATA__.detail.playurl_video（仅 MV 视频作品）
// ============================================================================
internal object QuanminkgeParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        var s = ParserSupport.query(ctx.shareUrl, "s")
        if (s.isNullOrBlank()) {
            val final = ParserSupport.resolveRedirects(ctx.shareUrl, ParserSupport.UA_PC, "https://kg.qq.com/")
            s = ParserSupport.query(final, "s")
        }
        if (s.isNullOrBlank()) return null
        val html = ParserSupport.get(
            "https://kg.qq.com/node/play?s=$s", ua = ParserSupport.UA_PC, referer = "https://kg.qq.com/"
        ).body
        val detail = ParserSupport.extractJsonAfter(html, "window.__DATA__")?.obj("detail") ?: return null
        val video = detail.str("playurl_video") // 纯翻唱音频作品该字段为空，明确不支持
        if (video.isNullOrBlank()) return null
        return makeVideoInfo(
            platform = "quanminkge",
            videoUrl = video,
            title = detail.str("content") ?: detail.str("title"),
            author = detail.str("nick"),
            cover = detail.str("cover"),
        )
    }
}

// ============================================================================
// 虎牙（短视频/动态 moment）：liveapi getMomentContent -> definitions 取最高 mp4
// ============================================================================
internal object HuyaParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val id = Regex("(\\d{4,})").find(ParserSupport.path(ctx.shareUrl))?.value
            ?: Regex("(\\d{4,})").find(ctx.shareUrl)?.value ?: return null
        val j = ParserSupport.parseJsonObject(
            ParserSupport.get(
                "https://liveapi.huya.com/moment/getMomentContent?videoId=$id",
                ua = ParserSupport.UA_PC, referer = "https://www.huya.com/"
            ).body
        ) ?: return null
        val vi = j.obj("data")?.obj("moment")?.obj("videoInfo") ?: return null
        val mp4 = mutableListOf<String>()
        vi.getAsJsonArray("definitions").eachObject { d ->
            d.str("url")?.let { u -> if (!ParserSupport.isM3u8(u)) mp4.add(u) }
        }
        return makeVideoInfo(
            platform = "huya",
            videoUrl = mp4.lastOrNull(),
            title = vi.str("videoTitle"),
            cover = vi.str("videoCover"),
        )
    }
}

// ============================================================================
// 配音秀：页面内联 filmurl / filmimg 变量
// ============================================================================
internal object PeiyinxiuParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val html = ParserSupport.get(
            ctx.shareUrl, ua = ParserSupport.UA_IPHONE, referer = "https://www.peiyinxiu.com/"
        ).body
        val video = ParserSupport.firstMatch(
            Regex("""\bfilmurl\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE), html
        )?.let { ParserSupport.absolutize(ParserSupport.unescapeHtml(it), ctx.shareUrl) }
        val cover = ParserSupport.firstMatch(
            Regex("""\bfilmimg\s*:\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE), html
        )?.let { ParserSupport.absolutize(it, ctx.shareUrl) } ?: ParserSupport.meta(html, "og:image")
        return makeVideoInfo(
            platform = "peiyinxiu",
            videoUrl = video,
            title = ParserSupport.meta(html, "og:title"),
            cover = cover,
        )
    }
}

// ============================================================================
// 新片场：app 文章 API 拿 vid -> mod-api media API 取 progressive 最高清
// ============================================================================
internal object XinpianchangParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val id = Regex("(\\d{3,})").find(ParserSupport.path(ctx.shareUrl))?.value
            ?: Regex("(\\d{3,})").find(ctx.shareUrl)?.value ?: return null
        val accept = mapOf("Accept" to "application/json, text/plain, */*")
        val a = ParserSupport.parseJsonObject(
            ParserSupport.get("https://app.xinpianchang.com/article/$id", ua = ParserSupport.UA_IPHONE, extra = accept).body
        ) ?: return null
        if (a.int("status") != 0) return null
        val data = a.obj("data") ?: return null
        val vid = data.str("vid") ?: data.str("media_id") ?: return null
        val appKey = data.obj("video").str("appKey") ?: "61a2f329348b3bf77"
        val m = ParserSupport.parseJsonObject(
            ParserSupport.get(
                "https://mod-api.xinpianchang.com/mod/api/v2/media/$vid?appKey=$appKey",
                ua = ParserSupport.UA_IPHONE, extra = accept
            ).body
        ) ?: return null
        if (m.int("status") != 0) return null
        val prog = m.obj("data")?.obj("resource")?.getAsJsonArray("progressive")
        var best: JsonObject? = null
        prog.eachObject { p ->
            if (p.str("url")?.startsWith("http") == true) {
                val cur = best
                if (cur == null || (p.int("width") ?: 0) >= (cur.int("width") ?: 0)) best = p
            }
        }
        return makeVideoInfo(
            platform = "xinpianchang",
            videoUrl = best?.str("url"),
            title = data.str("title"),
            author = data.obj("author")?.obj("userinfo").str("username"),
            cover = data.str("cover"),
        )
    }
}

// ============================================================================
// 得物：Next.js __NEXT_DATA__ -> metaOGInfo.data[0].content.media.list
// ============================================================================
internal object DewuParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val html = ParserSupport.get(
            ctx.shareUrl, ua = ParserSupport.UA_IPHONE, referer = "https://m.dewu.com/"
        ).body
        val root = ParserSupport.extractJsonAfter(html, "__NEXT_DATA__") ?: return null
        val og = ParserSupport.deepObj(root, "metaOGInfo") ?: return null
        val target = og.getAsJsonArray("data")?.get(0)?.asObj() ?: return null
        val content = target.obj("content") ?: return null
        val videos = mutableListOf<String>()
        val imgs = mutableListOf<String>()
        content.obj("media")?.getAsJsonArray("list").eachObject { item ->
            val u = item.str("url") ?: return@eachObject
            when (item.str("mediaType")) {
                "video" -> videos.add(u)
                "img" -> imgs.add(u)
            }
        }
        val video = videos.firstOrNull() ?: content.str("videoShareUrl")
        if (video == null && imgs.isEmpty()) {
            content.obj("cover")?.str("url")?.let { imgs.add(it) }
        }
        return makeVideoInfo(
            platform = "dewu",
            videoUrl = video,
            images = imgs,
            title = content.str("title") ?: stripHtml(content.str("content")),
            author = target.obj("userInfo").str("userName"),
            cover = content.obj("cover").str("url"),
        )
    }
}

// ============================================================================
// 网易 LOFTER：window.__initialize_data__（视频贴 videoPostView / 图文贴 photoPostView）
// ============================================================================
internal object LofterParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val html = ParserSupport.get(
            ctx.shareUrl, ua = ParserSupport.UA_IPHONE, referer = "https://www.lofter.com/"
        ).body
        val root = ParserSupport.extractJsonAfter(html, "__initialize_data__")
        if (root != null) {
            // 问答 / 标签讨论
            root.obj("answerDetailData")?.let { ans ->
                val imgs = mutableListOf<String>()
                ans.getAsJsonArray("images").eachObject { imObj ->
                    (imObj.str("orign") ?: imObj.str("raw"))?.let { imgs.add(it) }
                }
                val info = makeVideoInfo(
                    platform = "lofter", images = imgs,
                    title = ans.str("barrage") ?: ans.str("answer"),
                    author = ans.obj("blogInfo").let { it.str("blogNickName") ?: it.str("blogName") },
                    cover = ans.obj("blogInfo").str("bigAvaImg"),
                )
                if (info != null) return info
            }
            // 标准博客
            val postView = root.obj("postData")?.obj("data")?.obj("postData")?.obj("postView")
            if (postView != null) {
                val blog = root.obj("postData")?.obj("data")?.obj("blogInfo")
                var video: String? = null
                var cover: String? = null
                val imgs = mutableListOf<String>()
                postView.obj("videoPostView")?.let { vp ->
                    val vi = vp.obj("videoInfo")
                    video = vi.str("originUrl") ?: vi.str("flashurl")
                    cover = vi.str("video_img_url") ?: vi.str("video_first_img")
                }
                postView.obj("photoPostView")?.let { pp ->
                    pp.getAsJsonArray("photoLinks").eachObject { ph ->
                        (ph.str("raw") ?: ph.str("orign"))?.let { imgs.add(it) }
                    }
                    if (cover == null) {
                        cover = pp.obj("firstImage")?.let { it.str("raw") ?: it.str("orign") }
                            ?: postView.obj("firstImage")?.let { it.str("raw") ?: it.str("orign") }
                    }
                }
                val info = makeVideoInfo(
                    platform = "lofter", videoUrl = video, images = imgs,
                    title = postView.str("title") ?: postView.str("digest"),
                    author = blog.let { it.str("blogNickName") ?: it.str("blogName") },
                    cover = cover,
                )
                if (info != null) return info
            }
        }
        // 老版页面 / 未渲染出初始化数据：通用 HTML 兜底
        return GenericHtmlExtractor.extract(html, ctx.shareUrl, "lofter", "https://www.lofter.com/")
    }
}

// ============================================================================
// Soul：sign 三参数来自分享 URL（query 或 fragment）-> post/detail 接口
// ============================================================================
internal object SoulParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val postIdEcpt = rawParam(ctx.shareUrl, "postIdEcpt")
        val sign = rawParam(ctx.shareUrl, "sign")
        val signVer = rawParam(ctx.shareUrl, "signVersion")
        if (postIdEcpt.isNullOrBlank() || sign.isNullOrBlank() || signVer.isNullOrBlank()) return null
        val origin = "https://w13.soulsmile.cn"
        val accept = mapOf("Accept" to "application/json, text/plain, */*")
        val api = "https://api-h5.soulapp.cn/html/v3/post/detail" +
            "?postIdEcpt=${urlEnc(postIdEcpt)}&sign=${urlEnc(sign)}&signVersion=${urlEnc(signVer)}"
        val j = ParserSupport.parseJsonObject(
            ParserSupport.get(api, ua = ParserSupport.UA_IPHONE, referer = "$origin/", origin = origin, extra = accept).body
        ) ?: return null
        if (j.get("success")?.takeIf { it.isJsonPrimitive }?.asBoolean != true) return null
        val post = j.obj("data")?.obj("post") ?: return null

        var video: String? = null
        var cover: String? = null
        val imgs = mutableListOf<String>()
        post.getAsJsonArray("attachments").eachObject { att ->
            if (att.str("type") == "VIDEO") {
                video = att.str("fileUrl")
                cover = att.str("videoCoverUrl")
                    ?: att.str("ext")?.let { ParserSupport.parseJsonObject(it)?.str("videoCoverUrl") }
            } else {
                for (k in listOf("fileUrl", "imageUrl", "imageOriginUrl", "pictureUrl")) {
                    val u = att.str(k)
                    if (u != null) { imgs.add(u); break }
                }
            }
        }
        var author: String? = null
        post.str("authorIdEcpt")?.let { aid ->
            try {
                val u = ParserSupport.parseJsonObject(
                    ParserSupport.get(
                        "https://api-h5.soulapp.cn/html/v2/user/info?userIdEcpt=${urlEnc(aid)}",
                        ua = ParserSupport.UA_IPHONE, referer = "$origin/", origin = origin, extra = accept
                    ).body
                )
                author = u?.obj("data").str("nickName")
            } catch (_: Exception) {}
        }
        return makeVideoInfo(
            platform = "soul",
            videoUrl = video,
            images = imgs,
            title = post.str("title") ?: stripHtml(post.str("content")),
            author = author,
            cover = cover,
        )
    }

    /** 从 query 或 fragment（#/?...）中取原始（未二次解码）参数值 */
    private fun rawParam(url: String, key: String): String? {
        val m = Regex("[?&#]$key=([^&]*)").find(url) ?: return null
        return m.groupValues[1].ifBlank { null }
    }
}
