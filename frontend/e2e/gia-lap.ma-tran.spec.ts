import { test } from '@playwright/test';
import { SECTIONS, CHILD_ROUTES, checkSection, checkChild } from './kiem-tra';
import { MOCK_ROLES } from './matrix';

/**
 * Chế độ giả lập: mỗi vai trò của ma trận (AC-01..AC-10, AC-12, vai trò con) chạy riêng với người
 * dùng giả lập mang đúng vai trò đó (sessionStorage `vt.devRoles`, chỉ đọc khi tắt đăng nhập), trên
 * mọi tuyến cha của shell và tuyến con của fe-task.
 */
for (const { ac, role } of MOCK_ROLES) {
  test.describe(`${ac} ${role}`, () => {
    test.beforeEach(async ({ context }) => {
      await context.addInitScript((r) => sessionStorage.setItem('vt.devRoles', r), role);
    });

    for (const s of SECTIONS) {
      test(`tuyến ${s.path}`, async ({ page }, info) => {
        await checkSection(page, info, role, [role], s);
      });
    }

    for (const child of CHILD_ROUTES) {
      test(`tuyến con ${child.url}`, async ({ page }, info) => {
        await checkChild(page, info, role, [role], child);
      });
    }
  });
}
