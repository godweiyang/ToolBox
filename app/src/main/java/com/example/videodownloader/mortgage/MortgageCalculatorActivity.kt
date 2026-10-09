package com.example.videodownloader.mortgage

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.videodownloader.R
import com.example.videodownloader.databinding.ActivityMortgageBinding
import com.example.videodownloader.databinding.ItemMortgageRowBinding
import com.example.videodownloader.databinding.ViewMortgageLoanBinding
import java.util.Calendar
import java.util.Locale

/* 原生房贷计算器：表单录入 → MortgageEngine 测算 → 汇总卡片 + 逐月明细。 */
class MortgageCalculatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMortgageBinding

    // 选中的提前还款月份（1-based）
    private val prepayMonths = mutableSetOf<Int>()
    private var lastResult: ScheduleResult? = null
    private var loanNames: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMortgageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mortgageHeader.pageTitle.setText(R.string.tool_mortgage_native_title)
        binding.mortgageHeader.pageSubtitle.setText(R.string.tool_mortgage_native_desc)

        val now = Calendar.getInstance()
        binding.etStartYear.setText(now.get(Calendar.YEAR).toString())
        binding.etStartMonth.setText((now.get(Calendar.MONTH) + 1).toString())

        binding.sectionCommercial.tvLoanSectionTitle.setText(R.string.mortgage_type_commercial)
        binding.sectionFund.tvLoanSectionTitle.setText(R.string.mortgage_type_fund)
        binding.sectionCommercial.etLoanRate.setText("3.5")
        binding.sectionFund.etLoanRate.setText("2.85")
        binding.sectionCommercial.etLoanYears.setText("30")
        binding.sectionFund.etLoanYears.setText("30")

        // 贷款类型
        bindSegment(
            listOf(binding.tvTypeCommercial, binding.tvTypeFund, binding.tvTypeCombo), 0
        ) { index -> applyLoanType(index) }

        // 每笔贷款的还款方式
        bindSectionMethod(binding.sectionCommercial)
        bindSectionMethod(binding.sectionFund)

        // 提前还款
        binding.swPrepay.setOnCheckedChangeListener { _, on ->
            binding.prepayBody.visibility = if (on) View.VISIBLE else View.GONE
        }
        bindSegment(
            listOf(binding.tvTargetCommercial, binding.tvTargetFund, binding.tvTargetAuto), 2
        ) {}
        bindSegment(listOf(binding.tvModeShorten, binding.tvModeReduce), 0) {}
        buildMonthChips()

        // 收入与存款
        binding.swIncome.setOnCheckedChangeListener { _, on ->
            binding.incomeBody.visibility = if (on) View.VISIBLE else View.GONE
        }

        binding.btnCalculate.setOnClickListener { calculate() }

        // 明细筛选
        bindSegment(
            listOf(binding.tvFilterAll, binding.tvFilterPrepay, binding.tvFilterAnnual), 0
        ) { renderSchedule() }

        binding.rvSchedule.layoutManager = LinearLayoutManager(this)
    }

    private fun applyLoanType(index: Int) {
        binding.sectionCommercial.root.visibility =
            if (index == 0 || index == 2) View.VISIBLE else View.GONE
        binding.sectionFund.root.visibility =
            if (index == 1 || index == 2) View.VISIBLE else View.GONE
        // 单贷款时提前还款目标自动对齐
        when (index) {
            0 -> listOf(binding.tvTargetCommercial, binding.tvTargetFund, binding.tvTargetAuto)
                .forEachIndexed { i, v -> v.isSelected = i == 0 }
            1 -> listOf(binding.tvTargetCommercial, binding.tvTargetFund, binding.tvTargetAuto)
                .forEachIndexed { i, v -> v.isSelected = i == 1 }
        }
    }

    private fun bindSectionMethod(section: ViewMortgageLoanBinding) {
        bindSegment(
            listOf(section.tvMethodEqualPayment, section.tvMethodEqualPrincipal), 0
        ) {}
    }

    /** 通用分段控件：返回选中项，点击切换。 */
    private fun bindSegment(options: List<TextView>, defaultIndex: Int, onChange: (Int) -> Unit) {
        fun select(i: Int) {
            options.forEachIndexed { idx, v -> v.isSelected = idx == i }
            onChange(i)
        }
        options.forEachIndexed { i, v -> v.setOnClickListener { select(i) } }
        select(defaultIndex)
    }

    private fun buildMonthChips() {
        val container = binding.monthChipContainer
        for (row in 0 until 3) {
            val ll = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { if (row > 0) topMargin = dp(6) }
            }
            for (col in 0 until 4) {
                val month = row * 4 + col + 1
                val tv = TextView(this).apply {
                    text = month.toString()
                    gravity = android.view.Gravity.CENTER
                    textSize = 12.5f
                    setBackgroundResource(R.drawable.mortgage_chip_bg)
                    setTextColor(getColorStateList(R.color.mortgage_chip_text))
                    layoutParams = LinearLayout.LayoutParams(0, dp(36)).apply {
                        weight = 1f
                        if (col > 0) marginStart = dp(6)
                    }
                    setOnClickListener {
                        isSelected = !isSelected
                        if (isSelected) prepayMonths.add(month) else prepayMonths.remove(month)
                    }
                }
                ll.addView(tv)
            }
            container.addView(ll)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun readSection(section: ViewMortgageLoanBinding, name: String): LoanInput? {
        val wan = section.etLoanAmount.text.toString().toDoubleOrNull()
        val rate = section.etLoanRate.text.toString().toDoubleOrNull()
        val years = section.etLoanYears.text.toString().toIntOrNull()
        if (wan == null || rate == null || years == null) return null
        if (wan <= 0 || rate < 0 || years <= 0) return null
        val method = if (section.tvMethodEqualPayment.isSelected)
            LoanMethod.EQUAL_PAYMENT else LoanMethod.EQUAL_PRINCIPAL
        return LoanInput(name, wan * 10000, rate, years * 12, method)
    }

    private fun calculate() {
        val typeIndex = listOf(
            binding.tvTypeCommercial, binding.tvTypeFund, binding.tvTypeCombo
        ).indexOfFirst { it.isSelected }

        val loans = mutableListOf<LoanInput>()
        if (typeIndex == 0 || typeIndex == 2)
            readSection(binding.sectionCommercial, getString(R.string.mortgage_type_commercial))
                ?.let { loans.add(it) }
        if (typeIndex == 1 || typeIndex == 2)
            readSection(binding.sectionFund, getString(R.string.mortgage_type_fund))
                ?.let { loans.add(it) }

        if (loans.isEmpty()) {
            Toast.makeText(this, R.string.mortgage_input_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        val startYear = binding.etStartYear.text.toString().toIntOrNull()
        val startMonthInput = binding.etStartMonth.text.toString().toIntOrNull()
        if (startYear == null || startMonthInput == null ||
            startMonthInput !in 1..12
        ) {
            Toast.makeText(this, R.string.mortgage_input_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        val prepay = if (binding.swPrepay.isChecked) {
            val amount = binding.etPrepayAmount.text.toString().toDoubleOrNull() ?: 0.0
            val target = when {
                binding.tvTargetCommercial.isSelected ->
                    PrepayTarget.Index(loans.indexOfFirst { it.name == getString(R.string.mortgage_type_commercial) }
                        .coerceAtLeast(0))
                binding.tvTargetFund.isSelected ->
                    PrepayTarget.Index(loans.indexOfFirst { it.name == getString(R.string.mortgage_type_fund) }
                        .coerceAtLeast(0))
                else -> PrepayTarget.Highest
            }
            val mode = if (binding.tvModeShorten.isSelected)
                PrepayMode.SHORTEN else PrepayMode.REDUCE
            PrepayConfig(true, amount, prepayMonths.sorted(), target, mode)
        } else PrepayConfig()

        val income = if (binding.swIncome.isChecked) IncomeConfig(
            enabled = true,
            startingSavings = binding.etSavings.text.toString().toDoubleOrNull() ?: 0.0,
            annualIncome = binding.etAnnualIncome.text.toString().toDoubleOrNull() ?: 0.0,
            annualLiving = binding.etAnnualLiving.text.toString().toDoubleOrNull() ?: 0.0,
            monthlyFund = binding.etMonthlyFund.text.toString().toDoubleOrNull() ?: 0.0
        ) else IncomeConfig()

        val cfg = ScheduleConfig(startYear, startMonthInput - 1, loans, prepay, income)
        val result = MortgageEngine.buildSchedule(cfg)
        lastResult = result
        loanNames = loans.map { it.name }
        renderResult(result)
    }

    private fun money(v: Long): String = "%,d".format(Locale.US, v)

    private fun renderResult(result: ScheduleResult) {
        binding.resultArea.visibility = View.VISIBLE
        val first = result.rows.first()
        val last = result.rows.last()
        binding.tvResFirstPay.text = "${money(first.regPayTotal)} 元"
        binding.tvResMonths.text = "${result.rows.size} 期"
        binding.tvResInterest.text = "${money(result.totalInterest)} 元"
        binding.tvResPrepay.text = "${money(result.totalPrepay)} 元"
        binding.tvResPayoff.text = "${last.y}年${last.m + 1}月"
        binding.tvResEarliest.text = result.earliest?.label ?: "—"

        // 每笔贷款汇总
        val container = binding.loanSummaryContainer
        container.removeAllViews()
        result.loanSummary.forEachIndexed { i, summary ->
            val title = TextView(this).apply {
                text = "${loanNames.getOrElse(i) { summary.name }} · ${summary.payoff} 结清"
                setTextColor(0xFF0F3B5D.toInt())
                textSize = 14f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            container.addView(title)
            val body = TextView(this).apply {
                text = "累计利息 ${money(summary.totalInterest)} 元 · " +
                    "提前还款 ${money(summary.totalPrepay)} 元"
                setTextColor(0xFF5C6B77.toInt())
                textSize = 12.5f
                setPadding(0, dp(5), 0, if (i == result.loanSummary.lastIndex) 0 else dp(10))
            }
            container.addView(body)
        }
        renderSchedule()
    }

    private fun renderSchedule() {
        val result = lastResult ?: return
        val filterIndex = listOf(
            binding.tvFilterAll, binding.tvFilterPrepay, binding.tvFilterAnnual
        ).indexOfFirst { it.isSelected }
        val rows = result.rows.filter { r ->
            when (filterIndex) {
                1 -> r.prepayTotal > 0
                2 -> r.m == 11
                else -> true
            }
        }
        binding.rvSchedule.adapter = ScheduleAdapter(rows, loanNames)
    }

    private inner class ScheduleAdapter(
        val rows: List<ScheduleRow>,
        val names: List<String>
    ) : RecyclerView.Adapter<ScheduleAdapter.VH>() {

        inner class VH(val itemBinding: ItemMortgageRowBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val b = ItemMortgageRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(b)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = rows[position]
            with(holder.itemBinding) {
                tvRowDate.text = "第${position + 1}期 · ${r.label}"
                tvRowPay.text = "月供 ${money(r.regPayTotal)}"
                tvRowDetail.text = "本金 ${money(r.regPrinTotal)} · 利息 ${money(r.interestTotal)}" +
                    " · 余额 ${money(r.endTotal)}"
                if (r.prepayTotal > 0) {
                    tvRowPrepay.visibility = View.VISIBLE
                    tvRowPrepay.text = "本月提前还款 ${money(r.prepayTotal)} 元"
                } else tvRowPrepay.visibility = View.GONE

                rowLoanDetail.removeAllViews()
                r.perLoan.forEachIndexed { i, p ->
                    if (p.begin > 0 || p.extra > 0) {
                        val tv = TextView(root.context).apply {
                            text = "${names.getOrElse(i) { "贷款${i + 1}" }}：月供 ${money(p.regPay)}" +
                                " · 本金 ${money(p.regPrin + p.extra)} · 利息 ${money(p.interest)}" +
                                " · 余额 ${money(p.end)}"
                            setTextColor(0xFF5C6B77.toInt())
                            textSize = 11.5f
                            setPadding(0, dp(3), 0, dp(3))
                        }
                        rowLoanDetail.addView(tv)
                    }
                }
                root.setOnClickListener {
                    rowLoanDetail.visibility =
                        if (rowLoanDetail.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                }
            }
        }

        override fun getItemCount(): Int = rows.size
    }
}
