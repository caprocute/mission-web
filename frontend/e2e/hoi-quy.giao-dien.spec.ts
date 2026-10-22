import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import AxeBuilder from '@axe-core/playwright';
import { expect, test } from '@playwright/test';

/**
 * WEB-005: mỗi story `hoi-quy` × bốn bộ token: ảnh chụp so với ảnh chuẩn và không vi phạm WCAG 2.1
 * AA theo axe-core (NFR-11). Đồng hồ trang cố định để ngày tương đối của dữ liệu giả lập ổn định.
 */
const TOKEN_SETS = ['default', 'cao-tuoi', 'thuong-hieu-toi', 'tuong-phan-cao'];
/** Ngoài bốn bộ token, chụp thêm chế độ "Đảng viên cao tuổi" trên bộ mặc định (NFR-11). */
const BIEN_THE = [
  ...TOKEN_SETS.map((t) => ({ ten: t, globals: `tokenSet:${t}` })),
  { ten: 'default-elderly', globals: 'tokenSet:default;cheDo:elderly' },
];
const NGAY_CO_DINH = new Date('2026-10-22T09:00:00+07:00');
/** Story có dữ liệu từ API giả lập: chờ phần tử có dữ liệu để không chụp trạng thái đang tải. */
const CHO_DU_LIEU: Record<string, string> = { 'vt.widget.myTasksToday': 'li a' };

interface IndexEntry {
  id: string;
  type: string;
  name: string;
  tags?: string[];
}
const index = JSON.parse(readFileSync(resolve('dist/storybook/index.json'), 'utf8')) as {
  entries: Record<string, IndexEntry>;
};
const stories = Object.values(index.entries).filter(
  (e) => e.type === 'story' && e.tags?.includes('hoi-quy'),
);

test('có story cho mọi component của registry trong phạm vi tầng 1', () => {
  const names = stories.map((s) => s.name);
  for (const c of [
    'vt.layout.grid',
    'vt.widget.myTasksToday',
    'vt.widget.okrProgress',
    'vt.widget.pendingApprovals',
    'vt.widget.announcement',
  ]) {
    expect(names).toContain(c);
  }
  expect(names.some((n) => n.startsWith('vt-form'))).toBe(true);
});

for (const story of stories) {
  for (const bt of BIEN_THE) {
    test(`${story.name} · ${bt.ten}`, async ({ page }) => {
      const errors: string[] = [];
      page.on('console', (m) => {
        if (m.type() === 'error') errors.push(m.text());
      });
      page.on('pageerror', (e) => errors.push(e.message));
      await page.clock.setFixedTime(NGAY_CO_DINH);
      await page.goto(
        `/iframe.html?id=${encodeURIComponent(story.id)}&viewMode=story&globals=${bt.globals}`,
      );
      const root = page.locator('#storybook-root');
      await expect(root).not.toBeEmpty();
      await page.waitForLoadState('networkidle');
      const cho = CHO_DU_LIEU[story.name];
      if (cho) await expect(root.locator(cho).first()).toBeVisible();
      await page.evaluate(() => document.fonts.ready);
      // UI Pack đã áp: thẻ style token của tenant có mặt
      await expect(page.locator('style#vt-tenant-tokens')).toHaveCount(1);
      if (bt.ten.endsWith('elderly')) {
        await expect(page.locator('html')).toHaveAttribute('data-mode', 'elderly');
      }

      const ten = story.name.replace(/[^A-Za-z0-9.-]+/g, '-').toLowerCase();
      await expect(root).toHaveScreenshot(`${ten}--${bt.ten}.png`);

      const axe = await new AxeBuilder({ page })
        .include('#storybook-root')
        .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
        .analyze();
      expect(
        axe.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.target).join(', ')}`),
      ).toEqual([]);
      expect(errors).toEqual([]);
    });
  }
}
