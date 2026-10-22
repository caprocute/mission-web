#!/usr/bin/env node
/**
 * Công cụ biên dịch và kiểm tra token UI Pack (WEB-004, TL-01 "Pipeline token sang CSS variables
 * và preset PrimeNG"). Dùng đúng mã `token-compiler.ts` của `@vt/design-system` (Node chạy thẳng
 * TypeScript nhờ type stripping), nên công cụ, trình duyệt và kiểm thử Vitest cho cùng kết quả.
 *
 *   node tools/token-compiler/compile.mjs <pack.json>...          kiểm tra, in kết quả
 *   node tools/token-compiler/compile.mjs --ghi <pack.json>...    kiểm tra rồi ghi `compiledCss` vào gói
 *   node tools/token-compiler/compile.mjs --tat-ca                 kiểm mọi gói trong public/ui-packs
 *
 * Mã thoát 1 khi có gói không đạt (tương phản, chế độ cao tuổi, giới hạn token, quá 30 KB gzip)
 * hoặc khi `compiledCss` đã ghi trong gói lệch với token hiện tại.
 */
import { registerHooks } from 'node:module';
import { existsSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

// Thư viện viết theo kiểu Angular (import không đuôi); thêm `.ts` khi tệp tồn tại.
registerHooks({
  resolve(specifier, context, next) {
    let spec = specifier;
    if (spec.startsWith('.') && !/\.[cm]?[jt]s$/.test(spec) && context.parentURL) {
      if (existsSync(fileURLToPath(new URL(`${spec}.ts`, context.parentURL)))) spec = `${spec}.ts`;
    }
    const result = next(spec, context);
    // Thư viện không khai báo "type": "module"; báo trước định dạng để Node khỏi đoán và cảnh báo.
    return result.url.endsWith('.ts') ? { ...result, format: 'module-typescript' } : result;
  },
});

const HERE = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(HERE, '../..');
const PACKS = join(ROOT, 'projects/fe-shell/public/ui-packs');
const { compileUiPack, checkUiPackSize, formatRatio } = await import(
  join(ROOT, 'projects/design-system/src/lib/token-compiler.ts')
);

/** Mọi `pack.json` trong `public/ui-packs/<tenant>/<phiên bản>/`. */
export function allPacks(dir = PACKS) {
  const out = [];
  for (const tenant of readdirSync(dir, { withFileTypes: true })) {
    if (!tenant.isDirectory()) continue;
    for (const ver of readdirSync(join(dir, tenant.name), { withFileTypes: true })) {
      const p = join(dir, tenant.name, ver.name, 'pack.json');
      if (ver.isDirectory() && existsSync(p)) out.push(p);
    }
  }
  return out.sort();
}

/** Biên dịch một gói; trả kết quả, kích thước gzip và lỗi (gồm lỗi kích thước, lỗi lệch CSS). */
export function compileFile(file, { write = false } = {}) {
  const pack = JSON.parse(readFileSync(file, 'utf8'));
  const { css, contrast, issues } = compileUiPack(pack);
  if (!write && pack.compiledCss !== undefined && pack.compiledCss !== css) {
    issues.push({
      code: 'CSS',
      path: 'compiledCss',
      message: 'compiledCss lệch token; chạy lại với --ghi.',
    });
  }
  const output = { ...pack, compiledCss: css };
  const gzipBytes = gzipSync(Buffer.from(JSON.stringify(output))).length;
  const size = checkUiPackSize(gzipBytes);
  if (size) issues.push(size);
  if (write && issues.length === 0) writeFileSync(file, `${JSON.stringify(output, null, 2)}\n`);
  return { file, css, contrast, issues, gzipBytes };
}

function main(argv) {
  const write = argv.includes('--ghi');
  const files = argv.includes('--tat-ca') ? allPacks() : argv.filter((a) => !a.startsWith('--'));
  if (files.length === 0) {
    console.error('Cần đường dẫn pack.json hoặc --tat-ca.');
    return 2;
  }
  let failed = 0;
  for (const f of files) {
    const r = compileFile(resolve(f), { write });
    const name = relative(process.cwd(), r.file);
    const worst = Math.min(...r.contrast.filter((c) => c.mode === 'default').map((c) => c.ratio));
    const elder = Math.min(...r.contrast.filter((c) => c.mode === 'elderly').map((c) => c.ratio));
    if (r.issues.length) {
      failed++;
      console.error(`KHÔNG ĐẠT ${name}`);
      for (const i of r.issues) console.error(`  [${i.code}] ${i.path}: ${i.message}`);
    } else {
      console.log(
        `ĐẠT ${name}: ${r.contrast.length} cặp màu, thấp nhất ${formatRatio(worst)} : 1` +
          (Number.isFinite(elder) ? `, cao tuổi ${formatRatio(elder)} : 1` : '') +
          `, ${(r.gzipBytes / 1024).toFixed(1)} KB gzip${write ? ', đã ghi compiledCss' : ''}`,
      );
    }
  }
  return failed ? 1 : 0;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  process.exitCode = main(process.argv.slice(2));
}
