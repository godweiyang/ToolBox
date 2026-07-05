package com.example.videodownloader

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.videodownloader.databinding.ActivityMetalDetectorBinding
import kotlin.math.sqrt

/**
 * 金属探测器工具。
 *
 * 原理：
 *  - 手机磁力计测量三轴磁场强度（μT），合成总强度 = √(x²+y²+z²)
 *  - 地球磁场约 25-65 μT，靠近金属/磁铁时数值会显著变化
 *  - 总强度超过阈值（默认 60 μT）触发报警：震动 + UI 变红
 *
 * 注意：
 *  - 只能探测铁磁性物质（铁、钴、镍、磁铁等），铝/铜/不锈钢等非磁性金属无效
 *  - 受附近电子设备、音箱磁体、磁吸附件干扰
 *  - 数值为相对参考，不同手机磁力计灵敏度有差异
 */
class MetalDetectorActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMetalDetectorBinding
    private var sensorManager: SensorManager? = null
    private var magnetometer: Sensor? = null
    private var vibrator: Vibrator? = null

    private var isDetecting = false
    private var threshold = 60f

    // 统计
    private var minValue = Float.MAX_VALUE
    private var maxValue = Float.MIN_VALUE
    private var sumValue = 0.0
    private var countValue = 0

    // 报警去重：避免持续震动
    private var lastAlarmTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMetalDetectorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        magnetometer = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }

        if (magnetometer == null) {
            Toast.makeText(this, getString(R.string.metal_no_sensor), Toast.LENGTH_LONG).show()
            binding.btnToggle.isEnabled = false
            return
        }

        binding.sbThreshold.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                threshold = progress.toFloat()
                binding.tvThreshold.text = getString(R.string.metal_threshold, threshold)
                binding.chartView.setThreshold(threshold)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        // 初始显示
        binding.tvThreshold.text = getString(R.string.metal_threshold, threshold.toFloat())
        binding.chartView.setThreshold(threshold)

        binding.btnToggle.setOnClickListener {
            if (isDetecting) stopDetecting() else startDetecting()
        }
    }

    private fun startDetecting() {
        if (magnetometer == null) return
        // 重置统计
        minValue = Float.MAX_VALUE
        maxValue = Float.MIN_VALUE
        sumValue = 0.0
        countValue = 0
        binding.chartView.reset()

        isDetecting = true
        binding.btnToggle.text = getString(R.string.metal_btn_stop)
        binding.tvLevel.text = getString(R.string.metal_calibrating)
        sensorManager?.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI)
    }

    private fun stopDetecting() {
        isDetecting = false
        sensorManager?.unregisterListener(this)
        binding.btnToggle.text = getString(R.string.metal_btn_start)
        binding.tvLevel.text = getString(R.string.metal_hint)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isDetecting) return
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)

        // 更新统计
        if (magnitude < minValue) minValue = magnitude
        if (magnitude > maxValue) maxValue = magnitude
        sumValue += magnitude
        countValue++

        // 更新 UI
        runOnUiThread { updateUi(magnitude, x, y, z) }

        // 阈值报警（带 1 秒去重，避免持续震动）
        if (magnitude > threshold) {
            val now = System.currentTimeMillis()
            if (now - lastAlarmTime > 1000) {
                lastAlarmTime = now
                triggerAlarm(magnitude > threshold * 1.5f)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            runOnUiThread {
                Toast.makeText(this, "磁力计需要校准：请在空中画 8 字", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateUi(value: Float, x: Float, y: Float, z: Float) {
        binding.tvCurrent.text = getString(R.string.metal_current, value)
        binding.tvAxis.text = getString(R.string.metal_axis, x, y, z)
        binding.levelBar.progress = value.toInt().coerceAtMost(binding.levelBar.max)

        // 等级标签和颜色
        val levelText: String
        val levelColor: Int
        when {
            value > threshold * 1.5f -> {
                levelText = getString(R.string.metal_level_strong)
                levelColor = getColor(R.color.metal_alert)
            }
            value > threshold -> {
                levelText = getString(R.string.metal_level_detect)
                levelColor = getColor(R.color.metal_warn)
            }
            else -> {
                levelText = getString(R.string.metal_level_normal)
                levelColor = getColor(R.color.metal_normal)
            }
        }
        binding.tvLevel.text = levelText
        binding.tvCurrent.setTextColor(levelColor)

        // 统计
        if (countValue > 0) {
            binding.tvMin.text = getString(R.string.metal_min, minValue)
            binding.tvMax.text = getString(R.string.metal_max, maxValue)
            binding.tvAvg.text = getString(R.string.metal_avg, sumValue / countValue)
        }

        // 波形图
        binding.chartView.addPoint(value)
    }

    /** 触发报警：震动 + 可视反馈 */
    private fun triggerAlarm(strong: Boolean) {
        val pattern = if (strong) longArrayOf(0, 400, 100, 400) else longArrayOf(0, 200)
        val amplitudes = if (strong) intArrayOf(0, 255, 0, 255) else intArrayOf(0, 255)
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, -1)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        stopDetecting()
    }
}
