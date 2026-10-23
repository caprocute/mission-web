import { Page, expect, test } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { FORBIDDEN, SECTIONS, tasksLanding } from './kiem-tra';
import { MOCK_ROLES, canAny, remoteChildRoutes } from './matrix';

/**
 * YC-PT-21: giao diện chỉ hiện chức năng mà vai trò có quyền, không để người dùng bấm rồi mới báo
 * "không có quyền". Với từng vai trò của ma trận (chế độ giả lập), mở mọi trang mà vai trò vào được
 * rồi kiểm ba điều trên lúc tải, chưa bấm gì:
 * 1. mọi liên kết đang hiện (menu, lối tắt, thẻ, tab) dẫn tới tuyến mà vai trò vào được theo guard;
 * 2. không có lời gọi API nào bị từ chối (403) hay nằm ngoài ma trận API của vai trò, tức màn hình
 *    không đọc phần không được xem (chế độ giả lập không trả 403 nên đối chiếu thẳng ma trận);
 * 3. không hiện câu "không có quyền" (thông báo chung hay cảnh báo tại chỗ).
 * Quyền của tuyến tính từ ma trận và guard trong mã (`matrix.ts`), giống bộ WEB-057.
 */
const CHILDREN = remoteChildRoutes();

interface ApiRow {
  module: string;
  quyen: Record<string, string>;
}
const MATRIX = JSON.parse(
  readFileSync(join(__dirname, '..', '..', 'docs/phan-quyen/ma-tran.json'), 'utf8'),
) as { vai_tro: { ac: string; role: string }[]; api: ApiRow[] };
const AC_OF = new Map(MATRIX.vai_tro.map((r) => [r.role, r.ac]));

/** Vai trò được gọi `method` của module theo ma trận API; module chưa có dòng thì không kết luận. */
function apiAllowed(role: string, method: string, path: string): boolean {
  const m = /\/api\/v1\/([a-z0-9-]+)/.exec(path);
  // `/registry/resolve` gọi trước khi có vai trò (ghi chú của ma trận API)
  if (!m || path.includes('/registry/resolve')) return true;
  const row = MATRIX.api.find((r) => r.module === m[1]);
  if (!row) return true;
  // Vai trò con (ví dụ wf_template_author) dùng cột của vai trò cha trong ma trận API
  const ac = AC_OF.get(role) ?? (role === 'wf_template_author' ? 'AC-01' : undefined);
  const cell = ac ? (row.quyen[ac] ?? '-') : '-';
  return cell.split(' ').some((t) => t.replace(/\*$/, '') === method);
}
const FORBIDDEN_TEXT = /không có quyền/i;

/** Khoá phải có để vào `path`: tuyến cha của shell và mọi tuyến con có guard bao quanh `path`. */
function canOpen(roles: string[], path: string): boolean {
  if (path === '/' || path === FORBIDDEN || path === '/loi') return true;
  // Trang đầu `/tasks` chuyển theo vai trò; `appraiser`, `inspector` chỉ mở chi tiết nhiệm vụ
  if (path === '/tasks' && tasksLanding(roles) === FORBIDDEN) return false;
  const section = SECTIONS.find((s) => path === s.path || path.startsWith(s.path + '/'));
  if (section && !canAny(roles, section.routeKeys)) return false;
  const segs = path.split('/');
  for (let i = 2; i <= segs.length; i++) {
    const prefix = segs.slice(0, i).join('/');
    for (const c of CHILDREN) if (c.pattern.test(prefix) && !canAny(roles, c.keys)) return false;
  }
  return true;
}

/** Trang mở thử: trang chủ, tuyến cha và tuyến con không tham số mà vai trò vào được. */
function pagesFor(roles: string[]): string[] {
  const urls = ['/', ...SECTIONS.map((s) => s.path), ...CHILDREN.map((c) => c.url)];
  return [...new Set(urls)].filter((u) => !u.includes('E2E-1') && canOpen(roles, u));
}

async function visibleLinks(page: Page): Promise<string[]> {
  return page.evaluate(() => {
    const out: string[] = [];
    for (const a of Array.from(document.querySelectorAll<HTMLAnchorElement>('a[href]'))) {
      const r = a.getBoundingClientRect();
      if (!r.width || !r.height || getComputedStyle(a).visibility === 'hidden') continue;
      const url = new URL(a.href, location.href);
      if (url.origin === location.origin) out.push(url.pathname.replace(/\/$/, '') || '/');
    }
    return out;
  });
}

for (const { ac, role } of MOCK_ROLES) {
  test(`${ac} ${role}: không bày chức năng không có quyền`, async ({ page }) => {
    test.setTimeout(240_000);
    await page.context().addInitScript((r) => sessionStorage.setItem('vt.devRoles', r), role);
    const denied: string[] = [];
    const outside: string[] = [];
    page.on('request', (req) => {
      const path = new URL(req.url()).pathname;
      if (path.includes('/api/v1/') && !apiAllowed(role, req.method(), path))
        outside.push(`${req.method()} ${path}`);
    });
    page.on('response', (res) => {
      if (res.status() === 403 && res.url().includes('/api/'))
        denied.push(`${res.request().method()} ${new URL(res.url()).pathname}`);
    });

    const problems: string[] = [];
    for (const url of pagesFor([role])) {
      denied.length = 0;
      outside.length = 0;
      await page.goto(url);
      await page.locator('main#main router-outlet + *').first().waitFor({ timeout: 15_000 });
      await page.waitForLoadState('networkidle').catch(() => undefined);
      const at = new URL(page.url()).pathname;
      if (at === FORBIDDEN) {
        problems.push(`${url}: tuyến mở được theo ma trận nhưng bị chặn`);
        continue;
      }
      for (const href of new Set(await visibleLinks(page)))
        if (!canOpen([role], href)) problems.push(`${url}: hiện liên kết tới ${href}`);
      for (const d of new Set(denied)) problems.push(`${url}: API bị từ chối ${d}`);
      for (const d of new Set(outside)) problems.push(`${url}: gọi API ngoài ma trận ${d}`);
      const text = await page.locator('main#main, [role="alert"], .p-toast').allInnerTexts();
      const hit = text
        .join('\n')
        .split('\n')
        .find((l) => FORBIDDEN_TEXT.test(l));
      if (hit) problems.push(`${url}: hiện câu "${hit.trim()}"`);
    }
    expect(problems, problems.join('\n')).toEqual([]);
  });
}
