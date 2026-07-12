package com.example.videodownloader

import android.util.Log
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * 视频解析器：从分享文本中提取链接，并解析出无水印视频直链。
 *
 * 目前支持抖音（Douyin）。其他平台可按相同思路扩展。
 *
 * 抖音解析思路：
 * 1. 从分享文本中用正则提取短链（如 https://v.douyin.com/D68hRKDKps8/）
 * 2. 不自动跟随重定向，手动 HEAD/GET 拿到 Location，从跳转后的 URL 里提取 video_id
 * 3. 调用 https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=ID
 *    从 item_list[0].video.play_addr.url_list[0] 拿到带水印地址，
 *    把 playwm 替换成 play 得到无水印地址，再跟随一次 302 拿最终直链。
 * 4. 兜底：iteminfo 接口失效时，直接请求 share 页 HTML，
 *    从中正则提取视频地址 / RENDER_DATA / _ROUTER_DATA。
 */
object VideoParser {

    private const val TAG = "VideoParser"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false) // 手动处理第一次重定向，便于拿到 video_id
            .build()
    }

    private val redirectClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 通用 UA：模拟手机端浏览器，避免被识别为爬虫 */
    private const val UA_MOBILE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    /** 从分享文本中提取第一条 http(s) 链接 */
    fun extractShareUrl(text: String): String? {
        if (text.isBlank()) return null
        // 匹配 http(s):// 开头，直到遇到空白或中英文标点
        val pattern = Regex("""https?://[^\s，，。、；；！!？?【】\]\[]+""")
        val match = pattern.find(text) ?: return null
        return match.value.trimEnd('/', '，', '。', '、').let {
            if (it.endsWith("http") || it.endsWith("https")) null else it
        }
    }

    /** 入口：根据分享文本解析视频信息。
     *  [context] 用于读取 B站登录 cookie（SESSDATA），可传 null 表示不登录 */
    suspend fun parse(shareText: String, context: android.content.Context? = null): Result<VideoInfo> = runCatching {
        withContext(Dispatchers.IO) {
            val shareUrl = extractShareUrl(shareText)
                ?: throw IllegalStateException("未在文本中找到链接")

            Log.i(TAG, "提取到的分享链接: $shareUrl")

            // 判断平台
            val platform = detectPlatform(shareUrl)
            when (platform) {
                "douyin" -> parseDouyin(shareUrl)
                "kuaishou" -> parseKuaishou(shareUrl)
                "xiaohongshu" -> parseXiaohongshu(shareUrl)
                "bilibili" -> parseBilibili(shareUrl, context)
                else -> throw UnsupportedOperationException("暂不支持该平台: $shareUrl")
            }
        }
    }

    private fun detectPlatform(url: String): String = when {
        url.contains("v.douyin.com") || url.contains("iesdouyin.com") ||
            url.contains("douyin.com") -> "douyin"
        url.contains("v.kuaishou.com") || url.contains("kuaishou.com") -> "kuaishou"
        url.contains("xhslink.com") || url.contains("xiaohongshu.com") -> "xiaohongshu"
        url.contains("ixigua.com") -> "douyin" // 西瓜视频走抖音相同思路
        url.contains("b23.tv") || url.contains("bilibili.com") -> "bilibili"
        else -> "unknown"
    }

    // ===================== 抖音解析 =====================

    private fun parseDouyin(shareUrl: String): VideoInfo {
        // 1. 拿到跳转后的真实 URL，从中提取 video_id
        val resolvedUrl = resolveFirstRedirect(shareUrl) ?: shareUrl
        Log.i(TAG, "跳转后 URL: $resolvedUrl")

        var videoId = extractDouyinVideoId(resolvedUrl)
        if (videoId == null) {
            // 有些短链直接是 share/video/xxx 形式，尝试从原文本里再抓一遍
            videoId = extractDouyinVideoId(shareUrl)
        }

        if (videoId != null) {
            Log.i(TAG, "video_id = $videoId")
            // 2. 尝试 iteminfo 接口
            try {
                return parseViaItemInfoApi(videoId)
            } catch (e: Exception) {
                Log.w(TAG, "iteminfo 接口失败，尝试 HTML 兜底: ${e.message}")
            }
        }

        // 3. 兜底：直接抓 share 页 HTML
        val sharePageUrl =
            if (videoId != null) "https://www.iesdouyin.com/share/note/$videoId/"
            else resolvedUrl
        return parseFromSharePageHtml(sharePageUrl)
            ?: throw IllegalStateException("抖音视频解析失败，可能接口已变更")
    }

    /** 手动跟随一次 302，返回 Location 头里的 URL；如果不是 302 则返回 null */
    private fun resolveFirstRedirect(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA_MOBILE)
                .header("Accept", "text/html,application/xhtml+xml,*/*")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isRedirect) {
                    resp.header("Location")
                } else {
                    // 没有重定向，直接返回请求 URL
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveFirstRedirect 失败: ${e.message}")
            null
        }
    }

    /** 从 URL 里提取抖音 video_id，例如 /share/video/7123456789012345678/ 或 /share/note/xxx/ */
    private fun extractDouyinVideoId(url: String): String? {
        val patterns = listOf(
            Regex("""/share/note/(\d+)"""),
            Regex("""/share/video/(\d+)"""),
            Regex("""/video/(\d+)"""),
            Regex("""/note/(\d+)"""),
            Regex("""item_ids=(\d+)"""),
            Regex("""modal_id=(\d+)""")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /** 通过 iteminfo API 解析 */
    private fun parseViaItemInfoApi(videoId: String): VideoInfo {
        val apiUrl = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$videoId"
        val body = httpGet(apiUrl)
        if (body.isBlank()) throw IllegalStateException("iteminfo 返回空")

        val root = JsonParser.parseString(body).asJsonObject
        val itemList = root.getAsJsonArray("item_list")
        if (itemList == null || itemList.size() == 0) {
            throw IllegalStateException("iteminfo 没有返回 item_list")
        }
        val item = itemList[0].asJsonObject

        val title = item.safeStr("desc") ?: "抖音视频"
        val author = item
            .safeObj("author")?.safeStr("nickname") ?: "未知作者"

        val videoObj = item.safeObj("video") ?: throw IllegalStateException("没有视频字段")
        val playAddrObj = videoObj.safeObj("play_addr")
            ?: throw IllegalStateException("没有 play_addr")
        val urlList = playAddrObj.getAsJsonArray("url_list")
        if (urlList == null || urlList.size() == 0) {
            throw IllegalStateException("没有 url_list")
        }
        var playUrl = urlList[0].asString
        // 关键一步：把带水印的 playwm 替换为 play 得到无水印地址
        playUrl = playUrl.replace("playwm", "play")

        // 跟随一次重定向拿到最终直链（无水印地址会 302）
        val finalUrl = resolveFinalUrl(playUrl) ?: playUrl

        val coverUrl = videoObj.safeObj("cover")?.getAsJsonArray("url_list")?.let {
            if (it.size() > 0) it[0].asString else ""
        } ?: ""

        return VideoInfo(
            title = title,
            author = author,
            videoUrl = finalUrl,
            coverUrl = coverUrl,
            platform = "douyin"
        )
    }

    /** HTML 兜底：从 share 页里抓视频地址或图片 */
    private fun parseFromSharePageHtml(sharePageUrl: String): VideoInfo? {
        return try {
            val html = httpGet(sharePageUrl)
            if (html.isBlank()) return null

            val title = Regex("""<title>([^<]+)</title>""").find(html)
                ?.groupValues?.get(1)?.trim()?.ifBlank { null } ?: "抖音视频"

            // 优先解析 _ROUTER_DATA，检测图文笔记（有 images 数组，无真实视频）
            val routerData = extractJsonObject(html, "_ROUTER_DATA")
                ?: extractJsonObject(html, "RENDER_DATA")
            if (routerData != null) {
                val author = deepFindString(routerData, "nickname") ?: "未知作者"
                val awemeType = deepFindInt(routerData, "aweme_type") ?: -1
                val imagesArray = deepFindArray(routerData, "images")

                // 图文笔记：aweme_type==2 或有 images 数组
                if (awemeType == 2 || (imagesArray != null && imagesArray.size() > 0)) {
                    val imgUrls = mutableListOf<String>()
                    if (imagesArray != null) {
                        for (i in 0 until imagesArray.size()) {
                            val imgObj = imagesArray[i].asJsonObject
                            // url_list 里的图是无水印原图
                            val urlList = imgObj.getAsJsonArray("url_list")
                            if (urlList != null && urlList.size() > 0) {
                                // 优先取 jpeg 版本（兼容性最好），取不到就取第一个
                                var picked: String? = null
                                for (j in 0 until urlList.size()) {
                                    val u = urlList[j].asString
                                        .replace("\\u002F", "/")
                                        .replace("\\/", "/")
                                        .replace("&amp;", "&")
                                    if (u.contains(".jpeg")) { picked = u; break }
                                }
                                if (picked == null) {
                                    picked = urlList[0].asString
                                        .replace("\\u002F", "/")
                                        .replace("\\/", "/")
                                        .replace("&amp;", "&")
                                }
                                if (picked.startsWith("http")) imgUrls.add(picked)
                            }
                        }
                    }
                    if (imgUrls.isNotEmpty()) {
                        Log.i(TAG, "抖音图文笔记，共 ${imgUrls.size} 张图片")
                        return VideoInfo(
                            title = title,
                            author = author,
                            videoUrl = "",
                            platform = "douyin",
                            isImage = true,
                            imageUrls = imgUrls
                        )
                    }
                }

                // 视频笔记：从 _ROUTER_DATA 里找 play_addr
                val videoUrl = deepFindUrl(routerData, listOf("play_addr", "url_list"))
                    ?.replace("playwm", "play")
                if (!videoUrl.isNullOrBlank() && !videoUrl.contains(".mp3")) {
                    val finalUrl = resolveFinalUrl(videoUrl) ?: videoUrl
                    val cover = deepFindUrl(routerData, listOf("cover", "url_list")) ?: ""
                    return VideoInfo(title, author, finalUrl, cover, "douyin")
                }
            }

            // 兜底1：直接在 HTML 里搜 play_addr + url_list 模式
            val playAddrUrl = findPlayAddrUrl(html)
            if (!playAddrUrl.isNullOrBlank() && !playAddrUrl.contains(".mp3")) {
                val noWm = playAddrUrl.replace("playwm", "play")
                val finalUrl = resolveFinalUrl(noWm) ?: noWm
                val cover = findCoverUrl(html) ?: ""
                return VideoInfo(title, "未知作者", finalUrl, cover, "douyin")
            }

            // 兜底2：直接找 mp4 链接
            val mp4 = Regex("""https?://[^"'\s\\]+\.mp4[^"'\s\\]*""").find(html)?.value
            if (!mp4.isNullOrBlank()) {
                return VideoInfo(title, "未知作者", mp4, "", "douyin")
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "HTML 兜底失败: ${e.message}")
            null
        }
    }

    /** 在 HTML 里直接找 "play_addr":{"url_list":["xxx"]} 的第一个 URL */
    private fun findPlayAddrUrl(html: String): String? {
        val patterns = listOf(
            Regex(""""play_addr"\s*:\s*\{[^}]*?"url_list"\s*:\s*\[\s*"([^"]+)""""),
            Regex(""""playAddr"\s*:\s*\{[^}]*?"urlList"\s*:\s*\[\s*"([^"]+)""""),
            Regex(""""url_list"\s*:\s*\[\s*"([^"]+play[^"]*\.mp4[^"]*)"""")
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            val u = m.groupValues[1]
                .replace("\\u002F", "/")
                .replace("\\/", "/")
            if (u.isNotBlank()) return u
        }
        return null
    }

    /** 在 HTML 里找封面图 URL */
    private fun findCoverUrl(html: String): String? {
        val patterns = listOf(
            Regex(""""cover"\s*:\s*\{[^}]*?"url_list"\s*:\s*\[\s*"([^"]+)""""),
            Regex(""""origin_cover"\s*:\s*\{[^}]*?"url_list"\s*:\s*\[\s*"([^"]+)"""")
        )
        for (p in patterns) {
            val m = p.find(html) ?: continue
            return m.groupValues[1].replace("\\u002F", "/").replace("\\/", "/")
        }
        return null
    }

    // ===================== 快手解析（基础版） =====================

    private fun parseKuaishou(shareUrl: String): VideoInfo {
        // 快手短链同样会 302 到 https://www.kuaishou.com/short-video/XXX
        val resolved = resolveFirstRedirect(shareUrl) ?: shareUrl
        val html = httpGet(resolved)
        if (html.isBlank()) throw IllegalStateException("快手页面获取失败")

        // 快手页面里通常会有 "photoUrl":"xxx.mp4" 之类
        val mp4 = Regex(""""photoUrl"\s*:\s*"([^"]+\.mp4[^"]*)"""").find(html)
            ?: Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""").find(html)
            ?: throw IllegalStateException("未在快手页面找到视频地址")

        var url = mp4.groupValues.getOrElse(1) { mp4.value }
            .replace("\\u002F", "/")
            .replace("\\/", "/")
        val finalUrl = resolveFinalUrl(url) ?: url

        val titleMatch = Regex("""<title>([^<]+)</title>""").find(html)
        val title = titleMatch?.groupValues?.get(1)?.trim() ?: "快手视频"
        return VideoInfo(title, "未知作者", finalUrl, "", "kuaishou")
    }

    // ===================== 小红书解析 =====================

    /**
     * 小红书解析：
     * 短链 http://xhslink.com/o/xxx  302 →
     *   https://www.xiaohongshu.com/discovery/item/{noteId}?xsec_token=xxx&type=video&...
     *
     * 重点：跳转后的 URL 必须原样使用（xsec_token 是访问凭证，丢掉会拿不到数据）。
     * 笔记页 HTML 里有 window.__INITIAL_STATE__ = {...}，视频直链藏在里面，
     * 字段路径不固定，但可递归查找：
     *   - masterUrl   （完整直链，最可靠，形如 http://sns-video-v6.xhscdn.com/.../xx.mp4?sign=...）
     *   - originVideoKey（需要拼接前缀 https://sns-video-bd.xhscdn.com/{key}）
     */
    private fun parseXiaohongshu(shareUrl: String): VideoInfo {
        // 1. 短链 302 拿到真实 URL（带 xsec_token，必须保留）
        val resolvedUrl = resolveFirstRedirect(shareUrl) ?: shareUrl
        Log.i(TAG, "小红书跳转后 URL: $resolvedUrl")

        // 2. 直接用 resolvedUrl 请求页面（不要重新拼接，否则丢 token）
        val html = httpGet(resolvedUrl)
        if (html.isBlank()) throw IllegalStateException("小红书页面获取失败")

        // 提取标题（<title>）作为兜底标题
        val htmlTitle = Regex("""<title>([^<]+)</title>""").find(html)
            ?.groupValues?.get(1)?.trim()?.ifBlank { null }

        // 3. 从 HTML 里解析 __INITIAL_STATE__ JSON
        val stateJson = extractJsonObject(html, "__INITIAL_STATE__")
        if (stateJson != null) {
            // 标题 / 作者：递归找（字段位置不固定）
            val title = deepFindString(stateJson, "title")?.takeIf { it.isNotBlank() }
                ?: deepFindString(stateJson, "desc")?.takeIf { it.isNotBlank() }
                ?: htmlTitle
                ?: "小红书视频"
            val author = deepFindString(stateJson, "nickname")
                ?: deepFindString(stateJson, "name")
                ?: "未知作者"

            // 主路径：递归找 masterUrl（完整直链，最可靠）
            val masterUrl = deepFindString(stateJson, "masterUrl")
            if (!masterUrl.isNullOrBlank() && masterUrl.startsWith("http")) {
                Log.i(TAG, "小红书视频直链(masterUrl): $masterUrl")
                return VideoInfo(title, author, masterUrl, "", "xiaohongshu")
            }

            // 兜底1：递归找 originVideoKey，拼前缀
            val originKey = deepFindString(stateJson, "originVideoKey")
            if (!originKey.isNullOrBlank()) {
                val videoUrl = "https://sns-video-bd.xhscdn.com/$originKey"
                Log.i(TAG, "小红书视频直链(originVideoKey): $videoUrl")
                return VideoInfo(title, author, videoUrl, "", "xiaohongshu")
            }
        }

        // 兜底2：HTML 里正则找。注意 body 里的 URL 含 \u002F 转义，先替换
        val unescaped = html.replace("\\u002F", "/").replace("\\/", "/")
        // 优先选带 sign 参数的主 CDN 直链（sns-video），其次选任意 mp4 URL
        val mp4WithSign = Regex("""https?://sns-video[^"'\s]+\.mp4\?[^"'\s]*sign=[^"'\s]*""")
            .find(unescaped)?.value
        val mp4 = mp4WithSign
            ?: Regex("""https?://[^"'\s]+\.mp4\?[^"'\s]*sign=[^"'\s]*""").find(unescaped)?.value
            ?: Regex("""https?://[^"'\s]+\.mp4[^"'\s]*""").find(unescaped)?.value
        if (!mp4.isNullOrBlank()) {
            Log.i(TAG, "小红书视频直链(正则mp4): $mp4")
            return VideoInfo(htmlTitle ?: "小红书视频", "未知作者", mp4, "", "xiaohongshu")
        }
        val keyMatch = Regex(""""originVideoKey"\s*:\s*"([^"]+)"""").find(unescaped)
        if (keyMatch != null) {
            val u = "https://sns-video-bd.xhscdn.com/${keyMatch.groupValues[1]}"
            return VideoInfo(htmlTitle ?: "小红书视频", "未知作者", u, "", "xiaohongshu")
        }

        throw IllegalStateException("小红书视频解析失败，可能不是视频笔记或接口已变更")
    }

    /** 从 URL 里提取小红书 noteId（仅用于日志展示，实际请求用完整 URL） */
    private fun extractXhsNoteId(url: String): String? {
        val patterns = listOf(
            Regex("""/explore/([a-zA-Z0-9]+)"""),
            Regex("""/discovery/item/([a-zA-Z0-9]+)"""),
            Regex("""noteId=([a-zA-Z0-9]+)""")
        )
        for (p in patterns) {
            val m = p.find(url)
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /** 取 JsonObject 的第一个子对象（用于 noteDetailMap 里只有一项时） */
    private fun firstChildObject(obj: com.google.gson.JsonObject): com.google.gson.JsonObject? {
        for ((_, v) in obj.entrySet()) {
            if (v.isJsonObject) return v.asJsonObject
        }
        return null
    }

    // ===================== B站解析 =====================

    /**
     * B站解析（高清版）：
     * 1. 短链 b23.tv/xxx  302 → bilibili.com/video/BVxxx
     * 2. H5 页面 __INITIAL_STATE__ 取 bvid/cid/title/author
     * 3. 调 playurl API（fnval=16 dash，qn=127 4K，fourk=1）拿 dash 流
     *    - 带 SESSDATA cookie（如有）→ 1080p/4K
     *    - 无 cookie → 480p（非登录态上限）
     * 4. 从 dash.video 选 id 最大的（最高清），dash.audio 选 id 最大的（最高音质）
     * 5. 返回 VideoInfo(isDash=true)，由 DownloadManager 下载两个 m4s 后用 BiliMuxer 合成
     */
    private fun parseBilibili(shareUrl: String, context: android.content.Context?): VideoInfo {
        // 1. 短链 302 拿到最终 URL
        val resolvedUrl = resolveFirstRedirect(shareUrl) ?: shareUrl
        Log.i(TAG, "B站跳转后 URL: $resolvedUrl")

        // 2. 请求 H5 页面
        val html = httpGet(resolvedUrl)
        if (html.isBlank()) throw IllegalStateException("B站页面获取失败")

        // 标题兜底
        val htmlTitle = Regex("""<title>([^<]+)</title>""").find(html)
            ?.groupValues?.get(1)?.trim()
            ?.substringBefore("_哔哩哔哩")
            ?.substringBefore("_bilibili")
            ?.trim()
            ?.ifBlank { null }

        // 3. 提取 __INITIAL_STATE__
        val stateJson = extractJsonObject(html, "__INITIAL_STATE__")
            ?: throw IllegalStateException("B站 __INITIAL_STATE__ 提取失败")

        val title = deepFindString(stateJson, "title")?.takeIf { it.isNotBlank() }
            ?: htmlTitle ?: "B站视频"
        val author = deepFindString(stateJson, "name")
            ?: deepFindString(stateJson, "nickname")
            ?: "未知UP主"
        val bvid = deepFindString(stateJson, "bvid")
            ?: Regex("""/video/(BV[A-Za-z0-9]+)""").find(resolvedUrl)?.groupValues?.get(1)
            ?: throw IllegalStateException("B站 bvid 提取失败")
        val cid = deepFindString(stateJson, "cid")
            ?: throw IllegalStateException("B站 cid 提取失败")
        Log.i(TAG, "B站 bvid=$bvid cid=$cid")

        // 4. 调 playurl API
        val cookie = context?.let { BiliCookieStore.getCookieHeader(it) }
        val qualityLabel = if (cookie != null) "高清(登录态)" else "480P(未登录)"
        Log.i(TAG, "B站 cookie: ${if (cookie != null) "有" else "无"}，画质预期: $qualityLabel")

        val apiUrl = "https://api.bilibili.com/x/player/playurl?bvid=$bvid" +
            "&cid=$cid&qn=127&fnval=16&fnver=0&fourk=1"
        val resp = httpGetWithCookie(apiUrl, cookie, "https://www.bilibili.com/video/$bvid")
        val root = JsonParser.parseString(resp).asJsonObject
        val code = root.safeInt("code") ?: -1
        if (code != 0) {
            throw IllegalStateException("B站 playurl API 失败: code=$code ${root.safeStr("message")}")
        }
        val data = root.safeObj("data") ?: throw IllegalStateException("B站 playurl 无 data")
        val dash = data.safeObj("dash")
            ?: throw IllegalStateException("B站 playurl 未返回 dash 流（可能该视频不支持 dash）")

        val realQn = data.safeInt("quality") ?: 0
        val realLabel = qualityLabelForQn(realQn)
        Log.i(TAG, "B站实际画质 qn=$realQn ($realLabel)")

        // 5. 从 dash.video 选最高清晰度（按 id 降序，avc 优先于 hevc 以保兼容性）
        val videos = dash.getAsJsonArray("video") ?: throw IllegalStateException("dash 无视频流")
        var bestVideo = videos[0].asJsonObject
        for (i in 1 until videos.size()) {
            val cur = videos[i].asJsonObject
            val curId = cur.safeInt("id") ?: 0
            val bestId = bestVideo.safeInt("id") ?: 0
            // 优先选 id 大的；id 相同时选 avc（codecs 含 avc1）以兼容 MediaMuxer
            if (curId > bestId || (curId == bestId && (cur.safeStr("codecs")?.contains("avc1") == true))) {
                bestVideo = cur
            }
        }
        val videoUrl = (bestVideo.safeStr("baseUrl") ?: bestVideo.safeStr("base_url"))
            ?.replace("\\u002F", "/")?.replace("\\/", "/")
            ?: throw IllegalStateException("dash 视频流无 url")
        val w = bestVideo.safeInt("width") ?: 0
        val h = bestVideo.safeInt("height") ?: 0
        Log.i(TAG, "B站视频流: id=${bestVideo.safeInt("id")} ${w}x${h} ${bestVideo.safeStr("codecs")}")

        // 6. 从 dash.audio 选最高音质（按 id 降序）
        val videoUrlFinal = videoUrl
        val audioUrlFinal: String
        val audios = dash.getAsJsonArray("audio")
        if (audios != null && audios.size() > 0) {
            var bestAudio = audios[0].asJsonObject
            for (i in 1 until audios.size()) {
                val cur = audios[i].asJsonObject
                val curId = cur.safeInt("id") ?: 0
                val bestId = bestAudio.safeInt("id") ?: 0
                if (curId > bestId) bestAudio = cur
            }
            audioUrlFinal = (bestAudio.safeStr("baseUrl") ?: bestAudio.safeStr("base_url"))
                ?.replace("\\u002F", "/")?.replace("\\/", "/")
                ?: ""
            Log.i(TAG, "B站音频流: id=${bestAudio.safeInt("id")} ${bestAudio.safeStr("codecs")}")
        } else {
            audioUrlFinal = ""
        }

        return VideoInfo(
            title = title,
            author = author,
            videoUrl = videoUrlFinal,
            coverUrl = "",
            platform = "bilibili",
            isDash = audioUrlFinal.isNotBlank(),
            audioUrl = audioUrlFinal,
            qualityLabel = "$realLabel ${w}x${h}"
        )
    }

    /** B站 qn 码 → 清晰度中文名 */
    private fun qualityLabelForQn(qn: Int): String = when (qn) {
        120 -> "4K"
        116 -> "1080P60"
        112 -> "1080P+"
        80 -> "1080P"
        64 -> "720P"
        32 -> "480P"
        16 -> "360P"
        else -> "QN$qn"
    }

    // ===================== 工具方法 =====================

    private fun httpGet(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA_MOBILE)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", "https://www.douyin.com/")
            .get()
            .build()
        // 用跟随重定向的 client，避免 share 页 302 时拿到空 body
        return redirectClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && !resp.isRedirect) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            resp.body?.string() ?: ""
        }
    }

    /** 带 cookie 和自定义 referer 的 GET（用于 B站 playurl API） */
    private fun httpGetWithCookie(url: String, cookie: String?, referer: String): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", UA_MOBILE)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Referer", referer)
        if (!cookie.isNullOrBlank()) {
            builder.header("Cookie", cookie)
        }
        val req = builder.get().build()
        return redirectClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful && !resp.isRedirect) {
                throw IllegalStateException("HTTP ${resp.code}")
            }
            resp.body?.string() ?: ""
        }
    }

    /** 用一个会自动跟随重定向的 client 请求，拿到最终 URL */
    private fun resolveFinalUrl(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA_MOBILE)
                .header("Referer", "https://www.douyin.com/")
                .get()
                .build()
            redirectClient.newCall(req).execute().use { resp ->
                resp.request.url.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveFinalUrl 失败: ${e.message}")
            null
        }
    }

    /**
     * 从 HTML 文本里提取 varName = {...} 形式的 JSON 对象。
     * 用花括号平衡计数，避免非贪婪正则截断嵌套 JSON。
     * 支持两种结束方式：`</script>` 或 `;`（取先出现的）。
     */
    private fun extractJsonObject(html: String, varName: String): com.google.gson.JsonObject? {
        return try {
            // 先定位 varName = 的位置
            val startRegex = Regex("""$varName\s*=\s*\{""")
            val startMatch = startRegex.find(html) ?: return null
            val openBraceIdx = startMatch.range.last // 指向 '{'

            // 从 '{' 开始花括号平衡计数，找到匹配的 '}'
            var depth = 0
            var inString = false
            var escape = false
            var endIdx = -1
            var i = openBraceIdx
            while (i < html.length) {
                val c = html[i]
                if (escape) {
                    escape = false
                } else if (c == '\\') {
                    escape = true
                } else if (c == '"') {
                    inString = !inString
                } else if (!inString) {
                    when (c) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                endIdx = i
                                break
                            }
                        }
                    }
                }
                i++
            }
            if (endIdx < 0) return null

            val jsonStr = html.substring(openBraceIdx, endIdx + 1)
            // 清洗 JavaScript 特有的非 JSON 值（undefined/NaN/Infinity），
            // 小红书等平台的 __INITIAL_STATE__ 可能包含这些值，会导致 Gson 解析失败
            val sanitized = sanitizeJsJson(jsonStr)
            return try {
                JsonParser.parseString(sanitized).asJsonObject
            } catch (_: Exception) {
                // 可能是被 URL encode 过
                JsonParser.parseString(URLDecoder.decode(sanitized, "UTF-8")).asJsonObject
            }
        } catch (e: Exception) {
            Log.w(TAG, "extractJsonObject($varName) 失败: ${e.message}")
            null
        }
    }

    /**
     * 清洗 JSON 字符串中的 JavaScript 特有值（undefined/NaN/Infinity/-Infinity），
     * 替换为 null。仅替换出现在 JSON 值位置的（前面是 : , [ ，后面是 , } ]），
     * 不会误替换字符串内容中的 "undefined" 等文字。
     */
    private fun sanitizeJsJson(jsonStr: String): String {
        return jsonStr.replace(
            Regex("""([:,\[]\s*)(?:undefined|NaN|Infinity|-Infinity)(\s*[,}\]])"""),
            "$1null$2"
        )
    }

    /** 在 JSON 树里递归查找某个 key 下 url_list 的第一个 URL */
    private fun deepFindUrl(
        element: com.google.gson.JsonElement,
        parentKeys: List<String>
    ): String? {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                // 命中目标：当前对象含有 parentKeys 指定的字段，且对应值有 url_list
                if (parentKeys.all { obj.has(it) } || obj.has("url_list")) {
                    var target: com.google.gson.JsonElement? = obj
                    for (k in parentKeys) {
                        target = (target as? com.google.gson.JsonObject)?.safeObj(k)
                        if (target == null) break
                    }
                    val urlList = (target as? com.google.gson.JsonObject)
                        ?.getAsJsonArray("url_list")
                        ?: obj.getAsJsonArray("url_list")
                    if (urlList != null && urlList.size() > 0) {
                        return urlList[0].asString
                    }
                }
                for ((_, v) in obj.entrySet()) {
                    val r = deepFindUrl(v, parentKeys)
                    if (r != null) return r
                }
            }
            element.isJsonArray -> {
                for (e in element.asJsonArray) {
                    val r = deepFindUrl(e, parentKeys)
                    if (r != null) return r
                }
            }
        }
        return null
    }

    /** 在 JSON 树里递归查找第一个 key == targetKey 的字符串值 */
    private fun deepFindString(
        element: com.google.gson.JsonElement,
        targetKey: String
    ): String? {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                if (obj.has(targetKey) && obj.get(targetKey).isJsonPrimitive) {
                    return obj.get(targetKey).asString
                }
                for ((_, v) in obj.entrySet()) {
                    val r = deepFindString(v, targetKey)
                    if (r != null) return r
                }
            }
            element.isJsonArray -> {
                for (e in element.asJsonArray) {
                    val r = deepFindString(e, targetKey)
                    if (r != null) return r
                }
            }
        }
        return null
    }

    /** 在 JSON 树里递归查找第一个 key == targetKey 的数组 */
    private fun deepFindArray(
        element: com.google.gson.JsonElement,
        targetKey: String
    ): com.google.gson.JsonArray? {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                if (obj.has(targetKey) && obj.get(targetKey).isJsonArray) {
                    return obj.getAsJsonArray(targetKey)
                }
                for ((_, v) in obj.entrySet()) {
                    val r = deepFindArray(v, targetKey)
                    if (r != null) return r
                }
            }
            element.isJsonArray -> {
                for (e in element.asJsonArray) {
                    val r = deepFindArray(e, targetKey)
                    if (r != null) return r
                }
            }
        }
        return null
    }

    /** 在 JSON 树里递归查找第一个 key == targetKey 的整数值 */
    private fun deepFindInt(
        element: com.google.gson.JsonElement,
        targetKey: String
    ): Int? {
        when {
            element.isJsonObject -> {
                val obj = element.asJsonObject
                if (obj.has(targetKey) && obj.get(targetKey).isJsonPrimitive && !obj.get(targetKey).isJsonNull) {
                    try { return obj.get(targetKey).asInt } catch (_: Exception) {}
                }
                for ((_, v) in obj.entrySet()) {
                    val r = deepFindInt(v, targetKey)
                    if (r != null) return r
                }
            }
            element.isJsonArray -> {
                for (e in element.asJsonArray) {
                    val r = deepFindInt(e, targetKey)
                    if (r != null) return r
                }
            }
        }
        return null
    }

    // ---- Gson 便捷扩展 ----
    private fun com.google.gson.JsonObject.safeObj(key: String): com.google.gson.JsonObject? =
        if (this.has(key) && this.get(key).isJsonObject) this.getAsJsonObject(key) else null

    private fun com.google.gson.JsonObject.safeStr(key: String): String? =
        if (this.has(key) && this.get(key).isJsonPrimitive && !this.get(key).isJsonNull)
            this.get(key).asString else null

    private fun com.google.gson.JsonObject.safeInt(key: String): Int? =
        if (this.has(key) && this.get(key).isJsonPrimitive && !this.get(key).isJsonNull) {
            try { this.get(key).asInt } catch (_: Exception) { null }
        } else null
}
