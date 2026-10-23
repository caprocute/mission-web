import { defineConfig } from '@playwright/test';
import base from '../../playwright.config';

/**
 * Chạy các hành trình QA-05 (`qt04-07/`) ở chế độ giả lập trên bản ghép `dist/e2e`:
 * `npx playwright test -c e2e/hanh-trinh/qt04-07/playwright.gia-lap.config.ts` (sau `npm run e2e:prepare`).
 * Cấu hình chung của QA-03 chỉ nạp `hanh-trinh/` khi `E2E_MODE=he-thong`; chế độ đó vẫn chạy các tệp này
 * bằng cấu hình chung. Chạy tuần tự vì mỗi hành trình đi nhiều màn hình trên một kho giả lập.
 */
export default defineConfig({
  ...base,
  testDir: '.',
  testMatch: /.*\.spec\.ts/,
  outputDir: '../../../dist/e2e-ket-qua/test-results-qa05',
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  reporter: [['list']],
  webServer: { ...(base.webServer as object), cwd: '../../..' } as typeof base.webServer,
});
