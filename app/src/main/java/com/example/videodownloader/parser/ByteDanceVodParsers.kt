package com.example.videodownloader.parser

import com.example.videodownloader.VideoInfo
import com.google.gson.JsonObject

// ============================================================================
// 西瓜 / 头条：移动页 RENDER_DATA -> playAuthTokenV2 -> 火山 VOD GetPlayInfo
// 两平台同属字节 VOD 体系，流程一致，仅移动页 host 不同，故抽公共对象。
// ============================================================================
internal object ByteDanceVod {

    fun parse(ctx: ParseContext, platform: String, referer: String, buildCandidates: (String) -> List<String>): VideoInfo? {
        val real = ParserSupport.resolveRedirects(ctx.shareUrl, ParserSupport.UA_IPHONE, referer)
        val id = Regex("(\\d{6,})").find(real)?.value
            ?: Regex("(\\d{6,})").find(ctx.shareUrl)?.value ?: return null

        var html: String? = null
        var pageUrl = real
        for (c in buildCandidates(id)) {
            try {
                val r = ParserSupport.get(c, ua = ParserSupport.UA_IPHONE, follow = true)
                if (r.body.contains("RENDER_DATA", true) || r.body.contains("_SSR_DATA", true)) {
                    html = r.body; pageUrl = r.finalUrl; break
                }
            } catch (_: Exception) {}
        }
        if (html == null) {
            val r = ParserSupport.get(real, ua = ParserSupport.UA_IPHONE, follow = true)
            html = r.body; pageUrl = r.finalUrl
        }

        val article = findArticleInfo(html) ?: return null

        var video: String? = null
        var cover: String? = null

        // 主路径：playAuthTokenV2(base64) -> GetPlayInfoToken(query) -> vod.bytedanceapi.com
        val authToken = article.str("playAuthTokenV2") ?: article.str("playAuthToken")
        if (!authToken.isNullOrBlank()) {
            try {
                val tokenJson = ParserSupport.base64Decode(authToken)?.let { ParserSupport.parseJsonObject(it) }
                val query = tokenJson?.str("GetPlayInfoToken")
                if (!query.isNullOrBlank()) {
                    val vodResp = ParserSupport.parseJsonObject(
                        ParserSupport.get("https://vod.bytedanceapi.com/?$query", ua = ParserSupport.UA_IPHONE, referer = pageUrl).body
                    )
                    val data = vodResp?.obj("Result")?.obj("Data")
                    val playList = data?.getAsJsonArray("PlayInfoList")
                    if (playList != null && playList.size() > 0) {
                        var best: JsonObject? = null
                        for (i in 0 until playList.size()) {
                            val item = playList[i].asObj() ?: continue
                            if (item.str("MainPlayUrl") != null || item.str("BackupPlayUrl") != null) {
                                if (best == null || (item.int("Bitrate") ?: 0) > (best!!.int("Bitrate") ?: 0)) best = item
                            }
                        }
                        video = best?.let { it.str("MainPlayUrl") ?: it.str("BackupPlayUrl") }
                        cover = data.str("CoverUrl")
                    }
                }
            } catch (_: Exception) {}
        }
        // 兜底：articleInfo.playUrlList[0]
        if (video.isNullOrBlank()) {
            article.getAsJsonArray("playUrlList")?.get(0)?.asObj()?.let { p ->
                video = p.str("mainUrl") ?: p.str("backupUrl")
            }
        }

        val user = article.obj("mediaUser") ?: article.obj("user")
        val author = user.let { it.str("screenName") ?: it.str("name") }
            ?: article.str("userName") ?: article.str("source")
        return makeVideoInfo(
            platform = platform,
            videoUrl = video,
            title = article.str("title") ?: article.str("content"),
            author = author,
            cover = cover ?: article.str("posterUrl"),
        )
    }

    /** 从 <script id="RENDER_DATA/_SSR_DATA">（URL-encoded JSON）里深度定位 articleInfo */
    private fun findArticleInfo(html: String): JsonObject? {
        val m = Regex("""id="(?:RENDER_DATA|_SSR_DATA)"[^>]*>([\s\S]*?)</script>""", RegexOption.IGNORE_CASE).find(html)
            ?: return null
        val raw = m.groupValues[1]
        val decoded = ParserSupport.urlDecode(ParserSupport.unescapeHtml(raw))
        val json = ParserSupport.parseJsonObject(decoded) ?: ParserSupport.parseJsonObject(raw) ?: return null
        return ParserSupport.deepObj(json, "articleInfo") ?: ParserSupport.deepObj(json, "videoInfo")
    }
}

internal object XiguaParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? = ByteDanceVod.parse(ctx, "xigua", "https://www.ixigua.com/") { id ->
        listOf(
            "https://m.ixigua.com/video/$id/",
            "https://m.ixigua.com/group/$id/",
            "https://www.ixigua.com/$id",
        )
    }
}

internal object ToutiaoParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? = ByteDanceVod.parse(ctx, "toutiao", "https://m.toutiao.com/") { id ->
        listOf(
            "https://m.toutiao.com/video/$id/",
            "https://m.toutiao.com/group/$id/",
            "https://www.toutiao.com/video/$id/",
        )
    }
}

// ============================================================================
// 番茄小说 / 红果短剧：SSR 页面内嵌直链，统一走通用 HTML 提取器
// ============================================================================
internal object FanqieParser : PlatformParser {
    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val real = ParserSupport.resolveRedirects(ctx.shareUrl, ParserSupport.UA_IPHONE, "https://fanqienovel.com/")
        val html = ParserSupport.get(real, ua = ParserSupport.UA_IPHONE, referer = "https://fanqienovel.com/").body
        return GenericHtmlExtractor.extract(html, real, "fanqie", "https://fanqienovel.com/")
    }
}

// ============================================================================
// 快影：OpenAPI getTemplateById，自实现 nonce/sign（SIGN_KEY 派生）
// ============================================================================
internal object KwaiyingParser : PlatformParser {
    private const val SIGN_KEY = "yiuhjkbvhbjisjchgdnx38uejd"

    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val templateId = ParserSupport.query(ctx.shareUrl, "id")
            ?: ParserSupport.query(ctx.shareUrl, "templateId")
            ?: Regex("""(?:id|templateId|template_id)=(\d+)""").find(ctx.shareUrl)?.groupValues?.get(1)
            ?: return null
        val signParams = buildSign()
        val qs = "timestamp=${signParams["timestamp"]}&nonce=${signParams["nonce"]}&sign=${signParams["sign"]}&templateId=$templateId"
        val j = ParserSupport.parseJsonObject(
            ParserSupport.get(
                "https://api.kmovie.gifshow.com/rest/n/kmovie/app/resource/getTemplateById?$qs",
                ua = ParserSupport.UA_IPHONE, referer = ctx.shareUrl, origin = "https://share.kwaiying.com",
            ).body
        ) ?: return null
        if (j.int("result") != 1) return null
        val res = j.obj("resource") ?: return null
        val bean = res.obj("templateBean")
        val user = res.obj("user")
        return makeVideoInfo(
            platform = "kwaiying",
            videoUrl = res.str("videoUrl"),
            title = res.str("name") ?: bean.str("description"),
            author = user.str("nickName"),
            cover = bean.str("coverUrl") ?: bean.str("coverWebPUrl"),
            musicUrl = res.obj("music")?.let { it.str("url") ?: it.str("playUrl") },
        )
    }

    /** 移植自 kwaiying_parser._generate_sign_params */
    private fun buildSign(): Map<String, String> {
        val nowMs = System.currentTimeMillis()
        val nonce = (100000000000L..999999999999L).random()
        val hexStr = SIGN_KEY.map { Integer.toHexString(it.code) }.joinToString("")
        val digitsOnly = hexStr.replace(Regex("[a-fA-F]"), "").take(16)
        val a = if (digitsOnly.isNotEmpty()) digitsOnly.toLong() else 0L
        val s = (a xor nowMs) or a
        val sign = ParserSupport.md5Hex((nonce xor s).toString())
        return mapOf("timestamp" to nowMs.toString(), "nonce" to nonce.toString(), "sign" to sign)
    }
}

// ============================================================================
// 剪映 / CapCut：固定盐 + 路径后 7 位 + 时间戳的 MD5 签名，两阶段或单阶段 POST
// ============================================================================
internal object JianyingParser : PlatformParser {
    private const val SALT_PREFIX = "9e2c"
    private const val SALT_SUFFIX = "11ac"

    override suspend fun parse(ctx: ParseContext): VideoInfo? {
        val host = ParserSupport.host(ctx.shareUrl)
        val path = ParserSupport.path(ctx.shareUrl)
        val isCapcut = host.contains("capcut") || path.contains("/share/")
        return if (isCapcut) parseCapcut(ctx) else parseTemplate(ctx)
    }

    private fun signOf(apiPath: String, ts: Long, pf: String): String {
        val tail = apiPath.takeLast(7)
        return ParserSupport.md5Hex("$SALT_PREFIX|$tail|$pf||$ts||$SALT_SUFFIX")
    }

    /** CapCut 分享：cluster_list 换 inner_share_id，再 share_detail_query 取详情 */
    private fun parseCapcut(ctx: ParseContext): VideoInfo? {
        val shareId = Regex("""/share/([A-Za-z0-9_-]+)""").find(ParserSupport.path(ctx.shareUrl))?.groupValues?.get(1)
            ?: return null
        val ts = System.currentTimeMillis() / 1000
        val path1 = "/lv/v1/coordination/cluster_list"
        val path2 = "/lv/v1/coordination/share_detail_query"
        val baseHeaders = mapOf("pf" to "7", "sign-ver" to "1", "device-time" to ts.toString())
        val origin = "https://www.capcut.cn"

        val r1 = ParserSupport.postJson(
            "$origin$path1", """{"share_id":"$shareId","password":""}""",
            ua = ParserSupport.UA_PC, referer = ctx.shareUrl, origin = origin,
            extra = baseHeaders + ("sign" to signOf(path1, ts, "7")),
        )
        val j1 = ParserSupport.parseJsonObject(r1.body)
        val innerId = j1?.obj("data")?.getAsJsonArray("share_info_list")?.get(0)?.asObj()?.str("share_id")
            ?: return null

        val r2 = ParserSupport.postJson(
            "$origin$path2", """{"share_id":"$innerId"}""",
            ua = ParserSupport.UA_PC, referer = ctx.shareUrl, origin = origin,
            extra = baseHeaders + ("sign" to signOf(path2, ts, "7")),
        )
        val data = ParserSupport.parseJsonObject(r2.body)?.obj("data") ?: return null

        val nv = data.obj("normal_video") ?: data.obj("video")
        var video: String? = null
        for (k in listOf("player_720p", "player_480p", "player_360p")) {
            val u = nv?.obj(k)?.str("main_url")
            if (u != null) { video = u; break }
        }
        val coverImg = data.obj("cover_image")
        val cover = coverImg.str("preview_1080p_url") ?: coverImg.str("preview_720p_url") ?: coverImg.str("preview_360p_url")
        return makeVideoInfo(
            platform = "jianying",
            videoUrl = video,
            title = data.str("file_name") ?: data.str("share_name"),
            author = data.str("uploader_name"),
            cover = cover,
        )
    }

    /** 剪映模板：lv.ulikecam.com，一次 multi_get_templates */
    private fun parseTemplate(ctx: ParseContext): VideoInfo? {
        val templateId = ParserSupport.query(ctx.shareUrl, "template_id")
            ?: ParserSupport.query(ctx.shareUrl, "templateId") ?: return null
        val itemType = ParserSupport.query(ctx.shareUrl, "item_type") ?: "0"
        val ts = System.currentTimeMillis() / 1000
        val apiPath = "/lv/v1/web/replicate/multi_get_templates"
        val origin = "https://lv.ulikecam.com"
        val body = """{"sdk_version":"100.0.0","id":["$templateId"],"scene":"share","item_type":$itemType}"""
        val r = ParserSupport.postJson(
            "https://lv-api.ulikecam.com$apiPath", body,
            ua = ParserSupport.UA_IPHONE, referer = "$origin/", origin = origin,
            extra = mapOf("pf" to "0", "sign-ver" to "1", "device-time" to ts.toString(), "sign" to signOf(apiPath, ts, "0")),
        )
        val tpl = ParserSupport.parseJsonObject(r.body)?.obj("data")?.getAsJsonArray("templates")?.get(0)?.asObj()
            ?: return null
        val author = tpl.obj("author")
        return makeVideoInfo(
            platform = "jianying",
            videoUrl = tpl.str("video_url"),
            title = tpl.str("title") ?: tpl.str("short_title"),
            author = author.let { it.str("name") ?: it.str("nickname") },
            cover = tpl.str("cover_url") ?: tpl.str("cover")
                ?: author.let { it.str("avatar") ?: it.str("avatar_url") },
        )
    }
}
