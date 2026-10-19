import {
  AppraisalProfile,
  AppraisalProfileStatus,
  AppraisalScoreTaskLineView,
  AppraisalScoreView,
  AppraisalSelfAssessmentView,
  RatingLevel,
} from '@vt/api-client';
import { FormRule, evaluateRules } from '@vt/form-engine';

/**
 * Quy tắc của SCR-0902 chạy trên trình duyệt để phản hồi tức thì; máy chủ kiểm lại khi ký (422
 * `APPRAISAL-422-REASON-REQUIRED`, 403 `APPRAISAL-403-SEPARATION-OF-DUTIES`).
 */

export const MSG_09_06 =
  'Mức thẩm định khác mức tự đề xuất. Theo QĐ Điều 16, đồng chí cần ghi lý do trước khi ký.';
export const MSG_09_07 =
  'Hồ sơ vừa được người khác cập nhật. Vui lòng tải lại để xem thay đổi trước khi lưu.';
export const MSG_09_08 =
  'Đồng chí không thể thẩm định hồ sơ này vì đã tham gia nghiệm thu một nhiệm vụ của hồ sơ (PL 4 V.1).';

/** MSG-09-09 sau khi trả lại; `n`, `max` lấy từ hồ sơ máy chủ trả về. */
export function msg0909(name: string, n: number, max: number): string {
  return `Đã trả lại hồ sơ cho đồng chí ${name} bổ sung. Lần trả lại thứ ${n}/${max}.`;
}

/** Bộ lọc hàng đợi (trường 1); mặc định "Chờ thẩm định". */
export const QUEUE_FILTERS: { value: AppraisalProfileStatus; label: string }[] = [
  { value: 'CHO_THAM_DINH', label: 'Chờ thẩm định' },
  { value: 'DA_THAM_DINH', label: 'Đã thẩm định' },
  { value: 'TU_DANH_GIA', label: 'Đang tự đánh giá, bổ sung' },
];

/** Dữ liệu đang soạn của vùng 4 (trường 6 đến 8). */
export interface ReviewForm {
  rating: RatingLevel;
  comment: string;
  reason: string;
}

/**
 * Quy tắc CEL theo dạng `FormRule` (biến `rating`, `selfRating`, `hasSelf`, `commentLen`, `reasonLen`):
 * trường 7 bắt buộc, tối đa 4.000 ký tự; trường 8 từ 20 đến 2.000 ký tự khi mức thẩm định khác mức tự
 * đề xuất (BR-09-12, MSG-09-06). Hồ sơ quá hạn không tự đánh giá (mức tự đề xuất trống) thì không đòi
 * lý do khác mức (suy luận).
 */
export const REVIEW_RULES: FormRule[] = [
  {
    id: 'comment',
    when: 'true',
    assert: 'commentLen > 0 && commentLen <= 4000',
    message: 'Vui lòng nhập nhận xét thẩm định (tối đa 4.000 ký tự).',
  },
  {
    id: 'reason',
    when: 'hasSelf && rating != selfRating',
    assert: 'reasonLen >= 20 && reasonLen <= 2000',
    message: 'Ghi lý do khác mức tự đề xuất, từ 20 đến 2.000 ký tự.',
  },
];

export function checkReview(f: ReviewForm, self: RatingLevel | null): Record<string, string> {
  const model = {
    rating: f.rating,
    selfRating: self ?? '',
    hasSelf: !!self,
    commentLen: f.comment.trim().length,
    reasonLen: f.reason.trim().length,
  };
  return Object.fromEntries(evaluateRules(REVIEW_RULES, model).map((v) => [v.ruleId, v.message]));
}

/** Lý do trả lại bổ sung: 20 đến 2.000 ký tự (theo quy ước lý do của TL-02b, suy luận). */
export function returnReasonError(reason: string): string | null {
  const n = reason.trim().length;
  if (n < 20) return 'Lý do trả lại cần ít nhất 20 ký tự.';
  if (n > 2000) return 'Lý do trả lại tối đa 2.000 ký tự.';
  return null;
}

/** Một dòng của bảng so sánh B, C (trường 5). */
export interface CompareRow {
  taskId: string;
  title: string;
  selfB: number | null;
  b: number | null;
  selfC: number | null;
  c: number | null;
  diffB: boolean;
  diffC: boolean;
  /** Căn cứ điều chỉnh, "Khớp" khi không khác. */
  note: string;
  matched: boolean;
}

/**
 * Ghép B, C tự nhận (`self_assessment.claims_json`) với B, C nghiệm thu trong ảnh chụp điểm cho các
 * nhiệm vụ then chốt (nhóm `THEN_CHOT`; không có nhóm này thì mọi nhiệm vụ).
 */
export function compareRows(view: AppraisalScoreView, profile: AppraisalProfile): CompareRow[] {
  const groups = view.snapshot.groups;
  const key = groups.find((g) => g.code === 'THEN_CHOT');
  const tasks = (key ? [key] : groups).flatMap(
    (g) => (g.tasks ?? []) as AppraisalScoreTaskLineView[],
  );
  const claims = (profile.selfAssessment as AppraisalSelfAssessmentView | null | undefined)?.claims;
  return tasks.map((t) => {
    const claim = claims?.find((c) => c.taskId === t.taskId);
    const selfB = claim?.qualityLevel ?? null;
    const selfC = claim?.delayFactor ?? null;
    const b = t.b ?? null;
    const c = t.c ?? null;
    const diffB = selfB !== null && b !== null && selfB !== b;
    const diffC = selfC !== null && c !== null && selfC !== c;
    const matched = !diffB && !diffC;
    return {
      taskId: t.taskId,
      title: t.title,
      selfB,
      b,
      selfC,
      c,
      diffB,
      diffC,
      matched,
      note: t.acceptanceNote ?? (matched ? 'Khớp' : 'Khác giá trị tự nhận'),
    };
  });
}

/** Mức tự đề xuất hiện hành của hồ sơ (dòng `TU_DE_XUAT` của vết mức). */
export function selfLevel(p: AppraisalProfile): RatingLevel | null {
  return (
    p.ratingTrail.find((r) => r.levelKind === 'TU_DE_XUAT' && r.isCurrent)?.ratingCode ??
    p.selfAssessment?.selfRatingCode ??
    null
  );
}

/** Mã băm rút gọn để hiện trên nhãn: `sha256:9c1e…a07b`. */
export function shortHash(h: string | undefined): string {
  if (!h) return '';
  return `sha256:${h.slice(0, 4)}…${h.slice(-4)}`;
}
