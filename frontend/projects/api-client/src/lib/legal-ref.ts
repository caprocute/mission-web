import { Pipe, PipeTransform } from '@angular/core';

/**
 * Tiền tố mã thiết kế nội bộ (màn hình, quy trình, quy tắc nghiệp vụ, thông điệp, yêu cầu…) không
 * được hiện cho người dùng (YC-PT-10, wiki/16). Giữ trùng với `DESIGN_CODE_PREFIXES` của
 * `tools/ui-text/rules.mjs`: `npm run check:ui-text` báo lỗi khi hai danh sách lệch nhau.
 */
export const INTERNAL_CODE_PREFIXES = [
  'SCR',
  'QT',
  'BR',
  'MSG',
  'ADR',
  'EVT',
  'MOD',
  'SVC',
  'NFR',
  'SEC',
  'OPS',
  'PERF',
  'DR',
  'AC',
  'TL',
  'WEB',
  'BS',
  'YC',
  'TASK',
  'DV',
] as const;

/** Mã nội bộ: tiền tố, có thể kèm nhóm chữ (`YC-PT-10`), rồi số (`BR-09-11`, `TL-02a`, `QT-04`). */
const INTERNAL_CODE_RE = new RegExp(
  `(?<![\\p{L}\\d_-])(${INTERNAL_CODE_PREFIXES.join('|')})-([A-Z]{1,4}-)?\\d[\\w.-]*`,
  'u',
);

/** Chuỗi có chứa mã thiết kế nội bộ hay không. */
export function hasInternalCode(text: string): boolean {
  return INTERNAL_CODE_RE.test(text);
}

/**
 * Phần hiển thị của trường căn cứ (`legal_ref`, `legal_basis`) trong chính sách đánh giá: tách theo
 * dấu `;`, bỏ mảnh chứa mã nội bộ (ví dụ `BR-09-11`, `QT-04 bước 9`), giữ căn cứ pháp lý thật.
 * `"NĐ 335/2025; PL A5; BR-09-11"` → `"NĐ 335/2025; PL A5"`. Trả `''` khi không còn gì để hiện,
 * để nơi gọi ẩn cả dòng "Căn cứ".
 */
export function legalRefText(raw: string | null | undefined): string {
  if (!raw) return '';
  return raw
    .split(';')
    .map((s) => s.trim())
    .filter((s) => s && !hasInternalCode(s))
    .join('; ');
}

/** `{{ x.legalRef | legalRef }}`: như {@link legalRefText}, dùng trong template. */
@Pipe({ name: 'legalRef' })
export class LegalRefPipe implements PipeTransform {
  transform(raw: string | null | undefined): string {
    return legalRefText(raw);
  }
}
