// Máy chủ tĩnh & reverse-proxy cấu hình động cho phân hệ Web Mission (WEB-057, YC-HT-03):
// - Phục vụ bản ghép `dist/e2e` (shell ở gốc, remote ở `remotes/<project>`) như nginx, trả index.html cho tuyến SPA.
// - Tự động nạp tệp `.env` ở gốc repo (nếu có) mà không ghi đè biến môi trường đã truyền trực tiếp.
// - Sinh động `/config.json` và `/federation.manifest.json` theo biến môi trường lúc chạy (không cần build lại).
// - Chuyển tiếp (reverse-proxy) các yêu cầu `/api/v1/*` và `/ui-pack/*` tới các dịch vụ backend tương ứng
//   (`TENANT_REGISTRY_URL`, `ORG_SERVICE_URL`, `UI_PACK_SERVICE_URL`, `API_PROXY_TARGET`) để chỉ cần mở 1 cổng Web duy nhất.
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
import { createServer, request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../dist/e2e/', import.meta.url));
const REPO_ROOT = fileURLToPath(new URL('../../', import.meta.url));

function loadDotEnv() {
  const envPath = join(REPO_ROOT, '.env');
  if (!existsSync(envPath)) return;
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
    if (process.env[key] === undefined) {
      process.env[key] = val;
    }
  }
}

loadDotEnv();

const PORT = Number(process.env.E2E_PORT ?? process.env.WEB_PORT ?? 4200);
const TYPES = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
};

function parseBool(val, fallback) {
  if (val === undefined || val === '') return fallback;
  return val === 'true' || val === '1';
}

function runtimeConfig() {
  const base = JSON.parse(readFileSync(join(ROOT, 'config.json'), 'utf8'));

  // Tương thích ngược hoàn toàn với Playwright E2E khi đặt E2E_MODE
  if (process.env.E2E_MODE === 'keycloak') {
    const kc = (process.env.E2E_KC_URL ?? 'http://localhost:8180').replace(/\/$/, '');
    return {
      ...base,
      auth: { ...base.auth, enabled: true, issuer: `${kc}/realms/mission` },
      useMockApi: true,
    };
  }
  if (process.env.E2E_MODE && process.env.E2E_MODE !== 'env') {
    return { ...base, auth: { ...base.auth, enabled: false }, useMockApi: true };
  }

  // Chế độ cấu hình động qua biến môi trường (.env hoặc process.env)
  const hasExplicitEnv =
    process.env.USE_MOCK_API !== undefined ||
    process.env.AUTH_ENABLED !== undefined ||
    process.env.API_BASE_URL !== undefined ||
    process.env.AUTH_ISSUER !== undefined ||
    process.env.CELL !== undefined ||
    process.env.TENANT !== undefined;

  if (!hasExplicitEnv) {
    return { ...base, auth: { ...base.auth, enabled: false }, useMockApi: true };
  }

  const kcUrl = (process.env.E2E_KC_URL ?? process.env.KEYCLOAK_URL ?? 'http://127.0.0.1:35080').replace(/\/$/, '');
  const realm = process.env.KEYCLOAK_REALM ?? 'mission';
  const issuer = process.env.AUTH_ISSUER ?? `${kcUrl}/realms/${realm}`;

  return {
    ...base,
    cell: process.env.CELL ?? base.cell ?? 'cell-tw',
    tenant: process.env.TENANT ?? base.tenant ?? 'default',
    apiBaseUrl: process.env.API_BASE_URL ?? base.apiBaseUrl ?? '/api/v1',
    uiPackBaseUrl: process.env.UI_PACK_BASE_URL ?? base.uiPackBaseUrl ?? '/ui-pack',
    auth: {
      ...base.auth,
      enabled: parseBool(process.env.AUTH_ENABLED, false),
      issuer,
      clientId: process.env.AUTH_CLIENT_ID ?? base.auth?.clientId ?? 'mission-web',
    },
    useMockApi: parseBool(process.env.USE_MOCK_API, true),
  };
}

function runtimeManifest() {
  const manifestPath = join(ROOT, 'federation.manifest.json');
  const base = existsSync(manifestPath)
    ? JSON.parse(readFileSync(manifestPath, 'utf8'))
    : {};
  const mapEnv = {
    'fe-task': process.env.FE_TASK_URL,
    'fe-okr': process.env.FE_OKR_URL,
    'fe-kpi': process.env.FE_KPI_URL,
    'fe-eval': process.env.FE_EVAL_URL,
    'fe-Scoring': process.env.FE_SCORING_URL,
    'fe-scoring': process.env.FE_SCORING_URL,
    'fe-report': process.env.FE_REPORT_URL,
    'fe-admin': process.env.FE_ADMIN_URL,
    'fe-workflow': process.env.FE_WORKFLOW_URL,
    'fe-signature': process.env.FE_SIGNATURE_URL,
    'fe-notification': process.env.FE_NOTIFICATION_URL,
    'fe-sync': process.env.FE_SYNC_URL,
  };
  const result = { ...base };
  for (const [remote, url] of Object.entries(mapEnv)) {
    if (url && result[remote] !== undefined) {
      result[remote] = url;
    }
  }
  return result;
}

function resolveProxyTarget(pathname) {
  const tenantRegistry = process.env.TENANT_REGISTRY_URL ?? 'http://127.0.0.1:8083';
  const orgService = process.env.ORG_SERVICE_URL ?? 'http://127.0.0.1:8084';
  const uiPackService = process.env.UI_PACK_SERVICE_URL ?? 'http://127.0.0.1:8090';
  const defaultApiTarget = process.env.API_PROXY_TARGET ?? 'http://127.0.0.1:8080';

  if (
    pathname.startsWith('/api/v1/tenants') ||
    pathname.startsWith('/api/v1/cells')
  ) {
    return tenantRegistry;
  }
  if (
    pathname.startsWith('/api/v1/units') ||
    pathname.startsWith('/api/v1/org') ||
    pathname.startsWith('/api/v1/positions') ||
    pathname.startsWith('/api/v1/delegations') ||
    pathname.startsWith('/api/v1/users')
  ) {
    return orgService;
  }
  if (pathname.startsWith('/ui-pack/') || pathname.startsWith('/api/v1/ui-packs')) {
    return uiPackService;
  }
  if (pathname.startsWith('/api/')) {
    return defaultApiTarget;
  }
  return null;
}

function proxyRequest(req, res, targetBaseUrl) {
  const targetUrl = new URL(req.url ?? '/', targetBaseUrl);
  const client = targetUrl.protocol === 'https:' ? httpsRequest : httpRequest;
  const proxyReq = client(
    targetUrl,
    {
      method: req.method,
      headers: {
        ...req.headers,
        host: targetUrl.host,
      },
    },
    (proxyRes) => {
      res.writeHead(proxyRes.statusCode ?? 502, proxyRes.headers);
      proxyRes.pipe(res);
    },
  );
  proxyReq.on('error', (err) => {
    res.writeHead(502, { 'content-type': 'application/problem+json; charset=utf-8' });
    res.end(
      JSON.stringify({
        type: 'https://mission.vt.vn/errors/bad-gateway',
        title: 'Dịch vụ máy chủ tạm thời chưa phản hồi',
        status: 502,
        detail: `Không thể kết nối tới ${targetBaseUrl}: ${err.message}`,
      }),
    );
  });
  req.pipe(proxyReq);
}

if (!existsSync(join(ROOT, 'index.html'))) {
  console.error('Chưa có dist/e2e: chạy `npm run e2e:prepare` trước.');
  process.exit(1);
}

createServer((req, res) => {
  const parsedUrl = new URL(req.url ?? '/', 'http://x');
  const path = decodeURIComponent(parsedUrl.pathname);

  if (path === '/config.json') {
    res.writeHead(200, { 'content-type': TYPES['.json'], 'cache-control': 'no-store' });
    res.end(JSON.stringify(runtimeConfig()));
    return;
  }
  if (path === '/federation.manifest.json') {
    res.writeHead(200, { 'content-type': TYPES['.json'], 'cache-control': 'no-store' });
    res.end(JSON.stringify(runtimeManifest()));
    return;
  }

  const proxyTarget = resolveProxyTarget(path);
  if (proxyTarget) {
    proxyRequest(req, res, proxyTarget);
    return;
  }

  let file = normalize(join(ROOT, path));
  if (!file.startsWith(ROOT)) {
    res.writeHead(403).end();
    return;
  }
  if (!existsSync(file) || statSync(file).isDirectory()) {
    if (extname(path)) {
      res.writeHead(404).end();
      return;
    }
    file = join(ROOT, 'index.html');
  }
  res.writeHead(200, { 'content-type': TYPES[extname(file)] ?? 'application/octet-stream' });
  createReadStream(file).pipe(res);
}).listen(PORT, () => {
  const cfg = runtimeConfig();
  console.log(
    `e2e: http://localhost:${PORT} (mode=${process.env.E2E_MODE ?? 'dynamic'}, useMockApi=${cfg.useMockApi}, auth=${cfg.auth?.enabled})`,
  );
});
