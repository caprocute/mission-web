import { defineConfig, devices } from '@playwright/test';

/**
 * Kiểm thử đầu cuối đối chiếu ma trận phân quyền (WEB-057). Hai chế độ:
 * - mặc định "giả lập": tắt đăng nhập, mock API, người dùng giả lập mang đúng một vai trò;
 * - `E2E_MODE=keycloak`: đăng nhập thật bằng tài khoản thử của realm dev (WEB-056), mật khẩu và
 *   khoá OTP đọc từ `KC_TEST_USER_PASSWORD`, `KC_TEST_OTP_SECRET`; Keycloak ở `E2E_KC_URL`.
 * Cả hai chạy trên bản ghép `dist/e2e` (`npm run e2e:prepare`) do `e2e/serve.mjs` phục vụ.
 */
const keycloak = process.env.E2E_MODE === 'keycloak';
const port = Number(process.env.E2E_PORT ?? 4200);

export default defineConfig({
  testDir: '.',
  testMatch: keycloak ? /keycloak\..*spec\.ts/ : /gia-lap\..*spec\.ts/,
  outputDir: '../dist/e2e-ket-qua/test-results',
  fullyParallel: true,
  workers: keycloak ? 2 : undefined,
  retries: 0,
  timeout: keycloak ? 120_000 : 30_000,
  reporter: [['list'], ['./ma-tran-reporter.ts', { mode: keycloak ? 'keycloak-dev' : 'gia-lap' }]],
  use: {
    baseURL: `http://localhost:${port}`,
    // Không chụp ảnh hay trace ở chế độ Keycloak để không lưu lại màn hình nhập mật khẩu, OTP
    screenshot: keycloak ? 'off' : 'only-on-failure',
    trace: 'off',
    ...devices['Desktop Chrome'],
  },
  webServer: {
    command: 'node e2e/serve.mjs',
    cwd: '..',
    url: `http://localhost:${port}/config.json`,
    reuseExistingServer: false,
    env: { E2E_PORT: String(port), E2E_MODE: process.env.E2E_MODE ?? '' },
  },
});
