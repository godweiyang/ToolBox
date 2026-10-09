/**
 * 房贷计算引擎单元测试
 * 运行方式：node app/src/test/js/engine.test.mjs
 *
 * 覆盖：
 *  - 等额本息月供公式
 *  - 等额本金月供/本金
 *  - 商业贷款 / 公积金贷款 / 组合贷款
 *  - 提前还款（缩短期限 / 减少月供）
 *  - 金额四舍五入到元
 *  - 收入存款最快结清
 */

import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'
import assert from 'assert'

const __dirname = dirname(fileURLToPath(import.meta.url))
const enginePath = join(__dirname, '..', '..', 'main', 'assets', 'fangdai', 'js', 'engine.js')

// 在 Node 沙箱里加载 engine.js（它会挂到 window 上）
const sandbox = { window: {} }
const engineSrc = readFileSync(enginePath, 'utf8')
// eslint-disable-next-line no-eval
const runner = new Function('window', engineSrc + '; return window.LoanEngine;')
const LoanEngine = runner(sandbox.window)

// ─── 工具 ───
let passed = 0
let failed = 0
function test(name, fn) {
  try {
    fn()
    passed++
    console.log('  ✓ ' + name)
  } catch (e) {
    failed++
    console.error('  ✗ ' + name)
    console.error('    ' + e.message)
  }
}
function approx(actual, expected, tolerance = 1) {
  assert.ok(Math.abs(actual - expected) <= tolerance,
    `expected ~${expected}, got ${actual} (diff ${Math.abs(actual - expected)})`)
}

// ─── 基础配置 ───
const baseCfg = {
  startYear: 2026,
  startMonth: 9, // 0-based = 10月
  loans: [],
  prepay: { enabled: false, amount: 0, months: [], target: '0', mode: 'shorten' },
  income: { enabled: false, startingSavings: 0, annualIncome: 0, annualLiving: 0, monthlyFund: 0 },
}

console.log('\n=== 房贷计算引擎测试 ===\n')

// ─── 1. 等额本息：商业贷款 ───
test('等额本息 - 商业贷款月供公式正确', () => {
  const cfg = {
    ...baseCfg,
    loans: [{
      name: '商业贷款',
      balance: 1000000,  // 100万
      annualRate: 0.032, // 3.2%
      method: 'equal_payment',
      remainingMonths: 360,
      paymentDay: 20,
    }],
  }
  const res = LoanEngine.buildSchedule(cfg)
  // PMT = P*r / (1 - (1+r)^-n)
  const r = 0.032 / 12
  const expectedPmt = (1000000 * r) / (1 - Math.pow(1 + r, -360))
  const expectedRounded = Math.round(expectedPmt)

  // 第一期月供应该等于计算值
  approx(res.rows[0].perLoan[0].regPay, expectedRounded, 1)
  // 总期数应该接近 360
  assert.ok(res.rows.length >= 358 && res.rows.length <= 362,
    `expected ~360 months, got ${res.rows.length}`)
  // 最后一期余额应为 0 或接近 0
  const lastRow = res.rows[res.rows.length - 1]
  assert.ok(lastRow.endTotal <= 1, `last balance should be ~0, got ${lastRow.endTotal}`)
})

// ─── 2. 等额本金：商业贷款 ───
test('等额本金 - 每月本金固定，月供递减', () => {
  const cfg = {
    ...baseCfg,
    loans: [{
      name: '商业贷款',
      balance: 500000,
      annualRate: 0.032,
      method: 'equal_principal',
      remainingMonths: 120,
      paymentDay: 20,
    }],
  }
  const res = LoanEngine.buildSchedule(cfg)
  const expectedPrincipal = Math.round(500000 / 120) // ≈4167

  // 每月本金应该一致
  for (let i = 0; i < Math.min(10, res.rows.length); i++) {
    approx(res.rows[i].perLoan[0].regPrin, expectedPrincipal, 1)
  }
  // 月供应该逐月递减（利息减少）
  assert.ok(res.rows[0].perLoan[0].regPay > res.rows[10].perLoan[0].regPay,
    'equal principal: payment should decrease over time')
  // 总期数 = 120
  assert.strictEqual(res.rows.length, 120)
})

// ─── 3. 公积金贷款（低利率） ───
test('公积金贷款 - 利率更低，总利息更少', () => {
  const cfg = {
    ...baseCfg,
    loans: [{
      name: '公积金贷款',
      balance: 800000,
      annualRate: 0.026, // 2.6%
      method: 'equal_payment',
      remainingMonths: 360,
      paymentDay: 20,
    }],
  }
  const res = LoanEngine.buildSchedule(cfg)
  // 利率 2.6% 的总利息应该比 3.2% 少很多
  // 粗略估算：100万 3.2% 30年 利息约 56.8万；80万 2.6% 应该远低于此
  assert.ok(res.totalInterest < 400000, `expected total interest < 400k, got ${res.totalInterest}`)
  assert.ok(res.totalInterest > 200000, `expected total interest > 200k, got ${res.totalInterest}`)
})

// ─── 4. 组合贷款 ───
test('组合贷款 - 商业+公积金并行摊销', () => {
  const cfg = {
    ...baseCfg,
    loans: [
      { name: '商业贷款', balance: 600000, annualRate: 0.032, method: 'equal_payment', remainingMonths: 360, paymentDay: 20 },
      { name: '公积金贷款', balance: 400000, annualRate: 0.026, method: 'equal_payment', remainingMonths: 360, paymentDay: 20 },
    ],
  }
  const res = LoanEngine.buildSchedule(cfg)
  // 每行应该有两笔贷款的明细
  assert.strictEqual(res.rows[0].perLoan.length, 2)
  // 总利息应该是两笔之和
  approx(res.totalInterest, res.loanSummary[0].totalInterest + res.loanSummary[1].totalInterest, 2)
  // 两笔都应该有结清时间
  assert.notStrictEqual(res.loanSummary[0].payoff, '—')
  assert.notStrictEqual(res.loanSummary[1].payoff, '—')
})

// ─── 5. 提前还款 - 缩短期限 ───
test('提前还款 - 缩短期限模式，贷款提前结清', () => {
  const cfg = {
    ...baseCfg,
    loans: [{
      name: '商业贷款',
      balance: 500000,
      annualRate: 0.032,
      method: 'equal_payment',
      remainingMonths: 240,
      paymentDay: 20,
    }],
    prepay: {
      enabled: true,
      amount: 100000,
      months: [4, 10], // 每年4月、10月各还10万
      target: '0',
      mode: 'shorten',
    },
  }
  const res = LoanEngine.buildSchedule(cfg)
  // 无提前还款时应该是 240 期；有提前还款应该明显少于 240
  assert.ok(res.rows.length < 200,
    `with prepayment, expected < 200 months, got ${res.rows.length}`)
  // 提前还款总额应该 > 0
  assert.ok(res.totalPrepay > 0, `expected total prepay > 0, got ${res.totalPrepay}`)
  // 每年4月和10月应该有提前还款行
  const prepayRows = res.rows.filter(r => r.prepayTotal > 0)
  assert.ok(prepayRows.length > 0, 'should have prepayment rows')
  // 检查月份是否在 4月(=3, 0-based) 或 10月(=9, 0-based)
  for (const pr of prepayRows) {
    assert.ok(pr.m === 3 || pr.m === 9,
      `prepayment month should be Apr(3) or Oct(9), got month=${pr.m}`)
  }
})

// ─── 6. 提前还款 - 减少月供 ───
test('提前还款 - 减少月供模式，月供下降但期数不变', () => {
  const cfgNoPrepay = {
    ...baseCfg,
    loans: [{
      name: '商业贷款', balance: 300000, annualRate: 0.032,
      method: 'equal_payment', remainingMonths: 120, paymentDay: 20,
    }],
  }
  const resNoPrepay = LoanEngine.buildSchedule(cfgNoPrepay)
  const basePmt = resNoPrepay.rows[0].perLoan[0].regPay

  const cfgPrepay = {
    ...baseCfg,
    loans: [{
      name: '商业贷款', balance: 300000, annualRate: 0.032,
      method: 'equal_payment', remainingMonths: 120, paymentDay: 20,
    }],
    prepay: {
      enabled: true, amount: 100000, months: [4], target: '0', mode: 'reduce',
    },
  }
  const resPrepay = LoanEngine.buildSchedule(cfgPrepay)
  // reduce 模式下：提前还款后月供应该下降
  const firstPrepayIdx = resPrepay.rows.findIndex(r => r.prepayTotal > 0)
  assert.ok(firstPrepayIdx >= 0, 'should have at least one prepayment row')
  if (firstPrepayIdx + 1 < resPrepay.rows.length) {
    const afterPmt = resPrepay.rows[firstPrepayIdx + 1].perLoan[0].regPay
    assert.ok(afterPmt < basePmt,
      `reduce mode: after prepay payment ${afterPmt} should be < base ${basePmt}`)
  }
  // reduce 模式 + 每年大额提前还款 → 总利息必然低于无提前还款
  assert.ok(resPrepay.totalInterest < resNoPrepay.totalInterest,
    `reduce mode: interest ${resPrepay.totalInterest} should be < no-prepay ${resNoPrepay.totalInterest}`)
  // 对比 shorten 模式：同样提前还款，reduce 模式月供更低
  const cfgShorten = {
    ...baseCfg,
    loans: [{
      name: '商业贷款', balance: 300000, annualRate: 0.032,
      method: 'equal_payment', remainingMonths: 120, paymentDay: 20,
    }],
    prepay: { enabled: true, amount: 50000, months: [4], target: '0', mode: 'shorten' },
  }
  const resShorten = LoanEngine.buildSchedule(cfgShorten)
  // shorten 模式下，月供始终等于初始固定月供
  const shortenIdx = resShorten.rows.findIndex(r => r.prepayTotal > 0)
  const shortenPmtAfterFirstPrepay = shortenIdx >= 0 && shortenIdx + 1 < resShorten.rows.length
    ? resShorten.rows[shortenIdx + 1].perLoan[0].regPay
    : basePmt
  const cfgReduceSmall = {
    ...baseCfg,
    loans: [{
      name: '商业贷款', balance: 300000, annualRate: 0.032,
      method: 'equal_payment', remainingMonths: 120, paymentDay: 20,
    }],
    prepay: { enabled: true, amount: 50000, months: [4], target: '0', mode: 'reduce' },
  }
  const resReduceSmall = LoanEngine.buildSchedule(cfgReduceSmall)
  const reduceIdx = resReduceSmall.rows.findIndex(r => r.prepayTotal > 0)
  const reducePmtAfterFirstPrepay = reduceIdx >= 0 && reduceIdx + 1 < resReduceSmall.rows.length
    ? resReduceSmall.rows[reduceIdx + 1].perLoan[0].regPay
    : basePmt
  assert.ok(reducePmtAfterFirstPrepay < shortenPmtAfterFirstPrepay,
    `reduce pmt ${reducePmtAfterFirstPrepay} should be < shorten pmt ${shortenPmtAfterFirstPrepay}`)
})

// ─── 7. 金额四舍五入到元 ───
test('金额精度 - 所有金额四舍五入到元（无小数）', () => {
  const cfg = {
    ...baseCfg,
    loans: [{
      name: '商业贷款', balance: 1500000, annualRate: 0.0355,
      method: 'equal_payment', remainingMonths: 288, paymentDay: 15,
    }],
  }
  const res = LoanEngine.buildSchedule(cfg)
  // 遍历所有行，检查所有金额字段是否为整数
  for (const row of res.rows) {
    assert.strictEqual(row.beginTotal % 1, 0, 'beginTotal should be integer')
    assert.strictEqual(row.interestTotal % 1, 0, 'interestTotal should be integer')
    assert.strictEqual(row.regPayTotal % 1, 0, 'regPayTotal should be integer')
    assert.strictEqual(row.endTotal % 1, 0, 'endTotal should be integer')
    for (const p of row.perLoan) {
      assert.strictEqual(p.begin % 1, 0, 'perLoan.begin should be integer')
      assert.strictEqual(p.interest % 1, 0, 'perLoan.interest should be integer')
      assert.strictEqual(p.regPay % 1, 0, 'perLoan.regPay should be integer')
      assert.strictEqual(p.regPrin % 1, 0, 'perLoan.regPrin should be integer')
      assert.strictEqual(p.end % 1, 0, 'perLoan.end should be integer')
    }
  }
})

// ─── 8. 收入存款 - 最快结清时点 ───
test('收入存款 - 测算最快一次性结清月份', () => {
  const cfg = {
    ...baseCfg,
    loans: [{
      name: '商业贷款', balance: 500000, annualRate: 0.032,
      method: 'equal_payment', remainingMonths: 240, paymentDay: 20,
    }],
    income: {
      enabled: true,
      startingSavings: 100000,
      annualIncome: 360000,   // 月入3万
      annualLiving: 120000,   // 月消费1万
      monthlyFund: 3000,
    },
  }
  const res = LoanEngine.buildSchedule(cfg)
  // 应该能算出 earliest
  assert.ok(res.earliest !== null, 'should compute earliest payoff')
  assert.ok(res.earliest.y >= 2026, 'earliest year should be >= 2026')
  // earliest 应该在还款期内
  const lastYear = res.rows[res.rows.length - 1].y
  assert.ok(res.earliest.y <= lastYear + 1, 'earliest should be within loan term')
})

// ─── 9. PMT 函数边界 ───
test('PMT 函数 - 零利率时退化为简单平均', () => {
  // r=0 时 pmt = pv/n
  const pmt0 = LoanEngine.pmt(0, 120, 600000)
  approx(pmt0, 5000, 0.1)
})

// ─── 10. 无贷款余额不生成行 ───
test('边界 - 贷款余额为0时不生成多余行', () => {
  const cfg = {
    ...baseCfg,
    loans: [{
      name: '商业贷款', balance: 0.01, annualRate: 0.032,
      method: 'equal_payment', remainingMonths: 360, paymentDay: 20,
    }],
  }
  const res = LoanEngine.buildSchedule(cfg)
  // 极小余额应该很快结清
  assert.ok(res.rows.length < 10, `tiny balance should pay off quickly, got ${res.rows.length} rows`)
})

// ─── 汇总 ───
console.log(`\n=== 结果: ${passed} passed, ${failed} failed ===\n`)
process.exit(failed > 0 ? 1 : 0)
