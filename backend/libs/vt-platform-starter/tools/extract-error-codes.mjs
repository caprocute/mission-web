#!/usr/bin/env node
// NEN-009 · TL-01 "Định dạng lỗi chuẩn RFC 9457" ("Danh mục mã lỗi được quản lý trong kho hợp đồng
// cùng OpenAPI") · contracts/openapi/README.md mục "Lỗi" · BS-02 HD-01.
//
// Quét contracts/openapi/**/*.yaml, lấy mọi mã lỗi dạng <MODULE>-<HTTP>-<TÊN> cùng mã MSG đi kèm,
// ghi ra tài nguyên error-codes.json mà ErrorCatalog nạp. Không cần thư viện ngoài: hợp đồng ghi mã
// lỗi trong `description` (văn bản) và trong `examples` (khối `code:`/`msg:`), nên quét văn bản đủ.
//
// Cách dùng (từ gốc repo hoặc bất kỳ đâu):
//   node backend/libs/vt-platform-starter/tools/extract-error-codes.mjs          # ghi lại tệp
//   node backend/libs/vt-platform-starter/tools/extract-error-codes.mjs --check  # chỉ kiểm, lệch thì mã thoát 1
//
// Quy tắc gán (ghi vào báo cáo NEN-009):
//   - msg: MSG-xx-yy đứng ngay sau mã, trong ngoặc liền kề "`CODE` (… MSG-xx-yy …)" hoặc sau dấu phẩy
//     "`CODE`, MSG-xx-yy" / "`CODE`, `msg` MSG-xx-yy"; hoặc khoá `msg:` cùng khối ví dụ với `code: CODE`.
//   - type: lấy từ ví dụ cùng khối nếu có; nếu không, `<MODULE>-409-VERSION-CONFLICT` dùng
//     `concurrent-modification` (common.yaml, response Conflict), còn lại là phần TÊN viết thường
//     (khớp mọi ví dụ của hợp đồng và TL-01, ví dụ TASK-409-PERIOD-LOCKED → period-locked).
//   - title: lấy từ ví dụ của chính mã; nếu không có, lấy tiêu đề ví dụ cùng type trong common.yaml
//     (loại lỗi chung: concurrent-modification, period-locked, validation, forbidden); thiếu nữa thì
//     ErrorCatalog dùng tiêu đề chung theo HTTP status.

import { readFileSync, readdirSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '../../../..');
const contractsDir = join(repoRoot, 'contracts', 'openapi');
const outFile = join(here, '..', 'src', 'main', 'resources', 'vn', 'viettel', 'okrkpi', 'shared', 'error', 'error-codes.json');
const TYPE_BASE = 'https://okrkpi.vn/problems/';

const CODE_RE = /\b([A-Z][A-Z0-9]*)-([1-5]\d{2})-([A-Z0-9]+(?:-[A-Z0-9]+)*)\b/g;
const MSG_RE = /MSG-\d{2}-\d{2}/g;

function yamlFiles(dir) {
  const out = [];
  for (const entry of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...yamlFiles(p));
    else if (/\.ya?ml$/.test(entry.name)) out.push(p);
  }
  return out.sort();
}

const indentOf = (line) => line.length - line.trimStart().length;

/** Các khoá vô hướng cùng khối (cùng thụt lề, liền nhau) với dòng `code: CODE` trong ví dụ YAML. */
function exampleBlock(lines, i) {
  const indent = indentOf(lines[i]);
  const keys = {};
  const take = (line) => {
    const m = /^\s*([A-Za-z]+):\s*(.*?)\s*$/.exec(line);
    if (m) keys[m[1]] = m[2];
  };
  for (let j = i; j >= 0 && lines[j].trim() !== '' && indentOf(lines[j]) === indent; j--) take(lines[j]);
  for (let j = i + 1; j < lines.length && lines[j].trim() !== '' && indentOf(lines[j]) >= indent; j++) {
    if (indentOf(lines[j]) === indent) take(lines[j]);
  }
  return keys;
}

function extract() {
  const entries = new Map();
  const typeTitles = new Map();
  const get = (code, module, status, name) => {
    if (!entries.has(code)) {
      entries.set(code, { code, module, status: Number(status), name, types: new Set(), titles: new Set(), msgs: new Set(), sources: new Set() });
    }
    return entries.get(code);
  };

  for (const file of yamlFiles(contractsDir)) {
    const text = readFileSync(file, 'utf8');
    const source = relative(contractsDir, file).split(sep).join('/');

    for (const m of text.matchAll(CODE_RE)) {
      const e = get(m[0], m[1], m[2], m[3]);
      e.sources.add(source);
      const after = text.slice(m.index + m[0].length, m.index + m[0].length + 400);
      const paren = /^`?\s*\(([^)]*)\)/.exec(after);
      if (paren) for (const msg of paren[1].match(MSG_RE) ?? []) e.msgs.add(msg);
      const comma = /^`?\s*,\s*(?:`msg`\s*)?(MSG-\d{2}-\d{2})/.exec(after);
      if (comma) e.msgs.add(comma[1]);
    }

    const lines = text.split('\n');
    lines.forEach((line, i) => {
      const m = /^\s*code:\s*([A-Z][A-Z0-9]*-[1-5]\d{2}-[A-Z0-9-]+)\s*$/.exec(line);
      if (!m || !entries.has(m[1])) return;
      const e = entries.get(m[1]);
      const keys = exampleBlock(lines, i);
      if (keys.msg && /^MSG-\d{2}-\d{2}$/.test(keys.msg)) e.msgs.add(keys.msg);
      if (keys.type && keys.type.startsWith(TYPE_BASE)) {
        e.types.add(keys.type);
        if (keys.title) {
          e.titles.add(keys.title);
          // Chỉ ví dụ trong common.yaml là tiêu đề chung của loại lỗi; ví dụ ở tệp module mang nghĩa riêng.
          if (source.startsWith('common/')) typeTitles.set(keys.type, keys.title);
        }
      }
    });
  }

  const warnings = [];
  const codes = [...entries.values()]
    .sort((a, b) => a.code.localeCompare(b.code))
    .map((e) => {
      if (e.types.size > 1) warnings.push(`${e.code}: nhiều type trong ví dụ (${[...e.types].join(', ')})`);
      const type = [...e.types][0]
        ?? TYPE_BASE + (e.name === 'VERSION-CONFLICT' ? 'concurrent-modification' : e.name.toLowerCase());
      const title = [...e.titles][0] ?? typeTitles.get(type);
      const msgs = [...e.msgs].sort();
      const out = { code: e.code, module: e.module, status: e.status, name: e.name, type };
      if (title) out.title = title;
      if (msgs.length === 1) out.msg = msgs[0];
      if (msgs.length > 1) {
        out.msgCandidates = msgs;
        warnings.push(`${e.code}: nhiều MSG (${msgs.join(', ')}), không chọn mặc định`);
      }
      out.sources = [...e.sources].sort();
      return out;
    });

  return { codes, warnings };
}

const { codes, warnings } = extract();
const doc = {
  description: 'Danh mục mã lỗi <MODULE>-<HTTP>-<TÊN> trích từ contracts/openapi (NEN-009). Không sửa tay: chạy tools/extract-error-codes.mjs.',
  source: 'contracts/openapi/**/*.yaml',
  count: codes.length,
  codes,
};
const json = JSON.stringify(doc, null, 2) + '\n';
const withoutMsg = codes.filter((c) => !c.msg && !c.msgCandidates).length;

if (process.argv.includes('--check')) {
  const current = existsSync(outFile) ? readFileSync(outFile, 'utf8') : '';
  if (current !== json) {
    console.error(`error-codes.json đã lỗi thời so với hợp đồng; chạy lại: node ${relative(process.cwd(), fileURLToPath(import.meta.url))}`);
    process.exit(1);
  }
  console.log(`error-codes.json khớp hợp đồng (${codes.length} mã).`);
} else {
  writeFileSync(outFile, json);
  console.log(`Đã ghi ${relative(repoRoot, outFile)}: ${codes.length} mã, ${codes.length - withoutMsg} mã có MSG, ${withoutMsg} mã chưa ánh xạ MSG.`);
}
for (const w of warnings) console.warn(`Cảnh báo: ${w}`);
