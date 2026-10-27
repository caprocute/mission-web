import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { ReopenRequestInput } from '../../lib/models/reopen';
import { AppraisalApi } from '../../lib/appraisal-api';
import { ReopenApi } from '../../lib/reopen-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { mockSession, mockStepUp } from '../mock-session';
import { LOCKED_PROFILE } from '../appraisals/locked-profiles';

/** WEB-043: hành vi giả lập SCR-1301 (QT-13) theo hợp đồng `oversight.yaml`. */
function setup() {
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
  return TestBed.inject(ReopenApi);
}

async function errorOf(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('không có lỗi');
}

const INPUT: ReopenRequestInput = {
  periodId: 'p-2026q1',
  auditKind: 'PHAN_ANH',
  scope: 'HO_SO',
  scopeIds: [LOCKED_PROFILE.binhQ1],
  scopeDetail: {
    items: [
      { profileId: LOCKED_PROFILE.binhQ1, table: 'product.acceptance', fields: ['c_factor'] },
    ],
  },
  openWorkingDays: 3,
  reason:
    'Thi hành kết luận khiếu nại KN-2026-0040: điều chỉnh hệ số C của nhiệm vụ thường xuyên 4 do lỗi lịch nghỉ lễ.',
  basis: { kind: 'KHIEU_NAI', code: 'KN-2026-0040' },
};

describe('mock oversight – SCR-1301 mở lại kỳ (WEB-043)', () => {
  beforeEach(() => {
    resetMockData();
    mockSession.reset();
  });

  it('danh sách mới nhất trước; lọc trạng thái và việc chờ tôi duyệt', async () => {
    const api = setup();
    const all = await firstValueFrom(api.list());
    expect(all.map((r) => r.code)).toEqual([
      'MLK-2026-009',
      'MLK-2026-008',
      'MLK-2026-007',
      'MLK-2026-004',
      'MLK-2026-002',
    ]);
    const pending = await firstValueFrom(api.list({ status: ['CHO_DUYET_2'] }));
    expect(pending.map((r) => r.code)).toEqual(['MLK-2026-007']);
    const mine = await firstValueFrom(api.list({ awaitingMyApproval: true }));
    expect(mine.map((r) => r.code)).toEqual(['MLK-2026-008', 'MLK-2026-007']);
  });

  it('tạo: cần step-up, kiểm độ dài lý do, kỳ phải đã khoá, không trùng phạm vi đang chờ', async () => {
    const api = setup();
    expect((await errorOf(firstValueFrom(api.create(INPUT)))).status).toBe(401);
    mockStepUp();
    const short = await errorOf(firstValueFrom(api.create({ ...INPUT, reason: 'Ngắn quá' })));
    expect(short.status).toBe(422);
    expect(short.error.errors[0].field).toBe('reason');
    const whole = await errorOf(firstValueFrom(api.create({ ...INPUT, scope: 'TOAN_KY' })));
    expect(whole.error.errors[0].field).toBe('scope');
    const open = await errorOf(firstValueFrom(api.create({ ...INPUT, periodId: 'p-2026q3' })));
    expect(open.error.code).toBe('OVERSIGHT-409-PERIOD-NOT-LOCKED');
    const r = await firstValueFrom(api.create(INPUT));
    expect(r).toMatchObject({
      status: 'CHO_DUYET_1',
      requestedBy: { id: 'dev-user' },
      period: { label: 'Quý I/2026' },
      approvalsRequired: 2,
    });
    expect(r.code).toMatch(/^MLK-\d{4}-\d{3}$/);
    expect(r.approvers?.map((s) => s.role)).toEqual(['decider', 'second_approver']);
    const dup = await errorOf(firstValueFrom(api.create(INPUT)));
    expect(dup.error.code).toBe('OVERSIGHT-409-INVALID-STATE');
  });

  it('HT-34: mã hồ sơ không phải UUID bị từ chối 400 như máy chủ, kể cả trước step-up', async () => {
    const api = setup();
    const typed = await errorOf(
      firstValueFrom(api.create({ ...INPUT, scopeIds: ['KN-2026-0040'] })),
    );
    expect(typed.status).toBe(400);
    expect(typed.error).toMatchObject({ title: 'Yêu cầu không hợp lệ' });
    expect(typed.error.errors).toBeUndefined();
    mockStepUp();
    const item = await errorOf(
      firstValueFrom(
        api.create({
          ...INPUT,
          scopeDetail: {
            items: [
              { profileId: 'ho-so-tu-go', table: 'product.acceptance', fields: ['c_factor'] },
            ],
          },
        }),
      ),
    );
    expect(item.status).toBe(400);
    expect((await firstValueFrom(api.create(INPUT))).scopeIds).toEqual([LOCKED_PROFILE.binhQ1]);
  });

  it('hồ sơ của kỳ đã khoá có trong danh sách hồ sơ theo kỳ (nguồn ô chọn hồ sơ SCR-1301)', async () => {
    setup();
    const page = await firstValueFrom(
      TestBed.inject(AppraisalApi).profiles({ periodId: 'p-2026q1', status: ['DA_KHOA'] }),
    );
    expect(page.items.map((r) => r.id)).toContain(LOCKED_PROFILE.binhQ1);
    expect(page.items.every((r) => r.period.id === 'p-2026q1' && r.status === 'DA_KHOA')).toBe(
      true,
    );
    const queue = await firstValueFrom(
      TestBed.inject(AppraisalApi).profiles({ periodId: 'p-2026q1' }),
    );
    expect(queue.items.map((r) => r.id)).not.toContain(LOCKED_PROFILE.binhQ1);
  });

  it('tính thử ảnh hưởng như Hình 11.7: 91,2 thành 92,1, hai biểu mẫu sinh lại', async () => {
    const api = setup();
    const impact = await firstValueFrom(api.simulate('rr-2026-007'));
    expect(impact.rows[0]).toMatchObject({ metric: 'Điểm', before: '91,2', after: '92,1' });
    expect(impact.formsToRegenerate).toBe(2);
  });

  it('duyệt hai lượt: step-up, If-Match, ý kiến; lượt 2 mở lại kỳ trong phạm vi', async () => {
    const api = setup();
    let r = await firstValueFrom(api.get('rr-2026-007'));
    const comment = 'Đồng ý mở lại theo kết luận khiếu nại.';
    expect((await errorOf(firstValueFrom(api.approve(r, comment)))).status).toBe(401);
    mockStepUp();
    expect((await errorOf(firstValueFrom(api.approve(r, 'ngắn')))).status).toBe(422);
    expect(
      (await errorOf(firstValueFrom(api.approve({ ...r, rowVersion: 1 }, comment)))).status,
    ).toBe(412);
    r = await firstValueFrom(api.approve(r, comment));
    expect(r).toMatchObject({ status: 'DA_DUYET', periodStatus: 'MO_LAI_HAU_KIEM' });
    expect(r.approvers?.[1].approval).toMatchObject({ decision: 'DONG_Y', mfaAcr: 'aal2' });
    expect(r.executedAt).toBeTruthy();
    expect((await errorOf(firstValueFrom(api.approve(r, comment)))).status).toBe(409);
  });

  it('BR-13-02: người đã duyệt lượt 1 hoặc người tạo không duyệt được (403, MSG-13-01)', async () => {
    const api = setup();
    mockStepUp();
    const comment = 'Đồng ý mở lại trong phạm vi một hồ sơ.';
    let r = await firstValueFrom(api.get('rr-2026-008'));
    r = await firstValueFrom(api.approve(r, comment));
    expect(r.status).toBe('CHO_DUYET_2');
    const same = await errorOf(firstValueFrom(api.approve(r, comment)));
    expect(same.status).toBe(403);
    expect(same.error).toMatchObject({ code: 'OVERSIGHT-403-FOUR-EYES', msg: 'MSG-13-01' });
    const own = await firstValueFrom(api.get('rr-2026-009'));
    expect((await errorOf(firstValueFrom(api.approve(own, comment)))).status).toBe(403);
  });

  it('từ chối có ý kiến; người tạo huỷ được yêu cầu của mình, không huỷ được của người khác', async () => {
    const api = setup();
    mockStepUp();
    const other = await firstValueFrom(api.get('rr-2026-008'));
    expect(
      (await errorOf(firstValueFrom(api.cancel(other, 'Không còn cần thiết nữa')))).status,
    ).toBe(403);
    const rejected = await firstValueFrom(api.reject(other, 'Căn cứ chưa đủ, đề nghị bổ sung.'));
    expect(rejected.status).toBe('TU_CHOI');
    expect(rejected.approvers?.[0].approval?.decision).toBe('TU_CHOI');
    const own = await firstValueFrom(api.get('rr-2026-009'));
    expect((await errorOf(firstValueFrom(api.cancel(own, 'ngắn')))).status).toBe(422);
    const closed = await firstValueFrom(api.cancel(own, 'Đã xử lý qua phiếu bổ sung khác.'));
    expect(closed.status).toBe('DA_DONG');
  });
});
