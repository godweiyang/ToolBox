package com.example.videodownloader.mortgage

import kotlin.math.round

/* =========================================================
 * 贷款摊还计算引擎（纯 Kotlin，移植自 fangdai/js/engine.js）
 * 支持：多笔贷款、等额本息/等额本金、
 *       提前还款（缩短期限月供不变 / 减少月供期限不变）、
 *       收入-存款视角的最快一次性结清时点。
 * 口径：所有金额一律精确到“元”（四舍五入取整）；
 *       月供统一由本金/利率/期数自动计算。
 * ========================================================= */

enum class LoanMethod { EQUAL_PAYMENT, EQUAL_PRINCIPAL }
enum class PrepayMode { SHORTEN, REDUCE }

/** 提前还款冲抵目标：[HIGHEST] 按利率从高到低自动跨贷款分配；[INDEX] 指定某一笔。 */
sealed class PrepayTarget {
    object Highest : PrepayTarget()
    data class Index(val index: Int) : PrepayTarget()
}

data class LoanInput(
    val name: String,
    val balance: Double,          // 本金（元）
    val annualRatePercent: Double, // 年利率百分数，如 3.5 表示 3.5%
    val remainingMonths: Int,
    val method: LoanMethod
)

data class PrepayConfig(
    val enabled: Boolean = false,
    val amount: Double = 0.0,
    val months: List<Int> = emptyList(), // 1-based 月份
    val target: PrepayTarget = PrepayTarget.Highest,
    val mode: PrepayMode = PrepayMode.SHORTEN
)

data class IncomeConfig(
    val enabled: Boolean = false,
    val startingSavings: Double = 0.0,
    val annualIncome: Double = 0.0,
    val annualLiving: Double = 0.0,
    val monthlyFund: Double = 0.0
)

data class ScheduleConfig(
    val startYear: Int,
    val startMonth: Int, // 0-based
    val loans: List<LoanInput>,
    val prepay: PrepayConfig = PrepayConfig(),
    val income: IncomeConfig = IncomeConfig()
)

data class LoanMonthRow(
    var begin: Long = 0,
    var interest: Long = 0,
    var regPay: Long = 0,
    var regPrin: Long = 0,
    var extra: Long = 0,
    var end: Long = 0
)

data class SettleInfo(
    val liquid: Long,
    val needed: Long,
    val savingsAfter: Long,
    val fundToPay: Long,
    val fundSurplus: Long,
    val payGap: Long
)

data class ScheduleRow(
    val y: Int,
    val m: Int,
    val label: String,
    val perLoan: List<LoanMonthRow>,
    val beginTotal: Long,
    val interestTotal: Long,
    val regPayTotal: Long,
    val regPrinTotal: Long,
    val prepayTotal: Long,
    val endTotal: Long,
    val cashOut: Long,
    var cumInterest: Long = 0,
    val settle: SettleInfo?
)

data class AnnualSummary(
    val year: Int,
    val interest: Long,
    val prepay: Long,
    val principal: Long,
    val regPay: Long,
    val endBalance: Long,
    val cashOut: Long
)

data class LoanSummary(
    val name: String,
    val payoff: String,
    val totalInterest: Long,
    val totalPrepay: Long
)

data class Earliest(
    val label: String,
    val y: Int,
    val m: Int,
    val liquid: Long,
    val needed: Long
)

data class ScheduleResult(
    val rows: List<ScheduleRow>,
    val annual: List<AnnualSummary>,
    val loanSummary: List<LoanSummary>,
    val totalInterest: Long,
    val totalPrepay: Long,
    val earliest: Earliest?
)

object MortgageEngine {

    // 金额精确到“元”：四舍五入取整
    private fun r0(x: Double): Long = round(x + 1e-9).toLong()

    /** 等额本息每期还款（r=月利率, n=期数, pv=本金） */
    fun pmt(r: Double, n: Int, pv: Double): Double {
        if (r == 0.0) return pv / n
        return (pv * r) / (1 - Math.pow(1 + r, -n.toDouble()))
    }

    private fun addMonths(y: Int, m: Int, k: Int): Pair<Int, Int> {
        val t = y * 12 + m + k
        val ny = Math.floorDiv(t, 12)
        val nm = Math.floorMod(t, 12)
        return ny to nm
    }

    private fun fmtYm(y: Int, m: Int): String = "${y}年${m + 1}月"

    private class LoanState(val L: LoanInput) {
        val name: String = L.name
        val method: LoanMethod = L.method
        val mr: Double = L.annualRatePercent / 100.0 / 12.0
        var bal: Long = r0(L.balance)
        var nRem: Int = L.remainingMonths // 合同剩余期数（reduce 模式用，逐月递减）
        var fixedPay: Long = 0
        var fixedPrin: Long = 0
        var payoff: Pair<Int, Int>? = null
        var totalInterest: Long = 0
        var totalPrepay: Long = 0

        init {
            when (method) {
                LoanMethod.EQUAL_PAYMENT -> fixedPay = r0(pmt(mr, L.remainingMonths, L.balance))
                LoanMethod.EQUAL_PRINCIPAL -> fixedPrin = r0(L.balance / L.remainingMonths)
            }
        }
    }

    fun buildSchedule(cfg: ScheduleConfig): ScheduleResult {
        val prepay = cfg.prepay
        val income = cfg.income

        val states = cfg.loans.map { LoanState(it) }

        // 提前还款的贷款冲抵顺序
        val order: List<Int> = when (val target = prepay.target) {
            is PrepayTarget.Highest -> states.indices.sortedByDescending { states[it].mr }
            is PrepayTarget.Index -> {
                val t = target.index.coerceIn(0, (states.size - 1).coerceAtLeast(0))
                listOf(t) + states.indices.filter { it != t }
            }
        }

        val rows = mutableListOf<ScheduleRow>()
        var y = cfg.startYear
        var m = cfg.startMonth
        var savings: Long = if (income.enabled) r0(income.startingSavings) else 0L
        var earliest: Triple<Int, Int, Pair<Long, Long>>? = null
        val maxMonths = 600
        var guard = 0

        while (states.any { it.bal > 0 } && guard < maxMonths) {
            guard++
            val isPrepayMonth = prepay.enabled && (m + 1) in prepay.months

            val perLoan = states.map { s ->
                var begin = s.bal
                var interest = 0L
                var regPay = 0L
                var regPrin = 0L
                var end = begin
                if (begin > 0) {
                    interest = r0(begin * s.mr)
                    when (s.method) {
                        LoanMethod.EQUAL_PAYMENT -> {
                            val pay: Double = if (prepay.mode == PrepayMode.REDUCE)
                                pmt(s.mr, maxOf(s.nRem, 1), begin.toDouble())
                            else s.fixedPay.toDouble()
                            regPay = minOf(r0(pay), r0(begin + interest.toDouble()))
                            regPrin = r0(regPay - interest.toDouble())
                        }
                        LoanMethod.EQUAL_PRINCIPAL -> {
                            val prin: Double = if (prepay.mode == PrepayMode.REDUCE)
                                begin.toDouble() / maxOf(s.nRem, 1)
                            else s.fixedPrin.toDouble()
                            regPrin = minOf(r0(prin), begin)
                            regPay = r0(regPrin + interest.toDouble())
                        }
                    }
                    end = r0(begin - regPrin.toDouble())
                }
                LoanMonthRow(begin, interest, regPay, regPrin, 0, end)
            }

            // 提前还款（在正常月供之后冲抵本金）
            var prepayTotal = 0L
            if (isPrepayMonth) {
                var pool = r0(prepay.amount)
                val cascade = prepay.target is PrepayTarget.Highest
                val lim = if (cascade) order.size else 1
                var oi = 0
                while (oi < lim && pool > 0) {
                    val li = order[oi]
                    val avail = perLoan[li].end
                    val take = r0(minOf(pool.toDouble(), avail.toDouble()))
                    if (take > 0) {
                        perLoan[li].extra = take
                        perLoan[li].end = r0(perLoan[li].end - take.toDouble())
                        pool = r0(pool - take.toDouble())
                        prepayTotal = r0(prepayTotal + take.toDouble())
                    }
                    oi++
                }
            }

            var beginTotal = 0L
            var interestTotal = 0L
            var regPayTotal = 0L
            var regPrinTotal = 0L
            var endTotal = 0L
            perLoan.forEach { p ->
                beginTotal = r0(beginTotal + p.begin.toDouble())
                interestTotal = r0(interestTotal + p.interest.toDouble())
                regPayTotal = r0(regPayTotal + p.regPay.toDouble())
                regPrinTotal = r0(regPrinTotal + p.regPrin.toDouble())
                endTotal = r0(endTotal + p.end.toDouble())
            }

            // 存款 / 最快结清
            var settleInfo: SettleInfo? = null
            if (income.enabled) {
                val monthIncome = income.annualIncome / 12
                val monthLiving = income.annualLiving / 12
                val monthlyFund = income.monthlyFund
                val netFund = monthlyFund - regPayTotal
                val fundToPay = r0(minOf(monthlyFund, regPayTotal.toDouble()))
                val fundSurplus = r0(maxOf(0.0, netFund))
                val payGap = r0(maxOf(0.0, -netFund))
                val liquid = r0(savings + monthIncome - monthLiving + netFund)
                val needed = r0(beginTotal + interestTotal.toDouble())
                if (liquid >= needed && earliest == null && beginTotal > 0) {
                    earliest = Triple(y, m, liquid to needed)
                }
                savings = r0(liquid - prepayTotal.toDouble())
                settleInfo = SettleInfo(liquid, needed, savings, fundToPay, fundSurplus, payGap)
            }

            val label = "$y-${(m + 1).toString().padStart(2, '0')}"
            val cashOut = r0(regPayTotal + prepayTotal.toDouble())
            rows.add(
                ScheduleRow(
                    y, m, label, perLoan, beginTotal, interestTotal, regPayTotal,
                    regPrinTotal, prepayTotal, endTotal, cashOut, 0, settleInfo
                )
            )

            // 更新贷款状态
            states.forEachIndexed { i, s ->
                val p = perLoan[i]
                s.bal = p.end
                s.totalInterest = r0(s.totalInterest + p.interest.toDouble())
                s.totalPrepay = r0(s.totalPrepay + p.extra.toDouble())
                if (s.nRem > 0) s.nRem -= 1
                if (p.end <= 0 && s.payoff == null && (p.regPay > 0 || p.extra > 0)) {
                    s.payoff = y to m
                }
            }

            val next = addMonths(y, m, 1)
            y = next.first
            m = next.second
        }

        // 累计利息
        var cum = 0L
        rows.forEach { r ->
            cum = r0(cum + r.interestTotal.toDouble())
            r.cumInterest = cum
        }

        // 年度汇总
        val annual = rows.groupBy { it.y }.map { (year, rs) ->
            AnnualSummary(
                year = year,
                interest = r0(rs.sumOf { it.interestTotal.toDouble() }),
                prepay = r0(rs.sumOf { it.prepayTotal.toDouble() }),
                principal = r0(rs.sumOf { (it.regPrinTotal + it.prepayTotal).toDouble() }),
                regPay = r0(rs.sumOf { it.regPayTotal.toDouble() }),
                endBalance = rs.last().endTotal,
                cashOut = r0(rs.sumOf { it.cashOut.toDouble() })
            )
        }.sortedBy { it.year }

        val loanSummary = states.map { s ->
            LoanSummary(
                name = s.name,
                payoff = s.payoff?.let { fmtYm(it.first, it.second) } ?: "—",
                totalInterest = s.totalInterest,
                totalPrepay = s.totalPrepay
            )
        }

        val totalInterest = r0(states.sumOf { it.totalInterest.toDouble() })
        val totalPrepay = r0(states.sumOf { it.totalPrepay.toDouble() })

        val earliestResult = earliest?.let {
            Earliest(fmtYm(it.first, it.second), it.first, it.second, it.third.first, it.third.second)
        }

        return ScheduleResult(rows, annual, loanSummary, totalInterest, totalPrepay, earliestResult)
    }
}
