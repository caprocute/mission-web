import {
  ProblemDetails,
  ProspectCriteria,
  ProspectGroupBoard,
  ProspectGroupCode,
  ProspectGroupEntryInput,
  RATING_LEVEL_LABEL,
  RatingLevel,
} from '@vt/api-client';

/**
 * Quy tắc của SCR-1002 Phân nhóm triển vọng chạy trên trình duyệt để phản hồi tức thì khi kéo thả; máy
 * chủ kiểm lại khi lưu và khi trình (BR-10-07 đến BR-10-09). Kết quả phân nhóm là dữ liệu C2: chỉ giữ
 * trong bộ nhớ của trang, không ghi vào bộ nhớ cục bộ của trình duyệt.
 */

export const GROUPS: readonly ProspectGroupCode[] = ['NHOM_1', 'NHOM_2', 'NHOM_3', 'NHOM_4'];

/** Tên nhóm khi chưa đọc được tiêu chí của khối (tên theo PL 5, mockup TL-02b). */
export const DEFAULT_GROUP_NAME: Record<ProspectGroupCode, string> = {
  NHOM_1: 'Nhóm 1 – Phát triển vượt trội',
  NHOM_2: 'Nhóm 2 – Có triển vọng',
  NHOM_3: 'Nhóm 3 – Cần bồi dưỡng thêm',
  NHOM_4: 'Nhóm 4 – Cần xem xét bố trí',
};

/** Tên ngắn dùng trong câu thông báo: "Nhóm 1". */
export const groupShort = (g: ProspectGroupCode) => `Nhóm ${g.slice(-1)}`;

export const REASON_MIN = 20;
export const REASON_MAX = 1000;

/** Một thẻ cán bộ trên bảng (trường 1 đến 6). */
export interface ProspectCard {
  personId: string;
  name: string;
  summary: string;
  level: RatingLevel | null;
  reassignFlag: boolean;
  group: ProspectGroupCode;
  suggested: ProspectGroupCode | null;
  reason: string;
  plan: string;
}

export function toCards(b: ProspectGroupBoard): ProspectCard[] {
  return [...b.entries]
    .sort(
      (a, c) =>
        GROUPS.indexOf(a.groupCode) - GROUPS.indexOf(c.groupCode) ||
        (a.rankInGroup ?? 9999) - (c.rankInGroup ?? 9999) ||
        a.person.name.localeCompare(c.person.name, 'vi'),
    )
    .map((e) => ({
      personId: e.person.id,
      name: e.person.name,
      summary: e.summary ?? '',
      level: e.annualRatingCode ?? null,
      reassignFlag: !!e.reassignFlag,
      group: e.groupCode,
      suggested: e.suggestedGroup ?? null,
      reason: e.reason ?? '',
      plan: e.trainingPlanRef ?? '',
    }));
}

/** Các thẻ của một nhóm theo thứ tự hiện tại (thứ tự trong mảng là thứ tự trong nhóm). */
export function inGroup(cards: ProspectCard[], g: ProspectGroupCode): ProspectCard[] {
  return cards.filter((c) => c.group === g);
}

/**
 * Chuyển thẻ `personId` sang nhóm `to`, đứng ở vị trí `index` trong nhóm (bỏ trống là cuối nhóm).
 * Trả mảng mới; thứ tự các nhóm khác giữ nguyên.
 */
export function moveCard(
  cards: ProspectCard[],
  personId: string,
  to: ProspectGroupCode,
  index?: number,
): ProspectCard[] {
  const card = cards.find((c) => c.personId === personId);
  if (!card) return cards;
  const rest = cards.filter((c) => c.personId !== personId);
  const moved = { ...card, group: to };
  const target = inGroup(rest, to);
  const at = Math.max(0, Math.min(index ?? target.length, target.length));
  return GROUPS.flatMap((g) => {
    const list = inGroup(rest, g);
    if (g === to) list.splice(at, 0, moved);
    return list;
  });
}

/** Thứ tự (từ 1) của thẻ trong nhóm. */
export function rankOf(cards: ProspectCard[], c: ProspectCard): number {
  return inGroup(cards, c.group).findIndex((x) => x.personId === c.personId) + 1;
}

/**
 * BR-10-09: người xếp loại năm không hoàn thành nhiệm vụ hoặc có cờ bố trí lại không vào Nhóm 1, Nhóm 2
 * (hoặc các nhóm mà tiêu chí của khối loại trừ). Trả MSG-10-06 khi vi phạm, `null` khi được phép.
 */
export function eligibilityError(
  c: ProspectCard,
  to: ProspectGroupCode,
  criteria?: ProspectCriteria | null,
): string | null {
  const rule = criteria?.groups.find((g) => g.groupCode === to);
  const excludedLevels: RatingLevel[] = rule
    ? (rule.excludedRatingLevels ?? [])
    : to === 'NHOM_1' || to === 'NHOM_2'
      ? ['KHTNV']
      : [];
  const excludeFlag = rule ? !!rule.excludeReassignmentFlag : to === 'NHOM_1' || to === 'NHOM_2';
  if (c.level && excludedLevels.includes(c.level))
    return `Cán bộ ${c.name} xếp loại năm ${RATING_LEVEL_LABEL[c.level]} nên không thể đưa vào ${groupShort(to)}.`;
  if (excludeFlag && c.reassignFlag)
    return `Cán bộ ${c.name} có hai quý liên tiếp không hoàn thành nhiệm vụ nên không thể đưa vào ${groupShort(to)}.`;
  return null;
}

export const differs = (c: ProspectCard) => !!c.suggested && c.group !== c.suggested;

/** BR-10-08: khác nhóm gợi ý thì lý do 20 đến 1.000 ký tự. */
export function reasonMissing(c: ProspectCard): boolean {
  if (!differs(c)) return false;
  const n = c.reason.trim().length;
  return n < REASON_MIN || n > REASON_MAX;
}

/** MSG-10-07. */
export const msg1007 = (name: string) =>
  `Đồng chí đã xếp ${name} khác nhóm gợi ý. Vui lòng ghi lý do điều chỉnh.`;

export function toInput(cards: ProspectCard[]): { entries: ProspectGroupEntryInput[] } {
  return {
    entries: cards.map((c) => ({
      personId: c.personId,
      groupCode: c.group,
      rankInGroup: rankOf(cards, c),
      ...(differs(c) && c.reason.trim() ? { reason: c.reason.trim() } : {}),
      ...(c.plan.trim() ? { trainingPlanRef: c.plan.trim() } : {}),
    })),
  };
}

export const MSG_10_05 = 'Phân nhóm triển vọng chỉ thực hiện ở kỳ năm (QĐ khoản 12.1.5).';

/** Lỗi máy chủ của bảng phân nhóm viết thành câu nghiệp vụ (danh sách câu). */
export function prospectProblemMessages(p: ProblemDetails): string[] {
  switch (p.code) {
    case 'APPRAISAL-409-PROSPECT-QUARTER':
      return [MSG_10_05];
    case 'APPRAISAL-409-INVALID-STATUS':
      return ['Danh sách đã trình tập thể lãnh đạo nên không chỉnh sửa được.'];
    case 'APPRAISAL-409-VERSION-CONFLICT':
      return ['Danh sách vừa được người khác cập nhật. Vui lòng tải lại trước khi lưu.'];
  }
  if (p.status === 403)
    return ['Đồng chí không có quyền thực hiện thao tác này với danh sách phân nhóm.'];
  if (p.errors?.length) return p.errors.map((e) => e.message);
  return [[p.title, p.detail].filter(Boolean).join(' ')];
}
