#!/usr/bin/env node
/**
 * WEB-052 pha 2: cổng ngân sách kích thước của TL-01 "Ngân sách hiệu năng" (AGENTS.md quy tắc 10), đo trên
 * bản build production đã ghép (`npm run perf:prepare` → `dist/lhci`):
 * - trang chủ `/` (shell + design system + thư viện dùng chung tải đầu) ≤ 250 KB gzip;
 * - mỗi remote, phần tải thêm khi mở tuyến đầu của nó sau trang chủ ≤ 180 KB gzip.
 *
 * Khác Lighthouse (`npm run perf:lhci`, đo LCP trên 4G mô phỏng), cổng này không phụ thuộc mạng giả lập nên
 * cho cùng một số ở mọi máy: mở trang bằng Chromium của Playwright, gom mọi tệp JS, CSS, phông tải về, tính
 * cỡ gzip (mức 6) của từng tệp. Không tính bộ dữ liệu giả lập (`*.mock-*.js`) vì bản phát hành gọi máy chủ
 * thật, không tính JSON cấu hình và dữ liệu. Một tệp chỉ tính một lần cho trang đầu tiên tải nó.
 *
 * Dùng: node tools/perf/ngan-sach.mjs [--json <tệp>]   (mã thoát 1 khi vượt ngân sách)
 * Biến: PERF_DIR (mặc định dist/lhci), PERF_SHELL_KB (250), PERF_REMOTE_KB (180).
 */
import { createServer } from 'node:http';
import { existsSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';
import { chromium } from '@playwright/test';

const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const DIR = join(ROOT, process.env.PERF_DIR ?? 'dist/lhci');
const SHELL_KB = Number(process.env.PERF_SHELL_KB ?? 250);
const REMOTE_KB = Number(process.env.PERF_REMOTE_KB ?? 180);
const REMOTES = JSON.parse(readFileSync(join(ROOT, 'tools/remotes/remotes.json'), 'utf8')).remotes;

/** Vai trò giả lập đủ để vào tuyến đầu của mọi remote (sessionStorage `vt.devRoles`, chỉ khi tắt đăng nhập). */
const ROLES = 'member,manager,leader,policy_author,tenant_admin,inspector';

const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.woff2': 'font/woff2',
  '.woff': 'font/woff',
  '.ttf': 'font/ttf',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

if (!existsSync(join(DIR, 'index.html'))) {
  console.error(`Chưa có ${DIR}: chạy \`npm run perf:prepare\` trước.`);
  process.exit(2);
}

/** Máy chủ tĩnh như nginx của deploy/web: tệp có thật trả nguyên, tuyến SPA trả index.html. */
function serve() {
  const server = createServer((req, res) => {
    const path = normalize(decodeURIComponent(new URL(req.url, 'http://x').pathname)).replace(
      /^(\.\.[/\\])+/,
      '',
    );
    let file = join(DIR, path);
    if (!file.startsWith(DIR) || !existsSync(file) || statSync(file).isDirectory()) {
      if (extname(path) && !path.endsWith('.html')) {
        res.writeHead(404).end();
        return;
      }
      file = join(DIR, 'index.html');
    }
    res.writeHead(200, { 'Content-Type': TYPES[extname(file)] ?? 'application/octet-stream' });
    res.end(readFileSync(file));
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

/** Theo đuôi tệp, vì es-module-shims tải mô-đun bằng `fetch` chứ không phải thẻ script. */
const counted = (url, type) => {
  const path = new URL(url).pathname;
  if (/\.mock-[A-Za-z0-9_-]+\.js$/.test(path)) return false;
  return type === 'document' || /\.(m?js|css|woff2?|ttf)$/.test(path);
};

async function main() {
  const server = await serve();
  const base = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch();
  const ctx = await browser.newContext();
  await ctx.addInitScript((r) => sessionStorage.setItem('vt.devRoles', r), ROLES);
  // Giả lập không chờ: chỉ đo kích thước, không đo thời gian
  await ctx.route('**/config.json', async (route) => {
    const res = await route.fetch();
    const cfg = await res.json();
    await route.fulfill({
      response: res,
      json: { ...cfg, useMockApi: true, auth: { ...cfg.auth, enabled: false } },
    });
  });
  const seen = new Set();
  const pages = [];

  async function measure(label, path, budgetKb) {
    const page = await ctx.newPage();
    const files = [];
    page.on('response', async (res) => {
      const url = res.url();
      const type = res.request().resourceType();
      if (!url.startsWith(base) || seen.has(url) || !counted(url, type) || res.status() !== 200)
        return;
      seen.add(url);
      const entry = { url: url.slice(base.length), bytes: 0 };
      files.push(entry);
      try {
        entry.bytes = gzipSync(await res.body(), { level: 6 }).length;
      } catch {
        // phản hồi bị huỷ khi trang chuyển tuyến: bỏ qua
      }
    });
    await page.goto(base + path, { waitUntil: 'networkidle' });
    await page.locator('main#main router-outlet + *').first().waitFor({ timeout: 20_000 });
    await page.waitForLoadState('networkidle');
    await page.close();
    const kb = files.reduce((s, f) => s + f.bytes, 0) / 1024;
    const list = [...files].sort((a, b) => b.bytes - a.bytes);
    pages.push({ label, path, kb: Math.round(kb * 10) / 10, budgetKb, files: files.length, list });
  }

  await measure('shell (trang chủ)', '/', SHELL_KB);
  for (const r of REMOTES) await measure(r.project, `/${r.route}`, REMOTE_KB);
  await browser.close();
  server.close();

  let fail = 0;
  for (const p of pages) {
    const ok = p.kb <= p.budgetKb;
    if (!ok) fail++;
    console.log(
      `${ok ? 'ĐẠT ' : 'VƯỢT'}  ${p.label.padEnd(20)} ${String(p.kb).padStart(6)} KB / ${p.budgetKb} KB  (${p.files} tệp)`,
    );
    if (!ok)
      for (const t of p.list.slice(0, 5))
        console.log(`        ${(t.bytes / 1024).toFixed(1).padStart(6)} KB ${t.url}`);
  }
  const out = process.argv.indexOf('--json');
  if (out > 0) writeFileSync(process.argv[out + 1], JSON.stringify(pages, null, 2));
  process.exit(fail ? 1 : 0);
}

main().catch((e) => {
  console.error(e);
  process.exit(2);
});
