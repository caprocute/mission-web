// Máy chủ tĩnh & reverse-proxy cấu hình động cho phân hệ Web Mission (WEB-057, YC-HT-03):
// - Phục vụ bản ghép `dist/e2e` (shell ở gốc, remote ở `remotes/<project>`) như nginx, trả index.html cho tuyến SPA.
// - Tự động nạp tệp `.env` ở gốc repo theo thời gian thực (mỗi lần trình duyệt tải `/config.json`).
// - Mặc định bật đăng nhập Keycloak SSO (`AUTH_ENABLED=true`) và truy vấn trực tiếp Backend (`USE_MOCK_API=false`).
// - Tương thích ngược với bộ kiểm thử Playwright khi truyền `E2E_MODE=gia-lap` hoặc `E2E_MODE=keycloak`;
//   `E2E_MODE=that` (WEB-070, luồng chính với máy chủ thật QA-03) dựng cấu hình động như mặc định nhưng
//   luôn bật đăng nhập và tắt giả lập toàn bộ (chỉ còn giả lập các module trong `MOCK_API_MODULES`).
// - `E2E_MODE=he-thong` (QA-03, hành trình trên hệ thống thật): bật đăng nhập với Keycloak `E2E_KC_URL`,
//   tắt giả lập hoàn toàn và chuyển mọi `/api/*` tới APISIX dev ở `E2E_APISIX_URL`, không rơi về giả
//   lập khi máy chủ trả 404 hay 5xx để lỗi thật hiện ra trong kiểm thử.
// - Chuyển tiếp (reverse-proxy) các yêu cầu `/api/v1/*` tới các dịch vụ backend tương ứng
//   (`TENANT_REGISTRY_URL`, `ORG_SERVICE_URL`, `UI_PACK_SERVICE_URL`, `API_PROXY_TARGET`).
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
import { createServer, request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { handleServerApiRequest } from '../../scripts/dev-backend.mjs';

const ROOT = fileURLToPath(new URL('../dist/e2e/', import.meta.url));
const REPO_ROOT = fileURLToPath(new URL('../../', import.meta.url));

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
    if ((val.startsWith('"') && val.endsWith('"')) || (val.startsWith("'") && val.endsWith("'"))) {
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

/** QA-03: hệ thống thật qua APISIX, không có đường rơi về giả lập. */
const HE_THONG = process.env.E2E_MODE === 'he-thong';
const PORT = Number(getEnv('E2E_PORT', getEnv('WEB_PORT', '4200')));
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

  // Tương thích với bộ kiểm thử Playwright khi truyền rõ E2E_MODE
  if (process.env.E2E_MODE === 'keycloak') {
    const kc = (process.env.E2E_KC_URL ?? 'http://localhost:8180').replace(/\/$/, '');
    return {
      ...base,
      auth: { ...base.auth, enabled: true, issuer: `${kc}/realms/mission` },
      useMockApi: true,
    };
  }
  if (HE_THONG) {
    const kc = (process.env.E2E_KC_URL ?? '').replace(/\/$/, '');
    return {
      ...base,
      apiBaseUrl: '/api',
      auth: { ...base.auth, enabled: true, issuer: `${kc}/realms/mission` },
      useMockApi: false,
      mockApiModules: [],
    };
  }
  if (process.env.E2E_MODE === 'gia-lap' || process.env.E2E_MODE === 'mock') {
    return { ...base, auth: { ...base.auth, enabled: false }, useMockApi: true };
  }

  // Mặc định và `E2E_MODE=that`: nạp động từ .env / biến môi trường, bật Login SSO và gọi thẳng Backend
  // (useMockApi=false); chế độ `that` không cho biến môi trường tắt đăng nhập hay bật giả lập toàn bộ
  const that = process.env.E2E_MODE === 'that';
  const kcUrl = getEnv('E2E_KC_URL', getEnv('KEYCLOAK_URL', 'http://localhost:35080')).replace(
    /\/$/,
    '',
  );
  const realm = getEnv('KEYCLOAK_REALM', 'mission');
  const issuer = getEnv('AUTH_ISSUER', `${kcUrl}/realms/${realm}`);

  return {
    ...base,
    cell: getEnv('CELL', base.cell ?? 'cell-tw'),
    tenant: getEnv('TENANT', base.tenant ?? 'default'),
    apiBaseUrl: getEnv('API_BASE_URL', '/api'),
    uiPackBaseUrl: getEnv('UI_PACK_BASE_URL', '/ui-packs'),
    auth: {
      ...base.auth,
      enabled: that || parseBool(getEnv('AUTH_ENABLED', 'true'), true),
      issuer,
      clientId: getEnv('AUTH_CLIENT_ID', base.auth?.clientId ?? 'mission-web'),
    },
    useMockApi: !that && parseBool(getEnv('USE_MOCK_API', 'false'), false),
    // WEB-070: module còn giả lập khi gọi máy chủ thật, ví dụ MOCK_API_MODULES="dossiers oversight"
    mockApiModules: getEnv('MOCK_API_MODULES', '')
      .split(/[\s,]+/)
      .filter((m) => /^[a-z0-9*][a-z0-9*/-]{0,63}$/.test(m)),
  };
}

function runtimeManifest() {
  const manifestPath = join(ROOT, 'federation.manifest.json');
  const base = existsSync(manifestPath) ? JSON.parse(readFileSync(manifestPath, 'utf8')) : {};
  const mapEnv = {
    'fe-task': getEnv('FE_TASK_URL', undefined),
    'fe-okr': getEnv('FE_OKR_URL', undefined),
    'fe-kpi': getEnv('FE_KPI_URL', undefined),
    'fe-eval': getEnv('FE_EVAL_URL', undefined),
    'fe-scoring': getEnv('FE_SCORING_URL', undefined),
    'fe-report': getEnv('FE_REPORT_URL', undefined),
    'fe-admin': getEnv('FE_ADMIN_URL', undefined),
    'fe-workflow': getEnv('FE_WORKFLOW_URL', undefined),
    'fe-signature': getEnv('FE_SIGNATURE_URL', undefined),
    'fe-notification': getEnv('FE_NOTIFICATION_URL', undefined),
    'fe-sync': getEnv('FE_SYNC_URL', undefined),
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
  if (HE_THONG) return pathname.startsWith('/api/') ? process.env.E2E_APISIX_URL || null : null;
  const tenantRegistry = getEnv('TENANT_REGISTRY_URL', 'http://127.0.0.1:8083');
  const orgService = getEnv('ORG_SERVICE_URL', 'http://127.0.0.1:8084');
  const uiPackService = getEnv('UI_PACK_SERVICE_URL', 'http://127.0.0.1:8090');
  const defaultApiTarget = getEnv('API_PROXY_TARGET', 'http://127.0.0.1:8080');

  if (
    pathname.startsWith('/api/v1/registry') ||
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
  if (pathname.startsWith('/api/v1/ui-packs')) {
    return uiPackService;
  }
  if (pathname.startsWith('/api/')) {
    return defaultApiTarget;
  }
  return null;
}

function proxyRequest(req, res, targetBaseUrl) {
  const chunks = [];
  req.on('data', (chunk) => chunks.push(chunk));
  req.on('end', () => {
    const bodyBuffer = Buffer.concat(chunks);
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
        const status = proxyRes.statusCode ?? 502;
        if (!HE_THONG && (status === 404 || status >= 502)) {
          proxyRes.resume();
          handleServerApiRequest(req, res, bodyBuffer).catch(() => {
            res.writeHead(status, proxyRes.headers);
            res.end();
          });
          return;
        }
        res.writeHead(status, proxyRes.headers);
        proxyRes.pipe(res);
      },
    );
    // Trình duyệt đóng trang hay huỷ luồng SSE thì đóng luôn kết nối lên APISIX; nếu không, notification-service
    // giữ luồng cũ và trả 429 khi một tài khoản vượt 5 luồng đồng thời (NOTIFY-429-TOO-MANY-STREAMS).
    res.on('close', () => {
      if (!res.writableFinished) proxyReq.destroy();
    });
    proxyReq.on('error', (err) => {
      if (res.destroyed) return;
      if (HE_THONG) {
        res.writeHead(502, { 'content-type': 'application/problem+json; charset=utf-8' });
        res.end(
          JSON.stringify({ title: 'APISIX chưa phản hồi', status: 502, detail: err.message }),
        );
        return;
      }
      handleServerApiRequest(req, res, bodyBuffer).catch(() => {
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
    });
    if (bodyBuffer.length > 0) {
      proxyReq.write(bodyBuffer);
    }
    proxyReq.end();
  });
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
