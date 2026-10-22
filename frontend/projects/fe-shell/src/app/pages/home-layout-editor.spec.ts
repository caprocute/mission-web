import { describe, expect, it } from 'vitest';
import {
  buildEditorItems,
  moveItem,
  previewLayout,
  toOverlay,
  toggleHidden,
} from './home-layout-editor';

const A = { component: 'vt.widget.announcement' };
const T = { component: 'vt.widget.myTasksToday', mandatory: true };
const P = { component: 'vt.widget.pendingApprovals' };

describe('home-layout-editor (CNH-03)', () => {
  it('dựng danh sách: khối đang hiện theo thứ tự máy chủ, khối đã ẩn đặt cuối', () => {
    const items = buildEditorItems([T, A], [A, T, P], ['vt.widget.pendingApprovals'], []);
    expect(items.map((i) => [i.label, i.hidden, i.mandatory])).toEqual([
      ['Việc của tôi hôm nay', false, true],
      ['Thông báo của đơn vị', false, false],
      ['Việc chờ phê duyệt', true, false],
    ]);
  });

  it('đổi chỗ trong biên và không ẩn được khối bắt buộc', () => {
    const items = buildEditorItems([A, T, P], [], [], ['vt.widget.myTasksToday']);
    expect(moveItem(items, 0, -1)).toBe(items);
    expect(moveItem(items, 2, 1)).toBe(items);
    expect(moveItem(items, 0, 1).map((i) => i.id)[0]).toBe('vt.widget.myTasksToday');
    expect(toggleHidden(items, 1)).toBe(items);
    expect(toggleHidden(items, 2)[2].hidden).toBe(true);
  });

  it('lớp phủ giữ trường khác, bỏ ghim cũ, giữ độ rộng cột đã lưu', () => {
    let items = buildEditorItems([A, T, P], [], [], ['vt.widget.myTasksToday']);
    items = toggleHidden(moveItem(items, 2, -1), 2);
    const overlay = toOverlay(items, {
      baseUiPackVersion: '1.0.0',
      pinnedWidgets: ['vt.widget.announcement'],
      widgetOrder: [{ id: 'vt.widget.pendingApprovals', order: 0, colSpan: 6 }],
    });
    expect(overlay).toEqual({
      baseUiPackVersion: '1.0.0',
      pinnedWidgets: [],
      widgetOrder: [
        { id: 'vt.widget.announcement', order: 0 },
        { id: 'vt.widget.pendingApprovals', order: 1, colSpan: 6 },
        { id: 'vt.widget.myTasksToday', order: 2 },
      ],
      hiddenWidgets: [],
    });
    expect(toOverlay(toggleHidden(items, 1), null).hiddenWidgets).toEqual([
      'vt.widget.pendingApprovals',
    ]);
  });

  it('xem trước bỏ khối đã ẩn và theo thứ tự đang soạn', () => {
    const items = toggleHidden(buildEditorItems([A, T, P], [], [], []), 0);
    const nodes = new Map([A, T, P].map((n) => [n.component, n]));
    const root = previewLayout({ component: 'vt.layout.grid', children: [] }, items, nodes);
    expect(root.children?.map((n) => n.component)).toEqual([
      'vt.widget.myTasksToday',
      'vt.widget.pendingApprovals',
    ]);
  });
});
