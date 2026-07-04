package com.example.videodownloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * 用 MediaMuxer 把 B站的 dash 音视频流（m4s）合成为一个 mp4 文件。
 *
 * B站 dash 流是 fragmented MP4：
 *   - 视频 m4s：含 ftyp + moov + moof+mdat（多个 fragment）
 *   - 音频 m4s：同上
 *
 * 用 MediaExtractor 分别读两个 m4s 的轨道，加到 MediaMuxer 里，
 * 按时间戳交错写入，输出标准 mp4。
 *
 * 注意：MediaExtractor 能直接读 fmp4，无需特殊处理。
 */
object BiliMuxer {

    private const val TAG = "BiliMuxer"

    /**
     * 合成 mp4。
     *
     * @param videoFile 视频 m4s 临时文件
     * @param audioFile 音频 m4s 临时文件
     * @param outputFile 输出 mp4 文件
     * @param onProgress 进度回调 0..100
     * @return true 成功
     */
    fun mux(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: (Int) -> Unit = {}
    ): Boolean {
        var muxer: MediaMuxer? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        try {
            // 1. 估算总时长用于进度
            val videoDurationUs = getDurationUs(videoFile)
            val audioDurationUs = getDurationUs(audioFile)
            val totalDurationUs = maxOf(videoDurationUs, audioDurationUs)
            Log.i(TAG, "video duration: ${videoDurationUs}us, audio: ${audioDurationUs}us, total: ${totalDurationUs}us")

            // 2. 打开视频 extractor
            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(videoFile.absolutePath)
            val videoTrack = selectTrack(videoExtractor, true)
            if (videoTrack < 0) throw IllegalStateException("视频 m4s 无视频轨道")
            videoExtractor.selectTrack(videoTrack)
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)

            // 3. 打开音频 extractor
            audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(audioFile.absolutePath)
            val audioTrack = selectTrack(audioExtractor, false)
            val audioFormat = if (audioTrack >= 0) {
                audioExtractor.selectTrack(audioTrack)
                audioExtractor.getTrackFormat(audioTrack)
            } else null

            // 4. 创建 muxer，添加轨道
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val videoOutTrack = muxer.addTrack(videoFormat)
            val audioOutTrack = if (audioFormat != null) muxer.addTrack(audioFormat) else -1
            muxer.start()

            // 5. 交错写入：按时间戳取较早的样本写入
            val buffer = ByteBuffer.allocate(2 * 1024 * 1024) // 2MB 足够
            val info = MediaCodec.BufferInfo()

            var videoDone = false
            var audioDone = (audioFormat == null)
            var videoPts = 0L
            var audioPts = 0L
            var lastReported = -1

            while (!videoDone || !audioDone) {
                // 取时间戳较小的流写入
                val useVideo = if (videoDone) false
                    else if (audioDone) true
                    else videoPts <= audioPts

                if (useVideo) {
                    val size = videoExtractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        videoDone = true
                    } else {
                        info.offset = 0
                        info.size = size
                        info.flags = sampleFlagsToBufferInfo(videoExtractor.sampleFlags)
                        info.presentationTimeUs = videoExtractor.sampleTime
                        muxer.writeSampleData(videoOutTrack, buffer, info)
                        videoPts = videoExtractor.sampleTime
                        videoExtractor.advance()
                    }
                } else {
                    val size = audioExtractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        audioDone = true
                    } else {
                        info.offset = 0
                        info.size = size
                        info.flags = sampleFlagsToBufferInfo(audioExtractor.sampleFlags)
                        info.presentationTimeUs = audioExtractor.sampleTime
                        muxer.writeSampleData(audioOutTrack, buffer, info)
                        audioPts = audioExtractor.sampleTime
                        audioExtractor.advance()
                    }
                }

                // 进度
                val curPts = maxOf(videoPts, audioPts)
                if (totalDurationUs > 0) {
                    val pct = (curPts * 100 / totalDurationUs).toInt().coerceIn(0, 100)
                    if (pct != lastReported) {
                        lastReported = pct
                        onProgress(pct)
                    }
                }
            }
            onProgress(100)
            Log.i(TAG, "合成完成: ${outputFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "合成失败", e)
            return false
        } finally {
            try { muxer?.release() } catch (_: Exception) {}
            try { videoExtractor?.release() } catch (_: Exception) {}
            try { audioExtractor?.release() } catch (_: Exception) {}
        }
    }

    /** 获取媒体文件时长（微秒） */
    private fun getDurationUs(file: File): Long {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            val track = selectTrack(ex, true).let { if (it >= 0) it else selectTrack(ex, false) }
            if (track < 0) return 0L
            ex.selectTrack(track)
            var max = 0L
            while (true) {
                val pts = ex.sampleTime
                if (pts < 0) break
                if (pts > max) max = pts
                if (!ex.advance()) break
            }
            max
        } catch (e: Exception) {
            Log.w(TAG, "getDurationUs 失败: ${e.message}")
            0L
        } finally {
            try { ex.release() } catch (_: Exception) {}
        }
    }

    /**
     * 选择轨道。
     * @param video true 选视频轨，false 选音频轨
     */
    private fun selectTrack(extractor: MediaExtractor, video: Boolean): Int {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
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
