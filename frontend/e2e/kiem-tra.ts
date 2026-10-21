import { Page, TestInfo, expect } from '@playwright/test';
import type { CaseResult } from './ma-tran-reporter';
import { ShellSection, canAny, remoteChildRoutes, screenKeys, shellSections } from './matrix';

export const FORBIDDEN = '/khong-co-quyen';
export const SECTIONS = shellSections();
/** Tuyến con có `permissionGuard` của các remote đã có màn hình (quy ước wiki/10). */
const ALL_CHILDREN = remoteChildRoutes();
/** Tuyến con không phải trang đầu của tuyến cha; trang đầu kiểm cùng tuyến cha ở `checkSection`. */
export const CHILD_ROUTES = ALL_CHILDREN.filter((c) => c.path !== '');
const parentOf = (url: string) => SECTIONS.find((s) => url.startsWith(s.path + '/'));

/**
 * Trang đầu `/tasks` theo `myTasksOrFirstAllowed` của `fe-task/src/app/task.routes.ts`: SCR-0503 nếu
 * có, nếu không thì Giao việc (SCR-0501), rồi Thư viện danh mục (SCR-0401), còn lại bị chặn.
 */
function tasksLanding(roles: string[]): string {
  if (canAny(roles, screenKeys(['SCR-0503']))) return '/tasks';
  if (canAny(roles, screenKeys(['SCR-0501']))) return '/tasks/assign';
  if (canAny(roles, screenKeys(['SCR-0401']))) return '/tasks/catalog';
  return FORBIDDEN;
}

const describe = (path: string, target: string, menu?: boolean) =>
  `${menu ? 'menu, ' : menu === false ? 'ẩn, ' : ''}${target === path ? 'vào' : target === FORBIDDEN ? 'chặn' : `→ ${target}`}`;

/** Mở `url` và chờ tuyến xử lý xong (component của tuyến đã vào vùng nội dung), trả đường dẫn cuối. */
async function visit(page: Page, url: string): Promise<string> {
  await page.goto(url);
  await page.locator('main#main router-outlet + *').first().waitFor({ timeout: 15_000 });
  return new URL(page.url()).pathname;
}

async function menuLabels(page: Page): Promise<string[]> {
  // Mục menu có nhãn và dòng mô tả (`.nav-sub`); chỉ so nhãn chính
  const labels = page
    .getByRole('navigation', { name: 'Menu chính' })
    .getByRole('link')
    .locator('.nav-copy > span:first-child');
  return (await labels.allTextContents()).map((t) => t.trim());
}

function record(info: TestInfo, row: CaseResult) {
  info.annotations.push({ type: 'ca', description: JSON.stringify(row) });
  expect.soft(row.thucTe, `${row.vaiTro} ${row.tuyen}`).toBe(row.kyVong);
}

/** Kiểm một mục menu và tuyến cha của shell cho người dùng mang `roles`. */
export async function checkSection(
  page: Page,
  info: TestInfo,
  label: string,
  roles: string[],
  s: ShellSection,
) {
  const landing = ALL_CHILDREN.find((c) => c.url === s.path);
  const target = !canAny(roles, s.routeKeys)
    ? FORBIDDEN
    : s.path === '/tasks'
      ? tasksLanding(roles)
      : landing && !canAny(roles, landing.keys)
        ? FORBIDDEN
        : s.path;
  let path = await visit(page, s.path);
  // Remote tự chuyển trang đầu tới tuyến con (ví dụ quản trị nền tảng vào thẳng Danh sách tenant):
  // coi là "vào" khi tuyến con đó cho vai trò này vào theo ma trận
  const redirectedChild = ALL_CHILDREN.find((c) => c.path !== '' && c.pattern.test(path));
  if (
    target === s.path &&
    path.startsWith(s.path + '/') &&
    redirectedChild &&
    canAny(roles, redirectedChild.keys)
  ) {
    path = s.path;
  }
  const menu = (await menuLabels(page)).includes(s.label);
  record(info, {
    vaiTro: label,
    tuyen: s.path,
    kyVong: describe(s.path, target, canAny(roles, s.menuKeys)),
    thucTe: describe(s.path, path, menu),
  });
}

/** Kiểm một tuyến con của remote (chặn theo đúng SCR của tuyến, sau tuyến cha). */
export async function checkChild(
  page: Page,
  info: TestInfo,
  label: string,
  roles: string[],
  child: (typeof CHILD_ROUTES)[number],
) {
  const parent = parentOf(child.url);
  const allowed = parent && canAny(roles, parent.routeKeys) && canAny(roles, child.keys);
  const target = allowed ? child.url : FORBIDDEN;
  const path = await visit(page, child.url);
  record(info, {
    vaiTro: label,
    tuyen: child.url,
    kyVong: describe(child.url, target),
    thucTe: describe(child.url, path),
  });
}
