import type { HomeLayoutNode } from '../lib/models/preference';

/**
 * Bố cục trang chủ gốc theo UI Pack của tenant cho giả lập `GET /ui/layouts/home` (WEB-051). Máy chủ
 * thật dựng bố cục từ UI Pack hiện hành của tenant (kể cả widget plugin); giả lập không biết tenant
 * nên shell (chỉ khi `useMockApi` bật) đặt bố cục `home` của UI Pack đã nạp vào đây. Chưa đặt thì
 * giả lập dùng bố cục của UI Pack mặc định.
 */
let base: HomeLayoutNode | null = null;

export function setMockHomeBase(layout: HomeLayoutNode | null): void {
  base = layout ? structuredClone(layout) : null;
}

export function mockHomeBase(): HomeLayoutNode | null {
  return base;
}
