// LOL web asset tests: assert the shipped LOL page (outer index.html + arena.html +
// the base64-embedded single-view document) carries no PUBG cross-navigation control,
// and that HTML/embedded-document syntax remains valid.
//
// Run: node app/src/test/js/lol-assets.test.mjs

import { readFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const LOL_DIR = join(__dirname, '..', '..', 'main', 'assets', 'lol')
const indexHtml = readFileSync(join(LOL_DIR, 'index.html'), 'utf-8')
const arenaHtml = readFileSync(join(LOL_DIR, 'arena.html'), 'utf-8')

let passed = 0, failed = 0
function assert(cond, msg) {
  if (cond) { console.log('  ✓', msg); passed++ }
  else { console.log('  ✗ FAIL:', msg); failed++ }
}

// ---- Decode the base64-embedded single-view document (loaded into singleFrame) ----
const m = indexHtml.match(/const SINGLE_DOC_B64='([A-Za-z0-9+/=]+)'/)
assert(!!m, 'outer index.html declares SINGLE_DOC_B64')
let singleDoc = ''
try {
  singleDoc = new TextDecoder('utf-8').decode(Buffer.from(m[1], 'base64'))
  assert(true, 'SINGLE_DOC_B64 decodes as valid base64 → UTF-8')
} catch (e) {
  assert(false, 'SINGLE_DOC_B64 decodes as valid base64 → UTF-8 (' + e.message + ')')
}

// ---- 1. No PUBG cross-navigation in outer page, arena, or embedded doc ----
const surfaces = [
  ['outer index.html', indexHtml],
  ['arena.html', arenaHtml],
  ['embedded single-view doc', singleDoc],
]
for (const [name, text] of surfaces) {
  assert(!text.includes('cross-link'), `${name}: no cross-link class/markup`)
  assert(!text.includes('/pubg/'), `${name}: no /pubg/ path`)
  assert(!text.includes('PUBG 战绩查询'), `${name}: no PUBG cross-navigation label`)
  assert(!text.includes('href="/pubg/"'), `${name}: no <a href="/pubg/"> cross-link anchor`)
}

// ---- 2. LOL query/service behavior must be untouched ----
assert(indexHtml.includes('lolzjcx://start'), 'outer index.html: lolzjcx://start launch intent intact')
assert(indexHtml.includes('svcPill'), 'outer index.html: service status pill intact')
assert(singleDoc.includes('LOL 战绩详情'), 'embedded doc: LOL single-view h1 intact')
assert(indexHtml.includes('LOL 战绩查询'), 'outer index.html: LOL multi-view h1 intact')

// ---- 3. HTML / embedded-document syntax validity ----
function checkHtmlStructure(name, text) {
  assert(/^\s*<!doctype html>/i.test(text), `${name}: starts with <!doctype html>`)
  const count = (re) => (text.match(re) || []).length
  assert(count(/<html[\s>]/gi) === 1, `${name}: exactly one <html> open tag (${count(/<html[\s>]/gi)})`)
  assert(count(/<\/html>/gi) === 1, `${name}: exactly one </html> close tag`)
  assert(count(/<head[\s>]/gi) === 1, `${name}: exactly one <head>`)
  assert(count(/<\/head>/gi) === 1, `${name}: exactly one </head>`)
  assert(count(/<body[\s>]/gi) === 1, `${name}: exactly one <body>`)
  assert(count(/<\/body>/gi) === 1, `${name}: exactly one </body>`)
  const styleOpen = count(/<style[\s>]/gi), styleClose = count(/<\/style>/gi)
  assert(styleOpen === styleClose, `${name}: <style> balance (${styleOpen} open / ${styleClose} close)`)
  // Count real inline <script> blocks (the pages use only bare <script> tags;
  // a JS comment may textually mention "<script src=...>" which must not be counted).
  const scriptOpen = count(/<script>(?=\s)/g), scriptClose = count(/<\/script>/gi)
  assert(scriptOpen === scriptClose, `${name}: <script> balance (${scriptOpen} open / ${scriptClose} close)`)
  // no obviously broken unclosed anchors from the removed control
  assert(!/<a\s[^>]*$/.test(text), `${name}: no dangling/unclosed <a> tag`)
}
console.log('--- outer index.html ---')
checkHtmlStructure('outer index.html', indexHtml)
console.log('--- arena.html ---')
checkHtmlStructure('arena.html', arenaHtml)
console.log('--- embedded single-view doc ---')
checkHtmlStructure('embedded single-view doc', singleDoc)

console.log(`\n=== LOL assets 结果: ${passed} passed, ${failed} failed ===`)
process.exit(failed > 0 ? 1 : 0)
