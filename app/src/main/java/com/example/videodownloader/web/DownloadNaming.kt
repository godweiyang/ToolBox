package com.example.videodownloader.web

/**
 * 下载文件名解析与安全清洗（纯逻辑，JVM 单测）。
 *
 * 职责：
 *  1. 从 Content-Disposition（filename*= / filename=）解析文件名；
 *  2. 缺失时回退到 URL 路径最后一段；
 *  3. 仍无扩展名时按 MIME 补一个；
 *  4. 统一清洗：只取 basename、剥离 `../` 路径穿越、去掉非法字符与控制字符。
 *
 * 安全目标：无论服务端/页面给出什么名字，落盘文件名都不会逃逸到下载目录之外。
 */
object DownloadNaming {

    private val ILLEGAL = Regex("[\\\\/:*?\"<>|\\r\\n\\u0000-\\u001f]")

    /** 解析出一个安全的落盘文件名（不含任何目录分隔符）。 */
    fun resolve(url: String?, contentDisposition: String?, mimeType: String?): String {
        var name = fromContentDisposition(contentDisposition)
            ?: fromUrlPath(url)
            ?: "download"

        name = sanitize(name)
        if (name.isEmpty() || name == "." || name == "..") name = "download"

        if (!name.contains('.')) {
            extensionForMime(mimeType)?.let { ext -> name = "$name.$ext" }
        }
        return name.take(120).trimEnd('.', ' ')
    }

    internal fun fromContentDisposition(cd: String?): String? {
        if (cd.isNullOrBlank()) return null
        for (raw in cd.split(';')) {
            val part = raw.trim()
            if (part.startsWith("filename*=", ignoreCase = true)) {
                val v = part.substringAfter('=').trim()
                // 形如：UTF-8''<percent-encoded>
                val enc = if (v.contains("''")) v.substringAfter("''") else v
                percentDecode(enc).takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        for (raw in cd.split(';')) {
            val part = raw.trim()
            if (part.startsWith("filename=", ignoreCase = true)) {
                val v = part.substringAfter('=').trim().removeSurrounding("\"")
                if (v.isNotBlank()) return v
            }
        }
        return null
    }

    internal fun fromUrlPath(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val path = url.substringBefore('?').substringBefore('#')
        val seg = path.substringAfterLast('/')
        return seg.ifBlank { null }
    }

    /** 只保留 basename，并去掉非法字符 / 路径穿越。 */
    internal fun sanitize(raw: String): String {
        var n = raw.replace('\\', '/').substringAfterLast('/')
        n = n.replace(ILLEGAL, "_")
        n = n.replace("..", "_")
        return n.trim().trim('.', ' ')
    }

    fun extensionForMime(mimeType: String?): String? {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        return when (mime) {
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            "application/vnd.ms-excel" -> "xls"
            "text/csv", "application/csv" -> "csv"
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            "video/mp4" -> "mp4"
            "text/plain" -> "txt"
            "application/json" -> "json"
            else -> null
        }
    }

    internal fun percentDecode(s: String): String {
        if (!s.contains('%')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length) {
                val hex = s.substring(i + 1, i + 3)
                val v = hex.toIntOrNull(16)
                if (v != null) {
                    sb.append(v.toChar()); i += 3; continue
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }
}
