// Bridge fallback tests: verifies openPage('licai') falls back to external URL
// when native WebToolRegistry returns false (licai not a registered embedded tool).
// Run: node app/src/test/js/bridge.test.mjs

import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'
import vm from 'vm'

const __dirname = dirname(fileURLToPath(import.meta.url))
const bridgePath = join(__dirname, '..', '..', 'main', 'assets', 'fangdai', 'js', 'native-bridge.js')
const bridgeSrc = readFileSync(bridgePath, 'utf-8')

let passed = 0, failed = 0
function assert(cond, msg) {
  if (cond) { console.log('  ✓', msg); passed++ }
  else { console.log('  ✗ FAIL:', msg); failed++ }
}

// Build a sandbox with a mock NativeBridge and window object
function makeSandbox(mockNative) {
  const location = { href: '' }
  const sandbox = {
    window: {},
    navigator: { userAgent: 'Mozilla/5.0 (Linux; Android 12) Chrome/120' },
    location,
    document: { createElement: () => ({ style:{} , click(){}, removeChild(){} }), body: { appendChild(){} } },
    console,
    Promise,
    setTimeout,
  }
  sandbox.window = sandbox
  sandbox.global = sandbox
  if (mockNative) sandbox.NativeBridge = mockNative
  vm.createContext(sandbox)
  vm.runInContext(bridgeSrc, sandbox, { filename: 'native-bridge.js' })
  return { bridge: sandbox.NativeBridge, location }
}

console.log('=== Bridge openPage 回退测试 ===')

// Test 1: native returns true → navigated via native, no external fallback
{
  const mockNative = {
    saveFile(){}, shareImage(){}, copyText(){},
    openPage(key) { return true }, // handles the page
  }
  const { bridge, location } = makeSandbox(mockNative)
  const r = await bridge.openPage('fangdai')
  assert(r.via === 'native', 'native handled → via=native')
  assert(r.navigated === true, 'native handled → navigated=true')
  assert(location.href === '', 'native handled → location.href untouched')
}

// Test 2: native returns false for 'licai' → falls back to external URL
{
  let calledKey = null
  const mockNative = {
    saveFile(){}, shareImage(){}, copyText(){},
    openPage(key) { calledKey = key; return false }, // licai not registered
  }
  const { bridge, location } = makeSandbox(mockNative)
  const r = await bridge.openPage('licai')
  assert(calledKey === 'licai', 'native.openPage called with licai')
  assert(r.navigated === true, 'external fallback → navigated=true')
  assert(r.via === 'external', 'external fallback → via=external')
  assert(r.url === 'https://godweiyang.com/licai/', 'external URL correct: ' + r.url)
  assert(location.href === 'https://godweiyang.com/licai/', 'location.href set to external URL')
}

// Test 3: native returns false for unknown key with no URL mapping → navigated:false
{
  const mockNative = {
    saveFile(){}, shareImage(){}, copyText(){},
    openPage(key) { return false },
  }
  const { bridge, location } = makeSandbox(mockNative)
  const r = await bridge.openPage('nonexistent_page')
  assert(r.navigated === false, 'unknown key → navigated=false')
  assert(location.href === '', 'unknown key → location.href untouched')
}

// Test 4: no native bridge (browser) → relative path fallback
{
  const { bridge, location } = makeSandbox(null)
  const r = await bridge.openPage('licai')
  assert(r.via === 'browser', 'no native → via=browser')
  assert(r.navigated === true, 'browser fallback → navigated=true')
  assert(location.href === '/licai/', 'browser relative path: ' + location.href)
}

console.log(`\n=== 结果: ${passed} passed, ${failed} failed ===`)
process.exit(failed > 0 ? 1 : 0)
