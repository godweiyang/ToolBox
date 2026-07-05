package com.example.videodownloader

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.videodownloader.databinding.ActivityDecibelMeterBinding
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 分贝仪工具。
 *
 * 原理：
 *  - AudioRecord 录制 PCM 16-bit 单声道
 *  - 计算 RMS（均方根）
 *  - dBFS = 20 * log10(rms / 32767)，范围 -∞ ~ 0
 *  - 近似 SPL = dBFS + 90（粗略校准：0 dBFS ≈ 90 dB SPL）
 *  - 结果范围限制在 0-120 dB
 *
 * 注意：手机麦克风无标准 SPL 校准，数值为近似值，仅用于相对比较环境噪音。
 */
class DecibelMeterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDecibelMeterBinding
    private val uiHandler = Handler(Looper.getMainLooper())

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordThread: Thread? = null

    // 统计
    private var minDb = Double.MAX_VALUE
    private var maxDb = Double.MIN_VALUE
    private var sumDb = 0.0
    private var countDb = 0

    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    private val requestMicLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
        else Toast.makeText(this, getString(R.string.db_perm_denied), Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDecibelMeterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnToggle.setOnClickListener {
            if (isRecording) stopRecording()
            else {
                // 检查权限
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    startRecording()
                } else {
                    requestMicLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        // 配置波形图：紫色主色（和分贝仪主题一致），不显示阈值线，纵轴 0-120 dB
        binding.chartView.configure(
            color = 0xFF5E35B1.toInt(),
            showThreshold = false,
            initMax = 120f
        )
    }

    private fun startRecording() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) {
            Toast.makeText(this, "麦克风初始化失败", Toast.LENGTH_SHORT).show()
            return
        }
        val bufferSize = minBuf * 2
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: SecurityException) {
            Toast.makeText(this, getString(R.string.db_perm_denied), Toast.LENGTH_SHORT).show()
            return
        }
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Toast.makeText(this, "麦克风初始化失败", Toast.LENGTH_SHORT).show()
            return
        }

        // 重置统计
        minDb = Double.MAX_VALUE
        maxDb = Double.MIN_VALUE
        sumDb = 0.0
        countDb = 0
        binding.chartView.reset()

        isRecording = true
        binding.btnToggle.text = getString(R.string.db_btn_stop)
        binding.tvLevel.text = getString(R.string.db_level_normal)

        audioRecord?.startRecording()
        recordThread = Thread { recordingLoop(bufferSize) }.apply {
            isDaemon = true
            start()
        }
    }

    private fun stopRecording() {
        isRecording = false
        try { recordThread?.interrupt() } catch (_: Exception) {}
        recordThread = null
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        binding.btnToggle.text = getString(R.string.db_btn_start)
    }

    private fun recordingLoop(bufferSize: Int) {
        val buffer = ShortArray(bufferSize)
        while (isRecording && !Thread.interrupted()) {
            val read = audioRecord?.read(buffer, 0, bufferSize) ?: -1
            if (read <= 0) continue
            // 计算 RMS
            var sum = 0.0
            for (i in 0 until read) {
                val v = buffer[i].toDouble()
                sum += v * v
            }
            val rms = sqrt(sum / read)
            // dBFS = 20 * log10(rms / 32767)
            val dbFs = if (rms > 0) 20.0 * log10(rms / 32767.0) else -100.0
            // 近似 SPL
            val dbSpl = (dbFs + 90.0).coerceIn(0.0, 120.0)

            // 更新统计
            synchronized(this) {
                if (dbSpl < minDb) minDb = dbSpl
                if (dbSpl > maxDb) maxDb = dbSpl
                sumDb += dbSpl
                countDb++
            }

            // 更新 UI
            uiHandler.post { updateUi(dbSpl) }

            // 采样间隔 ~100ms
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }
    }

    private fun updateUi(db: Double) {
        binding.tvCurrentDb.text = getString(R.string.db_current, db)
        binding.levelBar.progress = db.toInt()
        // 等级标签
        binding.tvLevel.text = when {
            db < 30 -> getString(R.string.db_level_quiet)
            db < 60 -> getString(R.string.db_level_normal)
            db < 85 -> getString(R.string.db_level_loud)
            else -> getString(R.string.db_level_very_loud)
        }
        // 最小/最大/平均
        synchronized(this) {
            if (countDb > 0) {
                binding.tvMin.text = getString(R.string.db_min, minDb)
                binding.tvMax.text = getString(R.string.db_max, maxDb)
                binding.tvAvg.text = getString(R.string.db_avg, sumDb / countDb)
            }
        }
        // 波形图（按值缩放，超过 120 dB 自动放大纵轴）
        binding.chartView.addPoint(db.toFloat())
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
    }
}
