import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { PeriodApi } from '../../lib/period-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { mockSession, mockStepUp } from '../mock-session';

/** WEB-034: giả lập kỳ đánh giá và saga khoá kỳ SCR-0801 (QT-08). */
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
  return TestBed.inject(PeriodApi);
}

async function fails(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('Mong đợi lỗi');
}

describe('mock periods – SCR-0801 (WEB-034)', () => {
  beforeEach(() => {
    resetMockData();
    mockSession.reset();
  });

  it('danh sách kỳ mới nhất trước, kèm lệnh khoá kỳ hiện hành; lọc theo trạng thái', async () => {
    const api = setup();
    const all = await firstValueFrom(api.list());
    expect(all[0].name).toBe('Quý I/2027');
    expect(all[0].notStarted).toBe(true);
    const q3 = all.find((p) => p.id === 'p-2026q3')!;
    expect(q3.status).toBe('DANG_KHOA');
    expect(q3.currentLockOrder?.idempotencyKey).toBe('CK-2026Q3');
    const locked = await firstValueFrom(api.list({ status: 'DA_KHOA' }));
    expect(locked.map((p) => p.name)).toEqual(['Quý II/2026', 'Quý I/2026']);
  });

  it('tạo kỳ: chồng lấn cùng loại trả 409, kỳ mới ghim Policy Pack và cảnh báo khoá muộn', async () => {
    const api = setup();
    const overlap = await fails(
      firstValueFrom(
        api.create({
          code: '2026-Q4b',
          name: 'Quý IV/2026 (trùng)',
          kind: 'QUY',
          flow: 'DANG',
          startDate: '2026-11-01',
          endDate: '2027-01-31',
        }),
      ),
    );
    expect(overlap.status).toBe(409);
    expect(overlap.error.code).toBe('PERIOD-409-OVERLAP');
    const p = await firstValueFrom(
      api.create({
        code: '2027-Q2',
        name: 'Quý II/2027',
        kind: 'QUY',
        flow: 'CA_HAI',
        startDate: '2027-04-01',
        endDate: '2027-06-30',
        milestones: { plannedLockDate: '2027-07-15' },
      }),
    );
    expect(p.status).toBe('MO');
    expect(p.notStarted).toBe(true);
    expect(p.pinnedPolicyPacks?.length).toBe(2);
    expect(p.warnings?.[0]).toContain('muộn hơn');
  });

  it('lập lệnh rồi tự phê duyệt bị chặn BR-08-01; lệnh của người khác cần MFA rồi DA_DUYET', async () => {
    const api = setup();
    const o = await firstValueFrom(
      api.createLockOrder('p-2027q1', { basisDoc: 'Thông báo 30-TB/ĐUK' }),
    );
    expect(o.status).toBe('CHO_DUYET');
    expect(o.idempotencyKey).toBe('CK-2027Q1');
    mockStepUp();
    const self = await fails(firstValueFrom(api.approveLockOrder('p-2027q1', o)));
    expect(self.status).toBe(403);
    expect(self.error.code).toBe('PERIOD-403-SAME-PERSON');
    const again = await fails(
      firstValueFrom(api.createLockOrder('p-2027q1', { basisDoc: 'Trùng' })),
    );
    expect(again.error.code).toBe('PERIOD-409-ORDER-EXISTS');

    mockSession.reset();
    const q4 = await firstValueFrom(api.get('p-2026q4'));
    const noMfa = await fails(
      firstValueFrom(api.approveLockOrder('p-2026q4', q4.currentLockOrder!)),
    );
    expect(noMfa.status).toBe(401);
    mockStepUp();
    const ok = await firstValueFrom(api.approveLockOrder('p-2026q4', q4.currentLockOrder!));
    expect(ok.status).toBe('DA_DUYET');
    expect(ok.approvedBy?.id).toBe('dev-user');
    // người phê duyệt không được khởi chạy (BR-08-01)
    const start = await fails(firstValueFrom(api.start('p-2026q4', ok)));
    expect(start.error.code).toBe('PERIOD-403-SAME-PERSON');
  });

  it('khởi chạy kỳ Tháng 9: 202 + job, kỳ DANG_KHOA, saga chạy tới DA_KHOA qua các nhịp', async () => {
    const api = setup();
    const p = await firstValueFrom(api.get('p-2026m09'));
    mockStepUp();
    const res = await firstValueFrom(api.start(p.id, p.currentLockOrder!));
    expect(res.location).toMatch(/^\/api\/v1\/jobs\//);
    expect(res.job.type).toBe('PERIOD_LOCK');
    expect(res.job.status).toBe('DANG_CHAY');
    expect((await firstValueFrom(api.get(p.id))).status).toBe('DANG_KHOA');
    let pr = await firstValueFrom(api.progress(p.id));
    expect(pr.steps[0].status).toBe('DAT_CO_CANH_BAO');
    expect(pr.steps[2].status).toBe('DANG_CHAY');
    for (let i = 0; i < 20 && pr.sagaStatus === 'DANG_CHAY'; i++)
      pr = await firstValueFrom(api.progress(p.id));
    expect(pr.sagaStatus).toBe('HOAN_TAT');
    expect(pr.steps.every((s) => s.status === 'DAT' || s.status === 'DAT_CO_CANH_BAO')).toBe(true);
    expect(pr.lockOrder.status).toBe('HOAN_TAT');
    const after = await firstValueFrom(api.get(p.id));
    expect(after.status).toBe('DA_KHOA');
    expect(after.lockedBy?.id).toBe('dev-user');
    const ev = await firstValueFrom(api.events(p.id));
    expect(ev[0].evt).toBe('EVT-19');
    expect(ev.some((e) => e.evt === 'EVT-09')).toBe(true);
  });

  it('bước 0 không đạt khi chưa qua hạn nghiệm thu tồn đọng: job THAT_BAI, MSG-08-02', async () => {
    const api = setup();
    const y = await firstValueFrom(api.get('p-2026'));
    mockStepUp();
    const res = await firstValueFrom(api.start(y.id, y.currentLockOrder!));
    expect(res.job.status).toBe('THAT_BAI');
    expect(res.job.problem?.code).toBe('PERIOD-422-PRECONDITION');
    expect(res.job.problem?.detail).toBe(
      'Chưa thể khởi chạy: chưa qua hạn nghiệm thu tồn đọng (08/01/2027).',
    );
    const after = await firstValueFrom(api.get(y.id));
    expect(after.status).toBe('MO');
    expect(after.currentLockOrder?.status).toBe('DA_DUYET');
  });

  it('bước 2 chờ hàng đợi lỗi rỗng; chạy lại hồ sơ lỗi rồi tạm dừng sau bước hiện tại', async () => {
    const api = setup();
    let pr = await firstValueFrom(api.progress('p-2026q3'));
    for (let i = 0; i < 5; i++) pr = await firstValueFrom(api.progress('p-2026q3'));
    expect(pr.totals.scored + pr.totals.failed).toBe(pr.totals.profiles);
    expect(pr.steps[2].status).toBe('DANG_CHAY');
    expect((await firstValueFrom(api.failures('p-2026q3'))).length).toBe(17);

    expect((await fails(firstValueFrom(api.retryFailed('p-2026q3')))).status).toBe(401);
    mockStepUp();
    const retry = await firstValueFrom(api.retryFailed('p-2026q3'));
    expect(retry.job.type).toBe('PERIOD_LOCK_RETRY');
    expect(await firstValueFrom(api.failures('p-2026q3'))).toEqual([]);
    const none = await fails(firstValueFrom(api.retryFailed('p-2026q3')));
    expect(none.error.code).toBe('PERIOD-409-NO-FAILURE');

    pr = await firstValueFrom(api.pause('p-2026q3', pr.lockOrder));
    expect(pr.pauseRequested).toBe(true);
    pr = await firstValueFrom(api.progress('p-2026q3'));
    expect(pr.steps[2].status).toBe('DAT');
    expect(pr.sagaStatus).toBe('CHO_SU_KIEN');
    expect(pr.steps[3].status).toBe('CHO');
    pr = await firstValueFrom(api.resume('p-2026q3', pr.lockOrder));
    expect(pr.sagaStatus).toBe('DANG_CHAY');
    expect(pr.steps[3].status).toBe('DANG_CHAY');
  });

  it('huỷ và bù trừ: đề nghị chờ người duyệt thứ hai, tự duyệt bị chặn (BR-08-09)', async () => {
    const api = setup();
    const pr0 = await firstValueFrom(api.progress('p-2026q3'));
    expect(pr0.cancellable).toBe(true);
    mockStepUp();
    const short = await fails(firstValueFrom(api.requestCancel('p-2026q3', pr0.lockOrder, 'sai')));
    expect(short.status).toBe(422);
    const pr = await firstValueFrom(
      api.requestCancel('p-2026q3', pr0.lockOrder, 'Sai trọng số khối 3 trong Policy Pack'),
    );
    expect(pr.cancelRequest?.requestedBy.id).toBe('dev-user');
    const self = await fails(firstValueFrom(api.approveCancel('p-2026q3', pr.lockOrder)));
    expect(self.error.code).toBe('PERIOD-403-SAME-PERSON');
  });

  it('duyệt thứ hai đề nghị huỷ của Tháng 8: bù trừ ngược từng bước, kỳ về MO, lệnh DA_HUY', async () => {
    const api = setup();
    let pr = await firstValueFrom(api.progress('p-2026m08'));
    expect(pr.sagaStatus).toBe('CHO_SU_KIEN');
    expect(pr.cancelRequest?.requestedBy.name).toBe('Hoàng Đức Dũng');
    mockStepUp();
    const res = await firstValueFrom(api.approveCancel('p-2026m08', pr.lockOrder));
    expect(res.job.type).toBe('PERIOD_UNLOCK');
    pr = await firstValueFrom(api.progress('p-2026m08'));
    expect(pr.sagaStatus).toBe('DANG_BU_TRU');
    expect(pr.steps[2].status).toBe('DA_BU_TRU');
    for (let i = 0; i < 5 && pr.sagaStatus === 'DANG_BU_TRU'; i++)
      pr = await firstValueFrom(api.progress('p-2026m08'));
    expect(pr.sagaStatus).toBe('DA_BU_TRU');
    expect(pr.steps.map((s) => s.status)).toEqual([
      'DAT_CO_CANH_BAO',
      'DA_BU_TRU',
      'DA_BU_TRU',
      'CHO',
      'CHO',
      'CHO',
    ]);
    expect(pr.lockOrder.status).toBe('DA_HUY');
    expect((await firstValueFrom(api.get('p-2026m08'))).status).toBe('MO');
    expect((await firstValueFrom(api.events('p-2026m08')))[0].event).toContain(
      'trở về trạng thái Đang mở',
    );
  });

  it('If-Match cũ trả 412; sơ đồ đánh dấu hoạt động đang chạy và đã xong', async () => {
    const api = setup();
    const pr = await firstValueFrom(api.progress('p-2026q3'));
    mockStepUp();
    const stale = await fails(
      firstValueFrom(api.pause('p-2026q3', { ...pr.lockOrder, rowVersion: 1 })),
    );
    expect(stale.status).toBe(412);
    const d = await firstValueFrom(api.diagram('p-2026q3'));
    expect(d.activeActivityIds).toEqual(['buoc2TinhDiem']);
    expect(d.completedActivityIds).toEqual(['buoc0TienDieuKien', 'buoc1KhoaKy']);
    expect(await fails(firstValueFrom(api.progress('p-2027q1')))).toMatchObject({ status: 404 });
  });
});
