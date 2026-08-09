package com.example.videodownloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.videodownloader.databinding.ActivityGnssSkyBinding

/**
 * 卫星天空图工具：实时显示 GNSS 卫星在天空中的分布。
 *
 * 数据来源（均无需 root，需 ACCESS_FINE_LOCATION 权限）：
 *  1. LocationManager.registerGnssStatusCallback：每秒回调所有可见卫星的
 *     方位角、仰角、载噪比（C/N0）、星座类型、是否用于定位
 *  2. LocationManager.requestLocationUpdates：定位精度、速度、坐标
 *  3. NMEA 消息监听
 *
 * 界面：天空图（可点击卫星查看详情）+ 统计卡片 + 信号曲线 + 卫星列表。
 */
class GnssSkyActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGnssSkyBinding
    private lateinit var locationManager: LocationManager

    private var gnssCallback: GnssStatus.Callback? = null
    private var nmeaListener: OnNmeaMessageListener? = null
    private var currentLocation: Location? = null
    private var lastFixTime: Long = 0

    private var lastStatus: GnssStatus? = null
    private var avgCn0History = mutableListOf<Float>()

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            currentLocation = location
            lastFixTime = System.currentTimeMillis()
            updateLocationInfo()
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startGnss()
        else showPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGnssSkyBinding.inflate(layoutInflater)
        setContentView(binding.root)

        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        binding.chartCn0.configure(
            color = 0xFF6750A4.toInt(), showThreshold = false, initMax = 50f, initMin = 0f
        )

        binding.skyplot.onSatelliteSelected = { sat ->
            showSelectedSatInfo(sat)
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissionAndStart()
    }

    override fun onPause() {
        super.onPause()
        stopGnss()
    }

    // ---------------- 权限 ----------------

    @SuppressLint("MissingPermission")
    private fun checkPermissionAndStart() {
        val perm = Manifest.permission.ACCESS_FINE_LOCATION
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            startGnss()
        } else {
            try {
                locationPermissionLauncher.launch(perm)
            } catch (e: Exception) {
                showPermissionDenied()
            }
        }
    }

    private fun showPermissionDenied() {
        binding.permissionPrompt.visibility = View.VISIBLE
        binding.skyplot.visibility = View.GONE
        binding.chartCn0.visibility = View.GONE
    }

    // ---------------- GNSS 注册 ----------------

    @SuppressLint("MissingPermission")
    private fun startGnss() {
        binding.permissionPrompt.visibility = View.GONE
        binding.skyplot.visibility = View.VISIBLE
        binding.chartCn0.visibility = View.VISIBLE

        // 1. 卫星状态回调（核心数据源，约每秒一次）
        gnssCallback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                lastStatus = status
                updateUi()
            }

            override fun onStarted() {
                binding.tvStatus.text = "GNSS 已启动"
            }

            override fun onStopped() {
                binding.tvStatus.text = "GNSS 已停止"
            }

            override fun onFirstFix(ttffMillis: Int) {
                binding.tvStatus.text = "首次定位 ${ttffMillis / 1000.0}s"
            }
        }
        val callback = gnssCallback!!  // 局部 val 避免 smart cast 问题

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.registerGnssStatusCallback(
                ContextCompat.getMainExecutor(this), callback
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.registerGnssStatusCallback(callback)
        }

        // 2. 定位更新（获取精度、坐标、速度）
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener
        )

        // 3. NMEA 监听
        val nmea = OnNmeaMessageListener { _, _ -> }
        nmeaListener = nmea
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.addNmeaListener(
                ContextCompat.getMainExecutor(this), nmea
            )
        } else {
            @Suppress("DEPRECATION")
            locationManager.addNmeaListener(nmea)
        }
    }

    private fun stopGnss() {
        gnssCallback?.let {
            locationManager.unregisterGnssStatusCallback(it)
        }
        nmeaListener?.let {
            locationManager.removeNmeaListener(it)
        }
        locationManager.removeUpdates(locationListener)
    }

    // ---------------- UI 刷新 ----------------

    private fun updateUi() {
        val status = lastStatus ?: return
        val satCount = status.satelliteCount
        if (satCount == 0) {
            binding.tvVisibleCount.text = "0"
            binding.tvUsedCount.text = "0"
            binding.tvAvgCn0.text = "--"
            binding.skyplot.updateSatellites(emptyList())
            binding.satListContainer.removeAllViews()
            val emptyTv = TextView(this).apply {
                text = "等待卫星信号…"
                gravity = Gravity.CENTER
                setPadding(dpToPx(8), dpToPx(16), dpToPx(8), dpToPx(16))
                setTextColor(0xFF999999.toInt())
                textSize = 14f
            }
            binding.satListContainer.addView(emptyTv)
            return
        }

        val satList = mutableListOf<SkyplotView.Sat>()
        var usedCount = 0
        var totalCn0 = 0f
        var cn0Count = 0
        val constellationCount = mutableMapOf<Int, Int>()

        for (i in 0 until satCount) {
            val constellation = status.getConstellationType(i)
            val svid = status.getSvid(i)
            val az = status.getAzimuthDegrees(i)
            val el = status.getElevationDegrees(i)
            val cn0 = status.getCn0DbHz(i)
            val used = status.usedInFix(i)

            if (el < 0f || az < 0f) continue

            satList.add(SkyplotView.Sat(svid, constellation, az, el, cn0, used))
            if (used) usedCount++
            if (cn0 > 0f) {
                totalCn0 += cn0
                cn0Count++
            }
            constellationCount[constellation] = (constellationCount[constellation] ?: 0) + 1
        }

        // 统计卡片
        binding.tvVisibleCount.text = satList.size.toString()
        binding.tvUsedCount.text = usedCount.toString()
        val avgCn0 = if (cn0Count > 0) totalCn0 / cn0Count else 0f
        binding.tvAvgCn0.text = if (cn0Count > 0) "%.1f".format(avgCn0) else "--"

        // 信号曲线
        if (cn0Count > 0) {
            avgCn0History.add(avgCn0)
            if (avgCn0History.size > 400) avgCn0History.removeAt(0)
            binding.chartCn0.addPoint(avgCn0)
        }

        // 天空图
        binding.skyplot.updateSatellites(satList)

        // 星座统计行
        val constStr = constellationCount.entries
            .sortedByDescending { it.value }
            .joinToString("  ") { (c, n) ->
                "${SkyplotView.constellationName(c)}:$n"
            }
        binding.tvConstellations.text = constStr.ifBlank { "--" }

        // 卫星列表
        rebuildSatList(satList)
    }

    /** 动态构建卫星参数列表（按 C/N0 降序） */
    private fun rebuildSatList(sats: List<SkyplotView.Sat>) {
        val container = binding.satListContainer
        container.removeAllViews()

        // 表头
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(8), dpToPx(6), dpToPx(8), dpToPx(6))
            setBackgroundColor(0x10000000)
        }
        header.addView(makeListCell("编号", 1f, true, 0xFF666666.toInt()))
        header.addView(makeListCell("星座", 1f, true, 0xFF666666.toInt()))
        header.addView(makeListCell("仰角", 0.7f, true, 0xFF666666.toInt()))
        header.addView(makeListCell("方位", 0.7f, true, 0xFF666666.toInt()))
        header.addView(makeListCell("C/N0", 0.7f, true, 0xFF666666.toInt()))
        header.addView(makeListCell("定位", 0.5f, true, 0xFF666666.toInt()))
        container.addView(header)

        sats.sortedByDescending { it.cn0 }.forEachIndexed { index, s ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpToPx(8), dpToPx(5), dpToPx(8), dpToPx(5))
                if (index % 2 == 0) setBackgroundColor(0x08000000)
            }
            val prefix = when (s.constellation) {
                SkyplotView.CONSTELLATION_GPS -> "G"
                SkyplotView.CONSTELLATION_GLONASS -> "R"
                SkyplotView.CONSTELLATION_GALILEO -> "E"
                SkyplotView.CONSTELLATION_BEIDOU -> "C"
                SkyplotView.CONSTELLATION_SBAS -> "S"
                SkyplotView.CONSTELLATION_QZSS -> "J"
                else -> "?"
            }
            val color = SkyplotView.constellationColorValue(s.constellation)
            row.addView(makeListCell("$prefix${s.svid}", 1f, false, color))
            row.addView(makeListCell(
                SkyplotView.constellationName(s.constellation), 1f, false, 0xFF424242.toInt()
            ))
            row.addView(makeListCell("%.0f°".format(s.elevation), 0.7f, false, 0xFF424242.toInt()))
            row.addView(makeListCell("%.0f°".format(s.azimuth), 0.7f, false, 0xFF424242.toInt()))
            row.addView(makeListCell(
                if (s.cn0 > 0) "%.1f".format(s.cn0) else "--",
                0.7f, false, if (s.cn0 >= 30) 0xFF2E7D32.toInt() else 0xFFE65100.toInt()
            ))
            row.addView(makeListCell(
                if (s.usedInFix) "✓" else "",
                0.5f, false, if (s.usedInFix) 0xFF2E7D32.toInt() else 0xFF999999.toInt()
            ))
            container.addView(row)
        }
    }

    private fun makeListCell(
        text: String, weight: Float, isHeader: Boolean, color: Int
    ): TextView = TextView(this).apply {
        this.text = text
        textSize = if (isHeader) 12f else 13f
        setTextColor(color)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight)
    }

    private fun updateLocationInfo() {
        val loc = currentLocation ?: return
        binding.tvLat.text = "%.6f".format(loc.latitude)
        binding.tvLon.text = "%.6f".format(loc.longitude)
        binding.tvAccuracy.text = if (loc.hasAccuracy()) "±%.0f".format(loc.accuracy) else "--"
        if (loc.hasSpeed() && loc.speed > 0f) {
            binding.tvSpeed.text = "%.1f m/s".format(loc.speed)
        }
        val ageSec = (System.currentTimeMillis() - lastFixTime) / 1000.0
        binding.tvFixAge.text = "%.0fs".format(ageSec)
    }

    private fun showSelectedSatInfo(sat: SkyplotView.Sat?) {
        if (sat == null) {
            binding.tvSelectedSat.text = "点击卫星查看详情"
            binding.tvSelectedSat.visibility = View.GONE
            return
        }
        binding.tvSelectedSat.visibility = View.VISIBLE
        binding.tvSelectedSat.text = buildString {
            append(SkyplotView.constellationName(sat.constellation))
            append(" ")
            append(when (sat.constellation) {
                SkyplotView.CONSTELLATION_GPS -> "G"
                SkyplotView.CONSTELLATION_GLONASS -> "R"
                SkyplotView.CONSTELLATION_GALILEO -> "E"
                SkyplotView.CONSTELLATION_BEIDOU -> "C"
                SkyplotView.CONSTELLATION_SBAS -> "S"
                SkyplotView.CONSTELLATION_QZSS -> "J"
                else -> "?"
            })
            append(sat.svid)
            append("  仰角 %.0f°  方位 %.0f°  C/N0 %.1f dB".format(sat.elevation, sat.azimuth, sat.cn0))
            append(if (sat.usedInFix) "  ★已用于定位" else "  未参与定位")
        }
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
