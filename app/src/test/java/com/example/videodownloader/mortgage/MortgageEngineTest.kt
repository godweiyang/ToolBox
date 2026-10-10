package com.example.videodownloader.mortgage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MortgageEngineTest {

    private val commercialLoan = LoanInput(
        name = "商业贷款",
        balance = 1_000_000.0,
        annualRatePercent = 3.5,
        remainingMonths = 360,
        method = LoanMethod.EQUAL_PAYMENT
    )

    @Test
    fun equalPayment_basics() {
        val r = MortgageEngine.buildSchedule(ScheduleConfig(2026, 9, listOf(commercialLoan)))

        assertEquals(361, r.rows.size)
        // 首月：利息 2917，月供 4490，本金 1573
        val first = r.rows.first()
        assertEquals(2917, first.interestTotal)
        assertEquals(4490, first.regPayTotal)
        assertEquals(1573, first.regPrinTotal)
        // 末月结清
        assertEquals(0L, r.rows.last().endTotal)
        // 总利息（金额精确到元，与 JS 引擎一致）
        assertEquals(616_701L, r.totalInterest)
    }

    @Test
    fun equalPrincipal_basics() {
        val loan = commercialLoan.copy(method = LoanMethod.EQUAL_PRINCIPAL)
        val r = MortgageEngine.buildSchedule(ScheduleConfig(2026, 9, listOf(loan)))

        assertEquals(360, r.rows.size)
        val first = r.rows.first()
        // 固定本金 2778，首月利息 2917，月供 5695
        assertEquals(2778, first.regPrinTotal)
        assertEquals(2917, first.interestTotal)
        assertEquals(5695, first.regPayTotal)
        assertEquals(0L, r.rows.last().endTotal)
    }

    @Test
    fun prepayShorten_reducesMonths() {
        val cfg = ScheduleConfig(
            2026, 9, listOf(commercialLoan),
            prepay = PrepayConfig(
                enabled = true, amount = 100_000.0, months = listOf(12),
                target = PrepayTarget.Index(0), mode = PrepayMode.SHORTEN
            )
        )
        val r = MortgageEngine.buildSchedule(cfg)
        assertTrue("提前还款应缩短期限", r.rows.size < 360)
        // 每年 12 月都提前还款，与 JS 引擎一致
        assertEquals(87, r.rows.size)
        assertEquals(737_450L, r.totalPrepay)
    }

    @Test
    fun combo_twoLoans_totals() {
        val commercial = LoanInput("商业贷款", 1_000_000.0, 3.5, 120, LoanMethod.EQUAL_PAYMENT)
        val fund = LoanInput("公积金贷款", 400_000.0, 2.85, 120, LoanMethod.EQUAL_PAYMENT)
        val r = MortgageEngine.buildSchedule(ScheduleConfig(2026, 9, listOf(commercial, fund)))

        assertEquals(120, r.rows.size)
        val first = r.rows.first()
        // 商贷月供 9889 + 公积金月供 3835 = 13724
        assertEquals(13_724, first.regPayTotal)
        // 首月利息 2917 + 950 = 3867
        assertEquals(3_867, first.interestTotal)
        // 独立复算：商贷总还款 1186620 + 公积金 460173
        assertEquals(1_646_793L, r.rows.sumOf { it.cashOut })
        assertEquals(246_793L, r.totalInterest)
        // 两笔贷款都有独立汇总
        assertEquals(2, r.loanSummary.size)
        assertEquals("商业贷款", r.loanSummary[0].name)
        assertEquals("公积金贷款", r.loanSummary[1].name)
        assertEquals(186_620L, r.loanSummary[0].totalInterest)
        assertEquals(60_173L, r.loanSummary[1].totalInterest)
        assertEquals(0L, r.rows.last().endTotal)
    }

    @Test
    fun income_earliestPayoff() {
        val cfg = ScheduleConfig(
            2026, 9, listOf(commercialLoan),
            income = IncomeConfig(
                enabled = true, startingSavings = 1_000_000.0,
                annualIncome = 200_000.0, annualLiving = 50_000.0, monthlyFund = 0.0
            )
        )
        val r = MortgageEngine.buildSchedule(cfg)
        val earliest = r.earliest
        assertNotNull(earliest)
        assertEquals("2026年10月", earliest!!.label)
    }
}
