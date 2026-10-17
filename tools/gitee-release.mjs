#!/usr/bin/env node
// Gitee Release 发布工具（纯 Node >=18 实现，无需第三方依赖）
//
// 能力：
//   1. 空仓库自动初始化一个 README，保证 Release 有可挂载的 commit
//   2. 按 tag 创建或复用 Release
//   3. 幂等上传附件（同名附件已存在则跳过）
//   4. 打印每个附件的匿名下载地址（releases/download 直链，无需登录）
//
// 认证：从环境变量 GITEE_TOKEN 读取私人令牌（projects 权限）。
//       令牌只在请求时使用，不会写入任何文件或仓库。
//
// 用法：
//   node tools/gitee-release.mjs \
//     --owner godweiyang --repo storage \
//     --tag yanzhi-models-v1 --name "颜值分析模型 v1" \
//     --body "三路 ONNX 模型" \
//     --file "C:\path\to\corrected_huber_fp32.onnx" \
//     --file "C:\path\to\nima_qdq.onnx"
//
// 常用开关：
//   --prerelease           标记为预发布
//   --init-readme <path>   空仓库时初始化的文件内容（默认内置 README）
//   --dry-run              只打印计划，不创建/上传

import { readFileSync, existsSync, statSync } from 'node:fs';
import { basename } from 'node:path';
import { fileURLToPath } from 'node:url';

const API = 'https://gitee.com/api/v5';

function parseArgs(argv) {
  const args = { file: [], _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      if (next === undefined || next.startsWith('--')) {
        args[key] = true;
      } else if (key === 'file') {
        args.file.push(next);
        i++;
      } else {
        args[key] = next;
        i++;
      }
    } else {
      args._.push(a);
    }
  }
  return args;
}

function fail(msg) {
  console.error(`[gitee-release] 错误：${msg}`);
  process.exit(1);
}

function log(msg) {
  console.log(`[gitee-release] ${msg}`);
}

async function api(method, path, { token, json, form } = {}) {
  let url = path.startsWith('http') ? path : `${API}${path}`;
  const init = { method, headers: {} };
  if (form) {
    init.body = form; // FormData（含 access_token），自动设置 multipart 边界
  } else if (json) {
    init.headers['Content-Type'] = 'application/json;charset=utf-8';
    init.body = JSON.stringify({ access_token: token, ...json });
  } else if (token) {
    // GET/HEAD 等无 body 请求，令牌放 query string
    const sep = url.includes('?') ? '&' : '?';
    url = `${url}${sep}access_token=${encodeURIComponent(token)}`;
  }
  let res;
  try {
    res = await fetch(url, init);
  } catch (e) {
    throw new Error(`请求失败 ${method} ${url}: ${e.message}`);
  }
  const text = await res.text();
  let data = null;
  if (text) {
    try { data = JSON.parse(text); } catch { data = { raw: text }; }
  }
  if (!res.ok) {
    const detail = data && (data.message || data.raw) ? (data.message || data.raw) : text;
    const err = new Error(`HTTP ${res.status} ${method} ${url}: ${detail}`);
    err.status = res.status;
    err.data = data;
    throw err;
  }
  return data;
}

function sha256Hex(buf) {
  // Node 20+ 才有全局 crypto.subtle 同步封装；这里用 node:crypto 更稳
  return createHash('sha256').update(buf).digest('hex');
}
import { createHash } from 'node:crypto';

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const token = process.env.GITEE_TOKEN || process.env.GITEE_ACCESS_TOKEN;
  if (!token) fail('未检测到环境变量 GITEE_TOKEN，请先设置私人令牌（projects 权限）。');
  const owner = args.owner;
  const repo = args.repo;
  const tag = args.tag;
  if (!owner || !repo || !tag) fail('必须提供 --owner、--repo、--tag。');
  const releaseName = args.name || tag;
  const body = args.body || '';
  const dryRun = !!args['dry-run'];

  const files = (args.file || []).map((p) => {
    if (!existsSync(p)) fail(`文件不存在：${p}`);
    return { path: p, name: basename(p), size: statSync(p).size };
  });

  log(`目标仓库：${owner}/${repo}，tag：${tag}，附件数：${files.length}`);
  files.forEach((f) => log(`  - ${f.name} (${(f.size / 1048576).toFixed(2)} MB)`));
  if (dryRun) { log('dry-run 模式，结束。'); return; }

  // 1. 确认仓库可访问；空仓库则初始化 README
  let repoInfo;
  try {
    repoInfo = await api('GET', `/repos/${owner}/${repo}`, { token });
    log(`仓库可访问，默认分支：${repoInfo.default_branch || '(空)'}`);
  } catch (e) {
    fail(`无法访问仓库 ${owner}/${repo}：${e.message}`);
  }

  if (!repoInfo.default_branch) {
    log('仓库为空，正在初始化 README 以产生首个 commit…');
    const readme = args['init-readme'] && existsSync(args['init-readme'])
      ? readFileSync(args['init-readme'], 'utf8')
      : `# ${repo}\n\n该仓库用于托管 Gitee Release 附件（模型 / 安装包等静态资源）。\n`;
    await api('POST', `/repos/${owner}/${repo}/contents/README.md`, {
      token,
      json: {
        content: Buffer.from(readme, 'utf8').toString('base64'),
        message: 'chore: initialize repository',
      },
    });
    log('README 已创建。');
  }

  // 2. 创建或复用 Release
  let release;
  try {
    const list = await api('GET', `/repos/${owner}/${repo}/releases?per_page=100`, { token });
    release = (list || []).find((r) => r.tag_name === tag);
  } catch (e) {
    log(`列出现有 Release 失败（继续尝试创建）：${e.message}`);
  }

  if (release) {
    log(`Release ${tag} 已存在（id=${release.id}），将复用。`);
  } else {
    log(`创建 Release ${tag}…`);
    try {
      release = await api('POST', `/repos/${owner}/${repo}/releases`, {
        token,
        json: {
          tag_name: tag,
          name: releaseName,
          body,
          target_commitish: repoInfo.default_branch || 'master',
          prerelease: !!args.prerelease,
        },
      });
    } catch (e) {
      fail(`创建 Release 失败：${e.message}`);
    }
    log(`Release 已创建（id=${release.id}）。`);
  }

  // 3. 幂等上传附件
  const existing = new Set((release.assets || []).map((a) => a.name));
  const results = [];
  for (const f of files) {
    const downloadUrl = `https://gitee.com/${owner}/${repo}/releases/download/${tag}/${f.name}`;
    if (existing.has(f.name)) {
      log(`附件已存在，跳过：${f.name}`);
      results.push({ name: f.name, url: downloadUrl, skipped: true });
      continue;
    }
    log(`上传附件：${f.name} …`);
    const buf = readFileSync(f.path);
    const form = new FormData();
    form.append('access_token', token);
    form.append('file', new Blob([buf]), f.name);
    let attached;
    try {
      attached = await api('POST', `/repos/${owner}/${repo}/releases/${release.id}/attach_files`, { form });
    } catch (e) {
      fail(`上传附件 ${f.name} 失败：${e.message}`);
    }
    const hash = createHash('sha256').update(buf).digest('hex');
    log(`  上传完成 sha256=${hash.slice(0, 16)}…`);
    results.push({ name: f.name, url: downloadUrl, sha256: hash, size: f.size, resp: attached });
  }

  log('全部完成。匿名下载地址（免登录，会 302 到实际存储）：');
  for (const r of results) {
    console.log(`  ${r.name} -> ${r.url}`);
  }
}

main().catch((e) => fail(e.stack || e.message));
