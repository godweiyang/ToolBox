package com.example.videodownloader

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 行人航迹推算（PDR, Pedestrian Dead Reckoning）。
 *
 * 原理：
 * 1. 用加速度计检测步频（步数 = 步长累加）
 * 2. 用陀螺仪 + 加速度计 + 磁力计融合得到设备朝向（ yaw 角）
 * 3. 每检测到一步，按当前朝向推算位置增量：
 *    x += stepLength * sin(yaw)
 *    y += stepLength * cos(yaw)
 *
 * 精度限制：
 * - 步长估算误差 ±20%（默认 0.7m，可调）
 * - 朝向受金属/磁场干扰
 * - 漂移会随时间累积，1 分钟内轨迹相对正确
 *
 * 使用方式：
 *   tracker.start(sensorManager)
 *   tracker.setOnStep { x, y, stepCount -> ... }
 *   tracker.stop()
 *
 * 坐标系：手机屏幕朝上水平握持，Y 轴指向手机顶部（"前方"）
 */
class PdrTracker(
    private val stepLength: Float = 0.7f,  // 默认步长 0.7 米
    private val onStep: ((x: Float, y: Float, stepCount: Int) -> Unit)? = null
) : SensorEventListener {

    private var sensorManager: SensorManager? = null

    // 当前位置（米）
    private var currentX = 0f
    private var currentY = 0f
    private var stepCount = 0

    // 当前朝向（弧度，0 = 北，顺时针递增）
    private var currentYaw = 0f

    // 步频检测相关
    private val gravity = FloatArray(3) { 0f }  // 低通滤波后的重力分量
    private val linearAccel = FloatArray(3) { 0f }  // 去除重力后的线性加速度
    private var lastAccelMag = 0f
    private var accelMagBuffer = FloatArray(ACCEL_BUFFER_SIZE)
    private var bufferIndex = 0
    private var isBufferFilled = false

    // 步频检测状态机
    private var lastStepTime = 0L
    private var isStepInProgress = false
    private var peakValue = 0f

    // 朝向融合：用 Sensor.TYPE_ROTATION_VECTOR 最准，没有则用陀螺仪 + 磁力计
    private val rotationVectorValues = FloatArray(5)
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    fun start(sm: SensorManager) {
        sensorManager = sm
        currentX = 0f
        currentY = 0f
        stepCount = 0
        bufferIndex = 0
        isBufferFilled = false
        isStepInProgress = false
        peakValue = 0f

        // 优先用旋转矢量（自动融合加速度计+陀螺仪+磁力计）
        val rotVector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVector != null) {
            sm.registerListener(this, rotVector, SensorManager.SENSOR_DELAY_GAME)
        } else {
            // 退化方案：加速度计 + 磁力计
            sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }

        // 加速度计始终需要（用于步频检测），即使有旋转矢量也要单独注册
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // 如果有系统计步器，优先用（最准）
        val stepDetector = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepDetector != null) {
            sm.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    fun reset() {
        currentX = 0f
        currentY = 0f
        stepCount = 0
        bufferIndex = 0
        isBufferFilled = false
        isStepInProgress = false
        peakValue = 0f
    }

    fun getPosition(): Pair<Float, Float> = currentX to currentY
    fun getStepCount(): Int = stepCount

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                // 用旋转矢量获取朝向
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationAngles)
                // orientationAngles[0] = azimuth（绕 Z 轴），单位弧度
                currentYaw = orientationAngles[0]

                // 旋转矢量不包含步频检测，需要单独处理加速度计
                // 但有些设备旋转矢量已经融合了加速度计，我们另外注册加速度计
                handleStepDetectionFromRotation(event)
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                // 系统级步频检测，最准
                onStepDetected()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                processAccelerometer(event.values)
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                // 用磁力计 + 加速度计算朝向（退化方案）
                // 这里简化处理：实际需要保存磁力计数据，与加速度计一起算
                // 由于优先用旋转矢量，这里只是兜底
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /**
     * 从加速度计数据检测步频。
     * 算法：
     * 1. 低通滤波分离重力
     * 2. 计算去重力后的线性加速度幅值
     * 3. 滑动窗口找峰值，超过阈值且满足最小步间隔 → 判定为一步
     */
    private fun processAccelerometer(values: FloatArray) {
        // 1. 低通滤波分离重力（α 越大跟随越慢）
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1 - alpha) * values[0]
        gravity[1] = alpha * gravity[1] + (1 - alpha) * values[1]
        gravity[2] = alpha * gravity[2] + (1 - alpha) * values[2]

        // 2. 去重力
        linearAccel[0] = values[0] - gravity[0]
        linearAccel[1] = values[1] - gravity[1]
        linearAccel[2] = values[2] - gravity[2]

        // 3. 幅值
        val mag = sqrt(
            linearAccel[0] * linearAccel[0] +
                    linearAccel[1] * linearAccel[1] +
                    linearAccel[2] * linearAccel[2]
        )

        // 4. 滑动窗口均值（去噪）
        accelMagBuffer[bufferIndex] = mag
        bufferIndex = (bufferIndex + 1) % ACCEL_BUFFER_SIZE
        if (bufferIndex == 0) isBufferFilled = true

        val smoothed = if (isBufferFilled) {
            var sum = 0f
            for (v in accelMagBuffer) sum += v
            sum / ACCEL_BUFFER_SIZE
        } else {
            var sum = 0f
            for (i in 0 until bufferIndex) sum += accelMagBuffer[i]
            if (bufferIndex > 0) sum / bufferIndex else 0f
        }

        // 5. 步频检测状态机
        val now = System.currentTimeMillis()
        if (smoothed > STEP_THRESHOLD && !isStepInProgress && (now - lastStepTime) > MIN_STEP_INTERVAL_MS) {
            isStepInProgress = true
            peakValue = smoothed
        } else if (isStepInProgress) {
            if (smoothed > peakValue) {
                peakValue = smoothed
            }
            // 幅值回落到阈值以下，判定为一步
            if (smoothed < STEP_THRESHOLD * 0.5f) {
                isStepInProgress = false
                onStepDetected()
            }
        }
    }

    /**
     * 旋转矢量传感器的数据中也包含加速度信息，
     * 但实际不直接给线性加速度，所以这里不做步频检测。
     * 步频检测依赖单独注册的加速度计或系统计步器。
     */
    private fun handleStepDetectionFromRotation(event: SensorEvent) {
        // no-op：旋转矢量仅用于朝向
    }

    private fun onStepDetected() {
        val now = System.currentTimeMillis()
        if (now - lastStepTime < MIN_STEP_INTERVAL_MS) return
        lastStepTime = now

        stepCount++

        // 按当前朝向推算位置增量
        // 朝向 0 = 北（+Y 方向），逆时针为正
        // 但 Android 的 azimuth 是顺时针为正（北=0，东=π/2）
        // 屏幕朝上时，手机 Y 轴指向"前方"
        currentX += stepLength * sin(currentYaw)
        currentY += stepLength * cos(currentYaw)

        onStep?.invoke(currentX, currentY, stepCount)
    }

    companion object {
        private const val ACCEL_BUFFER_SIZE = 10
        private const val STEP_THRESHOLD = 1.2f  // m/s²，超过此值认为在迈步
        private const val MIN_STEP_INTERVAL_MS = 250L  // 最小步间隔，避免抖动误判
    }
}
