import {
  AppraisalProfileStatus,
  GeneralCriteriaLevel,
  RATING_LEVELS,
  RatingLevel,
} from '@vt/api-client';
import { FormRule, evaluateRules } from '@vt/form-engine';

/**
 * Quy tắc của phiếu tự đánh giá SCR-0901 chạy trên trình duyệt để phản hồi tức thì; máy chủ kiểm lại
 * khi nộp (422 `APPRAISAL-422-REASON-REQUIRED`, `APPRAISAL-422-BREAKTHROUGH-REQUIRED`).
 */

/** Dữ liệu đang soạn của vùng 4 (trường 9 đến 12). */
export interface SelfForm {
  selfRating: RatingLevel | null;
  breakthrough: string;
  evidenceIds: string[];
  reason: string;
  content: string;
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

export const MSG_09_02 =
  'Mức tự đề xuất khác mức hệ thống tham khảo. Đồng chí cần ghi giải trình trước khi nộp (Điều 16 Quy định 366).';
export const MSG_09_03 =
  'Đề xuất mức Hoàn thành xuất sắc nhiệm vụ cần mô tả giải pháp đột phá và gắn ít nhất một minh chứng đã được xác nhận.';

/**
 * Quy tắc CEL theo dạng `FormRule` của `@vt/form-engine` (biến: `selfRating`, `refRating`,
 * `breakthroughLen`, `evidenceCount`, `reasonLen`, `contentLen`). Độ dài chuỗi tính trước thành biến vì
 * cel-js 0.8.2 trên trình duyệt chưa có đủ hàm chuỗi (wiki/10, bẫy 13). BR-09-12 là quy tắc nền tảng
 * (không cấu hình theo Policy Pack), nên bộ quy tắc nằm trong mã; khi WEB-010 có loader quy tắc của
 * UI Pack, có thể chuyển sang khai báo (suy luận).
 */
export const SELF_RULES: FormRule[] = [
  {
    id: 'content',
    when: 'true',
    assert: 'contentLen > 0',
    message: 'Vui lòng nhập ưu điểm, hạn chế, hướng khắc phục.',
  },
  {
    id: 'reason',
    when: 'selfRating != refRating',
    assert: 'reasonLen >= 20 && reasonLen <= 2000',
    message: MSG_09_02,
  },
  {
    id: 'breakthrough',
    when: 'selfRating == "HTXSNV"',
    assert: 'breakthroughLen > 0 && evidenceCount > 0',
    message: MSG_09_03,
  },
];

/** Lỗi theo trường của vùng 4, khoá là `id` của quy tắc. */
export function checkSelfForm(f: SelfForm, ref: RatingLevel): Record<string, string> {
  const model = {
    selfRating: f.selfRating ?? ref,
    refRating: ref,
    breakthroughLen: f.breakthrough.trim().length,
    evidenceCount: f.evidenceIds.length,
    reasonLen: f.reason.trim().length,
    contentLen: f.content.trim().length,
  };
  return Object.fromEntries(evaluateRules(SELF_RULES, model).map((v) => [v.ruleId, v.message]));
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
