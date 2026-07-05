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
        var output: GifOutput? = null
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
            output = openGifOutput(context, displayName) ?: throw IllegalStateException("无法创建输出文件")
            outputStream = output.stream

            // 5. 编码 GIF
            // ⚠ GIF89a Graphic Control Extension 的 delay 单位是 1/100 秒(centi-second),
            // 不是毫秒!setDelay(71) 会被解读为 0.71 秒/帧 = 1.4 fps,导致 GIF 像慢放。
            // intervalMs 是毫秒,需除以 10 转成 1/100 秒:71ms → 7(0.07s)→ ~14fps。
            val encoder = AnimatedGifEncoder()
            encoder.setSize(outW, outH)
            encoder.setDelay((intervalMs / 10L).toInt().coerceAtLeast(2))
            encoder.setQuality(quality)
            encoder.setRepeat(0)  // 无限循环
            if (!encoder.start(outputStream)) {
                throw IllegalStateException("GIF encoder 启动失败")
            }

            // 6. 逐帧抽取
            // 用 OPTION_CLOSEST(非 SYNC)取最接近指定时间的帧,不限关键帧。
            // ⚠ 不能用 OPTION_CLOSEST_SYNC,它只返回 I帧,长 GOP 视频多个时间戳会返回同一帧 → GIF 不动。
            var lastReported = -1
            for (i in 0 until totalFrames) {
                val frameMs = safeStart + i * intervalMs
                val frameUs = frameMs * 1000L
                var frame: Bitmap? = null
                for (retry in 0 until 2) {
                    frame = retriever.getFrameAtTime(frameUs, MediaMetadataRetriever.OPTION_CLOSEST)
                    if (frame != null) break
                }
                if (frame == null) {
                    Log.w(TAG, "第 $i 帧抽取失败(ts=${frameMs}ms)，跳过")
                    continue
                }

                val cropped = cropFrame(frame, cropRect)
                val scaled = scaleFrame(cropped, outW, outH)
                encoder.addFrame(scaled)
                if (scaled !== cropped && scaled !== frame) scaled.recycle()
                if (cropped !== frame) cropped.recycle()
                frame.recycle()

                val pct = ((i + 1) * 100 / totalFrames).coerceIn(0, 100)
                if (pct != lastReported) {
                    lastReported = pct
                    withContext(Dispatchers.Main) { onProgress(pct) }
                }
            }

            encoder.finish()
            withContext(Dispatchers.Main) { onProgress(100) }
            Log.i(TAG, "GIF 生成完成")

            outputStream.flush()
            markGifReady(context, output.uri)
            return@withContext output.uri
        } catch (e: Exception) {
            Log.e(TAG, "GIF 转换失败", e)
            output?.let { discardGifOutput(context, it.uri) }
            null
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
            try { outputStream?.flush(); outputStream?.close() } catch (_: Exception) {}
        }
    }

    /** 裁剪帧。cropRect 为 null 或裁剪失败时返回原 frame */
    private fun cropFrame(frame: Bitmap, cropRect: android.graphics.Rect?): Bitmap {
        if (cropRect == null) return frame
        val cx = cropRect.left.coerceIn(0, frame.width - 1)
        val cy = cropRect.top.coerceIn(0, frame.height - 1)
        val cw = cropRect.width().coerceAtMost(frame.width - cx)
        val ch = cropRect.height().coerceAtMost(frame.height - cy)
        return try {
            Bitmap.createBitmap(frame, cx, cy, cw, ch)
        } catch (e: Exception) {
            Log.w(TAG, "裁剪失败，用原图: ${e.message}")
            frame
        }
    }

    /** 缩放帧到目标尺寸。已是目标尺寸时返回原 frame */
    private fun scaleFrame(cropped: Bitmap, outW: Int, outH: Int): Bitmap {
        return if (cropped.width != outW || cropped.height != outH) {
            Bitmap.createScaledBitmap(cropped, outW, outH, true)
        } else {
            cropped
        }
    }

    private data class GifOutput(val uri: Uri, val stream: OutputStream)

    /** 打开 GIF 输出：保留 insert 返回的 Uri，避免生成后按文件名反查出错 */
    private fun openGifOutput(context: Context, displayName: String): GifOutput? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // API 29+ 用 MediaStore，无需权限
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/gif")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GifOutput")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                val stream = uri?.let { resolver.openOutputStream(it) }
                if (uri != null && stream != null) GifOutput(uri, stream) else null
            } else {
                // API < 29 直接写公有目录
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "GifOutput")
                if (!dir.exists()) dir.mkdirs()
                val file = File(dir, displayName)
                GifOutput(Uri.fromFile(file), FileOutputStream(file))
            }
        } catch (e: Exception) {
            Log.e(TAG, "openGifOutput 失败", e)
            null
        }
    }

    private fun markGifReady(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "markGifReady 失败: ${e.message}")
        }
    }

    private fun discardGifOutput(context: Context, uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        try {
            context.contentResolver.delete(uri, null, null)
        } catch (e: Exception) {
            Log.w(TAG, "discardGifOutput 失败: ${e.message}")
        }
    }
}
