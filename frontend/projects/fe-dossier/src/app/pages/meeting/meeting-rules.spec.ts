import { DossierMeeting } from '@vt/api-client';
import { DossierPermissionKey } from '../../dossier-permissions';
import { createErrors, toCreate } from '../meetings/meetings-rules';
import {
  actions,
  deadlinePassedText,
  msg1101,
  problemMessage,
  specialCaseLabel,
  stepOf,
  voteError,
} from './meeting-rules';

/** WEB-040: quy tắc hiển thị của SCR-1101. */
const base: DossierMeeting = {
  id: 'm',
  meetingType: 'KIEM_DIEM_NAM',
  orgUnit: { id: 'u', name: 'Chi bộ' },
  period: { id: 'p', label: 'Năm 2026', startDate: '2026-01-01', endDate: '2026-12-31' },
  scheduledAt: '2027-01-14T08:00:00+07:00',
  status: 'DA_GUI_DU_THAO',
  chair: { id: 'a', name: 'A' },
  secretary: { id: 'b', name: 'B' },
  deadlineCheck: { workingDays: 3, requiredWorkingDays: 3, passed: true, legalRef: 'QĐ Điều 6' },
  rowVersion: 1,
};
const all = (_: DossierPermissionKey) => true;
const none = (_: DossierPermissionKey) => false;

describe('meeting-rules (WEB-040)', () => {
  it('bước hiện tại theo trạng thái và ngày họp', () => {
    expect(stepOf({ ...base, status: 'DU_KIEN' })).toBe(0);
    expect(stepOf(base, new Date('2027-01-10T08:00:00+07:00'))).toBe(1);
    expect(stepOf(base, new Date('2027-01-14T09:00:00+07:00'))).toBe(2);
    expect(stepOf({ ...base, status: 'DA_LAP_BIEN_BAN' })).toBe(3);
    expect(stepOf({ ...base, status: 'DA_KY' })).toBe(4);
  });

  it('MSG-11-01 và câu kết luận đạt thời hạn', () => {
    expect(deadlinePassedText(base.deadlineCheck)).toBe(
      'Đạt yêu cầu ≥ 03 ngày làm việc (QĐ Điều 6)',
    );
    const late = { ...base.deadlineCheck, workingDays: 1, passed: false };
    expect(msg1101(late)).toBe(
      'Dự thảo được gửi 1 ngày làm việc trước hội nghị, chưa đủ 03 ngày theo QĐ Điều 6. ' +
        'Đề nghị dời ngày hội nghị hoặc ghi lý do được cấp uỷ chấp thuận.',
    );
  });

  it('tổng phiếu không vượt số người có mặt', () => {
    expect(voteError({ HTXSNV: 1, HTTNV: 5, HTNV: 0, KHTNV: 0 }, 6)).toBeNull();
    expect(voteError({ HTXSNV: 1, HTTNV: 5, HTNV: 1, KHTNV: 0 }, 6)).toBe(
      'Tổng số phiếu (7) vượt số người có mặt (6).',
    );
    expect(voteError({ HTXSNV: 0, HTTNV: 0, HTNV: 0, KHTNV: 0 }, 6)).toContain('Nhập kết quả');
    expect(voteError({ HTXSNV: -1, HTTNV: 0, HTNV: 0, KHTNV: 0 }, 6)).toContain('không âm');
  });

  it('chức năng theo quyền, vai trò trong hội nghị và trạng thái', () => {
    const sec = { ...base, myRole: 'THU_KY' as const };
    expect(actions.record(sec, all)).toBe(true);
    expect(actions.minutes(sec, all)).toBe(true);
    expect(actions.record(sec, none)).toBe(false);
    expect(actions.comment(sec, all)).toBe(false);
    const subj = { ...base, myRole: 'NGUOI_DUOC_KIEM_DIEM' as const, status: 'DU_KIEN' as const };
    expect(actions.sendDraft(subj, all)).toBe(true);
    expect(actions.sendDraft({ ...subj, status: 'DA_GUI_DU_THAO' }, all)).toBe(false);
    const chair = { ...base, myRole: 'CHU_TRI' as const };
    expect(actions.close(chair, all)).toBe(false);
    expect(actions.close({ ...chair, vote: { HTTNV: 3 } }, all)).toBe(true);
    expect(actions.close({ ...chair, vote: { HTTNV: 3 }, commentsLocked: true }, all)).toBe(false);
    const late = {
      ...chair,
      draftSentAt: '2027-01-13T09:00:00+07:00',
      deadlineCheck: { ...base.deadlineCheck, workingDays: 0, passed: false },
    };
    expect(actions.keepDate(late, all)).toBe(true);
    expect(actions.keepDate(late, (k) => k !== 'SCR-1101.ket-thuc')).toBe(false);
    expect(actions.reschedule(base, (k) => k === 'SCR-1101.tao')).toBe(true);
    expect(actions.reschedule({ ...base, status: 'DA_HOP' }, all)).toBe(false);
  });

  it('tên trường hợp đặc thù theo mục PL 6; câu lỗi không chứa mã', () => {
    expect(specialCaseLabel('PL 6 §1')).toBe('Điều động, luân chuyển dưới 2 tháng trong kỳ');
    expect(specialCaseLabel('PL 6 §13')).toContain('Người đứng đầu');
    expect(specialCaseLabel('khác')).toBe('Trường hợp đặc thù');
    const msg = problemMessage({
      type: 'x',
      title: 't',
      status: 403,
      code: 'DOSSIER-403-NOT-CHAIR',
    });
    expect(msg).toBe('Chỉ chủ trì hội nghị thực hiện được thao tác này.');
    expect(problemMessage({ type: 'x', title: 't', status: 500 })).not.toMatch(/\d{3}|DOSSIER/);
  });

  it('lập hội nghị: kiểm tra trường, bỏ người đã có vai trò khỏi thành phần', () => {
    const f = {
      meetingType: 'KIEM_DIEM_NAM' as const,
      periodId: 'p-2026',
      scheduledAt: '2026-12-28T08:00',
      location: ' Phòng họp ',
      subjectPersonId: 's',
      chairPersonId: 'c',
      secretaryPersonId: 'c',
      participantIds: ['c', 's', 'x'],
    };
    expect(createErrors(f)).toEqual({ secretaryPersonId: 'Thư ký phải khác chủ trì.' });
    const body = toCreate({ ...f, secretaryPersonId: 't' }, 'u');
    expect(body.participantPersonIds).toEqual(['x']);
    expect(body.location).toBe('Phòng họp');
    expect(body.orgUnitId).toBe('u');
  });
});
