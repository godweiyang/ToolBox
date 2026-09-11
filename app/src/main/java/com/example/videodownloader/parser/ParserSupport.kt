package com.example.videodownloader.parser

import android.util.Base64
import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * 各平台解析器共用的 HTTP / JSON / HTML 工具层。
 *
 * 这一层是从 ucmao/media-parser（Python，requests + 正则/JSON）移植到 Android(OkHttp + Gson)
 * 后的公共底座：统一 UA、CookieJar、GET/POST、手动重定向跟踪、HTML 内 JSON 抽取、
 * 深度优先 JSON 查找、meta / <video> 标签提取、md5/base64 等。
 *
 * 设计为无状态、纯函数式，供 [PlatformRegistry] 注册的各平台 parser 复用。
 */
internal object ParserSupport {

    const val TAG = "ParserSupport"

    // ---------------- UA 池 ----------------
    /** iPhone Safari，多数 H5 分享页返回最完整的移动页面 */
    const val UA_IPHONE =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
            "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

    /** Android Chrome，部分平台（皮皮虾/微视等）按安卓 UA 返回直链 */
    const val UA_ANDROID =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 桌面 Chrome，用于需要 PC 页面的站点（知乎/得物/新片场等） */
    const val UA_PC =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Safari/537.36"

    /** 抖音系 App 内 UA（移动 feed 接口需要） */
    const val UA_DOUYIN_APP =
        "com.ss.android.ugc.aweme/280501 (Linux; U; Android 13; zh_CN; Pixel 7; Build/TQ3A.230705.001; " +
            "Cronet/TTNetVersion:b4d74d15 2023-06-25 QuicVersion:0144d358 2023-06-09)"

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // ---------------- OkHttp 客户端 ----------------
    private val cookieJar = SharedCookieJar()

    private val followClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .cookieJar(cookieJar)
            .build()
    }

    private val noRedirectClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(cookieJar)
            .build()
    }

    private fun client(follow: Boolean, timeoutSec: Int = 25): OkHttpClient {
        val base = if (follow) followClient else noRedirectClient
        return if (timeoutSec == 25) base
        else base.newBuilder().readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS).build()
    }

    /** 统一响应包装 */
    class Resp(
        val code: Int,
        val finalUrl: String,
        val body: String,
        val headers: Headers,
    ) {
        val ok: Boolean get() = code in 200..299
    }

    // ---------------- HTTP 方法 ----------------

    fun get(
        url: String,
        ua: String = UA_IPHONE,
        referer: String = "",
        origin: String = "",
        cookie: String = "",
        extra: Map<String, String> = emptyMap(),
        follow: Boolean = true,
        timeoutSec: Int = 25,
    ): Resp {
        val b = Request.Builder().url(url).get()
            .header("User-Agent", ua)
            .header("Accept", "*/*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (referer.isNotBlank()) b.header("Referer", referer)
        if (origin.isNotBlank()) b.header("Origin", origin)
        if (cookie.isNotBlank()) b.header("Cookie", cookie)
        extra.forEach { (k, v) -> b.header(k, v) }
        client(follow, timeoutSec).newCall(b.build()).execute().use { r ->
            return Resp(r.code, r.request.url.toString(), r.body?.string() ?: "", r.headers)
        }
    }

    fun postJson(
        url: String,
        json: String,
        ua: String = UA_IPHONE,
        referer: String = "",
        origin: String = "",
        cookie: String = "",
        extra: Map<String, String> = emptyMap(),
        follow: Boolean = true,
        timeoutSec: Int = 25,
    ): Resp {
        val b = Request.Builder().url(url).post(json.toRequestBody(JSON_MEDIA))
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        if (referer.isNotBlank()) b.header("Referer", referer)
        if (origin.isNotBlank()) b.header("Origin", origin)
        if (cookie.isNotBlank()) b.header("Cookie", cookie)
        extra.forEach { (k, v) -> b.header(k, v) }
        client(follow, timeoutSec).newCall(b.build()).execute().use { r ->
            return Resp(r.code, r.request.url.toString(), r.body?.string() ?: "", r.headers)
        }
    }

    fun postForm(
        url: String,
        form: Map<String, String>,
        ua: String = UA_IPHONE,
        referer: String = "",
        origin: String = "",
        follow: Boolean = true,
        timeoutSec: Int = 25,
    ): Resp {
        val fb = FormBody.Builder()
        form.forEach { (k, v) -> fb.add(k, v) }
        val b = Request.Builder().url(url).post(fb.build())
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
        if (referer.isNotBlank()) b.header("Referer", referer)
        if (origin.isNotBlank()) b.header("Origin", origin)
        client(follow, timeoutSec).newCall(b.build()).execute().use { r ->
            return Resp(r.code, r.request.url.toString(), r.body?.string() ?: "", r.headers)
        }
    }

    /**
     * 手动跟随重定向链（不自动跟随），返回最终落地 URL。
     * 用于需要拿到 302 后真实地址（含 query 凭证）的场景。
     */
    fun resolveRedirects(url: String, ua: String = UA_IPHONE, referer: String = "", max: Int = 10): String {
        var current = url
        repeat(max) {
            try {
                val b = Request.Builder().url(current).get()
                    .header("User-Agent", ua)
                    .header("Accept", "text/html,application/xhtml+xml,*/*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                if (referer.isNotBlank()) b.header("Referer", referer)
                noRedirectClient.newCall(b.build()).execute().use { r ->
                    if (r.isRedirect) {
                        val loc = r.header("Location") ?: return current
                        current = absolutize(loc, current)
                    } else {
                        return r.request.url.toString()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolveRedirects 失败: ${e.message}")
                return current
            }
        }
        return current
    }

    /** 请求一次直链并跟随 302，返回最终 CDN 地址（不解析 body 内容） */
    fun resolveFinalUrl(url: String, ua: String = UA_ANDROID, referer: String = ""): String {
        return try {
            get(url, ua = ua, referer = referer, follow = true).finalUrl
        } catch (e: Exception) {
            Log.w(TAG, "resolveFinalUrl 失败: ${e.message}")
            url
        }
    }

    // ---------------- URL / 文本工具 ----------------

    /** 从分享文案中提取第一条 http(s) 链接 */
    fun extractShareUrl(text: String): String? {
        if (text.isBlank()) return null
        val m = Regex("""https?://[^\s，,。、；;！!？?【】\]\[）)]+""").find(text) ?: return null
        var v = m.value.trimEnd('/', '，', ',', '。', '、', '；', ';', '）', ')', '】')
        if (v.endsWith("http") || v.endsWith("https")) return null
        return v.ifBlank { null }
    }

    fun host(url: String): String = try {
        url.toHttpUrlOrNull()?.host?.lowercase()
            ?: Regex("""https?://([^/]+)""").find(url)?.groupValues?.get(1)?.lowercase()?.substringBefore(':')
            ?: ""
    } catch (_: Exception) {
        ""
    }

    fun path(url: String): String = try {
        url.toHttpUrlOrNull()?.encodedPath ?: ""
    } catch (_: Exception) {
        ""
    }

    fun query(url: String, key: String): String? = try {
        url.toHttpUrlOrNull()?.queryParameter(key)
    } catch (_: Exception) {
        Regex("[?&]$key=([^&]*)").find(url)?.groupValues?.get(1)?.let { urlDecode(it) }
    }

    fun urlDecode(s: String): String = try {
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8").replace("%2B", "+")
    } catch (_: Exception) {
        s
    }

    /** 把可能的相对 URL 补成绝对地址 */
    fun absolutize(u: String, baseUrl: String): String {
        if (u.startsWith("http")) return u
        return try {
            val base = baseUrl.toHttpUrlOrNull()!!
            when {
                u.startsWith("//") -> "${base.scheme}:$u"
                else -> base.resolve(u)?.toString()
                    ?: (base.scheme + "://" + base.host + "/" + u.trimStart('/'))
            }
        } catch (_: Exception) {
            u
        }
    }

    fun isM3u8(u: String): Boolean = u.contains(".m3u8", ignoreCase = true)
    fun isAudio(u: String): Boolean {
        val l = u.lowercase()
        return l.contains(".mp3") || l.contains(".aac") || l.contains(".m4a") ||
            l.contains("/music/") || l.contains("music.play_addr")
    }

    fun md5Hex(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** base64 解码为字符串，标准/URL-safe 两种都尝试（用于 VOD playAuthToken 等） */
    fun base64Decode(s: String): String? = try {
        String(Base64.decode(s, Base64.DEFAULT), Charsets.UTF_8)
    } catch (_: Exception) {
        try {
            String(Base64.decode(s, Base64.URL_SAFE), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun randHex(len: Int): String {
        val cs = "0123456789abcdef"
        return (1..len).map { cs.random() }.joinToString("")
    }

    /** JSON / HTML 常见转义还原 */
    fun unescape(s: String?): String {
        if (s == null) return ""
        return s.replace("\\u002F", "/").replace("\\u002f", "/")
            .replace("\\/", "/").replace("&amp;", "&")
            .replace("\\x26amp;", "&")
    }

    fun unescapeHtml(s: String?): String {
        if (s == null) return ""
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
    }

    fun firstMatch(regex: Regex, text: String, group: Int = 1): String? =
        regex.find(text)?.let { if (group < it.groupValues.size) it.groupValues[group] else it.value }

    // ---------------- HTML 提取 ----------------

    fun htmlTitle(html: String): String? =
        firstMatch(Regex("""<title>([^<]*)</title>""", RegexOption.IGNORE_CASE), html)?.trim()

    /**
     * 取 meta 标签 content，按 property（og:xxx）或 name 匹配。
     * 不假设属性顺序，先截出含目标名的整个 <meta ...> 块再读 content。
     */
    fun meta(html: String, key: String): String? {
        val tag = Regex("""<meta[^>]*>""", RegexOption.IGNORE_CASE).findAll(html)
            .map { it.value }.firstOrNull { it.contains(key, ignoreCase = true) }
            ?: return null
        val c = Regex("""content\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)
            ?: return null
        return unescapeHtml(c.groupValues[1]).trim().ifBlank { null }
    }

    /** 取页面中第一个 <video src> / <source src>（通用兜底用） */
    fun tagVideoSrc(html: String): String? {
        // <video ... src="xxx"
        Regex("""<video\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?.let { return unescapeHtml(it.groupValues[1]) }
        // <video ...>...<source src="xxx">...</video>
        Regex("""<video\b[\s\S]*?</video>""", RegexOption.IGNORE_CASE).findAll(html).forEach { block ->
            Regex("""<source\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                .find(block.value)?.let { return unescapeHtml(it.groupValues[1]) }
        }
        // 任意 <source src>
        Regex("""<source\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(html)
            ?.let { return unescapeHtml(it.groupValues[1]) }
        return null
    }

    // ---------------- JSON 提取 ----------------

    fun parseJsonElement(s: String): JsonElement? = try {
        JsonParser.parseString(sanitizeJsJson(s))
    } catch (_: Exception) {
        try { JsonParser.parseString(urlDecode(sanitizeJsJson(s))) } catch (_: Exception) { null }
    }

    fun parseJsonObject(s: String): JsonObject? = parseJsonElement(s)?.takeIf { it.isJsonObject }?.asJsonObject

    /** 从 marker 之后第一个 '{' 起做花括号配平，截取完整 JSON 对象并解析 */
    fun extractJsonAfter(html: String, marker: String): JsonObject? {
        val idx = html.indexOf(marker)
        if (idx < 0) return null
        val brace = html.indexOf('{', idx + marker.length)
        if (brace < 0) return null
        return balancedObjectAt(html, brace)
    }

    /** 提取 `varName = { ... }` 形式的 JSON（花括号配平，容忍嵌套） */
    fun extractJsonVar(html: String, varName: String): JsonObject? {
        val m = Regex(Regex.escape(varName) + """\s*=\s*\{""").find(html) ?: return null
        // m.range.last 指向 '{'
        return balancedObjectAt(html, m.range.last)
    }

    /** 从 html[openBrace]（必须是 '{'）开始花括号配平，返回解析后的对象 */
    fun balancedObjectAt(html: String, openBrace: Int): JsonObject? {
        if (openBrace < 0 || openBrace >= html.length || html[openBrace] != '{') return null
        var depth = 0
        var inString = false
        var escape = false
        var i = openBrace
        while (i < html.length) {
            val c = html[i]
            when {
                escape -> escape = false
                c == '\\' -> escape = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> {
                    depth--
                    if (depth == 0) {
                        val raw = html.substring(openBrace, i + 1)
                        return parseJsonObject(raw)
                    }
                }
            }
            i++
        }
        return null
    }

    /** 清洗 JS 特有的非 JSON 值（undefined/NaN/Infinity）为 null */
    fun sanitizeJsJson(s: String): String =
        s.replace(Regex("""([:,\[]\s*)(?:undefined|NaN|Infinity|-Infinity)(\s*[,}\]])"""), "$1null$2")

    // ---------------- JSON 深度查找 ----------------

    fun deepStr(el: JsonElement?, key: String): String? {
        if (el == null) return null
        when {
            el.isJsonObject -> {
                val o = el.asJsonObject
                val v = o.get(key)
                if (v != null && v.isJsonPrimitive && !v.isJsonNull) {
                    val s = v.asString
                    if (s.isNotBlank()) return s
                }
                for ((_, child) in o.entrySet()) deepStr(child, key)?.let { return it }
            }
            el.isJsonArray -> for (c in el.asJsonArray) deepStr(c, key)?.let { return it }
        }
        return null
    }

    fun deepInt(el: JsonElement?, key: String): Int? {
        if (el == null) return null
        when {
            el.isJsonObject -> {
                val o = el.asJsonObject
                val v = o.get(key)
                if (v != null && v.isJsonPrimitive && !v.isJsonNull) {
                    try { return v.asInt } catch (_: Exception) {}
                }
                for ((_, child) in o.entrySet()) deepInt(child, key)?.let { return it }
            }
            el.isJsonArray -> for (c in el.asJsonArray) deepInt(c, key)?.let { return it }
        }
        return null
    }

    fun deepLong(el: JsonElement?, key: String): Long? {
        if (el == null) return null
        when {
            el.isJsonObject -> {
                val o = el.asJsonObject
                val v = o.get(key)
                if (v != null && v.isJsonPrimitive && !v.isJsonNull) {
                    try { return v.asLong } catch (_: Exception) {}
                }
                for ((_, child) in o.entrySet()) deepLong(child, key)?.let { return it }
            }
            el.isJsonArray -> for (c in el.asJsonArray) deepLong(c, key)?.let { return it }
        }
        return null
    }

    fun deepObj(el: JsonElement?, key: String): JsonObject? {
        if (el == null) return null
        when {
            el.isJsonObject -> {
                val o = el.asJsonObject
                val v = o.get(key)
                if (v != null && v.isJsonObject) return v.asJsonObject
                for ((_, child) in o.entrySet()) deepObj(child, key)?.let { return it }
            }
            el.isJsonArray -> for (c in el.asJsonArray) deepObj(c, key)?.let { return it }
        }
        return null
    }

    fun deepArr(el: JsonElement?, key: String): JsonArray? {
        if (el == null) return null
        when {
            el.isJsonObject -> {
                val o = el.asJsonObject
                val v = o.get(key)
                if (v != null && v.isJsonArray) return v.asJsonArray
                for ((_, child) in o.entrySet()) deepArr(child, key)?.let { return it }
            }
            el.isJsonArray -> for (c in el.asJsonArray) deepArr(c, key)?.let { return it }
        }
        return null
    }

    /**
     * 深度优先：找到第一个同时含 [parentKeys] 路径（或自身含 url_list）的对象，
     * 返回其 url_list 数组里的首个 http URL。
     */
    fun deepUrlList(el: JsonElement?, parentKeys: List<String> = emptyList()): String? {
        if (el == null) return null
        when {
            el.isJsonObject -> {
                val o = el.asJsonObject
                if (parentKeys.all { o.has(it) } || o.has("url_list")) {
                    var target: JsonElement? = o
                    for (k in parentKeys) {
                        target = (target as? JsonObject)?.let { it.get(k)?.takeIf { e -> e.isJsonObject } }
                        if (target == null) break
                    }
                    val arr = (target as? JsonObject)?.getAsJsonArray("url_list")
                        ?: o.getAsJsonArray("url_list")
                    pickFirstHttp(arr)?.let { return it }
                }
                for ((_, child) in o.entrySet()) deepUrlList(child, parentKeys)?.let { return it }
            }
            el.isJsonArray -> for (c in el.asJsonArray) deepUrlList(c, parentKeys)?.let { return it }
        }
        return null
    }

    /** 从 url_list 数组里挑第一个 http 链接（跳过 video_id= 嵌套死链） */
    fun pickFirstHttp(arr: JsonArray?, skipContains: String = ""): String? {
        if (arr == null) return null
        for (i in 0 until arr.size()) {
            val e = arr[i]
            if (e.isJsonPrimitive && !e.isJsonNull) {
                val u = unescape(e.asString)
                if (u.startsWith("http") && (skipContains.isBlank() || !u.contains(skipContains))) return u
            }
        }
        return null
    }

    /**
     * 深度优先收集所有满足 [predicate] 的字符串值（去重，保持出现顺序）。
     * 用于从 JSON 里搜集图集 / 多码率地址。
     */
    fun collectStrings(el: JsonElement?, out: LinkedHashSet<String>, predicate: (String) -> Boolean) {
        when {
            el == null -> {}
            el.isJsonPrimitive && !el.isJsonNull -> {
                val s = el.asString
                if (predicate(s)) out.add(unescape(s))
            }
            el.isJsonObject -> el.asJsonObject.entrySet().forEach { collectStrings(it.value, out, predicate) }
            el.isJsonArray -> el.asJsonArray.forEach { collectStrings(it, out, predicate) }
        }
    }

    /** 深度优先按候选 key 顺序返回第一个非空 http 字符串值（如 videoUrl/playUrl/url） */
    fun firstUrlByKeys(el: JsonElement?, keys: List<String>): String? {
        for (k in keys) {
            val s = deepStr(el, k)
            if (!s.isNullOrBlank() && s.startsWith("http")) return unescape(s)
        }
        return null
    }
}

// ---------------- JsonElement 便捷扩展（parser 包内统一使用） ----------------

internal fun JsonElement?.asObj(): JsonObject? =
    if (this != null && this.isJsonObject) this.asJsonObject else null

internal fun JsonElement?.asArr(): JsonArray? =
    if (this != null && this.isJsonArray) this.asJsonArray else null

internal fun JsonObject?.str(key: String): String? {
    val v = this?.get(key)
    return if (v != null && v.isJsonPrimitive && !v.isJsonNull) v.asString.ifBlank { null } else null
}

internal fun JsonObject?.int(key: String): Int? {
    val v = this?.get(key)
    return if (v != null && v.isJsonPrimitive && !v.isJsonNull) {
        try { v.asInt } catch (_: Exception) { null }
    } else null
}

internal fun JsonObject?.long(key: String): Long? {
    val v = this?.get(key)
    return if (v != null && v.isJsonPrimitive && !v.isJsonNull) {
        try { v.asLong } catch (_: Exception) { null }
    } else null
}

internal fun JsonObject?.obj(key: String): JsonObject? =
    this?.get(key)?.takeIf { it.isJsonObject }?.asJsonObject

internal fun JsonObject?.arr(key: String): JsonArray? =
    this?.get(key)?.takeIf { it.isJsonArray }?.asJsonArray

/** 跨请求共享的内存 CookieJar（按 host 保存） */
internal class SharedCookieJar : CookieJar {
    private val store = HashMap<String, MutableList<Cookie>>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val list = store.getOrPut(url.host) { mutableListOf() }
        for (c in cookies) {
            list.removeAll { it.name == c.name }
            list.add(c)
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = store[url.host] ?: emptyList()
}
