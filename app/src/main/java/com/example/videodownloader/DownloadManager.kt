package com.example.videodownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.videodownloader.parser.PlatformRegistry
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

    /** 各平台 CDN 防盗链 Referer：优先取注册表配置，兜底老平台映射与抖音 */
    private fun refererForPlatform(platform: String): String =
        PlatformRegistry.refererFor(platform).ifBlank {
            when (platform) {
                "xiaohongshu" -> "https://www.xiaohongshu.com/"
                "kuaishou" -> "https://www.kuaishou.com/"
                "bilibili" -> "https://www.bilibili.com/"
                else -> "https://www.douyin.com/"
            }
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
        var pendingUri: Uri? = null
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
            pendingUri = uri
            output.use { os ->
                tmpMux.inputStream().use { input ->
                    input.copyTo(os)
                }
                os.flush()
            }
            val publishedUri = publishVideo(context, uri)
                ?: run {
                    deleteOutput(context, uri)
                    pendingUri = null
                    return@withContext Result.Failure("视频已写入，但发布到系统相册失败")
                }
            pendingUri = null
            onProgress(100)
            Log.i(TAG, "B站 dash 下载合成完成: $fileName")
            Result.Success(fileName, publishedUri)
        } catch (e: Exception) {
            pendingUri?.let { deleteOutput(context, it) }
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
     * @param platform 视频来源平台，用于设置 CDN 防盗链 Referer
     * @param onProgress 进度回调，percent ∈ [0,100]
     */
    suspend fun download(
        context: Context,
        videoUrl: String,
        displayName: String,
        platform: String = "douyin",
        onProgress: (Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        var pendingUri: Uri? = null
        try {
            val req = Request.Builder()
                .url(videoUrl)
                .header("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .header("Referer", refererForPlatform(platform))
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
                pendingUri = uri

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
                val publishedUri = publishVideo(context, uri)
                    ?: run {
                        deleteOutput(context, uri)
                        pendingUri = null
                        return@withContext Result.Failure("视频已写入，但发布到系统相册失败")
                    }
                pendingUri = null
                onProgress(100)
                Log.i(TAG, "下载并发布完成: $fileName, uri=$publishedUri")
                Result.Success(fileName, publishedUri)
            }
        } catch (e: Exception) {
            pendingUri?.let { deleteOutput(context, it) }
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
        platform: String = "douyin",
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
                    .header("Referer", refererForPlatform(platform))
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

    /**
     * 下载图文笔记的图片并合成为 GIF 动图，保存到相册。
     */
    suspend fun downloadGif(
        context: Context,
        imageUrls: List<String>,
        displayName: String,
        frameDelayMs: Int = 1000,
        onProgress: (Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val tmpImages = mutableListOf<File>()
        val tmpGif = File(cacheDir, "gif_${System.currentTimeMillis()}.gif")
        try {
            // 1. 下载所有图片到临时文件（检查结果，跳过失败的）
            Log.i(TAG, "下载图片用于 GIF 合成…")
            imageUrls.forEachIndexed { index, url ->
                val imgFile = File(cacheDir, "gif_img_$index.jpg")
                val ok = downloadToTempWithReferer(url, imgFile, "https://www.douyin.com/")
                if (ok && imgFile.exists() && imgFile.length() > 100) {
                    tmpImages.add(imgFile)
                    Log.i(TAG, "图片 ${index + 1} 下载成功: ${imgFile.length()} bytes")
                } else {
                    Log.w(TAG, "图片 ${index + 1} 下载失败或文件太小")
                    imgFile.delete()
                }
                onProgress((index * 50 / imageUrls.size).coerceIn(0, 50))
            }
            if (tmpImages.isEmpty()) return@withContext Result.Failure("图片下载失败，可能 URL 已过期")

            // 2. 解码为 Bitmap（强制 ARGB_8888，避免 HARDWARE 配置导致 getPixels 失败）
            //    所有图片缩放到第一帧尺寸，避免 AnimatedGifEncoder 内部数组越界
            val rawBitmaps = mutableListOf<android.graphics.Bitmap>()
            for (f in tmpImages) {
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
                }
                val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
                if (bmp != null) {
                    rawBitmaps.add(bmp)
                    Log.i(TAG, "图片解码成功: ${bmp.width}x${bmp.height}")
                } else {
                    Log.w(TAG, "图片解码失败: ${f.absolutePath}")
                }
            }
            if (rawBitmaps.isEmpty()) return@withContext Result.Failure("无法解码图片，可能 URL 已过期")

            // 缩放所有帧到统一尺寸（第一帧尺寸），避免 setSize 与实际像素数不匹配导致越界
            val targetW = rawBitmaps[0].width
            val targetH = rawBitmaps[0].height
            val bitmaps = mutableListOf<android.graphics.Bitmap>()
            for ((i, bmp) in rawBitmaps.withIndex()) {
                if (bmp.width == targetW && bmp.height == targetH) {
                    bitmaps.add(bmp)
                } else {
                    val scaled = android.graphics.Bitmap.createScaledBitmap(bmp, targetW, targetH, true)
                    bmp.recycle()
                    bitmaps.add(scaled)
                    Log.i(TAG, "帧 ${i + 1} 缩放: ${bmp.width}x${bmp.height} -> ${targetW}x${targetH}")
                }
            }

            // 3. 用 AnimatedGifEncoder 合成 GIF
            Log.i(TAG, "合成 GIF… ${bitmaps.size} 帧, ${targetW}x${targetH}")
            val encoder = AnimatedGifEncoder()
            encoder.setSize(targetW, targetH)
            encoder.setDelay(frameDelayMs)
            encoder.setQuality(10)
            encoder.setRepeat(0)
            val fos = FileOutputStream(tmpGif)
            val started = encoder.start(fos)
            for ((i, bmp) in bitmaps.withIndex()) {
                encoder.addFrame(bmp)
                bmp.recycle()
                val pct = 50 + (i + 1) * 50 / bitmaps.size
                onProgress(pct.coerceIn(50, 100))
            }
            val finished = encoder.finish()
            fos.close()
            if (!started || !finished || !tmpGif.exists() || tmpGif.length() == 0L) {
                return@withContext Result.Failure("GIF 合成失败")
            }

            // 4. 写入相册
            val fileName = sanitizeFileName(displayName) + ".gif"
            val (uri, output) = openImageOutput(context, fileName)
                ?: return@withContext Result.Failure("无法创建输出文件")
            output.use { os ->
                tmpGif.inputStream().use { it.copyTo(os) }
                os.flush()
            }
            // 通知相册
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
                }
                try { context.contentResolver.update(uri, values, null, null) } catch (_: Exception) {}
            } else {
                val path = uri.path
                if (path != null) {
                    @Suppress("DEPRECATION")
                    android.media.MediaScannerConnection.scanFile(
                        context, arrayOf(path), arrayOf("image/gif"), null
                    )
                }
                Unit
            }
            onProgress(100)
            Log.i(TAG, "GIF 下载完成: $fileName (${tmpGif.length() / 1024}KB)")
            Result.Success(fileName, uri)
        } catch (e: Exception) {
            Log.e(TAG, "GIF 下载异常", e)
            Result.Failure(e.message ?: "GIF 下载异常")
        } finally {
            tmpImages.forEach { it.delete() }
            tmpGif.delete()
        }
    }

    /**
     * 下载实况照片：保存所有图片到相册，同时合成一个短视频（图片+背景音乐）与第一张图配对保存。
     * Google Photos 等相册应用会识别同名（去扩展名）的 JPEG + MP4 为实况照片。
     */
    suspend fun downloadLivePhoto(
        context: Context,
        imageUrls: List<String>,
        musicUrl: String,
        displayName: String,
        musicDurationSec: Int,
        platform: String = "douyin",
        onProgress: (Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val tmpImages = mutableListOf<File>()
        val tmpAudio = File(cacheDir, "live_audio_${System.currentTimeMillis()}.mp3")
        val tmpVideo = File(cacheDir, "live_video_${System.currentTimeMillis()}.mp4")
        try {
            val baseName = sanitizeFileName(displayName)
            val totalImages = imageUrls.size

            // 1. 下载所有图片并保存到相册 Pictures/VideoDownloader/
            Log.i(TAG, "下载图片用于实况照片…")
            val savedImageUris = mutableListOf<Uri>()
            imageUrls.forEachIndexed { index, url ->
                val imgFile = File(cacheDir, "live_img_$index.jpg")
                val ok = downloadToTempWithReferer(url, imgFile, refererForPlatform(platform))
                if (ok && imgFile.exists() && imgFile.length() > 100) {
                    tmpImages.add(imgFile)
                    // 保存图片到相册
                    val suffix = if (totalImages > 1) "_${index + 1}" else ""
                    val imgFileName = "$baseName$suffix.jpg"
                    val (imgUri, imgOut) = openImageOutput(context, imgFileName)
                        ?: run { Log.w(TAG, "无法创建图片文件: $imgFileName"); return@forEachIndexed }
                    imgOut.use { os ->
                        imgFile.inputStream().use { it.copyTo(os) }
                        os.flush()
                    }
                    // 通知相册
                    finalizeImage(context, imgUri)
                    savedImageUris.add(imgUri)
                    Log.i(TAG, "图片 ${index + 1} 保存成功: $imgFileName")
                } else {
                    Log.w(TAG, "图片 ${index + 1} 下载失败或文件太小")
                    imgFile.delete()
                }
                onProgress((index * 40 / totalImages).coerceIn(0, 40))
            }
            if (tmpImages.isEmpty()) return@withContext Result.Failure("图片下载失败，可能 URL 已过期")

            // 2. 下载背景音乐
            var audioFile: File? = null
            if (musicUrl.isNotBlank()) {
                Log.i(TAG, "下载背景音乐…")
                val ok = downloadToTempWithReferer(musicUrl, tmpAudio, refererForPlatform(platform))
                if (ok && tmpAudio.exists() && tmpAudio.length() > 0) {
                    audioFile = tmpAudio
                    Log.i(TAG, "背景音乐下载成功: ${tmpAudio.length()} bytes")
                } else {
                    Log.w(TAG, "背景音乐下载失败，将保存为无声实况照片")
                }
            }
            onProgress(45)

            // 3. 合成短视频（所有图轮播 + 背景音乐）
            val frameDurationMs = if (musicDurationSec > 0) {
                (musicDurationSec * 1000L / tmpImages.size)
            } else {
                2000L
            }
            Log.i(TAG, "合成实况照片视频… frameDuration=${frameDurationMs}ms, ${tmpImages.size} 图")
            val muxOk = SlideShowMuxer.mux(
                imageFiles = tmpImages,
                audioFile = audioFile,
                outputFile = tmpVideo,
                frameDurationMs = frameDurationMs,
                width = 720,
                height = 1280
            ) { p ->
                onProgress(45 + (p * 50 / 100).coerceIn(0, 50))
            }
            if (!muxOk || !tmpVideo.exists() || tmpVideo.length() == 0L) {
                // 视频合成失败，但图片已保存，返回图片成功
                Log.w(TAG, "短视频合成失败，但图片已保存")
                onProgress(100)
                return@withContext Result.Success(
                    "$baseName.jpg (+${savedImageUris.size - 1} 张图片)",
                    savedImageUris.first()
                )
            }
            onProgress(95)

            // 4. 短视频保存到相册 Pictures/VideoDownloader/（与第一张图同名配对）
            val videoFileName = "$baseName.mp4"
            val (videoUri, videoOut) = openVideoOutputToPictures(context, videoFileName)
                ?: run {
                    Log.w(TAG, "无法创建视频文件，但图片已保存")
                    onProgress(100)
                    return@withContext Result.Success(
                        "$baseName.jpg (+${savedImageUris.size - 1} 张图片)",
                        savedImageUris.first()
                    )
                }
            videoOut.use { os ->
                tmpVideo.inputStream().use { it.copyTo(os) }
                os.flush()
            }
            val publishedVideoUri = publishVideo(context, videoUri)
            if (publishedVideoUri == null) {
                deleteOutput(context, videoUri)
                Log.w(TAG, "实况照片视频发布失败，但图片已保存")
                onProgress(100)
                return@withContext Result.Success(
                    "$baseName.jpg (+${savedImageUris.size - 1} 张图片)",
                    savedImageUris.first()
                )
            }
            onProgress(100)
            Log.i(TAG, "实况照片保存完成: $baseName.jpg + $videoFileName (${tmpVideo.length() / 1024}KB)")
            Result.Success("$baseName.jpg + $videoFileName", savedImageUris.first())
        } catch (e: Exception) {
            Log.e(TAG, "实况照片保存异常", e)
            Result.Failure(e.message ?: "实况照片保存异常")
        } finally {
            tmpImages.forEach { it.delete() }
            tmpAudio.delete()
            tmpVideo.delete()
        }
    }

    /** 把视频保存到 Pictures/VideoDownloader/（与图片同目录，便于 Live Photo 配对识别） */
    private fun openVideoOutputToPictures(context: Context, fileName: String): Pair<Uri, OutputStream>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$FOLDER_NAME")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            )
            val uri = resolver.insert(collection, values) ?: return null
            val os = resolver.openOutputStream(uri, "w") ?: run {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
                return null
            }
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

    /** 通知 MediaStore 图片写入完成 */
    private fun finalizeImage(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            try { context.contentResolver.update(uri, values, null, null) } catch (_: Exception) {}
        } else {
            val path = uri.path ?: return
            @Suppress("DEPRECATION")
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(path), arrayOf("image/jpeg"), null
            )
        }
    }

    /**
     * 下载图文笔记的图片+音频，合成为 mp4 视频保存到相册。
     */
    suspend fun downloadSlideShow(
        context: Context,
        imageUrls: List<String>,
        musicUrl: String,
        displayName: String,
        musicDurationSec: Int,
        platform: String = "douyin",
        onProgress: (Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val tmpImages = mutableListOf<File>()
        val tmpAudio = File(cacheDir, "slide_audio_${System.currentTimeMillis()}.mp3")
        val tmpVideo = File(cacheDir, "slide_video_${System.currentTimeMillis()}.mp4")
        var pendingUri: Uri? = null
        try {
            // 1. 下载所有图片（检查结果，跳过失败的）
            Log.i(TAG, "下载图片用于视频合成…")
            imageUrls.forEachIndexed { index, url ->
                val f = File(cacheDir, "slide_img_$index.jpg")
                val ok = downloadToTempWithReferer(url, f, refererForPlatform(platform))
                if (ok && f.exists() && f.length() > 100) {
                    tmpImages.add(f)
                    Log.i(TAG, "图片 ${index + 1} 下载成功: ${f.length()} bytes")
                } else {
                    Log.w(TAG, "图片 ${index + 1} 下载失败或文件太小")
                    f.delete()
                }
                onProgress((index * 30 / imageUrls.size).coerceIn(0, 30))
            }
            if (tmpImages.isEmpty()) return@withContext Result.Failure("图片下载失败，可能 URL 已过期")

            // 2. 下载音频
            var audioFile: File? = null
            if (musicUrl.isNotBlank()) {
                Log.i(TAG, "下载背景音乐…")
                val ok = downloadToTempWithReferer(musicUrl, tmpAudio, refererForPlatform(platform))
                if (ok && tmpAudio.exists() && tmpAudio.length() > 0) {
                    audioFile = tmpAudio
                }
            }
            onProgress(35)

            // 3. 计算每帧显示时间
            val total = imageUrls.size
            val frameDurationMs = if (musicDurationSec > 0) {
                (musicDurationSec * 1000L / total)
            } else {
                2000L
            }

            // 4. 合成视频
            Log.i(TAG, "合成视频… frameDuration=${frameDurationMs}ms")
            val muxOk = SlideShowMuxer.mux(
                imageFiles = tmpImages,
                audioFile = audioFile,
                outputFile = tmpVideo,
                frameDurationMs = frameDurationMs,
                width = 720,
                height = 1280
            ) { p ->
                onProgress(35 + (p * 60 / 100).coerceIn(0, 60))
            }
            if (!muxOk || !tmpVideo.exists() || tmpVideo.length() == 0L) {
                return@withContext Result.Failure("视频合成失败")
            }
            onProgress(95)

            // 5. 写入相册
            val fileName = sanitizeFileName(displayName) + ".mp4"
            val (uri, output) = openOutput(context, fileName)
                ?: return@withContext Result.Failure("无法创建输出文件")
            pendingUri = uri
            output.use { os ->
                tmpVideo.inputStream().use { it.copyTo(os) }
                os.flush()
            }
            val publishedUri = publishVideo(context, uri)
                ?: run {
                    deleteOutput(context, uri)
                    pendingUri = null
                    return@withContext Result.Failure("视频已写入，但发布到系统相册失败")
                }
            pendingUri = null
            onProgress(100)
            Log.i(TAG, "图文视频合成完成: $fileName (${tmpVideo.length() / 1024}KB)")
            Result.Success(fileName, publishedUri)
        } catch (e: Exception) {
            pendingUri?.let { deleteOutput(context, it) }
            Log.e(TAG, "图文视频合成异常", e)
            Result.Failure(e.message ?: "图文视频合成异常")
        } finally {
            tmpImages.forEach { it.delete() }
            tmpAudio.delete()
            tmpVideo.delete()
        }
    }

    /** 下载 URL 到临时文件（带自定义 Referer） */
    private suspend fun downloadToTempWithReferer(
        url: String, dest: File, referer: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .header("Referer", referer)
                .header("Accept", "*/*")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                val body = resp.body ?: return@withContext false
                dest.outputStream().use { os -> body.byteStream().use { it.copyTo(os) } }
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "下载到临时文件失败: ${e.message}")
            false
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
        val os = resolver.openOutputStream(uri, "w") ?: run {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            return null
        }
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

    /**
     * 将 MediaStore 中的待处理视频正式发布。
     *
     * 正常路径把 IS_PENDING 设为 0；部分厂商系统若拒绝更新，则把内容复制到一个
     * 默认即为可见状态的新条目，避免界面显示成功但文件仍被系统隐藏。
     */
    private fun publishVideo(context: Context, uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val path = uri.path ?: return null
            @Suppress("DEPRECATION")
            android.media.MediaScannerConnection.scanFile(
                context, arrayOf(path), arrayOf("video/mp4"), null
            )
            return uri
        }

        val resolver = context.contentResolver
        try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
            }
            val updated = resolver.update(uri, values, null, null)
            if (updated > 0) {
                resolver.notifyChange(uri, null)
                Log.i(TAG, "MediaStore 视频发布成功: uri=$uri")
                return uri
            }
            Log.w(TAG, "MediaStore 发布更新 0 行，尝试可见条目兜底: uri=$uri")
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore 发布失败，尝试可见条目兜底: ${e.message}")
        }

        return copyToVisibleVideoEntry(context, uri)
    }

    /** 把待处理视频复制到不带 IS_PENDING 的可见 MediaStore 条目。 */
    private fun copyToVisibleVideoEntry(context: Context, sourceUri: Uri): Uri? {
        val resolver = context.contentResolver
        var displayName = "video_${System.currentTimeMillis()}.mp4"
        var relativePath = "${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME"
        try {
            resolver.query(
                sourceUri,
                arrayOf(
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.RELATIVE_PATH
                ),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(0)?.takeIf { it.isNotBlank() }?.let { displayName = it }
                    cursor.getString(1)?.takeIf { it.isNotBlank() }?.let { relativePath = it }
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
                put(MediaStore.Video.Media.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
                // 故意不写 IS_PENDING，插入后即对相册和文件管理器可见
            }
            val collection = MediaStore.Video.Media.getContentUri(
                MediaStore.VOLUME_EXTERNAL_PRIMARY
            )
            val targetUri = resolver.insert(collection, values) ?: return null
            try {
                val input = resolver.openInputStream(sourceUri)
                    ?: throw IllegalStateException("无法读取待发布视频")
                val output = resolver.openOutputStream(targetUri, "w")
                    ?: throw IllegalStateException("无法创建可见视频")
                input.use { src ->
                    output.use { dst -> src.copyTo(dst) }
                }
                try { resolver.delete(sourceUri, null, null) } catch (_: Exception) {}
                resolver.notifyChange(targetUri, null)
                Log.i(TAG, "MediaStore 可见条目兜底成功: uri=$targetUri")
                return targetUri
            } catch (e: Exception) {
                try { resolver.delete(targetUri, null, null) } catch (_: Exception) {}
                throw e
            }
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore 可见条目兜底失败", e)
            return null
        }
    }

    /** 删除失败或未发布的输出，避免残留隐藏文件。 */
    private fun deleteOutput(context: Context, uri: Uri) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.contentResolver.delete(uri, null, null)
            } else {
                uri.path?.let { File(it).delete() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理输出失败: ${e.message}")
        }
    }

    /**
     * 恢复旧版本可能遗留的隐藏视频。
     *
     * v1.9.4 及以前把视频先写成 IS_PENDING=1，再由 Activity 事后发布；
     * 如果发布失败，界面仍会提示成功。应用升级后进入下载页时调用本方法，
     * 会重新发布 Movies/Pictures/VideoDownloader 下仍处于 pending 的视频。
     */
    suspend fun recoverPendingVideos(context: Context): Int = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext 0

        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.getContentUri(
            MediaStore.VOLUME_EXTERNAL_PRIMARY
        )
        val pendingUris = mutableListOf<Uri>()
        val moviesPath = "${Environment.DIRECTORY_MOVIES}/$FOLDER_NAME"
        val picturesPath = "${Environment.DIRECTORY_PICTURES}/$FOLDER_NAME"
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.IS_PENDING}=1 AND " +
                    "(${MediaStore.Video.Media.RELATIVE_PATH} LIKE ? OR " +
                    "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?)",
                arrayOf("$moviesPath%", "$picturesPath%"),
                null
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) {
                    pendingUris.add(
                        Uri.withAppendedPath(collection, cursor.getLong(idIndex).toString())
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "查询历史隐藏视频失败: ${e.message}")
            return@withContext 0
        }

        var recovered = 0
        pendingUris.forEach { uri ->
            if (publishVideo(context, uri) != null) recovered++
        }
        if (recovered > 0) {
            Log.i(TAG, "已恢复 $recovered 个历史隐藏视频")
        }
        recovered
    }

    /** 下载完成后通知相册刷新；发布动作已在各下载方法返回成功前完成。 */
    fun notifyGallery(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                context.contentResolver.notifyChange(uri, null)
            } catch (e: Exception) {
                Log.w(TAG, "通知 MediaStore 刷新失败: ${e.message}")
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
        if (cleaned.isBlank()) return "video_${System.currentTimeMillis()}"
        val maxCodePoints = 80
        val end = if (cleaned.codePointCount(0, cleaned.length) > maxCodePoints) {
            cleaned.offsetByCodePoints(0, maxCodePoints)
        } else {
            cleaned.length
        }
        return cleaned.substring(0, end)
    }
}
