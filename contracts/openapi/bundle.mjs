// Gộp mỗi tệp module thành một tài liệu OpenAPI không còn $ref ra ngoài, ghi vào dist/ (WEB-063, ADR-32).
// Bản gộp dùng cho openapi-diff với springdoc của backend và cho công cụ không đọc được $ref nhiều tệp.
// Chạy: npm run bundle
import { execFileSync } from 'node:child_process';
import { readdirSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const modulesDir = join(root, 'openapi', 'modules');
const outDir = join(root, 'dist');
const redocly = join(root, 'node_modules', '.bin', 'redocly');

mkdirSync(outDir, { recursive: true });
for (const file of readdirSync(modulesDir).filter((f) => f.endsWith('.yaml')).sort()) {
  execFileSync(redocly, ['bundle', join(modulesDir, file), '--output', join(outDir, file)], {
    cwd: root,
    stdio: 'inherit',
  });
}
