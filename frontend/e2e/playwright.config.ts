import { defineConfig, devices } from '@playwright/test';

/**
 * Kiểm thử đầu cuối đối chiếu ma trận phân quyền (WEB-057). Hai chế độ:
 * - mặc định "giả lập": tắt đăng nhập, mock API, người dùng giả lập mang đúng một vai trò;
 * - `E2E_MODE=keycloak`: đăng nhập thật bằng tài khoản thử của realm dev (WEB-056), mật khẩu và
 *   khoá OTP đọc từ `KC_TEST_USER_PASSWORD`, `KC_TEST_OTP_SECRET`; Keycloak ở `E2E_KC_URL`.
 * - `E2E_MODE=that` (WEB-070): ba luồng chính `that.*.spec.ts` (nhãn `@qa-03`) với máy chủ thật của
 *   môi trường QA-03, đăng nhập Keycloak như chế độ trên; `serve.mjs` dựng `config.json` từ biến môi
 *   trường (`useMockApi: false`, `mockApiModules` từ `MOCK_API_MODULES`).
 * - `E2E_MODE=he-thong` (QA-03, hoặc `npm run e2e:he-thong`): các hành trình `*.spec.ts` trong `hanh-trinh/` với
 *   hệ thống thật trên server dev (backend thật qua APISIX ở `E2E_APISIX_URL`, Keycloak realm `mission`),
 *   chạy tuần tự, không chụp màn hình, báo cáo ở `dist/e2e-ket-qua/bao-cao-he-thong.md`; `serve.mjs`
 *   chuyển mọi `/api/*` tới APISIX và không rơi về giả lập.
 * Chế độ giả lập không nạp `that.*`, trừ khi đặt `E2E_THAT_GIA_LAP=1` để chạy các luồng đó trên dữ
 * liệu giả lập; khi đó kết quả ghi vào thư mục riêng để không xoá kết quả của bộ giả lập đang chạy.
 * Mọi chế độ chạy trên bản ghép `dist/e2e` (`npm run e2e:prepare`) do `e2e/serve.mjs` phục vụ.
 */
const keycloak = process.env.E2E_MODE === 'keycloak';
const that = process.env.E2E_MODE === 'that';
if (process.env.npm_lifecycle_event === 'e2e:he-thong') process.env.E2E_MODE = 'he-thong';
const heThong = process.env.E2E_MODE === 'he-thong';
const thatGiaLap = !keycloak && !that && !heThong && process.env.E2E_THAT_GIA_LAP === '1';
/** Đăng nhập Keycloak thật: chạy tuần tự hơn, thời gian chờ dài, không chụp màn hình. */
const real = keycloak || that || heThong;
const port = Number(process.env.E2E_PORT ?? 4200);

export default defineConfig({
  testDir: '.',
  testMatch: heThong
    ? /hanh-trinh\/.*\.spec\.ts/
    : keycloak
      ? /keycloak\..*spec\.ts/
      : that
        ? /that\..*spec\.ts/
        : thatGiaLap
          ? [/gia-lap\..*spec\.ts/, /that\..*spec\.ts/]
          : /gia-lap\..*spec\.ts/,
  outputDir: `../dist/e2e-ket-qua/test-results${heThong ? '-he-thong' : that || thatGiaLap ? '-that' : ''}`,
  fullyParallel: !heThong,
  workers: heThong ? 1 : real ? 2 : undefined,
  retries: 0,
  timeout: heThong ? 300_000 : real ? 120_000 : 30_000,
  reporter: heThong
    ? [['list'], ['./hanh-trinh/ho-tro/bao-cao-reporter.ts']]
    : that
      ? [['list']]
      : [
          ['list'],
          [
            './ma-tran-reporter.ts',
            { mode: keycloak ? 'keycloak-dev' : thatGiaLap ? 'gia-lap-that' : 'gia-lap' },
          ],
        ],
  use: {
    baseURL: `http://localhost:${port}`,
    // Không chụp ảnh hay trace khi đăng nhập thật để không lưu lại màn hình nhập mật khẩu, OTP
    screenshot: real ? 'off' : 'only-on-failure',
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
