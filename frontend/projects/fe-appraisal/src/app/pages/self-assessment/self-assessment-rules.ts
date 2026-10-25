import { UiFormVersion } from '@vt/api-client';
import { AppraisalProfileStatus, GeneralCriteriaLevel, RATING_LEVELS, RatingLevel } from '@vt/api-client/domains';
import { FormRule, evaluateRules } from '@vt/form-engine';

/**
 * Quy tắc của phiếu tự đánh giá SCR-0901 chạy trên trình duyệt để phản hồi tức thì. Phần tự đề xuất
 * mức (trường 9 đến 12) là biểu mẫu động Mẫu 1B lấy từ `GET /ui/forms/mau-1b/current`: lược đồ, cách
 * trình bày và quy tắc CEL do Policy Pack, UI Pack quy định; máy chủ kiểm lại khi lưu, nộp
 * (422 `APPRAISAL-422-REASON-REQUIRED`, `APPRAISAL-422-BREAKTHROUGH-REQUIRED`).
 */

/** Khoá biểu mẫu động của phiếu tự đánh giá (Mẫu 1B). */
export const SELF_FORM_KEY = 'mau-1b';

/** Nội dung phần tự đề xuất của phiếu; khoá trùng tên trường của `AppraisalSelfAssessmentInput`. */
export interface SelfForm {
  selfRatingCode: RatingLevel;
  breakthrough: string;
  reason: string;
  content: string;
}

/**
 * Khoá dữ liệu của mức tự đề xuất trong Mẫu 1B: khoá biểu mẫu viết snake_case (TL-02b SCR-1802 trường 2,
 * máy chủ từ chối khoá khác khi lưu biểu mẫu), còn API tự đánh giá dùng `selfRatingCode`.
 */
export const SELF_RATING_KEY = 'self_rating_code';

/** Nội dung phiếu sang mô hình của biểu mẫu Mẫu 1B. */
export function toModel(f: SelfForm): Record<string, unknown> {
  return {
    [SELF_RATING_KEY]: f.selfRatingCode,
    breakthrough: f.breakthrough,
    reason: f.reason,
    content: f.content,
  };
}

/** Ngữ cảnh chỉ đọc của quy tắc (`ctx.*`): mức hệ thống tham khảo và số minh chứng đã gắn. */
export interface SelfFormContext {
  mucThamKhao: RatingLevel;
  soMinhChung: number;
}

/** BR-09-05: 100% Thực hiện tốt; 90 đến dưới 100% Đảm bảo; 60 đến dưới 90% Cơ bản đảm bảo; còn lại Không đảm bảo. */
export function criteriaLevel(met: number, total: number): GeneralCriteriaLevel {
  const r = total ? met / total : 0;
  if (r >= 1) return 'THUC_HIEN_TOT';
  if (r >= 0.9) return 'DAM_BAO';
  if (r >= 0.6) return 'CO_BAN_DAM_BAO';
  return 'KHONG_DAM_BAO';
}

/** -1 khi `a` cao hơn `b` (HTXSNV cao nhất), 0 khi bằng, 1 khi thấp hơn. */
export function compareRating(a: RatingLevel, b: RatingLevel): number {
  return Math.sign(RATING_LEVELS.indexOf(a) - RATING_LEVELS.indexOf(b));
}

/**
 * Đổi quy tắc CEL của biểu mẫu (`celRules`) sang `FormRule` của `@vt/form-engine`: mỗi quy tắc gắn
 * với trường của nó, `expr` là điều kiện phải đúng.
 */
export function formRules(form: UiFormVersion): FormRule[] {
  return (form.celRules ?? []).map((r, i) => ({
    id: r.field || `rule-${i + 1}`,
    when: 'true',
    assert: r.expr,
    message: r.message,
    ...(r.field ? { field: r.field } : {}),
    ...(r.serverOnly ? { serverOnly: true } : {}),
  }));
}

/** Lỗi theo trường của biểu mẫu (khoá là tên trường), chuỗi đã bỏ khoảng trắng hai đầu. */
export function checkSelfForm(
  rules: readonly FormRule[],
  f: SelfForm,
  ctx: SelfFormContext,
): Record<string, string> {
  const model = toModel({
    selfRatingCode: f.selfRatingCode,
    breakthrough: f.breakthrough.trim(),
    reason: f.reason.trim(),
    content: f.content.trim(),
  });
  return Object.fromEntries(
    evaluateRules(rules, model, { context: { ...ctx } }).map((v) => [
      v.field ?? v.ruleId,
      v.message,
    ]),
  );
}

/** Năm bước của hồ sơ trên thanh bước (bước 1/5 là tự đánh giá). */
export const STEPS = ['Tự đánh giá', 'Thẩm định', 'Đề xuất', 'Quyết định', 'Ký số, khoá'] as const;

export function stepOf(status: AppraisalProfileStatus): number {
  switch (status) {
    case 'CHUA_LAP':
    case 'TU_DANH_GIA':
      return 0;
    case 'CHO_THAM_DINH':
      return 1;
    case 'DA_THAM_DINH':
      return 2;
    case 'CHO_QUYET_DINH':
    case 'DA_QUYET_DINH':
      return 3;
    default:
      return 4;
  }
}

/** Số kiểu Việt Nam: `0,908`, `91,2`. */
export function vn(n: number | null | undefined, digits = 1): string {
  if (n === null || n === undefined) return '—';
  return n.toLocaleString('vi-VN', {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  });
}

/** `2027-01-05` thành `05/01/2027`. */
export function dmy(iso: string | undefined): string {
  if (!iso) return '';
  const [y, m, d] = iso.slice(0, 10).split('-');
  return `${d}/${m}/${y}`;
}

const VN_TIME = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Asia/Ho_Chi_Minh',
  hour: '2-digit',
  minute: '2-digit',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hourCycle: 'h23',
});

/** Thời điểm theo giờ Việt Nam: `09:13 04/01/2027`. */
export function vnTime(iso: string): string {
  const p = Object.fromEntries(VN_TIME.formatToParts(new Date(iso)).map((x) => [x.type, x.value]));
  return `${p['hour']}:${p['minute']} ${p['day']}/${p['month']}/${p['year']}`;
}
