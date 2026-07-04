package com.example.videodownloader

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * 视频转 GIF 转换器。
 *
 * 流程：
 * 1. MediaMetadataRetriever 取视频元信息（时长/宽高/旋转）
 * 2. 按时间段 [startMs, endMs] 和 fps 计算总帧数
 * 3. 逐帧 getFrameAtTime → 裁剪 → 缩放 → AnimatedGifEncoder.addFrame → recycle
 * 4. 输出到 MediaStore(Pictures/GifOutput/) 或 app 私有目录
 *
 * 全程在工作线程跑，通过 [onProgress] 回调进度 0..100。
 */
object GifConverter {
    private const val TAG = "GifConverter"

    /**
     * @param videoUri 视频内容 URI
     * @param startMs 起始时间 ms
     * @param endMs 结束时间 ms
     * @param fps 帧率 10-15
     * @param cropRect 裁剪区域（视频帧实际像素坐标，null=不裁剪）
     * @param outputWidth 输出 GIF 宽度 px（高度按比例）
     * @param quality GIF 量化质量 1-30（越小越好）
     * @param onProgress 进度回调 0..100（主线程）
     * @return 生成的 GIF 文件 Uri，失败返回 null
     */
    suspend fun convert(
        context: Context,
        videoUri: Uri,
        startMs: Long,
        endMs: Long,
        fps: Int,
        cropRect: android.graphics.Rect? = null,
        outputWidth: Int = 480,
        quality: Int = 10,
        onProgress: (Int) -> Unit
    ): Uri? = withContext(Dispatchers.Default) {
        var retriever: MediaMetadataRetriever? = null
        var outputStream: OutputStream? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, videoUri)

            // 1. 元信息
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val srcWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val srcHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            Log.i(TAG, "视频: ${srcWidth}x${srcHeight}, 时长=${durationMs}ms, 旋转可能影响实际帧方向")

            if (srcWidth <= 0 || srcHeight <= 0) {
                throw IllegalStateException("无法读取视频尺寸")
            }

            // 2. 计算时间段和帧数
            val safeStart = startMs.coerceIn(0, durationMs)
            val safeEnd = endMs.coerceIn(safeStart + 100, durationMs)
            val intervalMs = 1000L / fps
            val totalFrames = ((safeEnd - safeStart) / intervalMs).toInt().coerceAtLeast(1)
            Log.i(TAG, "GIF 参数: ${safeStart}ms-${safeEnd}ms, fps=$fps, 总帧数=$totalFrames, 输出宽=$outputWidth")

            // 3. 计算输出尺寸
            val srcW: Int
            val srcH: Int
            if (cropRect != null) {
                srcW = cropRect.width()
                srcH = cropRect.height()
            } else {
                srcW = srcWidth
                srcH = srcHeight
            }
            val outW = outputWidth.coerceAtMost(srcW)
            val outH = (srcH * outW.toFloat() / srcW).toInt().coerceAtLeast(1)

            // 4. 准备输出流
            val displayName = "gif_${System.currentTimeMillis()}.gif"
            outputStream = openGifOutputStream(context, displayName) ?: throw IllegalStateException("无法创建输出文件")

            // 5. 编码 GIF
            val encoder = AnimatedGifEncoder()
            encoder.setSize(outW, outH)
            encoder.setDelay(intervalMs.toInt())
            encoder.setQuality(quality)
            encoder.setRepeat(0)  // 无限循环
            if (!encoder.start(outputStream)) {
                throw IllegalStateException("GIF encoder 启动失败")
            }

            // 6. 逐帧抽取
            var lastReported = -1
            for (i in 0 until totalFrames) {
                val frameMs = safeStart + i * intervalMs
                val frameUs = frameMs * 1000L
                var frame: Bitmap? = null
                // 用 OPTION_CLOSEST(非 SYNC)取最接近指定时间的帧,不限关键帧。
                // OPTION_CLOSEST_SYNC 只返回 I帧,长 GOP 视频多个时间戳会返回同一帧 → GIF 不动。
                for (retry in 0 until 2) {
                    frame = retriever.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) break
                }
                if (frame == null) {
                    Log.w(TAG, "第 $i 帧抽取失败(ts=${frameMs}ms)，跳过")
                    continue
                }

                // 裁剪
                val cropped: Bitmap = if (cropRect != null) {
                    val cx = cropRect.left.coerceIn(0, frame.width - 1)
                    val cy = cropRect.top.coerceIn(0, frame.height - 1)
                    val cw = cropRect.width().coerceAtMost(frame.width - cx)
                    val ch = cropRect.height().coerceAtMost(frame.height - cy)
                    try {
                        Bitmap.createBitmap(frame, cx, cy, cw, ch)
                    } catch (e: Exception) {
                        Log.w(TAG, "裁剪失败，用原图: ${e.message}")
                        frame
                    }
                } else {
                    frame
                }

                // 缩放
                val scaled: Bitmap = if (cropped.width != outW || cropped.height != outH) {
                    val s = Bitmap.createScaledBitmap(cropped, outW, outH, true)
                    if (cropped !== frame) cropped.recycle()
                    s
                } else {
                    cropped
                }

                encoder.addFrame(scaled)
                if (scaled !== frame) scaled.recycle()
                frame.recycle()

                // 进度
                val pct = ((i + 1) * 100 / totalFrames).coerceIn(0, 100)
                if (pct != lastReported) {
                    lastReported = pct
                    withContext(Dispatchers.Main) { onProgress(pct) }
                }
            }

            encoder.finish()
            withContext(Dispatchers.Main) { onProgress(100) }
            Log.i(TAG, "GIF 生成完成")

            // 返回 Uri：如果是 MediaStore，需要构造；如果是私有目录文件，需要 file->uri
            return@withContext getGifUri(context, displayName, outputStream)
        } catch (e: Exception) {
            Log.e(TAG, "GIF 转换失败", e)
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
            try { outputStream?.flush(); outputStream?.close() } catch (_: Exception) {}
        }
    }

    /** 打开 GIF 输出流：优先 MediaStore(Pictures/GifOutput/)，否则 app 私有目录 */
    private fun openGifOutputStream(context: Context, displayName: String): OutputStream? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ 用 MediaStore，无需权限
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GifOutput")
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                uri?.let { resolver.openOutputStream(it) }
            } else {
                // API < 29 直接写公有目录
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GifOutput")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, displayName)
                FileOutputStream(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "openGifOutputStream 失败", e)
            null
        }
    }

    /** 拿到生成文件的 Uri（用于返回给调用方） */
    private fun getGifUri(context: Context, displayName: String, os: OutputStream): Uri? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // MediaStore 模式：查询刚插入的记录
                val resolver = context.contentResolver
                val cursor = resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.MediaColumns._ID),
                    "${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf(displayName),
                    null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val id = it.getLong(0)
                        return Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString())
                    }
                }
                null
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GifOutput")
                Uri.fromFile(File(dir, displayName))
            }
        } catch (e: Exception) {
            Log.w(TAG, "getGifUri 失败: ${e.message}")
            null
        }
    }
}
