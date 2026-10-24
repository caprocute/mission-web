#!/usr/bin/env node
/**
 * WEB-052 pha 2: đặt `config.json` của bản ghép dùng để đo (`dist/lhci`) sang chế độ không đăng nhập và dữ
 * liệu giả lập. Từ INF-01, `fe-shell/public/config.json` mặc định bật đăng nhập Keycloak, nên Lighthouse chỉ
 * thấy trang chuyển hướng (NO_FCP); trước đây người đo phải sửa tay tệp này.
 *
 * Dùng: node tools/perf/cau-hinh-do.mjs [thư mục, mặc định dist/lhci]
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../..', import.meta.url));
const file = join(root, process.argv[2] ?? 'dist/lhci', 'config.json');
const cfg = JSON.parse(readFileSync(file, 'utf8'));
cfg.auth = { ...cfg.auth, enabled: false };
cfg.useMockApi = true;
writeFileSync(file, JSON.stringify(cfg, null, 2) + '\n');
console.log(`[perf] ${file}: tắt đăng nhập, bật dữ liệu giả lập`);
