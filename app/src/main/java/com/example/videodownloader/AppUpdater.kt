package com.example.videodownloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

internal object AppUpdater {
    const val RELEASES_PAGE = "https://github.com/godweiyang/ToolBox/releases/latest"
    private const val LATEST_API = "https://api.github.com/repos/godweiyang/ToolBox/releases/latest"
    private const val DOWNLOAD_BASE = "https://github.com/godweiyang/ToolBox/releases/download"
    private const val MAX_DOWNLOAD_ATTEMPTS = 4
    private const val PROGRESS_INTERVAL_MS = 200L

    /** 下载取消句柄：cancel() 同时中断网络请求，阻塞中的连接/读取会立即抛异常。 */
    class DownloadHandle {
        @Volatile var cancelled = false
            private set
        @Volatile var activeCall: okhttp3.Call? = null

        fun cancel() {
            cancelled = true
            activeCall?.cancel()
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // 下载专用 client：移动网络抖动多，读超时放宽、连接失败自动重试
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun fetchLatest(): ReleaseInfo {
        return runCatching { fetchFromApi() }.getOrElse { fetchFromRedirect() }
    }

    private fun fetchFromApi(): ReleaseInfo {
        val request = Request.Builder().url(LATEST_API)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ToolBox-Android-Updater")
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub ${response.code}" }
            val root = JsonParser.parseString(response.body?.string().orEmpty()).asJsonObject
            val tag = root.get("tag_name")?.asString.orEmpty()
            require(tag.isNotBlank()) { "missing tag" }
            val assets = root.getAsJsonArray("assets")
            val apk = assets?.mapNotNull { element ->
                val item = element.asJsonObject
                val name = item.get("name")?.asString.orEmpty()
                val url = item.get("browser_download_url")?.asString
                if (name.endsWith(".apk", true) && url?.startsWith("https://") == true) url else null
            }?.firstOrNull()
            val page = root.get("html_url")?.asString?.takeIf { it.startsWith("https://") }
                ?: RELEASES_PAGE
            return ReleaseInfo(tag, root.get("name")?.asString ?: tag,
                root.get("body")?.asString.orEmpty(), page, apk)
        }
    }

    private fun fetchFromRedirect(): ReleaseInfo {
        val request = Request.Builder().url(RELEASES_PAGE)
            .header("User-Agent", "ToolBox-Android-Updater")
            .head().build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "GitHub page ${response.code}" }
            val page = response.request.url.toString()
            val tag = response.request.url.pathSegments.lastOrNull().orEmpty()
            require(tag.startsWith("v") && AppVersions.compare(tag, "0") > 0) { "missing release tag" }
            val apk = "$DOWNLOAD_BASE/$tag/ToolBox-$tag.apk"
            return ReleaseInfo(tag, tag, "", page, apk)
        }
    }

    /**
     * 下载 APK 到缓存目录。
     * - 移动网络中断/读超时：最多 4 轮尝试，基于 HTTP Range 从 .part 断点续传，不重头开始；
     * - onProgress 约每 200ms 回调一次：copied 已下载字节、total 总字节（-1 表示未知，
     *   此时 UI 应显示不确定进度条）、speedBps 瞬时速度（字节/秒）。
     */
    fun downloadApk(
        context: Context,
        info: ReleaseInfo,
        handle: DownloadHandle = DownloadHandle(),
        onProgress: (copied: Long, total: Long, speedBps: Long) -> Unit
    ): File {
        val url = requireNotNull(info.apkUrl) { "no apk asset" }
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val target = dir.resolve("ToolBox-${info.tag}.apk")
        val part = dir.resolve("ToolBox-${info.tag}.apk.part")

        var lastError: Exception? = null
        for (attempt in 1..MAX_DOWNLOAD_ATTEMPTS) {
            if (handle.cancelled) throw java.util.concurrent.CancellationException("download cancelled")
            try {
                streamOnce(url, part, handle, onProgress)
                require(part.length() > 1024L) { "downloaded apk is empty" }
                part.inputStream().use { input ->
                    val magic = ByteArray(4)
                    require(input.read(magic) == 4 &&
                        magic.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))) {
                        "download is not an apk archive"
                    }
                }
                if (target.exists()) target.delete()
                check(part.renameTo(target)) { "rename apk failed" }
                return target
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (handle.cancelled) throw java.util.concurrent.CancellationException("download cancelled")
                lastError = e
                if (attempt < MAX_DOWNLOAD_ATTEMPTS) interruptibleSleep(1500L * attempt, handle)
            }
        }
        throw lastError ?: IllegalStateException("download failed")
    }

    private fun interruptibleSleep(ms: Long, handle: DownloadHandle) {
        var waited = 0L
        while (waited < ms) {
            if (handle.cancelled) throw java.util.concurrent.CancellationException("download cancelled")
            val step = minOf(200L, ms - waited)
            Thread.sleep(step)
            waited += step
        }
    }

    private fun streamOnce(
        url: String,
        part: File,
        handle: DownloadHandle,
        onProgress: (copied: Long, total: Long, speedBps: Long) -> Unit
    ) {
        val existing = if (part.exists()) part.length() else 0L
        val builder = Request.Builder().url(url).header("User-Agent", "ToolBox-Android-Updater")
        if (existing > 0) builder.header("Range", "bytes=$existing-")
        val call = downloadClient.newCall(builder.build())
        handle.activeCall = call
        call.execute().use { response ->
            if (response.code == 416) {
                // 本地 .part 比服务器文件还大（换了更低版本等），删掉重下
                part.delete()
                throw java.io.IOException("range not satisfiable")
            }
            check(response.code in 200..299) { "download HTTP ${response.code}" }
            val body = requireNotNull(response.body) { "empty response body" }

            val resumed = response.code == 206
            val start = if (resumed) existing else 0L
            if (!resumed && existing > 0) part.delete()

            // 总大小：206 时从 Content-Range 解析完整长度，否则取 Content-Length（可能为 -1）
            var total = body.contentLength()
            response.header("Content-Range")?.let { range ->
                Regex("bytes\\s+\\d+-\\d+/(\\d+)").find(range)?.let { m ->
                    m.groupValues[1].toLongOrNull()?.let { total = it }
                }
            }

            var copied = start
            FileOutputStream(part, resumed).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var lastEmit = 0L
                    var lastEmitCopied = copied
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (handle.cancelled) throw java.util.concurrent.CancellationException("download cancelled")
                        output.write(buffer, 0, read)
                        copied += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit >= PROGRESS_INTERVAL_MS) {
                            val speed = if (lastEmit > 0L)
                                (copied - lastEmitCopied) * 1000 / (now - lastEmit) else 0L
                            onProgress(copied, total, speed)
                            lastEmit = now
                            lastEmitCopied = copied
                        }
                    }
                }
            }
            onProgress(copied, total, 0L)
        }
    }

    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun openBrowser(context: Context, url: String = RELEASES_PAGE) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
