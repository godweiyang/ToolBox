package com.example.videodownloader

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.videodownloader.databinding.ActivityWifiSignalBinding
import com.example.videodownloader.databinding.PageWifiHeatmapBinding
import com.example.videodownloader.databinding.PageWifiRealtimeBinding
import com.google.android.material.tabs.TabLayoutMediator

/**
 * WiFi 信号探测器主界面。
 *
 * 两个 Tab：
 * 1. 实时信号：显示当前 RSSI、SSID、连接信息、信号曲线
 * 2. 信号热力图：拿着手机走动时绘制信号强度轨迹
 *
 * 关键技术：
 * - WifiManager.startScan() + ScanResult.level 获取 RSSI
 * - 实际上，已连接 AP 的 RSSI 通过 BroadcastReceiver 监听 RSSI_CHANGE_ACTION 更准
 * - 但 Android 8+ 限制了后台扫描频率，所以前台轮询 + 信号变化广播
 * - PDR 步行推算见 [PdrTracker]
 */
class WifiSignalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWifiSignalBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWifiSignalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ViewPager2 + TabLayout 双页面
        // 禁用 ViewPager2 的左右滑动手势，只允许点击 Tab 标签切换
        // 这样热力图页的单指/双指拖动不会被 ViewPager2 拦截
        binding.viewPager.isUserInputEnabled = false
        binding.viewPager.adapter = WifiPagerAdapter(this)
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = if (position == 0) getString(R.string.wifi_tab_realtime)
            else getString(R.string.wifi_tab_heatmap)
        }.attach()
    }

    private inner class WifiPagerAdapter(activity: FragmentActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = 2
        override fun createFragment(position: Int): Fragment =
            if (position == 0) RealtimeFragment() else HeatmapFragment()
    }
}

/** 实时信号页：显示当前 RSSI、SSID、信号等级、曲线图 */
class RealtimeFragment : Fragment() {

    private var _binding: PageWifiRealtimeBinding? = null
    private val binding get() = _binding!!

    private lateinit var wifiManager: WifiManager
    private val handler = Handler(Looper.getMainLooper())

    private var isDetecting = false
    private val rssiHistory = mutableListOf<Int>()
    private var minRssi = Int.MAX_VALUE
    private var maxRssi = Int.MIN_VALUE
    private var sumRssi = 0L
    private var countRssi = 0

    // 轮询 RSSI 的任务
    private val pollRunnable = object : Runnable {
        override fun run() {
            updateWifiInfo()
            if (isDetecting) handler.postDelayed(this, 1000)
        }
    }

    // 监听 RSSI 变化广播（更实时）
    private val rssiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.RSSI_CHANGED_ACTION) {
                updateWifiInfo()
            }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startDetection()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PageWifiRealtimeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // 配置曲线图：RSSI 范围 -100 ~ -30 dBm，越往上信号越好
        binding.chartRssi.configure(
            color = ContextCompat.getColor(requireContext(), R.color.tool_wifi_bg),
            showThreshold = false,
            initMax = -30f,
            initMin = -100f
        )

        binding.btnToggle.setOnClickListener {
            if (isDetecting) stopDetection() else startDetection()
        }

        updateEmptyUi()
    }

    private fun startDetection() {
        // Android 12+ 需要 ACCESS_FINE_LOCATION 才能读取 WiFi 信息
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val perm = Manifest.permission.ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED) {
                try {
                    locationPermissionLauncher.launch(perm)
                } catch (e: Exception) {
                    android.util.Log.e("WifiRealtime", "launch permission failed", e)
                    android.widget.Toast.makeText(
                        requireContext(),
                        "无法请求权限: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
                return
            }
        }

        try {
            isDetecting = true
            rssiHistory.clear()
            minRssi = Int.MAX_VALUE
            maxRssi = Int.MIN_VALUE
            sumRssi = 0L
            countRssi = 0
            binding.chartRssi.reset()
            binding.btnToggle.text = getString(R.string.wifi_btn_stop)
            binding.tvHint.text = getString(R.string.wifi_walk_hint)

            // 注册 RSSI 变化广播
            // Android 13+ 必须显式指定 RECEIVER_NOT_EXPORTED / RECEIVER_EXPORTED，否则抛 SecurityException
            val filter = IntentFilter(WifiManager.RSSI_CHANGED_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                requireContext().registerReceiver(rssiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                requireContext().registerReceiver(rssiReceiver, filter)
            }

            // 立即更新一次，然后每秒轮询（广播有延迟，轮询保证最低频率）
            updateWifiInfo()
            handler.post(pollRunnable)
        } catch (e: Exception) {
            android.util.Log.e("WifiRealtime", "startDetection failed", e)
            isDetecting = false
            android.widget.Toast.makeText(
                requireContext(),
                "启动失败: ${e.javaClass.simpleName}: ${e.message}",
                android.widget.Toast.LENGTH_LONG
            ).show()
            // 还原按钮状态
            try { binding.btnToggle.text = getString(R.string.wifi_btn_start) } catch (_: Exception) {}
        }
    }

    private fun stopDetection() {
        isDetecting = false
        handler.removeCallbacks(pollRunnable)
        try { requireContext().unregisterReceiver(rssiReceiver) } catch (_: Exception) {}
        binding.btnToggle.text = getString(R.string.wifi_btn_start)
        binding.tvHint.text = getString(R.string.wifi_hint)
    }

    private fun updateWifiInfo() {
        val info = try {
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo
        } catch (e: Exception) {
            null
        }

        if (info == null || info.bssid == null || info.bssid == "02:00:00:00:00:00") {
            binding.tvSsid.text = getString(R.string.wifi_no_connection)
            binding.tvRssi.text = "--"
            binding.tvLevel.text = ""
            binding.tvFreq.text = ""
            binding.tvLinkSpeed.text = ""
            binding.tvDistance.text = ""
            return
        }

        val rssi = info.rssi
        val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ 不再通过 WifiInfo.getSSID() 返回真实 SSID
            // 只能用 ConnectivityManager，这里简化处理
            info.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: "Unknown"
        } else {
            info.ssid?.removePrefix("\"")?.removeSuffix("\"") ?: "Unknown"
        }

        binding.tvSsid.text = getString(R.string.wifi_ssid, ssid)
        binding.tvRssi.text = getString(R.string.wifi_current_rssi, rssi)

        // 频段判断
        val freq = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) info.frequency else 0
        val bandStr = if (freq in 2400..2500) "2.4G"
        else if (freq in 4900..5900) "5G"
        else if (freq in 5900..7125) "5G"
        else "--"
        if (freq > 0) {
            binding.tvFreq.text = getString(R.string.wifi_freq, bandStr, freq / 1000f)
        } else {
            binding.tvFreq.text = ""
        }

        // 连接速率
        @Suppress("DEPRECATION")
        val linkSpeed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) info.linkSpeed else 0
        if (linkSpeed > 0) {
            binding.tvLinkSpeed.text = getString(R.string.wifi_link_speed, linkSpeed)
        } else {
            binding.tvLinkSpeed.text = ""
        }

        // 估算距离（基于信号衰减模型，仅粗略）
        val distance = estimateDistance(rssi)
        binding.tvDistance.text = getString(R.string.wifi_distance, distance)

        // 信号等级 + 颜色
        val (levelText, colorRes) = when {
            rssi >= -55 -> getString(R.string.wifi_level_excellent) to R.color.wifi_excellent
            rssi >= -65 -> getString(R.string.wifi_level_good) to R.color.wifi_good
            rssi >= -75 -> getString(R.string.wifi_level_fair) to R.color.wifi_fair
            rssi >= -85 -> getString(R.string.wifi_level_weak) to R.color.wifi_weak
            else -> getString(R.string.wifi_level_bad) to R.color.wifi_bad
        }
        binding.tvLevel.text = levelText
        binding.tvRssi.setTextColor(ContextCompat.getColor(requireContext(), colorRes))

        // 统计 + 曲线
        rssiHistory.add(rssi)
        if (rssi < minRssi) minRssi = rssi
        if (rssi > maxRssi) maxRssi = rssi
        sumRssi += rssi
        countRssi++
        binding.tvMin.text = getString(R.string.wifi_min, minRssi)
        binding.tvMax.text = getString(R.string.wifi_max, maxRssi)
        binding.tvAvg.text = getString(R.string.wifi_avg, sumRssi.toFloat() / countRssi)

        binding.chartRssi.addPoint(rssi.toFloat())
    }

    /**
     * 基于 RSSI 估算距离（米）。
     * 反推：d = 10^((RSSI - A) / (10*n))，A 是 1m 处的信号强度，n 是路径损耗指数
     * 室内 n 通常取 2.5-3.5，A 通常取 -40 ~ -50 dBm@1m
     */
    private fun estimateDistance(rssi: Int): Double {
        val A = -45  // 1m 处的信号强度（经验值）
        val n = 3.0  // 室内路径损耗指数
        val distance = Math.pow(10.0, (A - rssi) / (10.0 * n))
        return Math.round(distance * 10.0) / 10.0
    }

    private fun updateEmptyUi() {
        binding.tvRssi.text = "--"
        binding.tvLevel.text = getString(R.string.wifi_hint)
        binding.tvLevel.setTextColor(0xFF888888.toInt())
        binding.tvSsid.text = ""
        binding.tvFreq.text = ""
        binding.tvLinkSpeed.text = ""
        binding.tvDistance.text = ""
        binding.tvMin.text = ""
        binding.tvAvg.text = ""
        binding.tvMax.text = ""
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopDetection()
        _binding = null
    }
}

/**
 * 热力图页：自动采集 WiFi 信号，每 1.5 秒一个采样点。
 *
 * 采样点位置用 PDR（行人航迹推算）：
 * - TYPE_STEP_DETECTOR 系统级步频检测（准确，Android 4.4+）
 * - TYPE_ROTATION_VECTOR 提供朝向
 * - 每检测到一步，沿当前朝向推进固定步长 0.65 米
 * - 静止时采样点位置不变，只更新信号值
 *
 * 纯加速度计二次积分在室内定位不可行（噪声放大 + 重力分离不完美），
 * 步频检测 + 固定步长是手机端唯一靠谱的相对轨迹方案。
 */
class HeatmapFragment : Fragment(), SensorEventListener {

    private var _binding: PageWifiHeatmapBinding? = null
    private val binding get() = _binding!!

    private lateinit var wifiManager: WifiManager
    private lateinit var sensorManager: SensorManager
    private val handler = Handler(Looper.getMainLooper())

    private var isSampling = false

    /** 上次采样点的世界坐标（首次从 0,0 开始） */
    private var lastWorldX = 0f
    private var lastWorldY = 0f

    // ===== PDR 相关 =====
    /** 当前朝向（弧度，0=北，逆时针为正），已低通滤波 */
    private var currentYaw = 0f
    /** 原始朝向，用于低通滤波 */
    private var rawYaw = 0f
    /** 朝向低通滤波的复数分量（sin/cum, cos/cum），避免角度绕圈问题 */
    private var yawSinSum = 0.0
    private var yawCosSum = 0.0
    private val yawWindow = ArrayDeque<Double>(10)  // 滑动窗口存最近 10 个朝向（弧度）
    /** 本周期内检测到的步数 */
    private var stepsInPeriod = 0
    /** 总步数 */
    private var totalSteps = 0
    /** 是否有 STEP_DETECTOR 传感器 */
    private var hasStepDetector = false

    // ===== 自适应步长（Weinberg 算法） =====
    /** 加速度幅值的最大值/最小值，用于估算步长 */
    private var accelMax = Float.MIN_VALUE
    private var accelMin = Float.MAX_VALUE
    /** 上一次步长（米） */
    private var lastStepLength = 0.65f
    /** Weinberg 经验系数 */
    private val weinbergK = 0.55f

    private val sampleRunnable = object : Runnable {
        override fun run() {
            if (isSampling) {
                sampleOnce()
                handler.postDelayed(this, 1500)
            }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startSampling()
    }

    private val activityPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && isSampling) {
            registerStepDetector()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = PageWifiHeatmapBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        wifiManager = requireContext().applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        sensorManager = requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager

        binding.btnToggleHeat.setOnClickListener {
            if (isSampling) stopSampling() else startSampling()
        }

        binding.btnResetView.setOnClickListener {
            binding.heatMap.resetView()
        }

        binding.btnClear.setOnClickListener {
            stopSampling()
            binding.heatMap.clear()
            lastWorldX = 0f
            lastWorldY = 0f
            stepsInPeriod = 0
            totalSteps = 0
            binding.tvSteps.text = ""
            binding.tvHeatmapEmpty.visibility = View.VISIBLE
        }

        binding.tvHeatmapEmpty.visibility = View.VISIBLE
    }

    private fun startSampling() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val perm = Manifest.permission.ACCESS_FINE_LOCATION
            if (ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED) {
                try {
                    locationPermissionLauncher.launch(perm)
                } catch (e: Exception) {
                    android.util.Log.e("WifiHeatmap", "launch permission failed", e)
                }
                return
            }
        }

        isSampling = true
        binding.btnToggleHeat.text = getString(R.string.wifi_btn_stop)
        binding.tvHeatmapEmpty.visibility = View.GONE
        stepsInPeriod = 0
        accelMax = Float.MIN_VALUE
        accelMin = Float.MAX_VALUE
        // 注册旋转矢量获取朝向
        sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        // 始终注册加速度计：用于自适应步长估算（Weinberg 算法需要每步的加速度峰值）
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // 注册步频检测器（系统级，比手动加速度积分可靠）
        // Android 10+ 需要 ACTIVITY_RECOGNITION 运行时权限
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepDetector != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            registerStepDetector()
        } else if (stepDetector != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val perm = Manifest.permission.ACTIVITY_RECOGNITION
            if (ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED) {
                registerStepDetector()
            } else {
                hasStepDetector = false
                try {
                    activityPermissionLauncher.launch(perm)
                } catch (e: Exception) {
                    android.util.Log.e("WifiHeatmap", "launch activity permission failed", e)
                }
                android.util.Log.w("WifiHeatmap", "Requesting ACTIVITY_RECOGNITION, using accelerometer fallback for now")
            }
        } else {
            hasStepDetector = false
            android.util.Log.w("WifiHeatmap", "TYPE_STEP_DETECTOR not available, falling back to accelerometer")
        }
        // 立即采样一次，然后定时采样
        sampleOnce()
        handler.post(sampleRunnable)
    }

    private fun registerStepDetector() {
        if (hasStepDetector) return
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) ?: return
        hasStepDetector = true
        sensorManager.registerListener(this, stepDetector, SensorManager.SENSOR_DELAY_FASTEST)
        android.util.Log.i("WifiHeatmap", "Using TYPE_STEP_DETECTOR")
    }

    private fun stopSampling() {
        isSampling = false
        handler.removeCallbacks(sampleRunnable)
        sensorManager.unregisterListener(this)
        hasStepDetector = false
        binding.btnToggleHeat.text = getString(R.string.wifi_btn_start)
    }

    /** 采一个点：本周期内有步数则沿朝向推进，否则同位置更新 */
    private fun sampleOnce() {
        val rssi = getCurrentRssi()
        if (rssi == Int.MIN_VALUE) {
            android.widget.Toast.makeText(
                requireContext(),
                getString(R.string.wifi_no_connection),
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }

        val steps = stepsInPeriod
        stepsInPeriod = 0

        if (steps > 0) {
            // 走了 steps 步，用上一次估算的步长（每步触发时已通过 Weinberg 更新 lastStepLength）
            // 注意：用 currentYaw（已低通滤波）作为整段位移的朝向
            // Android 朝向：0=北，逆时针为正；世界坐标 y 轴朝北，x 轴朝东
            val dist = steps * lastStepLength
            val dx = (Math.sin(currentYaw.toDouble()) * dist).toFloat()  // 东向分量
            val dy = (Math.cos(currentYaw.toDouble()) * dist).toFloat()  // 北向分量
            lastWorldX += dx
            lastWorldY += dy
            binding.heatMap.addSample(lastWorldX, lastWorldY, rssi)
        } else {
            // 静止：同位置更新，不追加新点
            if (binding.heatMap.getSampleCount() > 0) {
                binding.heatMap.updateLastSample(rssi)
            } else {
                binding.heatMap.addSample(lastWorldX, lastWorldY, rssi)
            }
        }
        updateSampleCount()
    }

    private fun updateSampleCount() {
        val count = binding.heatMap.getSampleCount()
        binding.tvSteps.text = getString(R.string.wifi_heatmap_steps, count) + "  共 $totalSteps 步"
    }

    // ===== 加速度计回退步频检测 =====
    private val accelMagHistory = ArrayList<Float>(50)
    private var lastStepTimeMs = 0L
    private var inStepPeak = false

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                val r = FloatArray(9)
                SensorManager.getRotationMatrixFromVector(r, event.values)
                val orientation = FloatArray(3)
                SensorManager.getOrientation(r, orientation)
                rawYaw = orientation[0]
                // 用复数平均法做低通滤波（避免 359°↔1° 跳变）
                yawWindow.add(rawYaw.toDouble())
                if (yawWindow.size > 10) yawWindow.removeFirst()
                var s = 0.0
                var c = 0.0
                for (y in yawWindow) {
                    s += Math.sin(y)
                    c += Math.cos(y)
                }
                currentYaw = Math.atan2(s, c).toFloat()
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                // 系统级步频检测：每步触发一次
                onStepDetected()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // 始终更新加速度 max/min（用于 Weinberg 自适应步长）
                val ax = event.values[0]
                val ay = event.values[1]
                val az = event.values[2]
                val mag = Math.sqrt((ax * ax + ay * ay + az * az).toDouble()).toFloat()
                if (mag > accelMax) accelMax = mag
                if (mag < accelMin) accelMin = mag

                // 无系统步频检测时，用加速度计做手动步频检测
                if (hasStepDetector) return

                val linear = mag - 9.81f
                accelMagHistory.add(linear)
                if (accelMagHistory.size > 50) accelMagHistory.removeAt(0)
                if (accelMagHistory.size < 10) return

                val avg = accelMagHistory.average().toFloat()
                val threshold = if (avg > 0.5f) avg * 1.5f else 1.0f

                val now = System.currentTimeMillis()
                if (!inStepPeak && linear > threshold && now - lastStepTimeMs > 300) {
                    inStepPeak = true
                } else if (inStepPeak && linear < threshold * 0.5f) {
                    inStepPeak = false
                    lastStepTimeMs = now
                    onStepDetected()
                }
            }
        }
    }

    /** 每检测到一步时调用：用 Weinberg 算法估算步长，并累加步数 */
    private fun onStepDetected() {
        // Weinberg: stepLength = K * sqrt(accelMax - accelMin)
        // accelMax/accelMin 是这一步周期内的加速度幅值极值
        val diff = accelMax - accelMin
        if (diff > 0.1f) {  // 避免噪声导致极小值
            val estimated = weinbergK * Math.sqrt(diff.toDouble()).toFloat()
            // 限制步长在合理范围 0.3 ~ 1.2 米
            lastStepLength = estimated.coerceIn(0.3f, 1.2f)
        }
        // 重置极值，为下一步做准备
        accelMax = Float.MIN_VALUE
        accelMin = Float.MAX_VALUE

        stepsInPeriod++
        totalSteps++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun getCurrentRssi(): Int {
        return try {
            @Suppress("DEPRECATION")
            val info = wifiManager.connectionInfo
            if (info.bssid == null || info.bssid == "02:00:00:00:00:00") {
                Int.MIN_VALUE
            } else {
                info.rssi
            }
        } catch (e: Exception) {
            Int.MIN_VALUE
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopSampling()
        _binding = null
    }
}
