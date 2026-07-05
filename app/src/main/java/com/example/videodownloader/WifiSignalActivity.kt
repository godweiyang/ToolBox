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
        val distance = estimateDistance(rssi, freq)
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
     * 自由空间路径损耗：FSPL(dB) = 20log10(d) + 20log10(f) - 27.55
     * 其中 d 单位米，f 单位 MHz
     * 反推：d = 10^((RSSI - A) / (10*n))，A 是 1m 处的信号强度，n 是路径损耗指数
     * 室内 n 通常取 2.5-3.5，A 通常取 -40 ~ -50 dBm@1m
     */
    private fun estimateDistance(rssi: Int, freqMHz: Int): Double {
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

/** 热力图页：拿着手机走动时绘制信号轨迹 */
class HeatmapFragment : Fragment() {

    private var _binding: PageWifiHeatmapBinding? = null
    private val binding get() = _binding!!

    private lateinit var wifiManager: WifiManager
    private lateinit var pdrTracker: PdrTracker
    private val handler = Handler(Looper.getMainLooper())

    private var isTracking = false
    private var lastSampleTime = 0L
    private var totalDistance = 0f

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

        pdrTracker = PdrTracker(stepLength = 0.7f) { x, y, stepCount ->
            // 每走一步，记录当前位置的 RSSI
            val now = System.currentTimeMillis()
            if (now - lastSampleTime > 800) {  // 800ms 采样一次，避免过密
                lastSampleTime = now
                val rssi = getCurrentRssi()
                if (rssi != Int.MIN_VALUE) {
                    binding.heatMap.addSample(x, y, rssi)
                }
            }

            totalDistance = stepCount * 0.7f
            binding.tvSteps.text = getString(
                R.string.wifi_heatmap_steps, stepCount, totalDistance
            )
        }

        binding.btnToggleHeat.setOnClickListener {
            if (isTracking) stopTracking() else startTracking()
        }

        binding.btnClear.setOnClickListener {
            pdrTracker.reset()
            binding.heatMap.clear()
            totalDistance = 0f
            binding.tvSteps.text = ""
            binding.tvHeatmapEmpty.visibility = View.VISIBLE
        }

        binding.tvHeatmapEmpty.visibility = View.VISIBLE
    }

    private fun startTracking() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1001)
            return
        }

        isTracking = true
        pdrTracker.reset()
        binding.heatMap.clear()
        binding.tvHeatmapEmpty.visibility = View.GONE
        binding.btnToggleHeat.text = getString(R.string.wifi_btn_stop)
        totalDistance = 0f

        val sm = requireContext().getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        pdrTracker.start(sm)

        // 立即采样一次起点
        val rssi = getCurrentRssi()
        if (rssi != Int.MIN_VALUE) {
            binding.heatMap.addSample(0f, 0f, rssi)
        }
    }

    private fun stopTracking() {
        isTracking = false
        pdrTracker.stop()
        binding.btnToggleHeat.text = getString(R.string.wifi_btn_start)
    }

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

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startTracking()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopTracking()
        _binding = null
    }
}
