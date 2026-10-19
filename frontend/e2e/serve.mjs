// Máy chủ tĩnh cho kiểm thử đầu cuối (WEB-057): phục vụ bản ghép `dist/e2e` (shell ở gốc, remote ở
// `remotes/<project>`) như nginx, trả index.html cho tuyến SPA. `config.json` sinh theo chế độ:
// giả lập (tắt đăng nhập, mock API) hoặc Keycloak dev (E2E_MODE=keycloak, E2E_KC_URL).
import { createReadStream, existsSync, readFileSync, statSync } from 'node:fs';
import { createServer } from 'node:http';
import { extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../dist/e2e/', import.meta.url));
const PORT = Number(process.env.E2E_PORT ?? 4200);
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

function runtimeConfig() {
  const base = JSON.parse(readFileSync(join(ROOT, 'config.json'), 'utf8'));
  if (process.env.E2E_MODE !== 'keycloak')
    return { ...base, auth: { ...base.auth, enabled: false }, useMockApi: true };
  const kc = (process.env.E2E_KC_URL ?? 'http://localhost:8180').replace(/\/$/, '');
  // Đăng nhập thật nhưng vẫn dùng mock API (chưa có backend); kiểm thử step-up tự tắt mock bằng page.route
  return {
    ...base,
    auth: { ...base.auth, enabled: true, issuer: `${kc}/realms/mission` },
    useMockApi: true,
  };
}

if (!existsSync(join(ROOT, 'index.html'))) {
  console.error('Chưa có dist/e2e: chạy `npm run e2e:prepare` trước.');
  process.exit(1);
}

createServer((req, res) => {
  const path = decodeURIComponent(new URL(req.url ?? '/', 'http://x').pathname);
  if (path === '/config.json') {
    res.writeHead(200, { 'content-type': TYPES['.json'], 'cache-control': 'no-store' });
    res.end(JSON.stringify(runtimeConfig()));
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
}).listen(PORT, () =>
  console.log(`e2e: http://localhost:${PORT} (${process.env.E2E_MODE ?? 'gia-lap'})`),
);
