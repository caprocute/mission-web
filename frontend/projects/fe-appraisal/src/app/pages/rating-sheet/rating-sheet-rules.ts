import {
  AppraisalProfile,
  AppraisalProfileStatus,
  AppraisalRatingProposal,
  AppraisalRuleCheck,
  AppraisalScoreGroup,
  AppraisalScoreSnapshot,
  AppraisalScoreTraceStep,
  AppraisalScoreView,
  RATING_LEVELS,
  RATING_LEVEL_LABEL,
  RatingLevel,
} from '@vt/api-client';

/**
 * Quy tắc và cách trình bày của SCR-0903 chạy trên trình duyệt để phản hồi tức thì; máy chủ kiểm lại khi
 * ghi mức đề xuất (422 `APPRAISAL-422-REASON-REQUIRED`, 403 tách vai trò, 409 trạng thái hoặc phiên bản).
 */

export const MSG_09_12 = 'Mức đề xuất khác mức thẩm định. Vui lòng ghi lý do (khoản 16.3 Điều 16 Quy định 366).';
export const MSG_PROPOSAL_SOD =
  'Đồng chí không thể đề xuất mức cho hồ sơ này vì đã tham gia nghiệm thu một nhiệm vụ của hồ sơ (Phụ lục 4, mục V.1).';

/** MSG-09-10: câu giải thích nhãn bộ tiêu chí áp dụng (thay cho "Policy Pack" theo YC-PT-10). */
export function msg0910(policyVersion: string, period: string): string {
  return `Kết quả này được tính theo bộ tiêu chí ${policyVersion}, áp dụng cho kỳ ${period}.`;
}

/** Dữ liệu đang soạn của khối "Mức đề xuất của quản lý" (trường 10). */
export interface ProposalForm {
  rating: RatingLevel;
  reason: string;
  comment: string;
}

/**
 * Trường 10: khác mức thẩm định thì lý do từ 20 đến 2.000 ký tự (MSG-09-12, BR-09-12); nhận xét tuỳ
 * chọn, tối đa 4.000 ký tự. Hồ sơ chưa có mức thẩm định thì không đòi lý do (suy luận).
 */
export function checkProposal(
  f: ProposalForm,
  appraised: RatingLevel | null | undefined,
): Record<string, string> {
  const errors: Record<string, string> = {};
  const reason = f.reason.trim().length;
  if (appraised && f.rating !== appraised && (reason < 20 || reason > 2000))
    errors['reason'] = 'Ghi lý do khác mức thẩm định, từ 20 đến 2.000 ký tự.';
  if (f.comment.trim().length > 4000) errors['comment'] = 'Nhận xét tối đa 4.000 ký tự.';
  return errors;
}

/** Mức hiện hành của một loại trong vết các mức. */
export function currentLevel(
  p: Pick<AppraisalProfile, 'ratingTrail'>,
  kind: AppraisalRatingProposal['levelKind'],
): RatingLevel | null {
  return p.ratingTrail.find((r) => r.levelKind === kind && r.isCurrent)?.ratingCode ?? null;
}

/** Một nhóm điều kiện của một mức (vùng 2), theo thứ tự mức từ cao xuống thấp. */
export interface ConditionGroup {
  level: RatingLevel;
  title: string;
  items: AppraisalRuleCheck[];
}

/** Trường 4: điều kiện các mức, lấy từ câu giải thích của DMN, thiếu thì từ ảnh chụp điểm. */
export function conditionGroups(view: AppraisalScoreView): ConditionGroup[] {
  const all = view.explanation?.conditions?.length
    ? view.explanation.conditions
    : view.snapshot.ruleChecks;
  return RATING_LEVELS.map((level) => ({
    level,
    title: `Điều kiện mức ${RATING_LEVEL_LABEL[level]}`,
    items: all.filter((c) => c.ratingCode === level),
  })).filter((g) => g.items.length);
}

/** Một điều kiện viết thành câu: giá trị thực tế chỉ thêm khi khác ngưỡng hoặc chưa đạt. */
export function conditionText(c: AppraisalRuleCheck): string {
  if (!c.actual || c.actual === c.threshold || c.label.includes(c.actual)) return c.label;
  return /\d/.test(c.actual)
    ? `${c.label} (thực tế ${c.actual})`
    : `${c.label} (thực tế: ${c.actual})`;
}

/** Thứ tự cấu phần như mockup: nhiệm vụ thường xuyên trước, then chốt sau. */
const GROUP_ORDER: Record<string, number> = { THUONG_XUYEN: 0, THEN_CHOT: 1 };

/** Trường 2: các nhóm nhiệm vụ của bảng cấu phần. */
export function componentGroups(s: AppraisalScoreSnapshot): AppraisalScoreGroup[] {
  return [...s.groups].sort((a, b) => (GROUP_ORDER[a.code] ?? 9) - (GROUP_ORDER[b.code] ?? 9));
}

/** Trường 3: số nhiệm vụ vượt mục tiêu trên tổng số nhiệm vụ của ảnh chụp. */
export function exceedCount(s: AppraisalScoreSnapshot): { over: number; total: number } {
  const tasks = s.groups.flatMap((g) => g.tasks ?? []);
  return { over: tasks.filter((t) => t.exceedsTarget).length, total: tasks.length };
}

/** Tỷ lệ 0..1 thành phần trăm kiểu Việt Nam: `0.22` thành `22%`. */
export function pct(r: number | null | undefined): string {
  if (r === null || r === undefined) return '—';
  return `${Math.round(r * 1000) / 10}%`.replace('.', ',');
}

/** `2027-01-04T09:12:00+07:00` thành `04/01` theo giờ Việt Nam. */
export function dayMonth(iso: string): string {
  const p = Object.fromEntries(
    new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Asia/Ho_Chi_Minh',
      day: '2-digit',
      month: '2-digit',
    })
      .formatToParts(new Date(iso))
      .map((x) => [x.type, x.value]),
  );
  return `${p['day']}/${p['month']}`;
}

const KIND_LABEL: Record<AppraisalRatingProposal['levelKind'], string> = {
  TU_DE_XUAT: 'Tự đề xuất',
  THAM_DINH: 'Thẩm định',
  DE_XUAT: 'Đề xuất',
  QUYET_DINH: 'Quyết định',
};

/** Bước kế tiếp của hồ sơ, là ô cuối của dải "Vết các mức đề xuất". */
const NEXT_STEP: Partial<Record<AppraisalProfileStatus, string>> = {
  CHO_THAM_DINH: 'Chờ thẩm định',
  DA_THAM_DINH: 'Chờ đề xuất',
  CHO_QUYET_DINH: 'Chờ quyết định',
  DA_QUYET_DINH: 'Chờ ký số quyết định',
  DA_KY_SO: 'Đã ký số quyết định',
  DA_KHOA: 'Đã khoá hồ sơ',
  KHIEU_NAI: 'Đang giải quyết khiếu nại',
};

/** Một ô của dải vết: mức, giải trình bắt buộc, hoặc bước kế tiếp. */
export interface TrailChip {
  kind: 'level' | 'reason' | 'next';
  text: string;
  /** Tên mức đầy đủ (ô mức). */
  level?: string;
  date?: string;
  title?: string;
  current?: boolean;
}

/** Trường 8: chuỗi các mức theo thời gian, chèn ô "Giải trình bắt buộc · đã ghi" sau mức có giải trình. */
export function trailChips(p: Pick<AppraisalProfile, 'ratingTrail' | 'status'>): TrailChip[] {
  const out: TrailChip[] = [];
  // so theo thời điểm, không so chuỗi: máy chủ có thể trả múi giờ khác nhau (`Z`, `+07:00`)
  const rows = [...p.ratingTrail].sort(
    (a, b) => Date.parse(a.proposedAt) - Date.parse(b.proposedAt),
  );
  for (const r of rows) {
    out.push({
      kind: 'level',
      text: KIND_LABEL[r.levelKind],
      level: RATING_LEVEL_LABEL[r.ratingCode],
      date: dayMonth(r.proposedAt),
      title: `${r.actor.name}${r.signedAt ? ', đã ký số' : ''}${r.isCurrent ? '' : ', đã được thay bằng mức sau'}`,
      current: r.isCurrent,
    });
    if (r.explanationRequired)
      out.push({
        kind: 'reason',
        text: r.reason ? 'Giải trình bắt buộc · đã ghi' : 'Giải trình bắt buộc · chưa ghi',
        title: r.reason,
      });
  }
  const next = NEXT_STEP[p.status];
  if (next) out.push({ kind: 'next', text: next });
  return out;
}

/** Trạng thái hồ sơ theo cách gọi của cán bộ (không hiện mã trạng thái). */
export const PROFILE_STATUS_LABEL: Record<AppraisalProfileStatus, string> = {
  CHUA_LAP: 'Đang tính kết quả',
  TU_DANH_GIA: 'Đang tự đánh giá',
  CHO_THAM_DINH: 'Chờ thẩm định',
  DA_THAM_DINH: 'Chờ đề xuất mức',
  CHO_QUYET_DINH: 'Chờ quyết định',
  DA_QUYET_DINH: 'Đã quyết định, chờ ký số',
  DA_KY_SO: 'Đã có quyết định',
  DA_KHOA: 'Đã khoá hồ sơ',
  KHIEU_NAI: 'Đang giải quyết khiếu nại',
};

/** Hồ sơ đã có quyết định xếp loại: cán bộ xem kết quả, tải Mẫu 1B. */
export const DECIDED: readonly AppraisalProfileStatus[] = ['DA_KY_SO', 'DA_KHOA', 'KHIEU_NAI'];

/** "Gửi khiếu nại": hồ sơ đã ký số quyết định và còn hạn khiếu nại (so theo ngày, giờ Việt Nam). */
export function complaintOpen(
  p: Pick<AppraisalProfile, 'status' | 'complaintDeadline'>,
  today: string,
): boolean {
  return p.status === 'DA_KY_SO' && !!p.complaintDeadline && p.complaintDeadline >= today;
}

/** Ngày hôm nay theo giờ Việt Nam, dạng `YYYY-MM-DD`. */
export function todayVn(now = new Date()): string {
  return new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Ho_Chi_Minh' }).format(now);
}

const STEP_LABEL: Record<string, string> = {
  kpi_nhiem_vu: 'KPI nhiệm vụ',
  kpi_nhom: 'KPI nhóm',
  diem_ket_qua: 'Điểm kết quả nhiệm vụ',
};

/** Tên bước của vết tính theo cách gọi nghiệp vụ; bước chưa biết gọi chung là "Bước tính". */
export function stepLabel(s: AppraisalScoreTraceStep): string {
  return STEP_LABEL[s.ruleCode] ?? 'Bước tính';
}

/** Nhãn thẻ luồng: thang điểm của từng luồng. */
export const FLOW_TAB_LABEL = {
  DANG: 'Luồng Đảng · thang 100 điểm',
  NHA_NUOC: 'Luồng Nhà nước · thang 30/70',
} as const;
