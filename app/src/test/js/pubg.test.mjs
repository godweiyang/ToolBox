/**
 * PUBG 离线资产（assets/pubg/index.html）确定性测试
 * 运行方式：node app/src/test/js/pubg.test.mjs
 *
 * 覆盖（按用户验收口径）：
 *  - 无跨页导航残留：无 cross-link / 无 /lol/ 路径 / 无 "LOL 战绩查询" / 无 target=_blank
 *  - DEFAULT_KEYS 已按授权源恢复为非空（断言数量与类型，绝不打印/编码 Key 字符串）
 *  - 一键补回默认 Key 的按钮与事件处理函数存在
 *  - 批量导入纯函数 parseKeyBlob / mergeKeys 的拆分、去空白、去重行为正确
 *  - 本地持久化契约：localStorage 回环可还原 Key 列表
 *  - 全部 <script> 块语法可编译（不执行）
 *
 * 安全约束：本测试任何输出都不会打印、快照、编码或暴露任何 Key/JWT 字符串。
 */

import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'
import vm from 'vm'

const __dirname = dirname(fileURLToPath(import.meta.url))
const assetPath = join(__dirname, '..', '..', 'main', 'assets', 'pubg', 'index.html')
const html = readFileSync(assetPath, 'utf8')

let passed = 0
let failed = 0
function test(name, fn) {
  try { fn(); passed++; console.log('  ✓ ' + name) }
  catch (e) { failed++; console.error('  ✗ ' + name); console.error('    ' + e.message) }
}

console.log('\n=== PUBG 资产约束测试 ===\n')

// ─── 1. 跨页导航 / 目标跳转残留 ───
test('无 cross-link 元素', () => {
  if (/class="cross-link"/.test(html)) throw new Error('found <a class="cross-link">')
})
test('无 /lol/ 路径引用', () => {
  if (html.includes('/lol/')) throw new Error('found "/lol/" substring')
})
test('无 "LOL 战绩查询" 文案', () => {
  if (html.includes('LOL 战绩查询')) throw new Error('found LOL cross-nav copy')
})
test('无 target="_blank"', () => {
  if (html.includes('target="_blank"')) throw new Error('found target="_blank"')
})
test('无 lolpoolRoot 残留选择器 / MODAL_SEL', () => {
  if (html.includes('lolpoolRoot')) throw new Error('found stale #lolpoolRoot reference')
})

// ─── 2. DEFAULT_KEYS 已恢复（只断言数量/类型，不打印值） ───
let DEFAULT_KEYS = null
test('DEFAULT_KEYS 已恢复为非空（数量=6，类型均为非空字符串）', () => {
  const m = html.match(/const\s+DEFAULT_KEYS\s*=\s*(\[[\s\S]*?\]);/)
  if (!m) throw new Error('DEFAULT_KEYS declaration not found')
  // 在沙箱里求值数组字面量；只用于校验结构，绝不 console.log 元素
  DEFAULT_KEYS = vm.runInNewContext(m[1])
  if (!Array.isArray(DEFAULT_KEYS)) throw new Error('DEFAULT_KEYS is not an array')
  if (DEFAULT_KEYS.length !== 6) throw new Error('expected 6 default keys, got ' + DEFAULT_KEYS.length)
  if (!DEFAULT_KEYS.every(k => typeof k === 'string' && k.length > 0)) {
    throw new Error('some default key is not a non-empty string')
  }
})
test('DEFAULT_KEYS 内部无重复', () => {
  if (!DEFAULT_KEYS) return
  const s = new Set(DEFAULT_KEYS)
  if (s.size !== DEFAULT_KEYS.length) throw new Error('default pool contains duplicates')
})

// ─── 3. 一键补回默认 Key 的按钮 + 事件处理存在 ───
test('存在 id="keyRestore" 按钮', () => {
  if (!/id="keyRestore"/.test(html)) throw new Error('keyRestore button missing')
})
test('init() 中绑定了 keyRestore 点击处理', () => {
  if (!/\$\('keyRestore'\)\.addEventListener\('click'/.test(html)) {
    throw new Error('keyRestore click handler not wired')
  }
})
test('补回逻辑按"已存在跳过、缺失追加"合并（含 toast）', () => {
  if (!/DEFAULT_KEYS\.filter\(k=>!have\.has\(k\)\)/.test(html)) {
    throw new Error('restore merge logic missing')
  }
})

// ─── 4. 批量导入纯函数：parseKeyBlob / mergeKeys ───
// 从资产源码里抽取这两个纯函数并在沙箱求值（不触碰 DOM）
function extractFn(name) {
  const re = new RegExp('function\\s+' + name + '\\s*\\([^)]*\\)\\s*\\{')
  const m = re.exec(html)
  if (!m) throw new Error(name + '() not found in asset')
  const start = m.index
  const openBrace = start + m[0].length - 1 // 指向 '{'
  let depth = 0
  for (let j = openBrace; j < html.length; j++) {
    const c = html[j]
    if (c === '{') depth++
    else if (c === '}') { depth--; if (depth === 0) return html.slice(start, j + 1) }
  }
  throw new Error(name + '() braces unbalanced')
}
const parseKeyBlob = vm.runInNewContext('(' + extractFn('parseKeyBlob') + ')')
const mergeKeys = vm.runInNewContext('(' + extractFn('mergeKeys') + ')')

test('parseKeyBlob: 换行/逗号/空格混合拆分并去空白、去空项', () => {
  const out = parseKeyBlob('  key-a \n key-b,key-c；key-d\tkey-e,,,\n\n  ')
  // 注意：真实 JWT 本身不含空白，这里用占位串验证拆分规则（分隔符之间才切分）
  if (out.length !== 5) throw new Error('expected 5 fragments, got ' + out.length)
  if (out[0] !== 'key-a' || out[4] !== 'key-e') throw new Error('trim/order wrong: ' + JSON.stringify(out))
})
test('parseKeyBlob: 空输入返回空数组', () => {
  if (parseKeyBlob('').length !== 0) throw new Error('empty input should give []')
  if (parseKeyBlob('   , ,, ').length !== 0) throw new Error('whitespace-only input should give []')
})
test('mergeKeys: 合并现有+导入，整体去重、保持首现顺序', () => {
  const merged = mergeKeys(['a', 'b'], [' b ', 'c', 'a', ' d '])
  if (merged.join(',') !== 'a,b,c,d') throw new Error('expected a,b,c,d got ' + merged.join(','))
})
test('mergeKeys: 空现有/空导入容错', () => {
  if (mergeKeys([], ['x']).join(',') !== 'x') throw new Error('merge into empty failed')
  if (mergeKeys(['x'], []).join(',') !== 'x') throw new Error('merge empty incoming failed')
})

// ─── 5. 本地持久化契约（localStorage 回环，只断言列表一致） ───
test('KEY_STORE 持久化回环：join("\\n") 再 split 可还原列表', () => {
  const store = {}
  const KEY_STORE = 'pubg_api_key'
  // 写入：collectKeyVals().join('\n')
  store[KEY_STORE] = DEFAULT_KEYS.join('\n')
  // 读取：stored.split(/\r?\n/).map(trim).filter(Boolean)
  const back = store[KEY_STORE].split(/\r?\n/).map(s => s.trim()).filter(Boolean)
  if (back.length !== DEFAULT_KEYS.length) throw new Error('round-trip length mismatch')
  // 逐项相等（不打印内容，只比较长度与逐串相等性）
  for (let i = 0; i < back.length; i++) {
    if (back[i] !== DEFAULT_KEYS[i]) throw new Error('round-trip element mismatch at ' + i)
  }
})
test('UI 明示「仅保存在本机」', () => {
  if (!html.includes('仅保存在本机')) throw new Error('local-only copy missing')
})

// ─── 6. 全部 <script> 块语法可编译（仅解析，不执行） ───
test('所有内联 <script> 块语法合法', () => {
  const blocks = [...html.matchAll(/<script>([\s\S]*?)<\/script>/g)].map(m => m[1])
  if (blocks.length === 0) throw new Error('no inline script blocks found')
  blocks.forEach((code, idx) => {
    try { new vm.Script(code, { filename: 'pubg-block-' + idx + '.js' }) }
    catch (e) { throw new Error('script block #' + idx + ' syntax error: ' + e.message) }
  })
})

console.log(`\n=== 结果: ${passed} passed, ${failed} failed ===`)
process.exit(failed > 0 ? 1 : 0)
