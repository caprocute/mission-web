#!/usr/bin/env node
// NEN-014 · ADR-21 · TL-01 "Uỷ quyền tập trung bằng ABAC" · contracts/openapi/README.md mục "x-mission".
//
// Quét contracts/openapi/modules/*.yaml, trích bảng operationId → {aal, fourEyes, roles, scope, qt, scr, owner,
// module, errorModule, method, path, file} từ mở rộng x-mission của từng thao tác OpenAPI, ghi ra tài nguyên
// operation-policies.json mà OperationPolicyCatalog nạp.
//
// Cách dùng (từ gốc repo hoặc bất kỳ đâu):
//   node backend/libs/vt-platform-starter/tools/extract-x-mission.mjs          # ghi lại tệp
//   node backend/libs/vt-platform-starter/tools/extract-x-mission.mjs --check  # chỉ kiểm, lệch thì mã thoát 1

import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '../../../..');
const modulesDir = join(repoRoot, 'contracts', 'openapi', 'modules');
const outFile = join(
  here,
  '..',
  'src',
  'main',
  'resources',
  'vn',
  'viettel',
  'okrkpi',
  'shared',
  'authz',
  'operation-policies.json',
);

const HTTP_METHODS = new Set(['get', 'post', 'put', 'patch', 'delete']);

const FILE_ERROR_MODULE = {
  'appraisals.yaml': 'APPRAISAL',
  'config.yaml': 'CONFIG',
  'dossiers.yaml': 'DOSSIER',
  'jobs.yaml': 'JOB',
  'okr.yaml': 'OKR',
  'org.yaml': 'ORG',
  'oversight.yaml': 'OVERSIGHT',
  'platform.yaml': 'PLATFORM',
  'policies.yaml': 'POLICY',
  'products.yaml': 'PRODUCT',
  'tasks.yaml': 'TASK',
};

function parseInlineList(raw) {
  const trimmed = raw.trim();
  if (!trimmed.startsWith('[') || !trimmed.endsWith(']')) {
    return trimmed ? [unquote(trimmed)] : [];
  }
  const inner = trimmed.slice(1, -1).trim();
  if (!inner) return [];
  return inner.split(',').map((item) => unquote(item.trim())).filter(Boolean);
}

function unquote(value) {
  const s = value.trim();
  if ((s.startsWith("'") && s.endsWith("'")) || (s.startsWith('"') && s.endsWith('"'))) {
    return s.slice(1, -1);
  }
  return s;
}

function resolveErrorModule(fileName, apiModule) {
  if (apiModule === 'plans') return 'PLAN';
  return FILE_ERROR_MODULE[fileName] ?? apiModule.toUpperCase();
}

function extractFile(filePath, fileName) {
  const lines = readFileSync(filePath, 'utf8').split('\n');
  const operations = [];
  let inPaths = false;
  let currentPath = null;
  let currentMethod = null;
  let currentOp = null;
  let inXMission = false;
  let listKey = null;

  const flushOp = () => {
    if (currentOp && currentOp.operationId) {
      operations.push(currentOp);
    }
    currentOp = null;
    inXMission = false;
    listKey = null;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (/^\s*#/.test(line) || line.trim() === '') continue;

    if (/^paths:\s*$/.test(line)) {
      inPaths = true;
      continue;
    }
    if (inPaths && /^[A-Za-z0-9_-]+:\s*/.test(line)) {
      flushOp();
      inPaths = false;
      continue;
    }
    if (!inPaths) continue;

    const pathMatch = /^  (\/[^\s:]*):\s*$/.exec(line);
    if (pathMatch) {
      flushOp();
      currentPath = pathMatch[1];
      currentMethod = null;
      continue;
    }

    const methodMatch = /^    ([a-z]+):\s*$/.exec(line);
    if (methodMatch && HTTP_METHODS.has(methodMatch[1])) {
      flushOp();
      currentMethod = methodMatch[1].toUpperCase();
      const apiModule = (currentPath ?? '').replace(/^\/+/, '').split('/')[0] ?? '';
      currentOp = {
        operationId: null,
        file: fileName,
        module: apiModule,
        errorModule: resolveErrorModule(fileName, apiModule),
        method: currentMethod,
        path: currentPath,
        owner: null,
        qt: [],
        scr: [],
        roles: [],
        scope: null,
        aal: 1,
        fourEyes: false,
        source: null,
      };
      continue;
    }

    if (!currentOp) continue;

    const opIdMatch = /^      operationId:\s*(\S+)\s*$/.exec(line);
    if (opIdMatch) {
      currentOp.operationId = unquote(opIdMatch[1]);
      continue;
    }

    if (/^      x-mission:\s*$/.test(line)) {
      inXMission = true;
      listKey = null;
      continue;
    }

    if (inXMission && /^      [a-zA-Z0-9_-]+:/.test(line)) {
      inXMission = false;
      listKey = null;
    }

    if (inXMission) {
      const itemMatch = /^          -\s*(.+?)\s*$/.exec(line);
      if (itemMatch && listKey) {
        currentOp[listKey].push(unquote(itemMatch[1]));
        continue;
      }
      const kvMatch = /^        ([a-zA-Z0-9_-]+):\s*(.*?)\s*$/.exec(line);
      if (kvMatch) {
        const [, key, rawVal] = kvMatch;
        listKey = null;
        if (key === 'qt' || key === 'scr' || key === 'roles') {
          if (rawVal === '') {
            currentOp[key] = [];
            listKey = key;
          } else {
            currentOp[key] = parseInlineList(rawVal);
          }
        } else if (key === 'owner' || key === 'scope' || key === 'source') {
          currentOp[key] = unquote(rawVal);
        } else if (key === 'aal') {
          currentOp.aal = Number(rawVal);
        } else if (key === 'fourEyes') {
          currentOp.fourEyes = rawVal === 'true';
        }
      }
    }
  }
  flushOp();
  return operations;
}

function extractAll() {
  const files = readdirSync(modulesDir)
    .filter((f) => /\.ya?ml$/.test(f))
    .sort();
  const all = [];
  for (const file of files) {
    all.push(...extractFile(join(modulesDir, file), file));
  }
  all.sort((a, b) => a.operationId.localeCompare(b.operationId));
  return all;
}

const operations = extractAll();
const aal2Count = operations.filter((op) => op.aal === 2).length;
const fourEyesCount = operations.filter((op) => op.fourEyes === true).length;

const doc = {
  description:
    'Bảng chính sách thao tác trích từ mở rộng x-mission trong contracts/openapi/modules/*.yaml (NEN-014). Không sửa tay: chạy tools/extract-x-mission.mjs.',
  source: 'contracts/openapi/modules/*.yaml',
  count: operations.length,
  aal2Count,
  fourEyesCount,
  operations,
};

const json = JSON.stringify(doc, null, 2) + '\n';

if (process.argv.includes('--check')) {
  const current = existsSync(outFile) ? readFileSync(outFile, 'utf8') : '';
  if (current !== json) {
    console.error(
      `operation-policies.json đã lỗi thời so với hợp đồng; chạy lại: node ${relative(process.cwd(), fileURLToPath(import.meta.url))}`,
    );
    process.exit(1);
  }
  console.log(
    `operation-policies.json khớp hợp đồng (${operations.length} thao tác, ${aal2Count} AAL2, ${fourEyesCount} fourEyes).`,
  );
} else {
  mkdirSync(dirname(outFile), { recursive: true });
  writeFileSync(outFile, json);
  console.log(
    `Đã ghi ${relative(repoRoot, outFile)}: ${operations.length} thao tác, ${aal2Count} thao tác AAL2, ${fourEyesCount} thao tác fourEyes.`,
  );
}
