/**
 * Kiểm tra tức thì của SCR-0301 (QT-03). Chỉ để phản hồi nhanh; máy chủ kiểm lại khi gửi và trả
 * 422 với cùng quy tắc (AGENTS.md quy tắc 6).
 */

const NUMBER_RE = /^-?\d+([.,]\d{1,4})?$/;

/** Đọc số kiểu Việt Nam (`2,6`) hoặc kiểu máy (`2.6`), tối đa 4 chữ số lẻ (trường 5); sai thì `null`. */
export function parseValue(text: string): number | null {
  const t = text.trim();
  if (!NUMBER_RE.test(t)) return null;
  return Number(t.replace(',', '.'));
}

/** Số hiện trong ô nhập: dấu phẩy thập phân, không nhóm hàng nghìn. */
export function valueText(n: number | null | undefined): string {
  return n === null || n === undefined ? '' : String(n).replace('.', ',');
}

/**
 * BR-03-05: giá trị mới chênh quá 50% khoảng chỉ tiêu so với lần trước. Trả tỷ lệ chênh (0..∞) khi
 * vượt ngưỡng để điền MSG-03-02, ngược lại `null`.
 */
export function largeChange(
  previous: number | null | undefined,
  value: number,
  baseline: number | null,
  target: number,
): number | null {
  if (previous === null || previous === undefined) return null;
  const span = Math.abs(target - (baseline ?? 0));
  if (span === 0) return null;
  const diff = Math.abs(value - previous) / span;
  return diff > 0.5 ? diff : null;
}

/** MSG-03-02. */
export function largeChangeMessage(diff: number): string {
  return `Giá trị mới chênh ${Math.round(diff * 100)}% so với lần trước. Đồng chí có chắc chắn không?`;
}

/** BR-03-04: giá trị khác số liệu nguồn thì phải có lý do. */
export function isOverride(sourceValue: number | null | undefined, value: number | null): boolean {
  return (
    sourceValue !== null && sourceValue !== undefined && value !== null && value !== sourceValue
  );
}

const WEEKDAY = ['Chủ nhật', 'thứ Hai', 'thứ Ba', 'thứ Tư', 'thứ Năm', 'thứ Sáu', 'thứ Bảy'];

/** Ngày `yyyy-mm-dd` → "thứ Sáu 23/10/2026" cho hạn check-in. */
export function weekdayDate(iso: string): string {
  const [y, m, d] = iso.slice(0, 10).split('-').map(Number);
  const day = new Date(Date.UTC(y, m - 1, d)).getUTCDay();
  return `${WEEKDAY[day]} ${String(d).padStart(2, '0')}/${String(m).padStart(2, '0')}/${y}`;
}
