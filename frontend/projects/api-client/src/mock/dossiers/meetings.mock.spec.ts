import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { DossierApi } from '../../lib/dossier-api';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { mockSession, mockStepUp } from '../mock-session';
import { workingDaysBetween } from './meetings';

/** WEB-040: giả lập hội nghị kiểm điểm của MOD-06 (SCR-1101). */
function setup() {
  resetMockData();
  mockSession.reset();
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
    ],
  });
  return TestBed.inject(DossierApi);
}

async function errorOf(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('không có lỗi');
}

const M = (n: number) => `0192a3f4-1101-7000-8000-${String(n).padStart(12, '0')}`;

describe('dossier meetings mock (WEB-040)', () => {
  it('BR-11-01: đếm ngày làm việc trọn vẹn giữa hai mốc', () => {
    // Ví dụ của TL-02b: gửi 16:20 thứ Sáu 08/01/2027, họp thứ Năm 14/01/2027 → 11, 12, 13/01
    expect(workingDaysBetween(new Date(2027, 0, 8, 16, 20), new Date(2027, 0, 14, 8, 0))).toBe(3);
    expect(workingDaysBetween(new Date(2027, 0, 13, 9), new Date(2027, 0, 14, 8))).toBe(0);
  });

  it('danh sách, chi tiết theo mockup, góp ý ẩn danh với người được kiểm điểm', async () => {
    const api = setup();
    const page = await firstValueFrom(api.meetings());
    expect(page.items.length).toBe(7);
    const m1 = await firstValueFrom(api.meeting(M(1)));
    expect(m1.myRole).toBe('THU_KY');
    expect(m1.deadlineCheck.passed).toBe(true);
    expect(m1.deadlineCheck.workingDays).toBe(3);
    expect(m1.participants?.length).toBe(7);
    const c1 = await firstValueFrom(api.comments(M(1)));
    expect(c1.items.every((c) => c.commenter)).toBe(true);
    const c5 = await firstValueFrom(api.comments(M(5)));
    expect(c5.anonymous).toBe(true);
    expect(c5.items.every((c) => c.commenter === null)).toBe(true);
    const prev = await firstValueFrom(api.remediationItems(M(1), 'KY_TRUOC'));
    expect(prev.items.length).toBe(3);
  });

  it('gửi dự thảo muộn trả cảnh báo thời hạn; chỉ người được kiểm điểm gửi được', async () => {
    const api = setup();
    const m4 = await firstValueFrom(api.meeting(M(4)));
    expect((await errorOf(firstValueFrom(api.sendDraft(m4)))).status).toBe(403);
    const m2 = await firstValueFrom(api.meeting(M(2)));
    expect((await errorOf(firstValueFrom(api.sendDraft({ ...m2, rowVersion: 99 })))).status).toBe(
      409,
    );
    const sent = await firstValueFrom(api.sendDraft(m2));
    expect(sent.status).toBe('DA_GUI_DU_THAO');
    expect(sent.deadlineCheck.passed).toBe(false);
    expect(sent.deadlineCheck.workingDays).toBeLessThan(3);
    expect((await errorOf(firstValueFrom(api.sendDraft(sent)))).status).toBe(409);
  });

  it('lý do giữ ngày chỉ chủ trì ghi; dời ngày tính lại thời hạn', async () => {
    const api = setup();
    const m1 = await firstValueFrom(api.meeting(M(1)));
    const e = await errorOf(
      firstValueFrom(
        api.updateMeeting(m1, { deadlineOverrideReason: 'Cấp uỷ thống nhất giữ ngày.' }),
      ),
    );
    expect(e.status).toBe(403);
    const m7 = await firstValueFrom(api.meeting(M(7)));
    expect(m7.deadlineCheck.passed).toBe(false);
    const kept = await firstValueFrom(
      api.updateMeeting(m7, {
        deadlineOverrideReason: 'Cấp uỷ thống nhất giữ ngày họp do lịch công tác.',
      }),
    );
    expect(kept.deadlineCheck.overrideReason).toContain('Cấp uỷ');
    const later = new Date(kept.scheduledAt);
    later.setDate(later.getDate() + 14);
    const moved = await firstValueFrom(
      api.updateMeeting(kept, { scheduledAt: later.toISOString() }),
    );
    expect(moved.deadlineCheck.passed).toBe(true);
  });

  it('góp ý: thành viên gửi, chỉ thư ký ghi tại hội nghị', async () => {
    const api = setup();
    const body = { subjectPersonId: 'x', kind: 'HAN_CHE' as const, content: 'Còn chậm báo cáo.' };
    await firstValueFrom(api.addComment(M(4), { ...body, phase: 'TRUOC_HOI_NGHI' }));
    const m4 = await firstValueFrom(api.meeting(M(4)));
    expect(m4.participants?.find((p) => p.role === 'THANH_VIEN')?.status).toBe('DA_GOP_Y');
    expect(
      (await errorOf(firstValueFrom(api.addComment(M(4), { ...body, phase: 'HOI_NGHI' })))).status,
    ).toBe(403);
    const ok = await firstValueFrom(api.addComment(M(1), { ...body, phase: 'HOI_NGHI' }));
    expect(ok.phase).toBe('HOI_NGHI');
    expect(
      (await errorOf(firstValueFrom(api.addComment(M(2), { ...body, phase: 'TRUOC_HOI_NGHI' }))))
        .status,
    ).toBe(409);
  });

  it('sinh biên bản: step-up, tổng phiếu không vượt số có mặt, job hoàn tất', async () => {
    const api = setup();
    const m1 = await firstValueFrom(api.meeting(M(1)));
    const attendance = (m1.participants ?? []).map((p) => ({
      personId: p.person.id,
      attendance: p.status === 'VANG_CO_LY_DO' ? ('VANG_CO_LY_DO' as const) : ('CO_MAT' as const),
    }));
    const vote = { HTXSNV: 1, HTTNV: 5, HTNV: 0, KHTNV: 0 };
    const e401 = await errorOf(firstValueFrom(api.minutes(m1, { attendance, vote })));
    expect(e401.status).toBe(401);
    expect(e401.headers.get('WWW-Authenticate')).toContain('insufficient_user_authentication');
    mockStepUp();
    const e422 = await errorOf(
      firstValueFrom(api.minutes(m1, { attendance, vote: { ...vote, HTNV: 1 } })),
    );
    expect(e422.status).toBe(422);
    const { location } = await firstValueFrom(api.minutes(m1, { attendance, vote }));
    expect(location).toContain('/api/v1/dossiers/form-jobs/');
    const after = await firstValueFrom(api.meeting(M(1)));
    expect(after.status).toBe('DA_LAP_BIEN_BAN');
    expect(after.vote).toEqual(vote);
  });

  it('kết thúc: chỉ chủ trì, khoá góp ý, tạo hạn chế cần khắc phục, yêu cầu ký', async () => {
    const api = setup();
    const m1 = await firstValueFrom(api.meeting(M(1)));
    expect((await errorOf(firstValueFrom(api.close(m1)))).status).toBe(403);
    const m3 = await firstValueFrom(api.meeting(M(3)));
    const closed = await firstValueFrom(api.close(m3));
    expect(closed.commentsLocked).toBe(true);
    expect(closed.signRequestId).toBeTruthy();
    const created = await firstValueFrom(api.remediationItems(M(3), 'HOI_NGHI_NAY'));
    expect(created.items.length).toBe(2);
    const body = {
      subjectPersonId: 'x',
      kind: 'UU_DIEM' as const,
      content: 'x',
      phase: 'TRUOC_HOI_NGHI' as const,
    };
    expect((await errorOf(firstValueFrom(api.addComment(M(3), body)))).status).toBe(409);
  });

  it('thư ký cập nhật khắc phục; lập hội nghị kiểm tra dữ liệu; trường hợp đặc thù', async () => {
    const api = setup();
    const prev = await firstValueFrom(api.remediationItems(M(1), 'KY_TRUOC'));
    const item = prev.items.find((i) => i.status === 'DANG_KHAC_PHUC')!;
    const done = await firstValueFrom(api.updateRemediation(item, { status: 'DA_KHAC_PHUC' }));
    expect(done.verifiedBy).toBeTruthy();
    const base = {
      periodId: 'p-2026',
      orgUnitId: 'u-btc',
      meetingType: 'KIEM_DIEM_NAM' as const,
      scheduledAt: '2026-12-28T08:00:00+07:00',
      chairPersonId: 'p-dung',
      secretaryPersonId: 'p-dung',
      subjectPersonId: 'p-an',
    };
    const e = await errorOf(firstValueFrom(api.createMeeting(base)));
    expect(e.status).toBe(422);
    const m = await firstValueFrom(
      api.createMeeting({ ...base, secretaryPersonId: 'p-chau', participantPersonIds: ['p-binh'] }),
    );
    expect(m.status).toBe('DU_KIEN');
    expect(m.participants?.length).toBe(4);
    expect(m.draftForms?.[0].formCode).toBe('MAU_1B');
    const dup = await errorOf(
      firstValueFrom(api.createMeeting({ ...base, secretaryPersonId: 'p-chau' })),
    );
    expect(dup.status).toBe(409);
    const sc = await firstValueFrom(api.specialCases({ periodId: 'p-2026' }));
    expect(sc.items.some((c) => c.legalRef === 'PL 6 §1')).toBe(true);
  });
});
