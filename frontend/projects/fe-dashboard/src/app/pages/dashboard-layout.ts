import { DashboardChartType, DashboardWidget } from '@vt/api-client/domains';

/** Lưới 12 cột của dashboard tự dựng (`layout_json`: x, y, w, h theo ô lưới). */
export const GRID_COLUMNS = 12;

/** Vị trí CSS grid của một widget: hàng/cột bắt đầu từ 1. */
export function gridArea(w: Pick<DashboardWidget, 'x' | 'y' | 'w' | 'h'>): string {
  const w_ = Math.min(GRID_COLUMNS, Math.max(1, w.w));
  const x = Math.min(GRID_COLUMNS - w_, Math.max(0, w.x));
  return `${w.y + 1} / ${x + 1} / span ${Math.max(1, w.h)} / span ${w_}`;
}

/** Chiều cao mặc định theo kiểu biểu đồ: thẻ số 2 hàng, còn lại 4 hàng. */
export const defaultHeight = (t: DashboardChartType) => (t === 'THE_SO' ? 2 : 4);

/**
 * Xếp lại vị trí theo thứ tự widget (trái sang phải, xuống dòng khi hết 12 cột). Trình dựng cho chọn độ
 * rộng, thứ tự; toạ độ x, y tính lại khi lưu để định nghĩa luôn hợp lệ.
 */
export function pack(widgets: DashboardWidget[]): DashboardWidget[] {
  let x = 0;
  let y = 0;
  let rowH = 0;
  return widgets.map((w) => {
    const width = Math.min(GRID_COLUMNS, Math.max(1, w.w));
    if (x + width > GRID_COLUMNS) {
      x = 0;
      y += rowH;
      rowH = 0;
    }
    const out = { ...w, x, y, w: width };
    x += width;
    rowH = Math.max(rowH, w.h);
    return out;
  });
}

/** Kiểu biểu đồ gợi ý theo chỉ số và chiều (SCR-1602 trường 6 "theo chỉ số"). */
export function suggestChart(dimensions: string[], dimensionType?: string): DashboardChartType {
  if (!dimensions.length) return 'THE_SO';
  if (dimensionType === 'time') return 'DUONG';
  return dimensions.length > 1 ? 'BANG' : 'THANH';
}
