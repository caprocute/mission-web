import { expect, test } from '@playwright/test';
import { mkdirSync, readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { SECTIONS, CHILD_ROUTES } from './kiem-tra';
import { MOCK_ROLES } from './matrix';

/**
 * Khung xương khi tải trên mọi tuyến (YC-PT-28, wiki/16): mở từng tuyến của danh mục kiểm nội dung
 * (`gia-lap.noi-dung.spec.ts`: tuyến của shell, tuyến con của remote, tuyến chi tiết ở
 * `e2e/noi-dung/*.json`) với API giả lập trả chậm (`?mock-delay=`), người dùng mang mọi vai trò.
 * Khi màn hình đã vào vùng nội dung mà dữ liệu chưa về, vùng nội dung phải có `<vt-skeleton>` đang
 * hiện và không được có câu trạng thái rỗng ("Chưa có…") hay chữ "Đang tải…" trơn. Ảnh chụp trạng
 * thái tải của từng tuyến lưu ở `dist/e2e-ket-qua/khung-xuong/` làm bằng chứng.
 *
 * Tuyến không gọi dữ liệu khi mở (biểu mẫu tạo mới trống) khai ở `KHONG_TAI` kèm lý do.
 */
const DELAY = 6000;
const ROLES = MOCK_ROLES.map((r) => r.role).join(',');
const extra = readdirSync(join(__dirname, 'noi-dung'))
  .filter((f) => f.endsWith('.json'))
  .flatMap((f) => JSON.parse(readFileSync(join(__dirname, 'noi-dung', f), 'utf8')) as string[]);
const URLS = [
  ...new Set(['/', ...SECTIONS.map((s) => s.path), ...CHILD_ROUTES.map((c) => c.url), ...extra]),
];
/** Tuyến không nạp dữ liệu máy chủ khi mở; khung xương không áp dụng. */
const KHONG_TAI: Record<string, string> = {};
const OUT = join(__dirname, '..', 'dist', 'e2e-ket-qua', 'khung-xuong');
mkdirSync(OUT, { recursive: true });

const slug = (url: string) =>
  (url === '/' ? 'trang-chu' : url.replace(/^\//, '').replace(/[^\w-]+/g, '_')).slice(0, 120);
const withDelay = (url: string) => `${url}${url.includes('?') ? '&' : '?'}mock-delay=${DELAY}`;

for (const url of URLS) {
  test(`khung xương ${url}`, async ({ page, context }) => {
    test.setTimeout(60_000);
    test.skip(url in KHONG_TAI, KHONG_TAI[url]);
    await context.addInitScript((r) => sessionStorage.setItem('vt.devRoles', r), ROLES);
    const errors: string[] = [];
    page.on('pageerror', (e) => errors.push(e.message));
    await page.goto(withDelay(url));
    // Trước khi tuyến xong, khung trang (khung xương khởi động hoặc khung xương chuyển phân hệ) phải hiện
    await expect(page.locator('.vt-boot, vt-skeleton').first()).toBeVisible({ timeout: 5_000 });
    const shell = page.locator('main#main router-outlet + *').first();
    const notFound = page.getByRole('heading', { name: 'Không tìm thấy trang' });
    await shell.or(notFound).first().waitFor({ timeout: 20_000 });
    test.skip(await notFound.isVisible(), `${url} không phải tuyến của ứng dụng`);
    // Tuyến bị chặn theo quyền chuyển sang trang "không có quyền": không có dữ liệu để tải
    test.skip(new URL(page.url()).pathname === '/khong-co-quyen', `${url} bị chặn theo quyền`);
    await page.waitForTimeout(600);
    const main = page.locator('main#main');
    const skeletons = main.locator('vt-skeleton');
    await page.screenshot({ path: join(OUT, `${slug(url)}.png`), fullPage: false });
    expect(
      await skeletons.count(),
      `${url}: vùng nội dung không có khung xương khi đang tải`,
    ).toBeGreaterThan(0);
    await expect(skeletons.first()).toBeVisible();
    const text = await main.innerText();
    const bad = text
      .split('\n')
      .map((l) => l.trim())
      .filter((l) => /^(Chưa có|Không có) (dữ liệu|số liệu|bản ghi|mục)|^Đang tải…?$/.test(l));
    expect(bad, `${url}: câu trạng thái rỗng hoặc chữ trơn hiện khi dữ liệu chưa về`).toEqual([]);
    expect(errors, `${url}: lỗi trang`).toEqual([]);
  });
}
