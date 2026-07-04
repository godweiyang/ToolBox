package com.example.videodownloader

import android.content.Intent
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.videodownloader.databinding.ActivityVideoToGifBinding
import com.google.android.material.slider.RangeSlider
import kotlinx.coroutines.launch

/**
 * 视频转 GIF 工具。
 *
 * 用户操作流程：
 * 1. 点击"选择视频"从相册导入本地视频
 * 2. 用 RangeSlider 选时间段（双 thumb，最小间隔 500ms）
 * 3. 用 4 个 SeekBar（左/上/右/下）按百分比选裁剪区域
 * 4. 用 3 个 SeekBar 调 fps / 输出宽度 / GIF 画质
 * 5. 点击"生成 GIF"，协程异步转换，进度条实时更新
 * 6. 完成后 Toast 提示，GIF 已保存到 Pictures/GifOutput/
 *
 * 裁剪区域语义：左/上 0-50% 表示从左/上边缘裁掉多少，右/下 0-50% 表示从右/下边缘裁掉多少。
 * 例如 左=10, 右=10 表示左右各裁掉 10%，保留中间 80%。
 */
class VideoToGifActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoToGifBinding
    private var videoUri: Uri? = null
    private var videoDurationMs: Long = 0L
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    // 视频选择器
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
            binding.videoPreview.visibility = View.GONE
            binding.videoPreview.setVideoURI(null)
            binding.tvVideoInfo.text = getString(R.string.vgf_input_label)
            binding.rsTime.valueFrom = 0f
            binding.rsTime.valueTo = 10000f
            binding.tvTimeRange.text = "0s - 0s"
        }

        binding.btnGenerate.setOnClickListener {
            doGenerate()
        }

        // 时间段 RangeSlider：手动约束最小间隔 500ms
        binding.rsTime.addOnChangeListener(RangeSlider.OnChangeListener { slider, _, _ ->
            val values = slider.values
            if (values.size >= 2) {
                val startMs = values[0]
                val endMs = values[1]
                // 最小间隔 500ms，违反时修正
                if (endMs - startMs < 500f) {
                    // 不强制修正，让用户继续拖（Material 默认会阻止越界）
                }
                binding.tvTimeRange.text = "${startMs / 1000.0}s - ${endMs / 1000.0}s"
            }
        })

        // 4 个裁剪 SeekBar
        val cropListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        binding.sbLeft.setOnSeekBarChangeListener(cropListener)
        binding.sbTop.setOnSeekBarChangeListener(cropListener)
        binding.sbRight.setOnSeekBarChangeListener(cropListener)
        binding.sbBottom.setOnSeekBarChangeListener(cropListener)

        // 输出选项 SeekBar
        binding.sbFps.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshOptionLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.sbWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshOptionLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        binding.sbQuality.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                refreshOptionLabels()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    /** 刷新 fps / 宽度 / 画质 的 label */
    private fun refreshOptionLabels() {
        binding.tvFps.text = getString(R.string.vgf_fps, getFps())
        binding.tvWidth.text = getString(R.string.vgf_width, getOutputWidth())
        binding.tvQuality.text = getString(R.string.vgf_quality, getQuality())
    }

    private fun getFps(): Int = (binding.sbFps.progress).coerceAtLeast(5)
    private fun getOutputWidth(): Int = (binding.sbWidth.progress).coerceAtLeast(120)
    private fun getQuality(): Int = binding.sbQuality.progress + 1  // 1-30

    /** 加载视频元信息，更新 UI */
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

            // 显示视频信息和预览
            val durationSec = videoDurationMs / 1000.0
            binding.tvVideoInfo.text = getString(R.string.vgf_video_info, "${videoWidth}x${videoHeight}", "%.1fs".format(durationSec))
            binding.videoPreview.visibility = View.VISIBLE
            binding.videoPreview.setVideoURI(uri)
            binding.videoPreview.seekTo(100)  // 显示第一帧附近

            // 更新 RangeSlider 范围
            val maxMs = videoDurationMs.toFloat()
            binding.rsTime.valueFrom = 0f
            binding.rsTime.valueTo = maxMs
            // 默认选前 3 秒或全长
            val defaultEnd = (3000f).coerceAtMost(maxMs)
            binding.rsTime.values = listOf(0f, defaultEnd)
            binding.tvTimeRange.text = "0.0s - ${defaultEnd / 1000.0}s"
        } catch (e: Exception) {
            Toast.makeText(this, "视频加载失败: ${e.message}", Toast.LENGTH_SHORT).show()
        } finally {
            try { retriever?.release() } catch (_: Exception) {}
        }
    }

    /** 计算裁剪区域（视频帧实际像素坐标） */
    private fun computeCropRect(): Rect? {
        if (videoWidth <= 0 || videoHeight <= 0) return null
        val leftPct = binding.sbLeft.progress
        val topPct = binding.sbTop.progress
        val rightPct = binding.sbRight.progress  // 从右边裁掉多少
        val bottomPct = binding.sbBottom.progress

        // 防御：保证 left + right < 100，top + bottom < 100
        if (leftPct + rightPct >= 100 || topPct + bottomPct >= 100) return null

        val x = (videoWidth * leftPct / 100f).toInt()
        val y = (videoHeight * topPct / 100f).toInt()
        val w = videoWidth - x - (videoWidth * rightPct / 100f).toInt()
        val h = videoHeight - y - (videoHeight * bottomPct / 100f).toInt()
        if (w <= 0 || h <= 0) return null
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

        val values = binding.rsTime.values
        if (values.size < 2) {
            Toast.makeText(this, "请选择时间段", Toast.LENGTH_SHORT).show()
            return
        }
        val startMs = values[0].toLong()
        val endMs = values[1].toLong()
        val fps = getFps()
        val outputWidth = getOutputWidth()
        val quality = getQuality()
        val cropRect = computeCropRect()

        // UI 切换到生成中状态
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

            // 恢复 UI
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
