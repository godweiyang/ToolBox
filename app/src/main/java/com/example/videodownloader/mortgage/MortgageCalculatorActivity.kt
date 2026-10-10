package com.example.videodownloader.mortgage

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.videodownloader.R
import com.example.videodownloader.databinding.ActivityMortgageBinding
import com.example.videodownloader.databinding.ItemMortgageRowBinding
import com.example.videodownloader.databinding.SheetMortgageDetailBinding
import com.example.videodownloader.databinding.ViewMortgageLoanBinding
import java.util.Calendar

/**
 * 房贷计算器：
 * - 卡片化表单，贷款类型 / 年限 / 方式 / 目标等用 ExposedDropdown 下拉
 * - 逐月明细分页展示（每页 24 期），点击行用 BottomSheet 展示整洁详情
 * - 表单数据缓存到 SharedPreferences，下次打开自动恢复
 */
class MortgageCalculatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMortgageBinding

    private var result: ScheduleResult? = null
    private var filteredRows: List<ScheduleRow> = emptyList()
    private var pageItems: List<ScheduleRow> = emptyList()
    private var currentPage = 0
    private var filterMode = FILTER_ALL
    private val rowAdapter = RowAdapter()
    private val monthChips = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMortgageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val header = binding.mortgageHeader
        header.pageTitle.setText(R.string.tool_mortgage_native_title)
        header.pageSubtitle.setText(R.string.tool_mortgage_native_desc)

        setupDropdowns()
        setupMonthChips()
        setupResults()

        binding.swPrepay.setOnCheckedChangeListener { _, on ->
            binding.prepayBody.visibility = if (on) View.VISIBLE else View.GONE
        }
        binding.swIncome.setOnCheckedChangeListener { _, on ->
            binding.incomeBody.visibility = if (on) View.VISIBLE else View.GONE
        }
        binding.btnCalculate.setOnClickListener { calculate() }
        binding.btnPrevPage.setOnClickListener {
            if (currentPage > 0) { currentPage--; renderPage() }
        }
        binding.btnNextPage.setOnClickListener {
            if (currentPage < totalPages() - 1) { currentPage++; renderPage() }
        }

        setupFilterSegment()
        restorePrefs()
    }

    // ===== 初始化 =====

    private fun setupDropdowns() {
        val now = Calendar.getInstance()
        binding.etStartYear.setText(now.get(Calendar.YEAR).toString())

        bindDropdown(binding.ddStartMonth,
            (1..12).map { "${it}月" },
            "${now.get(Calendar.MONTH) + 1}月")

        bindDropdown(binding.ddLoanType,
            listOf(getString(R.string.mortgage_type_commercial),
                getString(R.string.mortgage_type_fund),
                getString(R.string.mortgage_type_combo)),
            getString(R.string.mortgage_type_commercial))
        binding.ddLoanType.setOnItemClickListener { _, _, position, _ -> applyLoanType(position) }

        bindLoanSection(binding.sectionCommercial, "3.5")
        binding.sectionCommercial.tvLoanSectionTitle.setText(R.string.mortgage_type_commercial)
        bindLoanSection(binding.sectionFund, "2.85")
        binding.sectionFund.tvLoanSectionTitle.setText(R.string.mortgage_type_fund)

        bindDropdown(binding.ddPrepayTarget,
            listOf(getString(R.string.mortgage_type_commercial),
                getString(R.string.mortgage_type_fund),
                getString(R.string.mortgage_target_auto)),
            getString(R.string.mortgage_target_auto))
        bindDropdown(binding.ddPrepayMode,
            listOf(getString(R.string.mortgage_mode_shorten),
                getString(R.string.mortgage_mode_reduce)),
            getString(R.string.mortgage_mode_shorten))
    }

    private fun bindLoanSection(section: ViewMortgageLoanBinding, defaultRate: String) {
        section.etMonths.setText("360")
        bindDropdown(section.ddMethod,
            listOf(getString(R.string.mortgage_method_equal_payment),
                getString(R.string.mortgage_method_equal_principal)),
            getString(R.string.mortgage_method_equal_payment))
        section.etRate.setText(defaultRate)
    }

    private fun bindDropdown(
        actv: AutoCompleteTextView, items: List<String>, initial: String
    ) {
        actv.setAdapter(ArrayAdapter(this, android.R.layout.simple_list_item_1, items))
        actv.setText(initial, false)
    }

    private fun applyLoanType(typeIdx: Int) {
        binding.sectionCommercial.root.visibility =
            if (typeIdx == 1) View.GONE else View.VISIBLE
        binding.sectionFund.root.visibility =
            if (typeIdx == 0) View.GONE else View.VISIBLE
    }

    private fun setupFilterSegment() {
        val views = listOf(binding.filterAll, binding.filterPrepay, binding.filterDecember)
        fun render(idx: Int) {
            views.forEachIndexed { i, v -> v.isSelected = i == idx }
        }
        views.forEachIndexed { i, v ->
            v.setOnClickListener {
                render(i)
                filterMode = i
                currentPage = 0
                applyFilter()
            }
        }
        render(0)
    }

    private fun setupMonthChips() {
        val container = binding.monthChipContainer
        monthChips.clear()
        for (row in 0 until 3) {
            val rowView = LinearLayoutRow()
            for (col in 0 until 4) {
                val month = row * 4 + col + 1
                val chip = layoutInflater.inflate(
                    R.layout.item_mortgage_month_chip, rowView, false
                ) as TextView
                chip.text = "${month}月"
                chip.layoutParams = (chip.layoutParams as android.widget.LinearLayout.LayoutParams).apply {
                    width = 0
                    weight = 1f
                    if (col > 0) marginStart = dp(8)
                }
                chip.setOnClickListener { chip.isSelected = !chip.isSelected }
                if (month == 12) chip.isSelected = true
                rowView.addView(chip)
                monthChips.add(chip)
            }
            if (row > 0) rowView.layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            container.addView(rowView)
        }
    }

    private fun LinearLayoutRow(): android.widget.LinearLayout =
        android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

    private fun setupResults() {
        binding.rvSchedule.layoutManager = LinearLayoutManager(this)
        binding.rvSchedule.adapter = rowAdapter
    }

    // ===== 测算 =====

    private fun currentLoanType(): Int = listOf(
        getString(R.string.mortgage_type_commercial),
        getString(R.string.mortgage_type_fund),
        getString(R.string.mortgage_type_combo)
    ).indexOf(binding.ddLoanType.text.toString()).coerceAtLeast(0)

    private fun calculate() {
        val startYear = binding.etStartYear.text.toString().toIntOrNull()
        val startMonth = binding.ddStartMonth.text.toString().trimEnd('月').toIntOrNull()?.minus(1)
        if (startYear == null || startMonth == null || startMonth !in 0..11) {
            invalid(); return
        }

        val typeIdx = currentLoanType()

        val loans = mutableListOf<LoanInput>()
        if (typeIdx != 1) {
            collectLoan(binding.sectionCommercial, getString(R.string.mortgage_type_commercial))
                ?.let { loans += it } ?: run { invalid(); return }
        }
        if (typeIdx != 0) {
            collectLoan(binding.sectionFund, getString(R.string.mortgage_type_fund))
                ?.let { loans += it } ?: run { invalid(); return }
        }

        val prepay = if (binding.swPrepay.isChecked) {
            val amount = binding.etPrepayAmount.text.toString().toDoubleOrNull()
            if (amount == null || amount <= 0) { invalid(); return }
            val months = monthChips.filter { it.isSelected }
                .map { it.text.toString().trimEnd('月').toInt() }
            if (months.isEmpty()) { invalid(); return }
            val target = when (binding.ddPrepayTarget.text.toString()) {
                getString(R.string.mortgage_type_commercial) -> PrepayTarget.Index(0)
                getString(R.string.mortgage_type_fund) ->
                    PrepayTarget.Index(if (typeIdx == 2) 1 else 0)
                else -> PrepayTarget.Highest
            }
            val mode = if (binding.ddPrepayMode.text.toString() ==
                getString(R.string.mortgage_mode_reduce)
            ) PrepayMode.REDUCE else PrepayMode.SHORTEN
            PrepayConfig(true, amount, months, target, mode)
        } else PrepayConfig()

        val income = if (binding.swIncome.isChecked) {
            fun numOf(id: TextView): Double? = id.text.toString().toDoubleOrNull()
            val savings = numOf(binding.etSavings)
            val annualIncome = numOf(binding.etAnnualIncome)
            val annualLiving = numOf(binding.etAnnualLiving)
            val monthlyFund = numOf(binding.etMonthlyFund)
            if (savings == null || annualIncome == null || annualLiving == null ||
                monthlyFund == null
            ) { invalid(); return }
            IncomeConfig(true, savings, annualIncome, annualLiving, monthlyFund)
        } else IncomeConfig()

        result = MortgageEngine.buildSchedule(
            ScheduleConfig(startYear, startMonth, loans, prepay, income)
        )
        savePrefs(typeIdx)
        renderResults(result!!)
        binding.resultsContainer.visibility = View.VISIBLE
        currentPage = 0
        applyFilter()
        binding.scrollView.post {
            binding.scrollView.smoothScrollTo(0, binding.resultsContainer.top)
        }
    }

    private fun collectLoan(section: ViewMortgageLoanBinding, name: String): LoanInput? {
        val amount = section.etAmount.text.toString().toDoubleOrNull()
        val rate = section.etRate.text.toString().toDoubleOrNull()
        val months = section.etMonths.text.toString().toIntOrNull()
        if (amount == null || rate == null || months == null ||
            amount <= 0 || rate < 0 || months <= 0
        ) return null
        val method = if (section.ddMethod.text.toString() ==
            getString(R.string.mortgage_method_equal_principal)
        ) LoanMethod.EQUAL_PRINCIPAL else LoanMethod.EQUAL_PAYMENT
        return LoanInput(name, amount, rate, months, method)
    }

    private fun invalid() {
        Toast.makeText(this, R.string.mortgage_input_invalid, Toast.LENGTH_SHORT).show()
    }

    // ===== 表单缓存 =====

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun savePrefs(typeIdx: Int) {
        fun AutoCompleteTextView.v(): String = text.toString()
        val p = prefs().edit()
        p.putBoolean(K_HAS, true)
        p.putString(K_YEAR, binding.etStartYear.text.toString())
        p.putString(K_MONTH, binding.ddStartMonth.v())
        p.putInt(K_TYPE, typeIdx)

        fun saveSection(prefix: String, s: ViewMortgageLoanBinding) {
            p.putString("${prefix}amount", s.etAmount.text.toString())
            p.putString("${prefix}rate", s.etRate.text.toString())
            p.putString("${prefix}months", s.etMonths.text.toString())
            p.putString("${prefix}method", s.ddMethod.v())
        }
        saveSection("c", binding.sectionCommercial)
        saveSection("f", binding.sectionFund)

        p.putBoolean(K_PP_ON, binding.swPrepay.isChecked)
        p.putString(K_PP_AMOUNT, binding.etPrepayAmount.text.toString())
        p.putString(K_PP_TARGET, binding.ddPrepayTarget.v())
        p.putString(K_PP_MODE, binding.ddPrepayMode.v())
        p.putString(K_PP_MONTHS, monthChips.filter { it.isSelected }
            .joinToString(",") { it.text.toString().trimEnd('月') })

        p.putBoolean(K_IN_ON, binding.swIncome.isChecked)
        p.putString(K_SAVINGS, binding.etSavings.text.toString())
        p.putString(K_INCOME, binding.etAnnualIncome.text.toString())
        p.putString(K_LIVING, binding.etAnnualLiving.text.toString())
        p.putString(K_FUND, binding.etMonthlyFund.text.toString())
        p.apply()
    }

    private fun restorePrefs() {
        val p = prefs()
        if (!p.getBoolean(K_HAS, false)) return

        p.getString(K_YEAR, null)?.let { binding.etStartYear.setText(it) }
        p.getString(K_MONTH, null)?.let { binding.ddStartMonth.setText(it, false) }
        val typeIdx = p.getInt(K_TYPE, 0)
        binding.ddLoanType.setText(
            binding.ddLoanType.adapter.getItem(typeIdx).toString(), false
        )
        applyLoanType(typeIdx)

        fun restoreSection(prefix: String, s: ViewMortgageLoanBinding) {
            p.getString("${prefix}amount", null)?.let { s.etAmount.setText(it) }
            p.getString("${prefix}rate", null)?.let { s.etRate.setText(it) }
            p.getString("${prefix}months", null)?.let { s.etMonths.setText(it) }
            p.getString("${prefix}method", null)?.let { s.ddMethod.setText(it, false) }
        }
        restoreSection("c", binding.sectionCommercial)
        restoreSection("f", binding.sectionFund)

        binding.swPrepay.isChecked = p.getBoolean(K_PP_ON, false)
        p.getString(K_PP_AMOUNT, null)?.let { binding.etPrepayAmount.setText(it) }
        p.getString(K_PP_TARGET, null)?.let { binding.ddPrepayTarget.setText(it, false) }
        p.getString(K_PP_MODE, null)?.let { binding.ddPrepayMode.setText(it, false) }
        val savedMonths = p.getString(K_PP_MONTHS, null)
            ?.split(",")?.mapNotNull { it.toIntOrNull() } ?: emptyList()
        monthChips.forEach { chip ->
            val m = chip.text.toString().trimEnd('月').toInt()
            chip.isSelected = m in savedMonths
        }

        binding.swIncome.isChecked = p.getBoolean(K_IN_ON, false)
        p.getString(K_SAVINGS, null)?.let { binding.etSavings.setText(it) }
        p.getString(K_INCOME, null)?.let { binding.etAnnualIncome.setText(it) }
        p.getString(K_LIVING, null)?.let { binding.etAnnualLiving.setText(it) }
        p.getString(K_FUND, null)?.let { binding.etMonthlyFund.setText(it) }
    }

    // ===== 结果渲染 =====

    private fun renderResults(r: ScheduleResult) {
        binding.tvTotalPayment.text = money(r.rows.sumOf { it.cashOut })
        binding.tvTotalInterest.text = money(r.totalInterest)
        binding.tvTotalPrepay.text = money(r.totalPrepay)
        binding.tvDuration.text = "${r.rows.size}期"
        binding.tvFirstPayment.text = money(r.rows.first().cashOut)
        binding.tvEarliest.text = r.earliest?.label ?: getString(R.string.mortgage_no_payoff)

        val c = binding.loanSummaryContainer
        c.removeAllViews()
        r.loanSummary.forEachIndexed { i, s ->
            val block = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
            }
            val title = TextView(this).apply {
                text = s.name
                setTextColor(0xFF0F3B5D.toInt())
                textSize = 13.5f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            block.addView(title)
            val stats = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            stats.addView(miniStat("累计利息", money(s.totalInterest)))
            stats.addView(miniStat("提前还款", money(s.totalPrepay), start = 10))
            stats.addView(miniStat("结清时间", s.payoff, start = 10))
            block.addView(stats)
            c.addView(block)
            if (i < r.loanSummary.lastIndex) c.addView(divider())
        }
    }

    private fun miniStat(label: String, value: String, start: Int = 0): View {
        val ll = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            ).apply { if (start > 0) marginStart = dp(start) }
        }
        ll.addView(TextView(this).apply {
            text = label
            setTextColor(0xFF8A98A3.toInt())
            textSize = 11f
        })
        ll.addView(TextView(this).apply {
            text = value
            setTextColor(0xFF1C2B36.toInt())
            textSize = 13.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        })
        return ll
    }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(0xFFEEF2F5.toInt())
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.MATCH_PARENT, dp(1)
        ).apply { topMargin = dp(14); bottomMargin = dp(14) }
    }

    // ===== 筛选与分页 =====

    private fun applyFilter() {
        val rows = result?.rows ?: return
        filteredRows = when (filterMode) {
            FILTER_PREPAY -> rows.filter { it.prepayTotal > 0 }
            FILTER_DECEMBER -> rows.filter { it.m == 11 }
            else -> rows
        }
        if (currentPage > totalPages() - 1) currentPage = 0
        renderPage()
    }

    private fun totalPages(): Int =
        maxOf(1, (filteredRows.size + PAGE_SIZE - 1) / PAGE_SIZE)

    private fun renderPage() {
        val from = currentPage * PAGE_SIZE
        val to = minOf(from + PAGE_SIZE, filteredRows.size)
        pageItems = if (filteredRows.isEmpty()) emptyList()
        else filteredRows.subList(from, to)
        rowAdapter.notifyDataSetChanged()
        binding.tvPageInfo.text =
            "第${currentPage + 1}/${totalPages()}页 · 共${filteredRows.size}期"
        binding.btnPrevPage.isEnabled = currentPage > 0
        binding.btnNextPage.isEnabled = currentPage < totalPages() - 1
    }

    // ===== 明细列表 =====

    inner class RowAdapter : RecyclerView.Adapter<RowAdapter.VH>() {
        inner class VH(val itemBinding: ItemMortgageRowBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val itemBinding = ItemMortgageRowBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return VH(itemBinding)
        }

        override fun getItemCount(): Int = pageItems.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = pageItems[position]
            with(holder.itemBinding) {
                tvRowIndex.text = "${result!!.rows.indexOf(row) + 1}"
                tvRowDate.text = "${row.y}年${row.m + 1}月"
                tvRowPay.text = money(row.cashOut)
                tvRowPrepay.visibility = if (row.prepayTotal > 0) View.VISIBLE else View.GONE
                root.setOnClickListener {
                    showDetail(row)
                }
            }
        }
    }

    // ===== 期次详情 BottomSheet =====

    private fun showDetail(row: ScheduleRow) {
        val sheetBinding = SheetMortgageDetailBinding.inflate(layoutInflater)
        sheetBinding.tvSheetTitle.text = "第${result!!.rows.indexOf(row) + 1}期"
        sheetBinding.tvSheetSubtitle.text = "${row.y}年${row.m + 1}月"
        sheetBinding.tvSheetPayment.text = money(row.cashOut)
        sheetBinding.tvSheetPrincipal.text = money(row.regPrinTotal)
        sheetBinding.tvSheetInterest.text = money(row.interestTotal)
        sheetBinding.tvSheetBalance.text = money(row.endTotal)
        if (row.prepayTotal > 0) {
            sheetBinding.tvSheetPrepay.visibility = View.VISIBLE
            sheetBinding.tvSheetPrepay.text = "本期提前还款 ${money(row.prepayTotal)} 元"
        }

        val container = sheetBinding.sheetLoanContainer
        val names = result!!.loanSummary.map { it.name }
        row.perLoan.forEachIndexed { loanIndex, loan ->
            val block = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.mortgage_card_inner)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
            }
            block.addView(TextView(this).apply {
                text = names.getOrElse(loanIndex) { "贷款${loanIndex + 1}" }
                setTextColor(0xFF0F3B5D.toInt())
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
            val cellRows = listOf(
                listOf("月供" to money(loan.regPay), "本金" to money(loan.regPrin)),
                listOf("利息" to money(loan.interest), "余额" to money(loan.end))
            )
            val grid = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            cellRows.forEachIndexed { rowIdx, cellRow ->
                val hr = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    if (rowIdx > 0) layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(8) }
                }
                cellRow.forEachIndexed { i, (label, value) ->
                    val cell = android.widget.LinearLayout(this).apply {
                        orientation = android.widget.LinearLayout.VERTICAL
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        ).apply { if (i > 0) marginStart = dp(10) }
                    }
                    cell.addView(TextView(this).apply {
                        text = label
                        setTextColor(0xFF8A98A3.toInt())
                        textSize = 11f
                    })
                    cell.addView(TextView(this).apply {
                        text = value
                        setTextColor(0xFF1C2B36.toInt())
                        textSize = 13f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        layoutParams = android.widget.LinearLayout.LayoutParams(
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(3) }
                    })
                    hr.addView(cell)
                }
                grid.addView(hr)
            }
            block.addView(grid)
            container.addView(block)
        }

        com.google.android.material.bottomsheet.BottomSheetDialog(this).apply {
            setContentView(sheetBinding.root)
            show()
        }
    }

    // ===== 工具 =====

    private fun money(v: Long): String {
        val s = v.toString()
        val sb = StringBuilder()
        val neg = s.startsWith("-")
        val digits = if (neg) s.substring(1) else s
        for (i in digits.indices) {
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(",")
            sb.append(digits[i])
        }
        return (if (neg) "-" else "") + sb.toString()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_SIZE = 24
        private const val FILTER_ALL = 0
        private const val FILTER_PREPAY = 1
        private const val FILTER_DECEMBER = 2

        private const val PREFS = "mortgage_form"
        private const val K_HAS = "has"
        private const val K_YEAR = "startYear"
        private const val K_MONTH = "startMonth"
        private const val K_TYPE = "loanType"
        private const val K_PP_ON = "prepayOn"
        private const val K_PP_AMOUNT = "prepayAmount"
        private const val K_PP_TARGET = "prepayTarget"
        private const val K_PP_MODE = "prepayMode"
        private const val K_PP_MONTHS = "prepayMonths"
        private const val K_IN_ON = "incomeOn"
        private const val K_SAVINGS = "savings"
        private const val K_INCOME = "annualIncome"
        private const val K_LIVING = "annualLiving"
        private const val K_FUND = "monthlyFund"
    }
}
