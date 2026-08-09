package com.example.videodownloader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.videodownloader.databinding.ActivityBatteryInfoBinding
import java.io.File
import kotlin.math.abs

/**
 * 充电信息工具：实时显示电池 / 充电相关数据，对标 vivo 工程模式（*#*#113#*#*）。
 *
 * 数据来源（均无需 root）：
 *  1. ACTION_BATTERY_CHANGED 粘性广播：电压、温度、电量、状态、充电方式、健康度、
 *     循环次数、最大充电电流/电压（后三项视机型/系统版本而定）
 *  2. BatteryManager 属性：瞬时/平均电流、剩余电荷、剩余能量、电量计容量
 *  3. /sys/class/power_supply 节点：部分机型可读，能拿到 Vbus、充电器类型等底层数据
 *  4. /sys/class/thermal 温度节点：部分机型可读，近似工程模式里的 Tboard
 *
 * 界面每秒刷新一次；sysfs / 温度节点每 5 秒在后台线程扫描一次。
 */
class BatteryInfoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBatteryInfoBinding
    private lateinit var batteryManager: BatteryManager
    private val handler = Handler(Looper.getMainLooper())

    private var lastBatteryIntent: Intent? = null
    private var tickCount = 0

    /** 最近一次从电源节点提取到的充电器输入端数据（Vbus 侧），null 表示机型不可读 */
    private var lastChargerInput: ChargerInput? = null

    @Volatile
    private var sysfsScanning = false

    /** 充电器输入端（Vbus 侧）数据：电压 V、电流 A、来源节点名 */
    private data class ChargerInput(
        val voltageV: Double?,
        val currentA: Double?,
        val source: String
    )

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lastBatteryIntent = intent
            updateUi()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateUi()
            tickCount++
            if (tickCount % 5 == 1) refreshSysfsAsync()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBatteryInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        batteryManager = getSystemService(BatteryManager::class.java)

        binding.chartCurrent.configure(
            color = 0xFFFB8C00.toInt(), showThreshold = false, initMax = 3000f, initMin = 0f
        )
        binding.chartVoltage.configure(
            color = 0xFF1E88E5.toInt(), showThreshold = false, initMax = 4.6f, initMin = 3f
        )
        binding.chartPower.configure(
            color = 0xFFE53935.toInt(), showThreshold = false, initMax = 30f, initMin = 0f
        )
        binding.chartTemp.configure(
            color = 0xFF43A047.toInt(), showThreshold = false, initMax = 45f, initMin = 15f
        )
    }

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        tickCount = 0
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(batteryReceiver)
        handler.removeCallbacks(ticker)
    }

    // ---------------- 数据读取 ----------------

    /** BatteryManager int 属性，不支持时系统返回 [Int.MIN_VALUE]，统一转成 null */
    private fun getIntProp(id: Int): Int? {
        val v = batteryManager.getIntProperty(id)
        return if (v == Int.MIN_VALUE) null else v
    }

    private fun getLongProp(id: Int): Long? {
        val v = batteryManager.getLongProperty(id)
        return if (v == Long.MIN_VALUE) null else v
    }

    // ---------------- UI 刷新 ----------------

    private fun updateUi() {
        val intent = lastBatteryIntent ?: return

        val status = intent.getIntExtra("status", BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra("plugged", 0)
        val health = intent.getIntExtra("health", BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val level = intent.getIntExtra("level", -1)
        val scale = intent.getIntExtra("scale", 100).takeIf { it > 0 } ?: 100
        val voltageMv = intent.getIntExtra("voltage", -1)
        val tempRaw = intent.getIntExtra("temperature", Int.MIN_VALUE)
        val technology = intent.getStringExtra("technology")
        val present = intent.getBooleanExtra("present", true)
        // 以下三个 extra 只在较新系统 / 部分机型上存在，用字符串 key 读取以兼容旧版本
        val cycleCount =
            if (intent.hasExtra("cycle_count")) intent.getIntExtra("cycle_count", -1) else null
        val maxChargeCurrentUa =
            if (intent.hasExtra("max_charging_current")) intent.getIntExtra(
                "max_charging_current", -1
            ) else null
        val maxChargeVoltageUv =
            if (intent.hasExtra("max_charging_voltage")) intent.getIntExtra(
                "max_charging_voltage", -1
            ) else null

        val currentNowUa = getIntProp(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentAvgUa = getIntProp(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        val capacityPct = getIntProp(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val chargeCounterUah = getLongProp(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        val energyCounterNwh = getLongProp(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)

        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        // 电流符号各厂商定义不一（高通平台多为放电为正），方向以充电状态为准，数值取绝对值
        val currentMa = currentNowUa?.let { abs(it) / 1000.0 }
        val voltageV = if (voltageMv > 0) voltageMv / 1000.0 else null
        val powerW = if (currentMa != null && voltageV != null) currentMa / 1000.0 * voltageV else null
        val tempC = if (tempRaw != Int.MIN_VALUE) tempRaw / 10.0 else null

        // 顶部：电量 + 状态
        binding.tvLevelBig.text = if (level >= 0) "%d%%".format(level * 100 / scale) else "--"
        binding.tvStatus.text = listOf(statusText(status), pluggedText(plugged))
            .filter { it.isNotEmpty() }
            .joinToString(" · ")

        // 4 大指标卡片
        binding.tvCurrent.text = currentMa?.let { "%.0f".format(it) } ?: "--"
        binding.tvCurrentCaption.text = when {
            currentNowUa == null -> "mA · 机型不支持读取"
            charging -> "mA · 流入电池"
            status == BatteryManager.BATTERY_STATUS_FULL -> "mA · 已充满"
            else -> "mA · 流出电池"
        }
        binding.tvVoltage.text = voltageV?.let { "%.3f".format(it) } ?: "--"
        binding.tvPower.text = powerW?.let { "%.2f".format(it) } ?: "--"
        binding.tvTemp.text = tempC?.let { "%.1f".format(it) } ?: "--"

        // 实时曲线
        currentMa?.let { binding.chartCurrent.addPoint(it.toFloat()) }
        voltageV?.let { binding.chartVoltage.addPoint(it.toFloat()) }
        powerW?.let { binding.chartPower.addPoint(it.toFloat()) }
        tempC?.let { binding.chartTemp.addPoint(it.toFloat()) }

        // 全部参数
        val rows = mutableListOf<Pair<String, String>>()
        rows.add("状态" to statusText(status))
        if (plugged != 0) rows.add("充电方式" to pluggedText(plugged))
        if (level >= 0) rows.add("电量" to "%d / %d（%d%%）".format(level, scale, level * 100 / scale))
        rows.add("电池存在" to if (present) "是" else "否")
        if (!technology.isNullOrBlank()) rows.add("电池技术" to technology)
        rows.add("健康度" to healthText(health))
        cycleCount?.takeIf { it >= 0 }?.let { rows.add("循环次数" to "$it 次") }
        currentNowUa?.let { rows.add("瞬时电流（原始值）" to "$it µA") }
        currentAvgUa?.let { rows.add("平均电流" to "%.0f mA".format(abs(it) / 1000.0)) }
        if (voltageMv > 0) rows.add("电池电压" to "$voltageMv mV")
        powerW?.let { rows.add("充电功率（估算）" to "%.2f W".format(it)) }
        tempC?.let { rows.add("电池温度" to "%.1f °C".format(it)) }
        chargeCounterUah?.let { rows.add("剩余电荷" to "%.0f mAh".format(it / 1000.0)) }
        energyCounterNwh?.let { rows.add("剩余能量" to "%.1f mWh".format(it / 1e6)) }
        capacityPct?.let { rows.add("电量计容量" to "$it %") }
        maxChargeCurrentUa?.takeIf { it > 0 }?.let { rows.add("最大充电电流" to "${it / 1000} mA") }
        maxChargeVoltageUv?.takeIf { it > 0 }?.let { rows.add("最大充电电压" to "${it / 1000} mV") }
        // 充电器输入端（Vbus 侧，来自电源节点）
        lastChargerInput?.let { input ->
            input.voltageV?.let { rows.add("充电器输入电压（Vbus）" to "%.2f V".format(it)) }
            input.currentA?.let { rows.add("充电器输入电流（Ibus）" to "%.2f A".format(abs(it))) }
            if (input.voltageV != null && input.currentA != null) {
                rows.add(
                    "充电器输入功率" to "%.1f W".format(input.voltageV * abs(input.currentA))
                )
            }
        }
        rebuildDetails(rows)

        // 广播原始数据
        binding.tvRawExtras.text = dumpExtras(intent)
    }

    private fun statusText(status: Int) = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
        BatteryManager.BATTERY_STATUS_FULL -> "已充满"
        else -> "未知"
    }

    /** plugged 是位掩码：1=AC 2=USB 4=无线 8=底座(API 33+)，不用常量以兼容旧系统 */
    private fun pluggedText(plugged: Int): String {
        val parts = mutableListOf<String>()
        if (plugged and 1 != 0) parts.add("交流适配器")
        if (plugged and 2 != 0) parts.add("USB")
        if (plugged and 4 != 0) parts.add("无线充电")
        if (plugged and 8 != 0) parts.add("底座")
        return parts.joinToString("+")
    }

    private fun healthText(health: Int) = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "良好"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "过热"
        BatteryManager.BATTERY_HEALTH_DEAD -> "损坏"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "电压过高"
        BatteryManager.BATTERY_HEALTH_COLD -> "温度过低"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "未知故障"
        else -> "未知"
    }

    /** 动态重建参数列表（部分参数视机型是否存在而定，所以每次全量重建） */
    private fun rebuildDetails(rows: List<Pair<String, String>>) {
        val container = binding.detailsContainer
        container.removeAllViews()
        rows.forEachIndexed { index, (label, value) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dpToPx(8), dpToPx(7), dpToPx(8), dpToPx(7))
                if (index % 2 == 0) setBackgroundColor(0x08000000)
            }
            val tvLabel = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(0xFF666666.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            val tvValue = TextView(this).apply {
                text = value
                textSize = 14f
                setTextColor(0xFF212121.toInt())
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.2f
                )
            }
            row.addView(tvLabel)
            row.addView(tvValue)
            container.addView(row)
        }
    }

    /** 把广播里的全部 extra 原样 dump 出来，机型差异带来的额外字段也能看到 */
    @Suppress("DEPRECATION")
    private fun dumpExtras(intent: Intent): String {
        val extras = intent.extras ?: return "（无数据）"
        val sb = StringBuilder()
        for (key in extras.keySet().sorted()) {
            sb.append(key).append(" = ").append(extras.get(key)).append('\n')
        }
        return sb.toString().trim()
    }

    // ---------------- sysfs / 温度节点 ----------------

    private fun refreshSysfsAsync() {
        if (sysfsScanning) return
        sysfsScanning = true
        Thread {
            val (psText, psData) = scanPowerSupply()
            val th = scanThermal()
            val input = extractChargerInput(psData)
            runOnUiThread {
                binding.tvSysfs.text = psText
                binding.tvThermal.text = th
                lastChargerInput = input
                updateInputCard(input)
                sysfsScanning = false
            }
        }.apply { isDaemon = true; start() }
    }

    /** 更新充电器输入端卡片：读不到数据时隐藏 */
    private fun updateInputCard(input: ChargerInput?) {
        if (input == null || (input.voltageV == null && input.currentA == null)) {
            binding.inputCard.visibility = View.GONE
            return
        }
        binding.inputCard.visibility = View.VISIBLE
        binding.tvInputVoltage.text = input.voltageV?.let { "%.2f".format(it) } ?: "--"
        binding.tvInputCurrent.text = input.currentA?.let { "%.2f".format(abs(it)) } ?: "--"
        val power =
            if (input.voltageV != null && input.currentA != null)
                input.voltageV * abs(input.currentA)
            else null
        binding.tvInputPower.text = power?.let { "%.1f".format(it) } ?: "--"
        binding.tvInputSource.text = "节点：${input.source}"
    }

    /**
     * 从电源节点结构化数据中提取充电器输入端（Vbus/Ibus）。
     * 优先在 usb / charger / main 等输入侧节点里找电压电流；
     * battery / bms 节点里的 VOLTAGE_NOW 是电池电压，不算输入端。
     */
    private fun extractChargerInput(
        data: Map<String, Map<String, String>>
    ): ChargerInput? {
        val preferred = data.keys.sortedBy { name ->
            when {
                "usb" in name.lowercase() -> 0
                "charger" in name.lowercase() || "charge_pump" in name.lowercase() -> 1
                "main" in name.lowercase() || "pmic" in name.lowercase() ||
                        "wireless" in name.lowercase() -> 2
                "battery" in name.lowercase() || "bms" in name.lowercase() -> 4
                else -> 3
            }
        }
        for (name in preferred) {
            val isBatteryNode = "battery" in name.lowercase() || "bms" in name.lowercase()
            val props = data[name] ?: continue
            var vRaw: Double? = null
            var iRaw: Double? = null
            for ((k, v) in props) {
                val key = k.uppercase()
                val num = v.toDoubleOrNull() ?: continue
                if (vRaw == null && ("VBUS" in key || key == "INPUT_VOLTAGE_NOW" ||
                            (key == "VOLTAGE_NOW" && !isBatteryNode))
                ) {
                    vRaw = num
                }
                if (iRaw == null && (key == "INPUT_CURRENT_NOW" || "IBUS" in key ||
                            (key == "CURRENT_NOW" && !isBatteryNode))
                ) {
                    iRaw = num
                }
            }
            if (vRaw != null || iRaw != null) {
                return ChargerInput(normalizeVoltage(vRaw), normalizeCurrent(iRaw), name)
            }
        }
        return null
    }

    /** 内核电源节点电压标准是 µV，按量级兼容 mV / V 的厂商私有节点 */
    private fun normalizeVoltage(raw: Double?): Double? = raw?.let {
        when {
            it > 100_000 -> it / 1e6
            it > 100 -> it / 1e3
            else -> it
        }
    }

    /** 内核电源节点电流标准是 µA，按量级兼容 mA / A 的厂商私有节点 */
    private fun normalizeCurrent(raw: Double?): Double? = raw?.let {
        when {
            abs(it) > 100_000 -> it / 1e6
            abs(it) > 100 -> it / 1e3
            else -> it
        }
    }

    /**
     * 扫描 /sys/class/power_supply 下所有电源节点。
     * 优先读 uevent（一个文件包含该节点全部属性）；读不到则逐个文件尝试。
     * 能否读取取决于机型 SELinux 策略，全部不可读时返回提示。
     *
     * 返回：展示文本 + 结构化数据（节点名 -> 属性名 -> 原始值），后者用于提取 Vbus/Ibus。
     */
    private fun scanPowerSupply(): Pair<String, Map<String, Map<String, String>>> {
        val base = File("/sys/class/power_supply")
        val dirs = try {
            base.listFiles()?.sortedBy { it.name }
        } catch (_: Exception) {
            null
        }
        if (dirs.isNullOrEmpty()) {
            return "无法访问 /sys/class/power_supply（系统限制）" to emptyMap()
        }

        val sb = StringBuilder()
        val structured = LinkedHashMap<String, Map<String, String>>()
        for (dir in dirs) {
            val values = LinkedHashMap<String, String>()
            try {
                val uevent = File(dir, "uevent")
                if (uevent.canRead()) {
                    uevent.forEachLine { line ->
                        val i = line.indexOf('=')
                        if (i > 0) {
                            values[line.substring(0, i).removePrefix("POWER_SUPPLY_")] =
                                line.substring(i + 1)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            if (values.isEmpty()) {
                val files = try {
                    dir.listFiles()?.sortedBy { it.name }
                } catch (_: Exception) {
                    null
                } ?: emptyList()
                for (f in files) {
                    if (!f.isFile) continue
                    try {
                        if (f.canRead() && f.length() in 1..4096) {
                            values[f.name] = f.readText().trim()
                        }
                    } catch (_: Exception) {
                    }
                }
            }
            if (values.isNotEmpty()) {
                structured[dir.name] = values
                sb.append("【").append(dir.name).append("】\n")
                values.forEach { (k, v) -> sb.append("  ").append(k).append(" = ").append(v).append('\n') }
            }
        }
        if (structured.isEmpty()) sb.append("所有节点均被系统 SELinux 限制，无法读取（部分机型可读）")
        return sb.toString().trim() to structured
    }

    /**
     * 扫描 /sys/class/thermal/thermal_zone* 的温度。
     * 原始值一般是毫摄氏度（如 36500），大于 1000 时按毫度换算。
     */
    private fun scanThermal(): String {
        val base = File("/sys/class/thermal")
        val zones = try {
            base.listFiles { f -> f.isDirectory && f.name.startsWith("thermal_zone") }
                ?.sortedBy { it.name.removePrefix("thermal_zone").toIntOrNull() ?: 0 }
        } catch (_: Exception) {
            null
        }
        if (zones.isNullOrEmpty()) return "无法访问温度节点（系统限制）"

        val sb = StringBuilder()
        var count = 0
        for (z in zones) {
            try {
                val typeFile = File(z, "type")
                val tempFile = File(z, "temp")
                if (!typeFile.canRead() || !tempFile.canRead()) continue
                val type = typeFile.readText().trim()
                val raw = tempFile.readText().trim().toLongOrNull() ?: continue
                val celsius = if (raw > 1000) raw / 1000.0 else raw.toDouble()
                if (celsius < -50 || celsius > 200) continue  // 过滤明显异常的值
                sb.append(type.padEnd(28)).append(" = ").append("%.1f °C".format(celsius)).append('\n')
                count++
            } catch (_: Exception) {
            }
        }
        if (count == 0) sb.append("所有温度节点均被系统限制，无法读取")
        return sb.toString().trim()
    }

    private fun dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
