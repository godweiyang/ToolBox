package com.example.videodownloader.web

/**
 * WebView 导航路由策略（纯逻辑，不依赖 Android 框架，可在 JVM 单测）。
 *
 * 安全边界：
 *  - 应用内资产源 `https://appassets.androidplatform.net/...` 一律在 WebView 内打开，
 *    这同时覆盖了 /lol、/pubg、/fangdai 三个页面以及它们之间的相互跳转（/lol ↔ /pubg）。
 *  - 仅放行本机回环 HTTP（127.0.0.1 / localhost / ::1），供离线页面本地回环使用。
 *  - 其它任意 http(s) 外链 → 交给系统浏览器打开，不在 WebView 内加载。
 *  - 任意 `file://` 导航一律拦截，杜绝 file:// 跨域访问本地文件。
 *  - mailto:/tel:/sms:/intent: 等交给系统处理。
 */
object WebRoutePolicy {

    /** 与 WebViewAssetLoader 默认约定一致的资产源主机名 */
    const val ASSET_ORIGIN_HOST = "appassets.androidplatform.net"

    enum class Decision {
        /** 在 WebView 内继续加载 */
        ALLOW_IN_APP,
        /** 取消 WebView 内导航，改用外部浏览器打开 */
        OPEN_EXTERNAL_BROWSER,
        /** 拦截，不做任何处理 */
        BLOCK
    }

    fun decide(rawUrl: String?): Decision {
        val url = rawUrl?.trim().orEmpty()
        if (url.isEmpty()) return Decision.BLOCK
        // WebView 自身容器页
        if (url.startsWith("about:", ignoreCase = true)) return Decision.ALLOW_IN_APP

        val scheme = schemeOf(url)?.lowercase() ?: return Decision.BLOCK
        return when (scheme) {
            "file" -> Decision.BLOCK
            "https" ->
                if (hostOf(url).equals(ASSET_ORIGIN_HOST, ignoreCase = true)) Decision.ALLOW_IN_APP
                else Decision.OPEN_EXTERNAL_BROWSER
            "http" ->
                if (isLoopback(url)) Decision.ALLOW_IN_APP
                else Decision.OPEN_EXTERNAL_BROWSER
            else -> Decision.OPEN_EXTERNAL_BROWSER
        }
    }

    /** 是否为应用内资产源（用于 shouldInterceptRequest 判定是否由本地 assets 提供） */
    fun isAssetOrigin(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        return schemeOf(u).equals("https", ignoreCase = true) &&
            hostOf(u).equals(ASSET_ORIGIN_HOST, ignoreCase = true)
    }

    /** 是否为本机回环地址（127.0.0.1 / localhost / ::1） */
    fun isLoopback(url: String?): Boolean {
        val h = hostOf(url?.trim().orEmpty())?.lowercase() ?: return false
        return h == "127.0.0.1" || h == "localhost" || h == "::1" || h == "[::1]"
    }

    /**
     * 把子资源路径解析为本地 assets 下的相对路径。
     * 覆盖两类入口：
     *  - /assets/<tool>/...           → assets/<tool>/...
     *  - /<tool>/...  (lol/pubg/fangdai 的对外友好路径) → assets/<tool>/...
     * 目录请求（/lol/ 或 /lol）自动补 index.html。
     * 不认识的顶层段返回 null（交网络/拦截方处理）。
     */
    fun resolveAssetPath(path: String?): String? {
        val p = path?.trim().orEmpty()
        if (p.startsWith("/assets/")) return p.removePrefix("/assets/").ifEmpty { null }
        for (tool in WebToolRegistry.ids) {
            if (p == "/$tool" || p == "/$tool/" || p.startsWith("/$tool/")) {
                var rest = p.removePrefix("/$tool").removePrefix("/")
                if (rest.isEmpty() || rest.endsWith("/")) return "$tool/$rest" + WebToolRegistry.ASSET_INDEX
                return "$tool/$rest"
            }
        }
        return null
    }

    /**
     * 子资源层面：是否拦截任意「非回环」HTTP（明文）请求。
     * 回环（127.0.0.1/localhost）是离线工具必需的本地服务通道；
     * 其它 http:// 一律拦截，避免从 HTTPS 页面加载任意明文资源。
     */
    fun isBlockedHttpSubResource(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        return schemeOf(u).equals("http", true) && !isLoopback(u)
    }

    /** 是否应作为「新窗口/外部链接」交给系统浏览器（含自定义协议如 lolzjcx://）。 */
    fun isExternalScheme(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        val s = schemeOf(u)?.lowercase() ?: return false
        return s != "http" && s != "https" && !u.startsWith("about:")
    }

    // ---- 最小 URL 解析（避免依赖 android.net.Uri，便于 JVM 单测）----

    internal fun schemeOf(url: String): String? {
        val idx = url.indexOf("://")
        if (idx in 1 until url.length) return url.substring(0, idx)
        val colon = url.indexOf(':')
        if (colon in 1..7) return url.substring(0, colon) // about:blank / mailto:
        return null
    }

    internal fun hostOf(url: String): String? {
        val start = when {
            url.startsWith("https://", ignoreCase = true) -> 8
            url.startsWith("http://", ignoreCase = true) -> 7
            else -> return null
        }
        val rest = url.substring(start)
        val end = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
        var host = rest.substring(0, end)
        val at = host.lastIndexOf('@')
        if (at >= 0) host = host.substring(at + 1)
        val colon = host.lastIndexOf(':')
        if (colon > 0) host = host.substring(0, colon)
        return host.ifEmpty { null }
    }
}
