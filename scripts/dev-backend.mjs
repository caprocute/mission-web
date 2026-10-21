#!/usr/bin/env node
// Máy chủ Backend HTTP hợp đồng cho môi trường DEV (phục vụ /api/v1/* trên các cổng 8080, 8084, 8090
// khi dịch vụ Java/Go tương ứng chưa khởi chạy hoặc đang biên dịch).
// Giải mã trực tiếp JWT Bearer từ Keycloak (preferred_username, name, realm_access.roles, unit_id, tenant)
// để trả đúng ngữ cảnh cán bộ đăng nhập khi trình duyệt chạy với useMockApi=false.

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO_ROOT = fileURLToPath(new URL('../', import.meta.url));
const ROUTER_BUNDLE = '/tmp/mission-backend-router.mjs';

function readDotEnvMap() {
  const envPath = join(REPO_ROOT, '.env');
  const map = {};
  if (!existsSync(envPath)) return map;
  const content = readFileSync(envPath, 'utf8');
  for (const rawLine of content.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const eqIdx = line.indexOf('=');
    if (eqIdx <= 0) continue;
    const key = line.slice(0, eqIdx).trim();
    let val = line.slice(eqIdx + 1).trim();
    if (
      (val.startsWith('"') && val.endsWith('"')) ||
      (val.startsWith("'") && val.endsWith("'"))
    ) {
      val = val.slice(1, -1);
    }
    map[key] = val;
  }
  return map;
}

function getEnv(key, fallback) {
  if (process.env[key] !== undefined && process.env[key] !== '') {
    return process.env[key];
  }
  const dotEnv = readDotEnvMap();
  if (dotEnv[key] !== undefined && dotEnv[key] !== '') {
    return dotEnv[key];
  }
  return fallback;
}

let cachedHandleMockRequest = null;

export async function getBackendRouter() {
  if (cachedHandleMockRequest) return cachedHandleMockRequest;
  const esbuildBin = join(REPO_ROOT, 'frontend/node_modules/.bin/esbuild');
  const entry = join(REPO_ROOT, 'frontend/projects/api-client/src/mock/mock-registry.ts');
  if (!existsSync(ROUTER_BUNDLE) && existsSync(esbuildBin)) {
    execFileSync(esbuildBin, [
      entry,
      '--bundle',
      '--format=esm',
      '--platform=node',
      `--outfile=${ROUTER_BUNDLE}`,
    ]);
  }
  const mod = await import(`${ROUTER_BUNDLE}?v=${Date.now()}`);
  cachedHandleMockRequest = mod.handleMockRequest;
  return cachedHandleMockRequest;
}

function createHeaderAccessor(rawHeaders) {
  const normalized = {};
  for (const [k, v] of Object.entries(rawHeaders ?? {})) {
    if (v !== undefined) {
      normalized[k.toLowerCase()] = Array.isArray(v) ? v.join(', ') : String(v);
    }
  }
  return {
    get(name) {
      return normalized[String(name).toLowerCase()] ?? null;
    },
  };
}

export async function handleServerApiRequest(req, res, rawBodyBuffer) {
  const parsedUrl = new URL(req.url ?? '/', 'http://localhost');
  const pathname = decodeURIComponent(parsedUrl.pathname);

  if (
    pathname === '/actuator/health' ||
    pathname === '/actuator/health/liveness' ||
    pathname === '/actuator/health/readiness' ||
    pathname === '/healthz'
  ) {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ status: 'UP' }));
    return;
  }

  let parsedBody = null;
  if (rawBodyBuffer && rawBodyBuffer.length > 0) {
    const text = rawBodyBuffer.toString('utf8').trim();
    if (text) {
      try {
        parsedBody = JSON.parse(text);
      } catch {
        parsedBody = text;
      }
    }
  }

  const handleMockRequest = await getBackendRouter();
  const result = handleMockRequest({
    method: (req.method ?? 'GET').toUpperCase(),
    path: pathname,
    params: parsedUrl.searchParams,
    headers: createHeaderAccessor(req.headers),
    body: parsedBody,
  });

  const outHeaders = {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    ...(result.headers ?? {}),
  };

  res.writeHead(result.status ?? 200, outHeaders);
  if (result.body === undefined || result.body === null || result.status === 204 || result.status === 304) {
    res.end();
  } else {
    res.end(JSON.stringify(result.body));
  }
}

function startPortServer(name, port) {
  const server = createServer((req, res) => {
    const chunks = [];
    req.on('data', (chunk) => chunks.push(chunk));
    req.on('end', () => {
      const bodyBuffer = Buffer.concat(chunks);
      handleServerApiRequest(req, res, bodyBuffer).catch((err) => {
        res.writeHead(500, { 'content-type': 'application/problem+json; charset=utf-8' });
        res.end(
          JSON.stringify({
            type: 'about:blank',
            title: 'Lỗi xử lý máy chủ',
            status: 500,
            detail: err.message,
          }),
        );
      });
    });
  });

  server.on('error', (err) => {
    if (err.code === 'EADDRINUSE') {
      console.log(`[dev-backend] Cổng ${port} (${name}) đã có dịch vụ gốc đang lắng nghe — giữ nguyên.`);
    } else {
      console.error(`[dev-backend] Lỗi trên cổng ${port} (${name}):`, err.message);
    }
  });

  server.listen(port, '127.0.0.1', () => {
    console.log(`[dev-backend] ${name} đang lắng nghe tại http://127.0.0.1:${port}`);
  });
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const esbuildBin = join(REPO_ROOT, 'frontend/node_modules/.bin/esbuild');
  const entry = join(REPO_ROOT, 'frontend/projects/api-client/src/mock/mock-registry.ts');
  execFileSync(esbuildBin, [
    entry,
    '--bundle',
    '--format=esm',
    '--platform=node',
    `--outfile=${ROUTER_BUNDLE}`,
  ]);
  await getBackendRouter();

  const corePort = Number(getEnv('MISSION_CORE_PORT', '8080'));
  const orgPort = Number(getEnv('ORG_SERVICE_PORT', '8084'));
  const uiPackPort = Number(getEnv('UI_PACK_SERVICE_PORT', '8090'));

  startPortServer('SVC-05 mission-core', corePort);
  startPortServer('SVC-04 org-service', orgPort);
  startPortServer('SVC-10 ui-pack-service', uiPackPort);
}
