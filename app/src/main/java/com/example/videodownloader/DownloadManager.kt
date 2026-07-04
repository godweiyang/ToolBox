package com.example.videodownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/**
 * 视频下载器：把 [VideoInfo] 里的视频直链下载到本地相册。
 *
 * - Android 10 (API 29) 及以上使用 MediaStore 写入 Movies/VideoDownloader
 * - Android 9 及以下直接写到外部存储的 Movies/VideoDownloader，并通知 MediaScanner
 *
 * 下载过程通过 [onProgress] 回调百分比（0..100）。
 */
object DownloadManager {

    private const val TAG = "DownloadManager"
    private const val FOLDER_NAME = "VideoDownloader"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    /** 下载结果 */
    sealed class Result {
        data class Success(val filePath: String, val uri: Uri) : Result()
        data class Failure(val message: String) : Result()
    }

    /**
     * @param videoUrl 视频直链
     * @param displayName 想要的文件名（不带扩展名）
     * @param onProgress 进度回调，percent ∈ [0,100]
     */
    suspend fun download(
        context: Context,
        videoUrl: String,
        displayName: String,
        onProgress: (Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(videoUrl)
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .header("Referer", "https://www.douyin.com/")
                .header("Accept", "*/*")
                .get()
                .build()

            val fileName = sanitizeFileName(displayName) + ".mp4"

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext Result.Failure("下载失败：HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext Result.Failure("响应体为空")
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: -1L

                val (uri, output) = openOutput(context, fileName)
                    ?: return@withContext Result.Failure("无法创建输出文件")

                output.use { os ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(8 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastReported = -1
                        while (input.read(buffer).also { read = it } != -1) {
                            os.write(buffer, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val percent = (downloaded * 100 / totalBytes).toInt()
                                    .coerceIn(0, 100)
                                if (percent != lastReported) {
                                    lastReported = percent
                                    onProgress(percent)
                                }
                            }
                        }
                        os.flush()
                    }
                }
                onProgress(100)
                Log.i(TAG, "下载完成: $fileName")
                Result.Success(fileName, uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载异常", e)
            Result.Failure(e.message ?: "下载异常")
        }
    }

    /** 根据系统版本打开输出，返回 (Uri, OutputStream) */
    private fun openOutput(context: Context, fileName: String): Pair<Uri, OutputStream>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            openViaMediaStore(context, fileName)
        } else {
            openViaLegacyFile(fileName)
        }
    }

    private fun openViaMediaStore(
        context: Context,
        fileName: String
    ): Pair<Uri, OutputStream>? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
        val uri = resolver.insert(collection, values) ?: return null
        val os = resolver.openOutputStream(uri) ?: return null
        return uri to os
    }

    private fun openViaLegacyFile(fileName: String): Pair<Uri, OutputStream>? {
        val moviesDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
            FOLDER_NAME
        )
        if (!moviesDir.exists()) moviesDir.mkdirs()
        val file = File(moviesDir, fileName)
        val fos = FileOutputStream(file)
        return Uri.fromFile(file) to fos
    }

    /** 下载完成后告诉系统扫描这个视频，让它出现在相册里 */
    fun notifyGallery(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Q+ 用 MediaStore 写入的，更新 IS_PENDING = 0 即可
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }
            try {
                context.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) {
                Log.w(TAG, "更新 IS_PENDING 失败: ${e.message}")
            }
        } else {
            // 旧版用 file:// 触发 MediaScanner
            val path = uri.path ?: return
            @Suppress("DEPRECATION")
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(path), arrayOf("video/mp4"), null
            )
        }
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleaned.isBlank()) "video_${System.currentTimeMillis()}" else cleaned
    }
}
