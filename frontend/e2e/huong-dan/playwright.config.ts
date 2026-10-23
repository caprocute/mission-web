import { defineConfig, devices } from '@playwright/test';

/**
 * Cấu hình chụp ảnh cho bộ hướng dẫn sử dụng (VH-05): `npx playwright test -c e2e/huong-dan` từ
 * thư mục `frontend`. Mặc định chạy trên dữ liệu giả lập của bản ghép `dist/e2e`
 * (`npm run e2e:prepare`); đặt `E2E_MODE=he-thong` cùng các biến môi trường của QA-03 để chụp trên
 * server dev với tài khoản thử của realm `mission`. Ảnh lưu vào `docs/huong-dan-su-dung/anh/`.
 */
const heThong = process.env.E2E_MODE === 'he-thong';
const port = Number(process.env.E2E_PORT ?? 4200);

export default defineConfig({
  testDir: '.',
  testMatch: /chup-anh\.spec\.ts/,
  outputDir: '../../dist/e2e-ket-qua/test-results-huong-dan',
  fullyParallel: true,
  workers: heThong ? 1 : undefined,
  retries: 0,
  timeout: heThong ? 300_000 : 90_000,
  reporter: [['list']],
  use: {
    baseURL: `http://localhost:${port}`,
    screenshot: 'off',
    trace: 'off',
    ...devices['Desktop Chrome'],
    viewport: { width: 1366, height: 820 },
    locale: 'vi-VN',
    timezoneId: 'Asia/Ho_Chi_Minh',
  },
  webServer: {
    command: 'node e2e/serve.mjs',
    cwd: '../..',
    url: `http://localhost:${port}/config.json`,
    reuseExistingServer: false,
    env: { E2E_PORT: String(port), E2E_MODE: process.env.E2E_MODE || 'gia-lap' },
  },
});
