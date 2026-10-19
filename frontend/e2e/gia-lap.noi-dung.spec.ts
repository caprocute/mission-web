import { expect, test } from '@playwright/test';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { SECTIONS, CHILD_ROUTES } from './kiem-tra';
import { MOCK_ROLES } from './matrix';

/**
 * Kiểm tra nội dung hiển thị (AGENTS.md quy tắc 9, wiki/16): mở từng tuyến ở chế độ giả lập với
 * người dùng mang mọi vai trò, đọc văn bản đang hiện cùng nhãn hỗ trợ (aria-label, title,
 * placeholder, alt) và đối chiếu bộ quy tắc của `tools/ui-text/rules.mjs`. Bắt được cả mã lộ ra từ
 * dữ liệu (trạng thái thô, tên trường), thứ mà kiểm tra tĩnh `npm run check:ui-text` không thấy.
 * Tuyến chi tiết cần mã có thật của dữ liệu giả lập khai ở `e2e/noi-dung/*.json` (mảng đường dẫn).
 */
const ROLES = MOCK_ROLES.map((r) => r.role).join(',');
const extra = readdirSync(join(__dirname, 'noi-dung'))
  .filter((f) => f.endsWith('.json'))
  .flatMap((f) => JSON.parse(readFileSync(join(__dirname, 'noi-dung', f), 'utf8')) as string[]);
const URLS = [
  ...new Set(['/', ...SECTIONS.map((s) => s.path), ...CHILD_ROUTES.map((c) => c.url), ...extra]),
];

interface Rule { id: string; re: RegExp; hint: string }
let rules: Rule[] = [];
test.beforeAll(async () => {
  rules = (await import('../tools/ui-text/rules.mjs')).RULES;
});

for (const url of URLS) {
  test(`nội dung ${url}`, async ({ page, context }) => {
    await context.addInitScript((r) => sessionStorage.setItem('vt.devRoles', r), ROLES);
    await page.goto(url);
    await page.locator('main#main router-outlet + *').first().waitFor({ timeout: 15_000 });
    await page.waitForLoadState('networkidle');
    const lines = await page.evaluate(() => {
      const out = document.body.innerText.split('\n');
      document.querySelectorAll('[aria-label],[title],[placeholder],[alt]').forEach((e) => {
        for (const a of ['aria-label', 'title', 'placeholder', 'alt']) {
          const v = e.getAttribute(a);
          if (v) out.push(v);
        }
      });
      return out.map((l) => l.trim()).filter(Boolean);
    });
    const found: string[] = [];
    for (const line of new Set(lines)) {
      for (const r of rules) {
        const m = r.re.exec(line);
        r.re.lastIndex = 0;
        if (m) found.push(`[${r.id}] «${m[0]}» trong: ${line.slice(0, 140)}`);
      }
    }
    expect(found, `Nội dung chưa chuẩn ở ${url} (xem wiki/16-noi-dung-giao-dien.md)`).toEqual([]);
  });
}
