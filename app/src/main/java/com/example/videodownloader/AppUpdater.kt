package com.example.videodownloader

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

internal object AppUpdater {
    const val RELEASES_PAGE = "https://github.com/godweiyang/ToolBox/releases/latest"
    private const val LATEST_API = "https://api.github.com/repos/godweiyang/ToolBox/releases/latest"
    private const val DOWNLOAD_BASE = "https://github.com/godweiyang/ToolBox/releases/download"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
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

    fun downloadApk(context: Context, info: ReleaseInfo, onProgress: (Int) -> Unit): File {
        val url = requireNotNull(info.apkUrl) { "no apk asset" }
        val request = Request.Builder().url(url).header("User-Agent", "ToolBox-Android-Updater").build()
        val destination = File(context.cacheDir, "updates").apply { mkdirs() }
            .resolve("ToolBox-${info.tag}.apk")
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "download ${response.code}" }
            val body = requireNotNull(response.body)
            val total = body.contentLength()
            destination.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read); copied += read
                        if (total > 0) onProgress((copied * 100 / total).toInt().coerceIn(0, 100))
                    }
                }
            }
        }
        require(destination.length() > 1024L) { "downloaded apk is empty" }
        destination.inputStream().use { input ->
            val magic = ByteArray(4)
            require(input.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x50, 0x4B, 0x03, 0x04))) {
                "download is not an apk archive"
            }
        }
        return destination
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
