// Đo ngân sách dựng biểu mẫu 50 trường của @vt/form-engine trên Chromium (TL-01, PERF-21, WEB-010).
// Dùng bản dựng Storybook (`npm run build-storybook`, Angular chế độ production) do
// `node e2e/storybook-serve.mjs` phục vụ ở cổng 6106, story "Đo ngân sách 50 trường".
// Chạy: `node e2e/do-ngan-sach-bieu-mau.mjs [hệ số làm chậm CPU, mặc định 1]`.
// In trung vị 8 lần (bỏ lần đầu): lượt vẽ đầu, số ô của lượt vẽ đầu, thời gian tới khi đủ 50 ô,
// tác vụ dài nhất (longtask, chỉ tính tác vụ trên 50 ms).
import { chromium } from '@playwright/test';

const slow = Number(process.argv[2] ?? 1);
const port = Number(process.env.VISUAL_PORT ?? 6106);
const id = encodeURIComponent('biểu-mẫu-vt-form--do-ngan-sach');
const browser = await chromium.launch();
const page = await browser.newPage();
const errors = [];
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
await page.goto(`http://localhost:${port}/iframe.html?id=${id}&viewMode=story`);
await page.waitForFunction(() => typeof globalThis.__doBieuMau === 'function', null, {
  timeout: 30_000,
});
if (slow > 1) {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('Emulation.setCPUThrottlingRate', { rate: slow });
}
const runs = [];
for (let i = 0; i < 9; i++) runs.push(await page.evaluate(() => globalThis.__doBieuMau()));
runs.shift();
const median = (k) => runs.map((r) => r[k]).sort((a, b) => a - b)[Math.floor(runs.length / 2)];
console.log(
  JSON.stringify({
    lamCham: slow,
    luotVeDauMs: +median('first').toFixed(1),
    soODauTien: runs[0].firstCount,
    duTruongMs: +median('all').toFixed(1),
    tacVuDaiNhatMs: median('maxTask'),
    tacVuDaiNhatMoiLanMs: Math.max(...runs.map((r) => r.maxTask)),
    loiConsole: errors.length,
  }),
);
await browser.close();
