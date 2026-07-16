package com.example.videodownloader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 把图片序列 + mp3 音频合成为 mp4 视频。
 *
 * 用 MediaCodec H264 编码器把每张图片编码为视频帧，
 * 每张图显示 frameDurationMs 毫秒（按 FPS 重复若干帧），
 * 可选地把 mp3 音频轨直接复用进 mp4。
 *
 * 注意：width / height 需为偶数（YUV420 要求）。minSdk 26。
 */
object SlideShowMuxer {

    private const val TAG = "SlideShowMuxer"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val FPS = 10

    /**
     * 合成 mp4。
     *
     * @param imageFiles 图片序列（按顺序显示）
     * @param audioFile  可选 mp3 音频文件，null 表示纯无声视频
     * @param outputFile 输出 mp4 文件
     * @param frameDurationMs 每张图显示的毫秒数
     * @param width  视频宽（需为偶数）
     * @param height 视频高（需为偶数）
     * @param onProgress 进度回调 0..100（视频 0-80，音频 80-100）
     * @return true 成功
     */
    fun mux(
        imageFiles: List<File>,
        audioFile: File?,
        outputFile: File,
        frameDurationMs: Long = 2000,
        width: Int = 720,
        height: Int = 1280,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        if (imageFiles.isEmpty()) {
            Log.e(TAG, "imageFiles 为空")
            return false
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var audioExtractor: MediaExtractor? = null

        try {
            val frameIntervalUs = 1_000_000L / FPS
            val framesPerImage = (frameDurationMs * FPS / 1000).toInt().coerceAtLeast(1)
            val totalFrames = imageFiles.size * framesPerImage

            // --- 1. 预解析音频轨道（muxer.start 前必须 addTrack）---
            var audioFormat: MediaFormat? = null
            var audioTrackIndex = -1
            if (audioFile != null && audioFile.exists()) {
                audioExtractor = MediaExtractor()
                try {
                    audioExtractor.setDataSource(audioFile.absolutePath)
                    val at = selectTrack(audioExtractor, false)
                    if (at >= 0) {
                        audioExtractor.selectTrack(at)
                        audioFormat = audioExtractor.getTrackFormat(at)
                        Log.i(TAG, "音频轨: ${audioFormat?.getString(MediaFormat.KEY_MIME)}")
                    } else {
                        Log.w(TAG, "音频文件无音频轨")
                        audioExtractor.release()
                        audioExtractor = null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "读取音频失败: ${e.message}")
                    audioExtractor?.release()
                    audioExtractor = null
                }
            }

            // --- 2. 创建 H264 编码器 ---
            val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec = MediaCodec.createEncoderByType(MIME)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            // --- 3. 创建 muxer ---
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // --- 4. 编码视频 ---
            val info = MediaCodec.BufferInfo()
            var videoTrackIndex = -1
            var muxerStarted = false
            var inputFrameIndex = 0
            var inputDone = false
            var outputDone = false
            var lastReported = -1

            // 缓存当前图片的 YUV 数据，避免同一张图重复解码
            var currentImageIndex = -1
            var currentYuv: ByteArray? = null

            while (!outputDone) {
                // 喂入输入帧
                if (!inputDone) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10_000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex)!!
                        inputBuffer.clear()
                        if (inputFrameIndex < totalFrames) {
                            val imageIndex = inputFrameIndex / framesPerImage
                            if (imageIndex != currentImageIndex) {
                                val bmp = decodeAndScale(imageFiles[imageIndex], width, height)
                                currentYuv = bitmapToYuv420(bmp, width, height)
                                bmp.recycle()
                                currentImageIndex = imageIndex
                            }
                            val yuv = currentYuv!!
                            inputBuffer.put(yuv)
                            val pts = inputFrameIndex * frameIntervalUs
                            codec.queueInputBuffer(inputBufferIndex, 0, yuv.size, pts, 0)
                            inputFrameIndex++

                            // 进度 0-80
                            val pct = (inputFrameIndex * 80 / totalFrames).toInt().coerceIn(0, 80)
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct)
                            }
                        } else {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        }
                    }
                }

                // 读取编码输出
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when (outIndex) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            throw IllegalStateException("编码器输出格式重复变化")
                        }
                        val outFormat = codec.outputFormat
                        videoTrackIndex = muxer.addTrack(outFormat)
                        if (audioFormat != null) {
                            audioTrackIndex = muxer.addTrack(audioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                        Log.i(TAG, "muxer 已启动, videoTrack=$videoTrackIndex, audioTrack=$audioTrackIndex")
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> { /* 稍后重试 */ }
                    else -> {
                        if (outIndex >= 0) {
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                // 编解码器配置数据（SPS/PPS），不写入 muxer
                                codec.releaseOutputBuffer(outIndex, false)
                            } else if (muxerStarted) {
                                val outBuffer = codec.getOutputBuffer(outIndex)!!
                                muxer.writeSampleData(videoTrackIndex, outBuffer, info)
                                codec.releaseOutputBuffer(outIndex, false)
                            }
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }

            // --- 5. 写入音频 ---
            if (audioExtractor != null && audioTrackIndex >= 0) {
                val audioBuffer = ByteBuffer.allocate(256 * 1024)
                val audioInfo = MediaCodec.BufferInfo()
                val audioDurationUs = try {
                    audioFormat?.getLong(MediaFormat.KEY_DURATION) ?: 0L
                } catch (_: Exception) { 0L }

                while (true) {
                    val size = audioExtractor.readSampleData(audioBuffer, 0)
                    if (size < 0) break
                    audioInfo.offset = 0
                    audioInfo.size = size
                    audioInfo.flags = sampleFlagsToBufferInfo(audioExtractor.sampleFlags)
                    audioInfo.presentationTimeUs = audioExtractor.sampleTime
                    muxer.writeSampleData(audioTrackIndex, audioBuffer, audioInfo)

                    // 进度 80-100
                    if (audioDurationUs > 0) {
                        val pct = (80 + audioExtractor.sampleTime * 20 / audioDurationUs).toInt().coerceIn(80, 100)
                        if (pct != lastReported) {
                            lastReported = pct
                            onProgress(pct)
                        }
                    }
                    audioExtractor.advance()
                }
            }

            onProgress(100)
            Log.i(TAG, "合成完成: ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "合成失败", e)
            return false
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
        }
    }

    /** 解码图片并缩放到目标尺寸 */
    private fun decodeAndScale(file: File, width: Int, height: Int): Bitmap {
        val opts = BitmapFactory.Options()
        opts.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, opts)
        var sampleSize = 1
        while (opts.outWidth / (sampleSize * 2) >= width && opts.outHeight / (sampleSize * 2) >= height) {
            sampleSize *= 2
        }
        opts.inSampleSize = sampleSize
        opts.inJustDecodeBounds = false
        val decoded = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalStateException("无法解码图片: ${file.absolutePath}")
        return if (decoded.width == width && decoded.height == height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, width, height, true).also { decoded.recycle() }
        }
    }

    /**
     * Bitmap 转 YUV420 Planar（Y, U, V 分量分别连续存放）。
     * Y = 0.299R + 0.587G + 0.114B
     * U = -0.169R - 0.331G + 0.5B + 128
     * V = 0.5R - 0.419G - 0.081B + 128
     */
    private fun bitmapToYuv420(bitmap: Bitmap, width: Int, height: Int): ByteArray {
        val ySize = width * height
        val uvSize = ySize / 4
        val yuv = ByteArray(ySize + uvSize * 2)
        val argb = IntArray(ySize)
        bitmap.getPixels(argb, 0, width, 0, 0, width, height)

        // Y 平面
        for (i in 0 until ySize) {
            val px = argb[i]
            val r = (px shr 16) and 0xFF
            val g = (px shr 8) and 0xFF
            val b = px and 0xFF
            val y = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            yuv[i] = y.toByte()
        }

        // U、V 平面（每 2x2 块采样一次）
        val uStart = ySize
        val vStart = ySize + uvSize
        var uvIdx = 0
        for (j in 0 until height step 2) {
            for (i in 0 until width step 2) {
                val px = argb[j * width + i]
                val r = (px shr 16) and 0xFF
                val g = (px shr 8) and 0xFF
                val b = px and 0xFF
                val u = (-0.169 * r - 0.331 * g + 0.5 * b + 128).toInt().coerceIn(0, 255)
                val v = (0.5 * r - 0.419 * g - 0.081 * b + 128).toInt().coerceIn(0, 255)
                yuv[uStart + uvIdx] = u.toByte()
                yuv[vStart + uvIdx] = v.toByte()
                uvIdx++
            }
        }
        return yuv
    }

    /** 选择轨道：video=true 选视频轨，false 选音频轨 */
    private fun selectTrack(extractor: MediaExtractor, video: Boolean): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (video && mime.startsWith("video/")) return i
            if (!video && mime.startsWith("audio/")) return i
        }
        return -1
    }

    /** MediaExtractor.SampleFlags → MediaCodec.BufferInfo flags */
    private fun sampleFlagsToBufferInfo(flags: Int): Int {
        var result = 0
        if (flags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            result = result or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        return result
    }
}
