import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { PlanItem } from '../../lib/models/plan';
import { PlanApi } from '../../lib/plan-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { mockStepUp, setMockEsignOutage } from '../mock-session';

/**
 * WEB-026, WEB-027: hành vi giả lập SCR-0402 (kiểm tra BR-04-02 … BR-04-07, lưu nháp, gửi duyệt, điều
 * chỉnh) và SCR-0403 (so sánh phiên bản, trả lại, duyệt và ký số).
 */
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
  return TestBed.inject(PlanApi);
}

async function errorOf(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('không có lỗi');
}

const Q1 = 'plan-2027-q1';
const Q4 = 'plan-2026-q4';

describe('mock plans – SCR-0402 (WEB-026)', () => {
  beforeEach(() => resetMockData());

  it('kế hoạch của đơn vị, kỳ mới nhất trước; phiên bản 1 Quý I/2027 như Hình 7.13', async () => {
    const api = setup();
    const mine = await firstValueFrom(api.mine());
    expect(mine.map((p) => [p.id, p.latestStatus])).toEqual([
      [Q1, 'NHAP'],
      [Q4, 'CHO_DUYET'],
    ]);
    const ws = await firstValueFrom(api.workspace(Q1));
    expect(ws.version.items).toHaveLength(6);
    expect(ws.version.items[0]).toMatchObject({ catalogItemCode: 'TC-DV-01', standardScore: 20 });
    expect(ws.groupWeights).toMatchObject({ THUONG_XUYEN: 30, THEN_CHOT: 70 });
    const q4 = await firstValueFrom(api.workspace(Q4));
    expect(q4.version.versionNo).toBe(2);
    expect(q4.adjustmentsUsed).toBe(1);
    expect(q4.version.items.map((i) => i.itemNo)).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
  });

  it('kiểm tra: KPI mơ hồ chặn, việc ngoài danh mục cảnh báo, KR chưa có nhiệm vụ cảnh báo', async () => {
    const api = setup();
    const ws = await firstValueFrom(api.workspace(Q1));
    const r = await firstValueFrom(api.check(Q1, 1, ws.version.items));
    const blocking = r.items.filter((i) => i.level === 'CHAN' && !i.passed).map((i) => i.message);
    expect(blocking).toEqual([
      'KPI mơ hồ ở dòng 6: "kịp thời", "đẩy mạnh". Vui lòng ghi rõ sản phẩm, đơn vị tính và hạn.',
    ]);
    expect(r.items.map((i) => i.message)).toContain('Dòng 4 chưa có điểm (việc ngoài danh mục)');
    expect(r.items.map((i) => i.message)).toContain('KR 2.1 chưa có nhiệm vụ');
    expect(r.groupTotals).toEqual({ THUONG_XUYEN: 5, THEN_CHOT: 70 });
    // thêm chủ trì trùng phối hợp và vượt tỷ trọng
    const more: PlanItem[] = [
      ...ws.version.items.slice(0, 5),
      { ...ws.version.items[0], id: '', catalogItemId: 'ci-tc-tc-02', coUnitIds: ['u-btc'] },
    ];
    const r2 = await firstValueFrom(api.check(Q1, 1, more));
    const msgs = r2.items.filter((i) => !i.passed).map((i) => i.message);
    expect(msgs).toContain('Dòng 6 có 2 đơn vị chủ trì. Mỗi việc chỉ có đúng một chủ trì.');
    expect(msgs).toContain('Tổng điểm nhóm then chốt là 105, vượt điểm tối đa 70.');
  });

  it('lưu nháp cần If-Match đúng; gửi duyệt bị chặn khi còn lỗi, đạt thì CHO_DUYET có mã băm', async () => {
    const api = setup();
    const ws = await firstValueFrom(api.workspace(Q1));
    const fixed = ws.version.items.map((i) =>
      i.itemNo === 6
        ? {
            ...i,
            title: 'Biên tập 3 bản tin công tác tổ chức',
            productName: 'Bản tin',
            plannedQuantity: 3,
            unit: 'số',
            recurrence: 'HANG_THANG' as const,
          }
        : i,
    );
    expect((await errorOf(firstValueFrom(api.submit(Q1, ws.version)))).status).toBe(422);
    const saved = await firstValueFrom(api.save(Q1, ws.version, fixed));
    expect(saved.rowVersion).toBe(ws.version.rowVersion + 1);
    expect((await errorOf(firstValueFrom(api.save(Q1, ws.version, fixed)))).status).toBe(409);
    const sent = await firstValueFrom(api.submit(Q1, saved));
    expect(sent.status).toBe('CHO_DUYET');
    expect(sent.contentHash).toMatch(/^[0-9a-f]{64}$/);
  });

  it('so sánh phiên bản 2 chờ duyệt với phiên bản 1 đã duyệt như Hình 7.14', async () => {
    const api = setup();
    const d = await firstValueFrom(api.diff(Q4, 2));
    expect(d.base?.versionNo).toBe(1);
    expect(d.summary).toEqual({ added: 1, changed: 1, removed: 1, unchanged: 6 });
    expect(d).toMatchObject({
      adjustmentNo: 1,
      adjustmentsMax: 2,
      keyGroupMax: { before: 70, after: 70 },
    });
    const changed = d.rows.filter((r) => r.change !== 'GIU');
    expect(changed.map((r) => [r.change, (r.after ?? r.before)!.title, r.reason])).toEqual([
      [
        'THEM',
        'Rà soát quy hoạch cấp uỷ nhiệm kỳ 2025–2030 tại 46 tổ chức đảng trực thuộc',
        'Văn bản 518-CV/ĐUK ngày 14/10',
      ],
      ['SUA', 'Thẩm định hồ sơ kết nạp đảng viên đợt 2', 'Chi bộ 2, 7 bổ sung hồ sơ'],
      ['BO', 'Tập huấn nghiệp vụ cho chi bộ cơ sở', 'Lùi sang Quý I/2027 theo chỉ đạo'],
    ]);
    expect(changed[1].changedFields).toEqual(['plannedQuantity']);
    expect([changed[1].before!.plannedQuantity, changed[1].after!.plannedQuantity]).toEqual([
      15, 18,
    ]);
    expect(d.target.contentHash).toMatch(/^[0-9a-f]{64}$/);
  });

  it('trả lại cần ý kiến và If-Match; kế hoạch thành TRA_LAI', async () => {
    const api = setup();
    const d = await firstValueFrom(api.diff(Q4, 2));
    expect(
      (await errorOf(firstValueFrom(api.returnPlan(Q4, 2, d.target.rowVersion, ' ')))).status,
    ).toBe(422);
    const r = await firstValueFrom(
      api.returnPlan(Q4, 2, d.target.rowVersion, 'Làm rõ sản phẩm dòng 5'),
    );
    expect(r).toMatchObject({ status: 'TRA_LAI', reviewComment: 'Làm rõ sản phẩm dòng 5' });
    expect((await errorOf(firstValueFrom(api.returnPlan(Q4, 2, r.rowVersion, 'x')))).status).toBe(
      409,
    );
  });

  it('duyệt: MFA bước lên (401), ký trên MySign rồi DA_DUYET; sau đó điều chỉnh tối đa 2 lần', async () => {
    const api = setup();
    const rv = (await firstValueFrom(api.diff(Q4, 2))).target.rowVersion;
    const e = await errorOf(firstValueFrom(api.approve(Q4, 2, rv, { signMethod: 'MYSIGN' })));
    expect(e.status).toBe(401);
    expect(e.headers.get('WWW-Authenticate')).toContain('insufficient_user_authentication');
    mockStepUp();
    expect(
      (await errorOf(firstValueFrom(api.approve(Q4, 2, rv, { signMethod: 'USB_TOKEN' })))).status,
    ).toBe(422);
    const { location, request } = await firstValueFrom(
      api.approve(Q4, 2, rv, { signMethod: 'MYSIGN', comment: 'Đồng ý' }),
    );
    expect(request.status).toBe('CHO_KY');
    expect(location).toBe(`/api/v1/esign/requests/${request.id}`);
    expect((await firstValueFrom(api.signRequest(location))).status).toBe('CHO_KY');
    expect((await firstValueFrom(api.signRequest(location))).status).toBe('DA_KY');
    const ws = await firstValueFrom(api.workspace(Q4));
    expect(ws.version).toMatchObject({
      status: 'DA_DUYET',
      isCurrent: true,
      reviewComment: 'Đồng ý',
    });
    expect(ws.versions.filter((v) => v.isCurrent).map((v) => v.versionNo)).toEqual([2]);
    // BR-04-10
    expect((await errorOf(firstValueFrom(api.createAdjustment(Q4, ' ')))).status).toBe(422);
    const v3 = await firstValueFrom(api.createAdjustment(Q4, 'Bổ sung việc theo chỉ đạo'));
    expect(v3).toMatchObject({ versionNo: 3, status: 'NHAP' });
    expect(v3.items[0].originItemId).toBe('pi-q4-v2-01');
    expect((await errorOf(firstValueFrom(api.createAdjustment(Q4, 'Lần nữa')))).status).toBe(409);
  });

  it('BR-04-08: người lập không tự duyệt; esign gián đoạn thì yêu cầu ký chờ gửi lại', async () => {
    const api = setup();
    mockStepUp();
    const ws = await firstValueFrom(api.workspace(Q1));
    const fixed = ws.version.items.slice(0, 5);
    const saved = await firstValueFrom(api.save(Q1, ws.version, fixed));
    const sent = await firstValueFrom(api.submit(Q1, saved));
    const self = await errorOf(
      firstValueFrom(api.approve(Q1, 1, sent.rowVersion, { signMethod: 'MYSIGN' })),
    );
    expect(self.status).toBe(403);
    setMockEsignOutage(true);
    const rv = (await firstValueFrom(api.diff(Q4, 2))).target.rowVersion;
    const { request } = await firstValueFrom(api.approve(Q4, 2, rv, { signMethod: 'MYSIGN' }));
    expect(request.status).toBe('CHO_GUI_LAI');
  });

  it('gợi ý từ OKR cho KR chưa có dòng', async () => {
    const api = setup();
    const s = await firstValueFrom(api.suggestions(Q1));
    expect(s.map((i) => i.keyResultId)).toEqual(['kr-2-1']);
  });
});
