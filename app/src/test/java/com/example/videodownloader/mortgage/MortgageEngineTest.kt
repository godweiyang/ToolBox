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
