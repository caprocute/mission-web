import { HomeLayoutNode, HomeLayoutOverlay } from '@vt/api-client';

/**
 * Logic chế độ "Tuỳ chỉnh trang chủ" (CNH-03, SCR-0002): danh sách widget sắp xếp được bằng nút
 * lên, xuống, ẩn (không bắt buộc kéo thả, dùng được bằng bàn phím), xem trước ở trình duyệt và
 * dựng lớp phủ `ui.home.layout` để lưu. Máy chủ vẫn là nơi ghép cuối cùng (`HomeLayoutOverlay`).
 */
export interface EditorItem {
  id: string;
  label: string;
  hidden: boolean;
  /** Widget bắt buộc của đơn vị: không ẩn được. */
  mandatory: boolean;
}

/** Nhãn nghiệp vụ của widget trang chủ; widget chưa có nhãn hiện tên chung. */
const LABELS: Record<string, string> = {
  'vt.widget.announcement': 'Thông báo của đơn vị',
  'vt.widget.myTasksToday': 'Việc của tôi hôm nay',
  'vt.widget.okrProgress': 'Tiến độ mục tiêu của đơn vị',
  'vt.widget.pendingApprovals': 'Việc chờ phê duyệt',
  'vt.widget.kpiSummary': 'Tổng hợp chỉ số KPI',
  'vt.widget.evaluationStatus': 'Tình hình đánh giá',
};

export const widgetId = (n: HomeLayoutNode): string => n.id ?? n.component;

export function widgetLabel(n: HomeLayoutNode, index: number): string {
  return LABELS[n.component] ?? `Khối thông tin ${index + 1}`;
}

/**
 * Danh sách đang soạn: widget đang hiện theo thứ tự máy chủ trả, rồi widget đã ẩn (lấy nút từ bố
 * cục gốc của UI Pack để còn hiện lại được).
 */
export function buildEditorItems(
  current: HomeLayoutNode[],
  base: HomeLayoutNode[],
  hiddenIds: string[],
  mandatoryIds: string[],
): EditorItem[] {
  const items: EditorItem[] = current.map((n, i) => ({
    id: widgetId(n),
    label: widgetLabel(n, i),
    hidden: false,
    mandatory: mandatoryIds.includes(widgetId(n)) || n.mandatory === true,
  }));
  const seen = new Set(items.map((i) => i.id));
  base.forEach((n, i) => {
    const id = widgetId(n);
    if (!seen.has(id) && hiddenIds.includes(id)) {
      items.push({ id, label: widgetLabel(n, i), hidden: true, mandatory: false });
      seen.add(id);
    }
  });
  return items;
}

/** Đổi chỗ một widget lên (`-1`) hoặc xuống (`+1`); ra ngoài biên thì giữ nguyên. */
export function moveItem(items: EditorItem[], index: number, delta: -1 | 1): EditorItem[] {
  const to = index + delta;
  if (index < 0 || index >= items.length || to < 0 || to >= items.length) return items;
  const next = [...items];
  [next[index], next[to]] = [next[to], next[index]];
  return next;
}

/** Ẩn hoặc hiện một widget; widget bắt buộc không đổi. */
export function toggleHidden(items: EditorItem[], index: number): EditorItem[] {
  const item = items[index];
  if (!item || item.mandatory) return items;
  return items.map((it, i) => (i === index ? { ...it, hidden: !it.hidden } : it));
}

/** Lớp phủ lưu vào `ui.home.layout`; giữ các trường mà màn hình này không sửa. */
export function toOverlay(
  items: EditorItem[],
  previous: HomeLayoutOverlay | null,
): HomeLayoutOverlay {
  const overlay: HomeLayoutOverlay = {
    ...(previous ?? {}),
    widgetOrder: items.map((it, order) => {
      const old = previous?.widgetOrder?.find((p) => p.id === it.id);
      return old?.colSpan ? { id: it.id, order, colSpan: old.colSpan } : { id: it.id, order };
    }),
    hiddenWidgets: items.filter((it) => it.hidden && !it.mandatory).map((it) => it.id),
    // Ghim thắng thứ tự ở máy chủ; màn hình này sắp xếp trực tiếp nên bỏ ghim cũ.
    pinnedWidgets: [],
  };
  return overlay;
}

/** Bố cục xem trước: gốc giữ nguyên, nút con theo danh sách đang soạn, bỏ widget đã ẩn. */
export function previewLayout(
  root: HomeLayoutNode,
  items: EditorItem[],
  nodes: Map<string, HomeLayoutNode>,
): HomeLayoutNode {
  return {
    ...root,
    children: items
      .filter((it) => !it.hidden)
      .map((it) => nodes.get(it.id))
      .filter((n): n is HomeLayoutNode => !!n),
  };
}
