import { CONFLICT_TITLE, ProblemDetails } from '@vt/api-client';
import { DossierDeadlineCheck, DossierMeeting, DossierParticipantStatus, DossierVote, RatingLevel } from '@vt/api-client/domains';
import { DossierPermissionKey } from '../../dossier-permissions';

/**
 * Quy tắc hiển thị của SCR-1101 (QT-11, WEB-040), tách khỏi component để kiểm thử. Máy chủ vẫn kiểm lại
 * mọi điều kiện (vai trò trong hội nghị, trạng thái, thời hạn, tổng phiếu) và trả 403, 409, 422 khi chặn.
 */

/** Năm bước của thanh tiến trình. */
export const STEPS = [
  'Gửi dự thảo',
  'Thu góp ý',
  'Hội nghị',
  'Biên bản, ký số',
  'Theo dõi khắc phục',
] as const;

/** Bước hiện tại (0..4): hội nghị đã gửi dự thảo chuyển sang bước "Hội nghị" khi tới ngày họp. */
export function stepOf(m: DossierMeeting, now = new Date()): number {
  switch (m.status) {
    case 'DU_KIEN':
      return 0;
    case 'DA_GUI_DU_THAO': {
      const day = new Date(m.scheduledAt);
      day.setHours(0, 0, 0, 0);
      return now >= day ? 2 : 1;
    }
    case 'DA_HOP':
    case 'DA_LAP_BIEN_BAN':
      return 3;
    default:
      return 4;
  }
}

const pad = (n: number) => String(n).padStart(2, '0');

/** Số ngày làm việc tối thiểu viết hai chữ số như văn bản quy định ("03 ngày làm việc"). */
export const requiredDays = (c: DossierDeadlineCheck) => pad(c.requiredWorkingDays);

/** MSG-11-01 khi thời hạn gửi dự thảo không đạt. */
export function msg1101(c: DossierDeadlineCheck): string {
  return (
    `Dự thảo được gửi ${c.workingDays ?? 0} ngày làm việc trước hội nghị, chưa đủ ` +
    `${requiredDays(c)} ngày theo ${c.legalRef ?? 'QĐ Điều 6'}. Đề nghị dời ngày hội nghị hoặc ghi lý do ` +
    'được cấp uỷ chấp thuận.'
  );
}

/** MSG-11-03 khi chủ trì kết thúc hội nghị. */
export const MSG_11_03 =
  'Đã kết thúc hội nghị. Biên bản đang được sinh và sẽ gửi chủ trì, thư ký ký số.';

/** Kết luận của thẻ "Kiểm tra thời hạn gửi dự thảo" khi đạt. */
export function deadlinePassedText(c: DossierDeadlineCheck): string {
  return `Đạt yêu cầu ≥ ${requiredDays(c)} ngày làm việc (${c.legalRef ?? 'QĐ Điều 6'})`;
}

/** Người được tính là có mặt trên thẻ thành phần (chưa điểm danh thì mọi người trừ vắng có lý do). */
export const isPresent = (s: DossierParticipantStatus | undefined) => s !== 'VANG_CO_LY_DO';

export const LEVELS: RatingLevel[] = ['HTXSNV', 'HTTNV', 'HTNV', 'KHTNV'];

export const voteTotal = (v: DossierVote | null | undefined) =>
  LEVELS.reduce((a, l) => a + (v?.[l] ?? 0), 0);

/** Kiểm tra trường 9: số nguyên không âm, tổng không vượt số người có mặt. */
export function voteError(v: DossierVote, present: number): string | null {
  if (LEVELS.some((l) => !Number.isInteger(v[l] ?? 0) || (v[l] ?? 0) < 0))
    return 'Số phiếu phải là số nguyên không âm.';
  const total = voteTotal(v);
  if (total === 0) return 'Nhập kết quả biểu quyết trước khi sinh biên bản.';
  if (total > present) return `Tổng số phiếu (${total}) vượt số người có mặt (${present}).`;
  return null;
}

/** Tên 13 trường hợp đặc thù của PL 6 (TL-02b QT-11), tra theo mục trong `legalRef`. */
export const SPECIAL_CASE_LABEL: Record<number, string> = {
  1: 'Điều động, luân chuyển dưới 2 tháng trong kỳ',
  2: 'Điều động, luân chuyển từ 2 tháng trở lên trong kỳ',
  3: 'Kiêm nhiệm nhiều chức vụ',
  4: 'Nghỉ thai sản, ốm đau dài ngày trên 50% thời gian kỳ',
  5: 'Đi học, biệt phái dài hạn',
  6: 'Mới tuyển dụng, mới bổ nhiệm dưới 6 tháng',
  7: 'Đang bị xem xét, xác minh, kiểm tra',
  8: 'Đã bị kỷ luật trong kỳ',
  9: 'Đơn vị mới thành lập, sáp nhập, chia tách trong kỳ',
  10: 'Nghỉ hưu, thôi việc trong kỳ',
  11: 'Đảng viên ngoài hệ thống chính trị',
  12: 'Không đủ điều kiện của mức đề xuất',
  13: 'Người đứng đầu có đơn vị phụ trách không hoàn thành nhiệm vụ trên 50%',
};

export function specialCaseLabel(legalRef: string): string {
  const n = Number(/§\s*(\d+)/.exec(legalRef)?.[1]);
  return SPECIAL_CASE_LABEL[n] ?? 'Trường hợp đặc thù';
}

type Can = (k: DossierPermissionKey) => boolean;
const before = (m: DossierMeeting) => m.status === 'DU_KIEN' || m.status === 'DA_GUI_DU_THAO';
const open = (m: DossierMeeting) =>
  !m.commentsLocked && (m.status === 'DA_GUI_DU_THAO' || m.status === 'DA_HOP');

/** Điều kiện hiện từng chức năng: quyền theo ma trận cộng vai trò trong hội nghị và trạng thái. */
export const actions = {
  sendDraft: (m: DossierMeeting, can: Can) =>
    can('SCR-1101.gui-du-thao') && m.myRole === 'NGUOI_DUOC_KIEM_DIEM' && m.status === 'DU_KIEN',
  downloadDraft: (m: DossierMeeting, can: Can) =>
    can('SCR-1101.gop-y') && !!m.draftForms?.some((f) => f.formCode === 'MAU_1B'),
  comment: (m: DossierMeeting, can: Can) =>
    can('SCR-1101.gop-y') &&
    (m.myRole === 'THANH_VIEN' || m.myRole === 'CHU_TRI') &&
    (open(m) || (m.status === 'DA_LAP_BIEN_BAN' && !m.commentsLocked)),
  record: (m: DossierMeeting, can: Can) =>
    can('SCR-1101.thu-ky') && m.myRole === 'THU_KY' && open(m),
  minutes: (m: DossierMeeting, can: Can) =>
    can('SCR-1101.thu-ky') && m.myRole === 'THU_KY' && open(m),
  remediation: (m: DossierMeeting, can: Can) =>
    can('SCR-1101.thu-ky') && m.myRole === 'THU_KY' && !m.commentsLocked,
  close: (m: DossierMeeting, can: Can) =>
    can('SCR-1101.ket-thuc') && m.myRole === 'CHU_TRI' && !m.commentsLocked && !!m.vote,
  keepDate: (m: DossierMeeting, can: Can) =>
    can('SCR-1101.ket-thuc') &&
    m.myRole === 'CHU_TRI' &&
    before(m) &&
    !!m.draftSentAt &&
    !m.deadlineCheck.passed,
  reschedule: (m: DossierMeeting, can: Can) => can('SCR-1101.tao') && before(m),
};

/** Câu báo lỗi theo mã của `dossiers.yaml`; không hiện mã hay trạng thái HTTP lên màn hình. */
export function problemMessage(p: ProblemDetails): string {
  switch (p.code) {
    case 'DOSSIER-409-VERSION-CONFLICT':
      return CONFLICT_TITLE;
    case 'DOSSIER-403-NOT-SUBJECT':
      return 'Chỉ người được kiểm điểm gửi được dự thảo của mình.';
    case 'DOSSIER-403-NOT-SECRETARY':
      return 'Chỉ thư ký hội nghị thực hiện được thao tác này.';
    case 'DOSSIER-403-NOT-CHAIR':
      return 'Chỉ chủ trì hội nghị thực hiện được thao tác này.';
    case 'DOSSIER-403-NOT-PARTICIPANT':
      return 'Đồng chí không thuộc thành phần hội nghị.';
    case 'DOSSIER-409-COMMENTS-LOCKED':
      return 'Hội nghị đã kết thúc, không nhận thêm góp ý.';
    case 'DOSSIER-409-NO-VOTE':
      return 'Chưa có kết quả biểu quyết, chưa kết thúc được hội nghị.';
    case 'DOSSIER-409-MEETING-HELD':
      return 'Hội nghị đã họp, không sửa được kế hoạch.';
    case 'DOSSIER-409-MEETING-EXISTS':
      return 'Đồng chí này đã có hội nghị kiểm điểm cùng loại trong kỳ.';
    case 'DOSSIER-422-VOTE-EXCEEDS-ATTENDEES':
      return 'Tổng số phiếu biểu quyết vượt số người có mặt.';
  }
  if (p.status === 403) return 'Đồng chí không có quyền thực hiện thao tác này.';
  if (p.status === 428) return 'Dữ liệu trên màn hình đã cũ. Vui lòng tải lại rồi thực hiện lại.';
  if (p.status === 422)
    return p.errors?.[0]?.message ?? 'Dữ liệu chưa hợp lệ, vui lòng kiểm tra lại.';
  return 'Chưa thực hiện được thao tác. Vui lòng thử lại.';
}
