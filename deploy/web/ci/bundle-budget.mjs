// WEB-002: kiểm ngân sách kích thước bundle theo TL-01 mục "Ngân sách hiệu năng" (PERF-21).
//
//   cd frontend && node ../deploy/web/ci/bundle-budget.mjs [--json <tệp>]
//
// Chạy sau `npm run build:all`. Đo kích thước gzip (mức 9, như bản .gz nén sẵn mà nginx của deploy/web
// phục vụ qua gzip_static, WEB-052) của:
//   1. "Shell + design system + runtime Angular" (≤ 400 KB, cổng chặn đã duyệt 24/10/2026; mục tiêu NFR
//      vẫn là 250 KB của TL-01, wiki/14 "Ngưỡng tải đầu của trang chủ"): index.html, CSS và script mà
//      index.html nạp, main.js, chunk khởi động mà main.js nạp động (bootstrap), rồi mọi
//      import tĩnh lan truyền từ đó; tên gói trần (@angular/core, primeng/button, @vt/*…) tra
//      theo importmap.json của shell. Mã nạp lười (tuyến, widget, hộp thoại, dữ liệu giả lập)
//      không tính, vì nó không nằm trong lần tải đầu.
//   2. Mỗi remote (≤ 180 KB): với mỗi module công bố trong remoteEntry.json, mã riêng của
//      remote mà module đó kéo theo bằng import tĩnh, cộng màn hình nạp lười nặng nhất của nó
//      (trường hợp xấu nhất của lần vào đầu). Thư viện dùng chung do shell cấp (singleton
//      Native Federation) không tính cho remote.
// Ngưỡng nằm ở BUDGETS bên dưới (remote khớp TL-01, shell theo ngưỡng đã duyệt); không nới thêm để pipeline
// xanh (AGENTS.md, YC-PT-07).
// Lighthouse CI (lighthouserc.json) là phép đo bổ sung trên trình duyệt mô phỏng 4G.
import { createRequire } from 'node:module';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { gzipSync } from 'node:zlib';

const KB = 1024;
const BUDGETS = { shell: 400 * KB, remote: 180 * KB };

const FRONTEND = process.cwd();
const DIST = join(FRONTEND, 'dist');
if (!existsSync(join(FRONTEND, 'package.json')) || !existsSync(join(DIST, 'fe-shell/browser'))) {
  console.error('[ngân sách] Chạy trong thư mục frontend/ sau npm run build:all');
  process.exit(2);
}

// es-module-lexer có sẵn trong package-lock (phụ thuộc của công cụ build), không cài thêm gói
const req = createRequire(join(FRONTEND, 'package.json'));
const { init, parse } = await import(pathToFileURL(req.resolve('es-module-lexer')).href);
await init;

const gz = new Map();
const gzSize = (file) => {
  if (!gz.has(file)) gz.set(file, gzipSync(readFileSync(file), { level: 9 }).length);
  return gz.get(file);
};

/** Import tĩnh (và động nếu withDynamic) của một module ES đã build. */
function importsOf(file, withDynamic) {
  const [imports] = parse(readFileSync(file, 'utf8'));
  return imports
    .filter((i) => i.n && (i.d === -1 || (withDynamic && i.d > -1)))
    .map((i) => i.n);
}

/**
 * Bao đóng import tĩnh từ các tệp gốc. resolveBare trả đường dẫn tệp cho tên gói trần,
 * hoặc null nếu không tính (gói dùng chung của shell khi đo remote).
 */
function closure(roots, baseDir, resolveBare, dynamicFrom = new Set()) {
  const seen = new Set();
  const stack = [...roots];
  while (stack.length) {
    const file = stack.pop();
    if (seen.has(file)) continue;
    seen.add(file);
    for (const spec of importsOf(file, dynamicFrom.has(file))) {
      let target;
      if (spec.startsWith('./') || spec.startsWith('../')) target = resolve(dirname(file), spec);
      else if (spec.startsWith('/')) target = join(baseDir, spec);
      else if (/^[a-z]+:/i.test(spec)) continue;
      else target = resolveBare(spec);
      if (!target) continue;
      if (!existsSync(target)) throw new Error(`Không tìm thấy ${spec} (từ ${relative(DIST, file)})`);
      stack.push(target);
    }
  }
  return seen;
}

const fmt = (b) => `${(b / KB).toFixed(1)} KB`;
const sum = (files) => [...files].reduce((a, f) => a + gzSize(f), 0);
const results = [];
let failed = false;

function report(name, files, budget, dir) {
  const total = sum(files);
  const ok = total <= budget;
  failed ||= !ok;
  results.push({ name, gzipBytes: total, budgetBytes: budget, ok, files: files.size });
  console.log(`${ok ? 'ĐẠT     ' : 'VƯỢT    '} ${name}: ${fmt(total)} / ${fmt(budget)} (${files.size} tệp)`);
  if (!ok || process.env.BUDGET_VERBOSE) {
    const top = [...files].sort((a, b) => gzSize(b) - gzSize(a)).slice(0, Number(process.env.BUDGET_TOP ?? 12));
    for (const f of top) console.log(`           ${fmt(gzSize(f)).padStart(9)}  ${relative(dir, f)}`);
  }
}

// 1. Shell
const shellDir = join(DIST, 'fe-shell/browser');
const importMap = JSON.parse(readFileSync(join(shellDir, 'importmap.json'), 'utf8')).imports;
const shellBare = (spec) => {
  if (!importMap[spec]) throw new Error(`importmap.json của shell không có ${spec}`);
  return join(shellDir, importMap[spec]);
};
const html = readFileSync(join(shellDir, 'index.html'), 'utf8');
const htmlRefs = [...html.matchAll(/<(?:script|link)\b[^>]*?(?:src|href)="([^"]+)"[^>]*>/g)]
  .map((m) => m[0].match(/\b(?:src|href)="([^"]+)"/)[1])
  .filter((u) => /\.(?:m?js|css)$/.test(u) && !/^[a-z]+:/i.test(u))
  .map((u) => join(shellDir, u.replace(/^\//, '')));
const main = htmlRefs.find((f) => /\/main-[^/]+\.js$/.test(f));
if (!main) throw new Error('index.html không nạp main-*.js');
const jsRoots = [...new Set(htmlRefs.filter((f) => f.endsWith('.js')))];
const shellFiles = closure(jsRoots, shellDir, shellBare, new Set([main]));
for (const f of htmlRefs.filter((f) => f.endsWith('.css'))) shellFiles.add(f);
shellFiles.add(join(shellDir, 'index.html'));
report('fe-shell (shell + design system + runtime Angular, tải đầu)', shellFiles, BUDGETS.shell, shellDir);

// 2. Remote
const registry = JSON.parse(readFileSync(join(FRONTEND, 'tools/remotes/remotes.json'), 'utf8'));
for (const { project } of registry.remotes) {
  const dir = join(DIST, project, 'browser');
  const entryPath = join(dir, 'remoteEntry.json');
  if (!existsSync(entryPath)) {
    console.error(`[ngân sách] Chưa build ${project}`);
    process.exit(2);
  }
  const entry = JSON.parse(readFileSync(entryPath, 'utf8'));
  for (const exp of entry.exposes ?? []) {
    // Tải đầu của remote = module công bố cùng màn hình (hoặc widget) nặng nhất mà nó nạp lười
    // trực tiếp (loadComponent, loadChildren), vì người dùng luôn vào ít nhất một màn hình.
    const root = join(dir, exp.outFileName);
    const base = closure([root], dir, () => null);
    let files = base;
    let heaviest = '';
    for (const spec of importsOf(root, true).filter((s) => s.startsWith('.'))) {
      const target = resolve(dirname(root), spec);
      if (base.has(target) || !existsSync(target)) continue;
      const withPage = new Set([...base, ...closure([target], dir, () => null)]);
      if (sum(withPage) > sum(files)) [files, heaviest] = [withPage, relative(dir, target)];
    }
    const label = heaviest ? `, kèm ${heaviest}` : '';
    report(`${project} ${exp.key} (mã riêng của remote${label})`, files, BUDGETS.remote, dir);
  }
}

const jsonIdx = process.argv.indexOf('--json');
if (jsonIdx > -1 && process.argv[jsonIdx + 1]) {
  writeFileSync(process.argv[jsonIdx + 1], JSON.stringify({ budgets: BUDGETS, results }, null, 2) + '\n');
}
if (failed) {
  console.error('[ngân sách] Có phần vượt ngân sách TL-01');
  process.exit(1);
}
console.log('[ngân sách] Mọi phần trong ngân sách (remote theo TL-01, shell theo ngưỡng đã duyệt 24/10/2026)');
