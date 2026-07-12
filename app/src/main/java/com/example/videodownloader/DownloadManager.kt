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
     * B站 dash 流下载：下载视频 m4s + 音频 m4s 到临时文件，用 BiliMuxer 合成 mp4，写入相册。
     *
     * 进度分配：下载视频 0-40%，下载音频 40-70%，合成 70-100%。
     *
     * @param videoUrl 视频 m4s 直链
     * @param audioUrl 音频 m4s 直链
     * @param displayName 文件名（不带扩展名）
     * @param onProgress 总进度 0..100
     */
    suspend fun downloadDash(
        context: Context,
        videoUrl: String,
        audioUrl: String,
        displayName: String,
        onProgress: (Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        // 临时文件存放地：cacheDir
        val cacheDir = context.cacheDir
        val tmpVideo = File(cacheDir, "bili_video_${System.currentTimeMillis()}.m4s")
        val tmpAudio = File(cacheDir, "bili_audio_${System.currentTimeMillis()}.m4s")
        val tmpMux = File(cacheDir, "bili_mux_${System.currentTimeMillis()}.mp4")
        try {
            // 1. 下载视频流（0-40%）
            Log.i(TAG, "下载 B站视频流…")
            val vOk = downloadToTemp(videoUrl, tmpVideo) { p ->
                onProgress((p * 0.4).toInt().coerceIn(0, 40))
            }
            if (!vOk) return@withContext Result.Failure("视频流下载失败")

            // 2. 下载音频流（40-70%）
            Log.i(TAG, "下载 B站音频流…")
            val aOk = downloadToTemp(audioUrl, tmpAudio) { p ->
                onProgress(40 + (p * 0.3).toInt().coerceIn(0, 30))
            }
            if (!aOk) return@withContext Result.Failure("音频流下载失败")

            // 3. 合成（70-100%）
            Log.i(TAG, "合成 mp4…")
            val muxOk = BiliMuxer.mux(tmpVideo, tmpAudio, tmpMux) { p ->
                onProgress(70 + (p * 0.3).toInt().coerceIn(0, 30))
            }
            if (!muxOk || !tmpMux.exists() || tmpMux.length() == 0L) {
                return@withContext Result.Failure("合成 mp4 失败")
            }

            // 4. 写入相册
            val fileName = sanitizeFileName(displayName) + ".mp4"
            val (uri, output) = openOutput(context, fileName)
                ?: return@withContext Result.Failure("无法创建输出文件")
            output.use { os ->
                tmpMux.inputStream().use { input ->
                    input.copyTo(os)
                }
                os.flush()
            }
            onProgress(100)
            Log.i(TAG, "B站 dash 下载合成完成: $fileName")
            Result.Success(fileName, uri)
        } catch (e: Exception) {
            Log.e(TAG, "B站 dash 下载异常", e)
            Result.Failure(e.message ?: "下载异常")
        } finally {
            // 清理临时文件
            tmpVideo.delete()
            tmpAudio.delete()
            tmpMux.delete()
        }
    }

    /** 下载 URL 到临时文件，返回是否成功。[onProgress] 0..100 */
    private suspend fun downloadToTemp(
        url: String,
        dest: File,
        onProgress: (Int) -> Unit = {}
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .header("Referer", "https://www.bilibili.com/")
                .header("Accept", "*/*")
                .header("Range", "bytes=0-") // B站 m4s 用 Range 请求更稳
                .get()
                .build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful && resp.code != 206) {
                    Log.e(TAG, "下载失败 HTTP ${resp.code}")
                    return@withContext false
                }
                val body = resp.body ?: return@withContext false
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                dest.outputStream().use { os ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(8 * 1024)
                        var read: Int
                        var downloaded = 0L
                        var lastReported = -1
                        while (input.read(buf).also { read = it } != -1) {
                            os.write(buf, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val pct = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct)
                                }
                            }
                        }
                        os.flush()
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载到临时文件失败: ${e.message}")
            false
        }
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

    /** 图片输出：Android 10+ 用 MediaStore.Images，旧版写 Pictures/VideoDownloader */
    private fun openImageOutput(context: Context, fileName: String): Pair<Uri, OutputStream>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$FOLDER_NAME")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Images.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            )
            val uri = resolver.insert(collection, values) ?: return null
            val os = resolver.openOutputStream(uri) ?: return null
            uri to os
        } else {
            val picsDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                FOLDER_NAME
            )
            if (!picsDir.exists()) picsDir.mkdirs()
            val file = File(picsDir, fileName)
            Uri.fromFile(file) to FileOutputStream(file)
        }
    }

    /**
     * 下载图文笔记的所有图片到相册（Pictures/VideoDownloader）。
     * 多张图片时文件名加序号后缀。
     */
    suspend fun downloadImages(
        context: Context,
        imageUrls: List<String>,
        displayName: String,
        onProgress: (Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val savedUris = mutableListOf<Uri>()
        val savedPaths = mutableListOf<String>()
        val total = imageUrls.size
        try {
            imageUrls.forEachIndexed { index, url ->
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                    .header("Referer", "https://www.douyin.com/")
                    .header("Accept", "*/*")
                    .get()
                    .build()

                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        return@withContext Result.Failure("图片 ${index + 1} 下载失败：HTTP ${resp.code}")
                    }
                    val body = resp.body ?: return@withContext Result.Failure("图片 ${index + 1} 响应体为空")
                    val suffix = if (total > 1) "_${index + 1}" else ""
                    val fileName = "${sanitizeFileName(displayName)}$suffix.jpg"
                    val (uri, output) = openImageOutput(context, fileName)
                        ?: return@withContext Result.Failure("无法创建图片文件")
                    output.use { os ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(8 * 1024)
                            var read: Int
                            while (input.read(buf).also { read = it } != -1) {
                                os.write(buf, 0, read)
                            }
                            os.flush()
                        }
                    }
                    savedUris.add(uri)
                    savedPaths.add(fileName)
                    // 通知相册刷新
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                        }
                        try { context.contentResolver.update(uri, values, null, null) } catch (_: Exception) {}
                    } else {
                        val path = uri.path
                        if (path != null) {
                            @Suppress("DEPRECATION")
                            android.media.MediaScannerConnection.scanFile(
                                context, arrayOf(path), arrayOf("image/jpeg"), null
                            )
                        }
                        Unit
                    }
                }
                val pct = ((index + 1) * 100 / total).coerceIn(0, 100)
                onProgress(pct)
            }
            Log.i(TAG, "图片下载完成: ${savedPaths.size} 张")
            Result.Success(savedPaths.joinToString(", "), savedUris.first())
        } catch (e: Exception) {
            Log.e(TAG, "图片下载异常", e)
            Result.Failure(e.message ?: "图片下载异常")
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
