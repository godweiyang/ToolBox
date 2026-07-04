package com.example.videodownloader

import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.videodownloader.databinding.ActivityVideoToGifBinding
import kotlinx.coroutines.launch

/**
 * 视频转 GIF 工具。
 *
 * UI 改进版：
 *  - 视频预览 + CropOverlayView 叠加，直接在预览图上拖拽裁剪框（带三分线 + 8 手柄）
 *  - 双 SeekBar 选时间段，拖动时实时 seek 视频到对应帧预览
 *  - 输出选项（fps / 宽度 / 画质）保持 SeekBar
 */
class VideoToGifActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoToGifBinding
    private var videoUri: Uri? = null
    private var videoDurationMs: Long = 0L
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    // seek 节流，避免频繁 seekTo 卡顿
    private val seekHandler = Handler(Looper.getMainLooper())
    private var pendingSeek: Long? = null

    private val pickVideoLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            videoUri = uri
            loadVideoInfo(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoToGifBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
        refreshOptionLabels()
    }

    private fun setupListeners() {
        binding.btnPick.setOnClickListener {
            pickVideoLauncher.launch("video/*")
        }

        binding.btnClear.setOnClickListener {
            videoUri = null
            videoDurationMs = 0L
            videoWidth = 0
            videoHeight = 0
            binding.videoPreview.setVideoURI(null)
            binding.videoPreview.visibility = View.GONE
            binding.cropOverlay.visibility = View.GONE
            binding.tvVideoInfo.text = getString(R.string.vgf_input_label)
            binding.tvTimeRange.text = "0.0s - 0.0s"
            binding.tvFrameTime.text = "0.0s"
            binding.sbStart.progress = 0
            binding.sbEnd.progress = 300
        }

        binding.btnGenerate.setOnClickListener {
            doGenerate()
        }

        // 时间段 SeekBar：拖动时实时预览，且约束 start < end - minGap
        val minGapPromille = 30  // 最小间隔 3%（千分之 30）
        val startListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (binding.sbEnd.progress - progress < minGapPromille) {
                    binding.sbStart.progress = binding.sbEnd.progress - minGapPromille
                    return
                }
                updateTimeLabel()
                if (fromUser) previewSeek(getStartTimeMs())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                // 拖动开始:暂停播放(如果在播放),避免与 seek 冲突
                if (binding.videoPreview.isPlaying) binding.videoPreview.pause()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                // 松手:取消节流,立即精确 seek 到最终位置
                commitSeek(getStartTimeMs())
            }
        }
        val endListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (progress - binding.sbStart.progress < minGapPromille) {
                    binding.sbEnd.progress = binding.sbStart.progress + minGapPromille
                    return
                }
                updateTimeLabel()
                if (fromUser) previewSeek(getEndTimeMs())
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                if (binding.videoPreview.isPlaying) binding.videoPreview.pause()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                commitSeek(getEndTimeMs())
            }
        }
        binding.sbStart.setOnSeekBarChangeListener(startListener)
        binding.sbEnd.setOnSeekBarChangeListener(endListener)

        // 输出选项 SeekBar
        val optListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshOptionLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        binding.sbFps.setOnSeekBarChangeListener(optListener)
        binding.sbWidth.setOnSeekBarChangeListener(optListener)
        binding.sbQuality.setOnSeekBarChangeListener(optListener)
    }

    private fun refreshOptionLabels() {
        binding.tvFps.text = getString(R.string.vgf_fps, getFps())
        binding.tvWidth.text = getString(R.string.vgf_width, getOutputWidth())
        binding.tvQuality.text = getString(R.string.vgf_quality, getQuality())
    }

    private fun getFps(): Int = binding.sbFps.progress.coerceAtLeast(5)
    private fun getOutputWidth(): Int = binding.sbWidth.progress.coerceAtLeast(120)
    private fun getQuality(): Int = binding.sbQuality.progress + 1  // 1-30

    /** 起始时间 ms = (sbStart / 1000) * duration */
    private fun getStartTimeMs(): Long = videoDurationMs * binding.sbStart.progress / 1000L
    private fun getEndTimeMs(): Long = videoDurationMs * binding.sbEnd.progress / 1000L

    private fun updateTimeLabel() {
        val s = getStartTimeMs() / 1000.0
        val e = getEndTimeMs() / 1000.0
        binding.tvTimeRange.text = "%.1fs - %.1fs".format(s, e)
    }

    /** 节流 seek:拖动过程中每 200ms 最多发一次 seek,避免 seekTo 请求积压导致卡顿 */
    private fun previewSeek(timeMs: Long) {
        pendingSeek = timeMs
        binding.tvFrameTime.text = "%.1fs".format(timeMs / 1000.0)
        seekHandler.removeCallbacksAndMessages(null)
        seekHandler.postDelayed({
            pendingSeek?.let { ts ->
                try { binding.videoPreview.seekTo(ts.toInt()) } catch (_: Exception) {}
            }
        }, 200)
    }

    /** 松手时精确 seek:取消所有节流,立即 seek 到最终位置 */
    private fun commitSeek(timeMs: Long) {
        seekHandler.removeCallbacksAndMessages(null)
        pendingSeek = null
        binding.tvFrameTime.text = "%.1fs".format(timeMs / 1000.0)
        try { binding.videoPreview.seekTo(timeMs.toInt()) } catch (_: Exception) {}
    }

    private fun loadVideoInfo(uri: Uri) {
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val wStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val hStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            videoDurationMs = durStr?.toLongOrNull() ?: 0L
            videoWidth = wStr?.toIntOrNull() ?: 0
            videoHeight = hStr?.toIntOrNull() ?: 0

            val durationSec = videoDurationMs / 1000.0
            binding.tvVideoInfo.text = getString(R.string.vgf_video_info, "${videoWidth}x${videoHeight}", "%.1fs".format(durationSec))
            binding.videoPreview.visibility = View.VISIBLE
            binding.videoPreview.setVideoURI(uri)
            binding.videoPreview.seekTo(100)

            // 设置裁剪 overlay 的宽高比
            if (videoWidth > 0 && videoHeight > 0) {
                binding.cropOverlay.setVideoAspectRatio(videoWidth, videoHeight)
                binding.cropOverlay.visibility = View.VISIBLE
            }

            // 默认选前 3 秒或前 30%
            val defaultEndPromille = ((3000f / videoDurationMs.coerceAtLeast(1)) * 1000).toInt().coerceIn(100, 1000)
            binding.sbStart.progress = 0
            binding.sbEnd.progress = defaultEndPromille
            updateTimeLabel()
        } catch (e: Exception) {
            Toast.makeText(this, "视频加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    /** 从 CropOverlayView 拿归一化裁剪区域，转成视频像素坐标 */
    private fun computeCropRect(): Rect? {
        if (videoWidth <= 0 || videoHeight <= 0) return null
        val norm = binding.cropOverlay.getCropRect()
        if (norm.width() <= 0 || norm.height() <= 0) return null
        val x = (videoWidth * norm.left).toInt().coerceIn(0, videoWidth - 1)
        val y = (videoHeight * norm.top).toInt().coerceIn(0, videoHeight - 1)
        val w = (videoWidth * norm.width()).toInt().coerceAtLeast(1).coerceAtMost(videoWidth - x)
        val h = (videoHeight * norm.height()).toInt().coerceAtLeast(1).coerceAtMost(videoHeight - y)
        return Rect(x, y, x + w, y + h)
    }

    private fun doGenerate() {
        val uri = videoUri
        if (uri == null) {
            Toast.makeText(this, R.string.vgf_no_video, Toast.LENGTH_SHORT).show()
            return
        }
        if (videoDurationMs <= 0L) {
            Toast.makeText(this, "视频时长无效", Toast.LENGTH_SHORT).show()
            return
        }

        val startMs = getStartTimeMs()
        val endMs = getEndTimeMs()
        val fps = getFps()
        val outputWidth = getOutputWidth()
        val quality = getQuality()
        val cropRect = computeCropRect()

        binding.btnGenerate.isEnabled = false
        binding.progressBar.visibility = View.VISIBLE
        binding.tvProgress.visibility = View.VISIBLE
        binding.progressBar.progress = 0
        binding.tvProgress.text = getString(R.string.vgf_progress, 0)

        lifecycleScope.launch {
            val resultUri = GifConverter.convert(
                context = applicationContext,
                videoUri = uri,
                startMs = startMs,
                endMs = endMs,
                fps = fps,
                cropRect = cropRect,
                outputWidth = outputWidth,
                quality = quality
            ) { pct ->
                binding.progressBar.progress = pct
                binding.tvProgress.text = getString(R.string.vgf_progress, pct)
            }

            binding.btnGenerate.isEnabled = true
            binding.progressBar.visibility = View.GONE
            binding.tvProgress.visibility = View.GONE

            if (resultUri != null) {
                Toast.makeText(this@VideoToGifActivity, R.string.vgf_success, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this@VideoToGifActivity, R.string.vgf_fail, Toast.LENGTH_LONG).show()
            }
        }
    }
}
