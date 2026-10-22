import { defineConfig, devices } from '@playwright/test';

/**
 * Kiểm thử hồi quy giao diện tầng 1 (TL-01 "Kiểm thử hồi quy giao diện theo tenant", WEB-005): mỗi
 * story có nhãn `hoi-quy` được chụp ảnh với bốn bộ token và quét axe-core. Chạy trên bản dựng tĩnh
 * `dist/storybook` (`npm run test:visual` dựng trước). Ảnh chuẩn nằm ở `e2e/anh-chuan/`; cập nhật
 * bằng `npm run test:visual -- --update-snapshots` khi thay đổi giao diện là có chủ đích.
 */
const port = Number(process.env.VISUAL_PORT ?? 6106);

export default defineConfig({
  testDir: '.',
  testMatch: /hoi-quy\.giao-dien\.spec\.ts/,
  outputDir: '../dist/e2e-ket-qua/hoi-quy',
  snapshotPathTemplate: '{testDir}/anh-chuan/{arg}-{projectName}{ext}',
  fullyParallel: true,
  retries: 0,
  timeout: 30_000,
  reporter: [['list']],
  expect: { toHaveScreenshot: { animations: 'disabled', caret: 'hide', maxDiffPixelRatio: 0.002 } },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 900 } },
    },
  ],
  use: { baseURL: `http://localhost:${port}`, screenshot: 'off', trace: 'off' },
  webServer: {
    command: 'node e2e/storybook-serve.mjs',
    cwd: '..',
    url: `http://localhost:${port}/index.json`,
    reuseExistingServer: false,
    env: { VISUAL_PORT: String(port) },
  },
});
