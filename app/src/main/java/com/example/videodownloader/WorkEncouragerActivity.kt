package com.example.videodownloader

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.util.Calendar
import java.util.Locale

/**
 * 上班鼓励器：按当天上班时间累计已赚金额，只显示现在最接近的那件日常物品。
 *
 * 口径：月薪按 21.75 个工作日折算，日工时 = 下班时间 - 上班时间。
 * 界面与 Windows / 网页版保持一致。
 */
class WorkEncouragerActivity : AppCompatActivity() {

    private companion object {
        const val WORK_DAYS = 21.75
        const val PREFS = "shangban_settings"
        const val KEY_SALARY = "salary"
        const val KEY_START = "start"
        const val KEY_END = "end"

        val REWARDS = arrayOf(
            "一瓶矿泉水" to 2, "一包纸巾" to 3, "一个包子" to 4, "一盒牛奶" to 5, "一瓶可乐" to 6,
            "一袋面包" to 8, "一份早餐" to 10, "一支牙刷" to 12, "一杯奶茶" to 18, "一支牙膏" to 20,
            "一杯咖啡" to 25, "一瓶洗洁精" to 30, "一顿午餐" to 35, "一袋洗衣液" to 45, "一把雨伞" to 59,
            "一个保温杯" to 79, "一瓶洗发水" to 99, "一件T恤" to 129, "一个充电宝" to 159,
            "一个电热水壶" to 199, "一套床单" to 299, "一双运动鞋" to 399, "一个电饭煲" to 499,
            "一副蓝牙耳机" to 699, "一台微波炉" to 899, "一把办公椅" to 1199, "一台显示器" to 1499,
            "一台扫地机器人" to 1999, "一台洗衣机" to 2499, "一台冰箱" to 2999, "一部手机" to 3999,
            "一台电视" to 4999, "一张床和床垫" to 5999, "一套沙发" to 6999, "一台笔记本" to 7999,
            "一辆电动自行车" to 8999, "一台高配电脑" to 12999, "一台洗烘套装" to 15999,
            "一套客厅家具" to 19999, "一套厨房电器" to 24999, "一套全屋家电" to 39999,
            "一套全屋家具" to 59999
        )
    }

    private val handler = Handler(Looper.getMainLooper())
    private var running = true
    private var salary = 10000.0
    private var startMinutes = 9 * 60
    private var endMinutes = 18 * 60

    private lateinit var tvClock: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvCountdown: TextView
    private lateinit var tvEarned: TextView
    private lateinit var tvRate: TextView
    private lateinit var tvMessage: TextView
    private lateinit var btnTimer: TextView
    private lateinit var tvProgress: TextView
    private lateinit var tvHours: TextView
    private lateinit var progressTrack: FrameLayout
    private lateinit var progressFill: View
    private lateinit var tvMonth: TextView
    private lateinit var tvHourly: TextView
    private lateinit var tvFullDay: TextView

    private val ticker = object : Runnable {
        override fun run() {
            if (running) update()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shangban)

        tvClock = findViewById(R.id.tvClock)
        tvStatus = findViewById(R.id.tvStatus)
        tvCountdown = findViewById(R.id.tvCountdown)
        tvEarned = findViewById(R.id.tvEarned)
        tvRate = findViewById(R.id.tvRate)
        tvMessage = findViewById(R.id.tvMessage)
        btnTimer = findViewById(R.id.btnTimer)
        tvProgress = findViewById(R.id.tvProgress)
        tvHours = findViewById(R.id.tvHours)
        progressTrack = findViewById(R.id.progressTrack)
        progressFill = findViewById(R.id.progressFill)
        tvMonth = findViewById(R.id.tvMonth)
        tvHourly = findViewById(R.id.tvHourly)
        tvFullDay = findViewById(R.id.tvFullDay)

        loadSettings()

        findViewById<ImageButton>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<ImageButton>(R.id.btnSettings).setOnClickListener { openSettings() }

        btnTimer.setOnClickListener {
            running = !running
            btnTimer.text = getString(if (running) R.string.tool_shangban_timer_off else R.string.tool_shangban_timer_on)
            if (running) update()
        }

        update()
        handler.postDelayed(ticker, 1000L)
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        super.onDestroy()
    }

    private fun loadSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        salary = prefs.getFloat(KEY_SALARY, 10000f).toDouble()
        startMinutes = prefs.getInt(KEY_START, 9 * 60)
        endMinutes = prefs.getInt(KEY_END, 18 * 60)
        if (endMinutes <= startMinutes) endMinutes = startMinutes + 60
    }

    private fun saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putFloat(KEY_SALARY, salary.toFloat())
            .putInt(KEY_START, startMinutes)
            .putInt(KEY_END, endMinutes)
            .apply()
    }

    private fun update() {
        val c = Calendar.getInstance()
        val nowSeconds = c.get(Calendar.HOUR_OF_DAY) * 3600 + c.get(Calendar.MINUTE) * 60 + c.get(Calendar.SECOND)
        val startSeconds = startMinutes * 60
        val endSeconds = endMinutes * 60
        val total = (endSeconds - startSeconds).toDouble()
        val fullDay = salary / WORK_DAYS
        val speed = salary / WORK_DAYS / total

        var worked: Double
        var remain: Double
        val status: String
        when {
            nowSeconds < startSeconds -> {
                status = "还没上班"
                worked = 0.0
                remain = total
            }
            nowSeconds >= endSeconds -> {
                status = "已下班"
                worked = total
                remain = 0.0
            }
            else -> {
                status = "上班中"
                worked = (nowSeconds - startSeconds).toDouble()
                remain = (endSeconds - nowSeconds).toDouble()
            }
        }
        if (worked > total) worked = total

        val earned = Math.round(worked * speed * 100.0) / 100.0
        val progress = if (total <= 0) 0.0 else worked / total

        val week = arrayOf("星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六")
        tvClock.text = String.format(
            Locale.CHINA, "%d年%d月%d日 %s  %02d:%02d:%02d",
            c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            week[c.get(Calendar.DAY_OF_WEEK) - 1],
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND)
        )

        tvStatus.text = status
        tvCountdown.text = when (status) {
            "上班中" -> "距离下班还有 " + duration(remain)
            "还没上班" -> "今天 " + hhmm(startMinutes) + " 开始上班"
            else -> "今天的班已经上完了"
        }

        tvEarned.text = "¥ " + money(earned)
        tvRate.text = "时薪 ¥ " + money(salary / WORK_DAYS / (total / 3600.0))
        tvMessage.text = message(earned, speed)

        val percent = Math.round(progress * 100).toInt().coerceAtMost(100)
        tvProgress.text = "今天已经过了 $percent%"
        tvHours.text = hhmm(startMinutes) + " - " + hhmm(endMinutes)

        progressTrack.post {
            val width = progressTrack.width
            if (width > 0) {
                val params = progressFill.layoutParams
                params.width = (width * progress).toInt().coerceIn(0, width)
                progressFill.layoutParams = params
            }
        }

        tvMonth.text = "¥ " + money(monthEarned(c, earned))
        tvHourly.text = "时薪 ¥ " + money(salary / WORK_DAYS / (total / 3600.0)) +
            " · 每秒 ¥ " + String.format(Locale.US, "%.4f", speed)
        tvFullDay.text = "全天满勤 ¥ " + money(fullDay)
    }

    private fun monthEarned(c: Calendar, earned: Double): Double {
        val fullDay = salary / WORK_DAYS
        var total = 0.0
        val day = Calendar.getInstance()
        day.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), 1, 0, 0, 0)
        val today = Calendar.getInstance()
        today.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH), 0, 0, 0)
        while (day.before(today)) {
            val dow = day.get(Calendar.DAY_OF_WEEK)
            if (dow != Calendar.SATURDAY && dow != Calendar.SUNDAY) total += fullDay
            day.add(Calendar.DAY_OF_MONTH, 1)
        }
        val dowToday = c.get(Calendar.DAY_OF_WEEK)
        if (dowToday != Calendar.SATURDAY && dowToday != Calendar.SUNDAY) total += earned
        return total
    }

    private fun message(earned: Double, speed: Double): String {
        val next = REWARDS.firstOrNull { it.second > earned }
            ?: return "今天赚的钱，已经能把最贵的那件赚回来了。"
        val seconds = if (speed <= 0) 0.0 else (next.second - earned) / speed
        return "再过 " + duration(seconds) + "，" + next.first + "就赚回来了。"
    }

    private fun duration(seconds: Double): String {
        val total = Math.ceil(if (seconds < 0) 0.0 else seconds).toLong()
        val h = total / 3600
        val m = (total % 3600) / 60
        return "$h 小时 $m 分"
    }

    private fun money(value: Double): String = String.format(Locale.US, "%,.2f", value)

    private fun hhmm(minutes: Int): String =
        String.format(Locale.US, "%02d:%02d", minutes / 60, minutes % 60)

    private fun openSettings() {
        val dialog: AlertDialog = AlertDialog.Builder(this)
            .setTitle(R.string.tool_shangban_settings)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.tool_shangban_save, null)
            .create()

        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(pad, pad / 2, pad, 0)

        val salaryBox = addField(container, getString(R.string.tool_shangban_salary_label), String.format(Locale.US, "%.2f", salary))
        salaryBox.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val startBox = addField(container, getString(R.string.tool_shangban_start_label), hhmm(startMinutes))
        val endBox = addField(container, getString(R.string.tool_shangban_end_label), hhmm(endMinutes))

        dialog.setView(container)
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsedSalary = salaryBox.text.toString().trim().toDoubleOrNull()
                if (parsedSalary == null || parsedSalary < 0) {
                    salaryBox.error = "月薪请填一个不小于 0 的数字"
                    return@setOnClickListener
                }
                val parsedStart = parseHhmm(startBox.text.toString())
                if (parsedStart == null) {
                    startBox.error = "请按 09:00 的格式填写"
                    return@setOnClickListener
                }
                val parsedEnd = parseHhmm(endBox.text.toString())
                if (parsedEnd == null) {
                    endBox.error = "请按 18:00 的格式填写"
                    return@setOnClickListener
                }
                if (parsedEnd <= parsedStart) {
                    endBox.error = "下班时间要晚于上班时间"
                    return@setOnClickListener
                }
                salary = parsedSalary
                startMinutes = parsedStart
                endMinutes = parsedEnd
                saveSettings()
                update()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun addField(parent: LinearLayout, label: String, value: String): EditText {
        val title = TextView(this)
        title.text = label
        title.textSize = 13f
        title.setTextColor(0xFF6C6C65.toInt())
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = (12 * resources.displayMetrics.density).toInt()
        parent.addView(title, lp)

        val box = EditText(this)
        box.setText(value)
        box.textSize = 16f
        box.setSingleLine(true)
        box.inputType = InputType.TYPE_CLASS_TEXT
        val lp2 = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp2.topMargin = (4 * resources.displayMetrics.density).toInt()
        parent.addView(box, lp2)
        return box
    }

    private fun parseHhmm(text: String): Int? {
        val parts = text.trim().replace('：', ':').split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h * 60 + m
    }
}
