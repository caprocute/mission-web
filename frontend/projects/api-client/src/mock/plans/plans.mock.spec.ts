import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { Plan, PlanItem } from '../../lib/models/plan';
import { PlanApi } from '../../lib/plan-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { mockStepUp, setMockEsignOutage } from '../mock-session';

/**
 * WEB-026, WEB-027, WEB-065: hành vi giả lập SCR-0402 (kiểm tra BR-04-02 … BR-04-07, BR-04-10, lưu nháp,
 * gửi duyệt, điều chỉnh) và SCR-0403 (so sánh phiên bản, trả lại, duyệt và ký số) theo hợp đồng.
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

async function plan(api: PlanApi, id: string): Promise<Plan> {
  return (await firstValueFrom(api.list())).find((p) => p.id === id)!;
}

describe('mock plans – SCR-0402 (WEB-026)', () => {
  beforeEach(() => resetMockData());

  it('kế hoạch trong phạm vi, kỳ mới nhất trước; phiên bản 1 Quý I/2027 như Hình 7.13', async () => {
    const api = setup();
    const list = await firstValueFrom(api.list());
    expect(list.map((p) => [p.id, p.currentVersion.status])).toEqual([
      [Q1, 'NHAP'],
      [Q4, 'CHO_DUYET'],
    ]);
    expect(list[1]).toMatchObject({ effectiveVersionNo: 1, adjustmentsUsed: 1, adjustmentsMax: 2 });
    const ws = await firstValueFrom(api.workspace(list[0]));
    expect(ws.version.items).toHaveLength(6);
    expect(ws.version.items[0]).toMatchObject({
      catalogItemCode: 'TC-DV-01',
      standardScore: 20,
      leadAssignee: { kind: 'UNIT', id: 'u-btc', name: 'Ban Tổ chức' },
      coAssignees: [{ kind: 'UNIT', id: 'u-vp', name: 'Văn phòng Đảng uỷ' }],
    });
    expect(ws.version.groupWeights).toMatchObject({ THUONG_XUYEN: 30, THEN_CHOT: 70 });
    expect(ws.version.period.label).toBe('Quý I/2027');
    const q4 = await firstValueFrom(api.workspace(list[1]));
    expect(q4.version.versionNo).toBe(2);
    expect(q4.versions.map((v) => v.versionNo)).toEqual([1, 2]);
    expect(q4.version.items.map((i) => i.lineNo)).toEqual([1, 2, 3, 4, 5, 6, 7, 8]);
  });

  it('kiểm tra: KPI mơ hồ chặn, việc ngoài danh mục cảnh báo, KR chưa có nhiệm vụ cảnh báo', async () => {
    const api = setup();
    const ws = await firstValueFrom(api.workspace(await plan(api, Q1)));
    expect(ws.version.checks.map((c) => c.rule)).toContain('BR-04-03');
    const r = await firstValueFrom(api.check(Q1, 1, ws.version.items));
    const blocking = r.filter((i) => i.level === 'CHAN').map((i) => i.message);
    expect(blocking).toEqual([
      'KPI mơ hồ ở dòng 6: "kịp thời", "đẩy mạnh". Vui lòng ghi rõ sản phẩm, đơn vị tính và hạn.',
    ]);
    expect(r.find((c) => c.rule === 'BR-04-03')?.lineNo).toBe(6);
    expect(r.map((i) => i.message)).toContain('Dòng 4 chưa có điểm (việc ngoài danh mục)');
    expect(r.map((i) => i.message)).toContain('KR 2.1 chưa có nhiệm vụ');
    // thêm chủ trì trùng phối hợp; điểm dòng là trọng số trong nhóm nên không vượt tỷ trọng
    const more: PlanItem[] = [
      ...ws.version.items.slice(0, 5),
      {
        ...ws.version.items[0],
        id: undefined,
        catalogItemId: 'ci-tc-tc-02',
        coAssignees: [ws.version.items[0].leadAssignee],
      },
    ];
    const msgs = (await firstValueFrom(api.check(Q1, 1, more))).map((i) => i.message);
    expect(msgs).toContain('Dòng 6 có 2 đơn vị chủ trì. Mỗi việc chỉ có đúng một chủ trì.');
    expect(msgs.some((x) => x.includes('vượt điểm tối đa'))).toBe(false);
  });

  it('lưu nháp cần If-Match đúng; gửi duyệt bị chặn khi còn lỗi, đạt thì CHO_DUYET có mã băm', async () => {
    const api = setup();
    const ws = await firstValueFrom(api.workspace(await plan(api, Q1)));
    const fixed = ws.version.items.map((i) =>
      i.lineNo === 6
        ? {
            ...i,
            title: 'Biên tập 3 bản tin công tác tổ chức',
            productName: 'Bản tin',
            quantity: 3,
            unit: 'số',
            recurrence: 'HANG_THANG' as const,
          }
        : i,
    );
    expect((await errorOf(firstValueFrom(api.submit(Q1, ws.version)))).status).toBe(422);
    const saved = await firstValueFrom(api.save(Q1, ws.version, fixed));
    expect(saved.rowVersion).toBe(ws.version.rowVersion + 1);
    expect(saved.checks.filter((c) => c.level === 'CHAN')).toEqual([]);
    expect((await errorOf(firstValueFrom(api.save(Q1, ws.version, fixed)))).status).toBe(409);
    const sent = await firstValueFrom(api.submit(Q1, saved));
    expect(sent.status).toBe('CHO_DUYET');
    expect(sent.contentHash).toMatch(/^[0-9a-f]{64}$/);
  });

  it('so sánh phiên bản 2 chờ duyệt với phiên bản 1 đã duyệt như Hình 7.14', async () => {
    const api = setup();
    const rv = await firstValueFrom(api.review(Q4));
    expect(rv.base?.versionNo).toBe(1);
    const d = rv.diff;
    expect(d).toMatchObject({
      baseVersionNo: 1,
      versionNo: 2,
      added: 1,
      modified: 1,
      removed: 1,
      adjustmentNo: 1,
      adjustmentsMax: 2,
    });
    expect(d.lines.filter((l) => l.change === 'KHONG_DOI')).toHaveLength(6);
    const changed = d.lines.filter((r) => r.change !== 'KHONG_DOI');
    expect(changed.map((r) => [r.change, (r.after ?? r.before)!.title, r.reason])).toEqual([
      [
        'THEM',
        'Rà soát quy hoạch cấp uỷ nhiệm kỳ 2025–2030 tại 46 tổ chức đảng trực thuộc',
        'Văn bản 518-CV/ĐUK ngày 14/10',
      ],
      ['SUA', 'Thẩm định hồ sơ kết nạp đảng viên đợt 2', 'Chi bộ 2, 7 bổ sung hồ sơ'],
      ['BO', 'Tập huấn nghiệp vụ cho chi bộ cơ sở', 'Lùi sang Quý I/2027 theo chỉ đạo'],
    ]);
    expect(changed[1].changedFields).toEqual(['quantity']);
    expect([changed[1].before!.quantity, changed[1].after!.quantity]).toEqual([15, 18]);
    expect(rv.version.contentHash).toMatch(/^[0-9a-f]{64}$/);
    expect(rv.version.checks.filter((c) => c.level === 'CHAN')).toEqual([]);
    expect(rv.summary).toMatchObject({ submittedByName: 'Lê Minh Châu' });
    // phiên bản đầu: so với kế hoạch rỗng, mọi dòng là THEM (giả định WEB-065)
    const first = await firstValueFrom(api.review(Q1));
    expect(first.base).toBeUndefined();
    expect(first.diff).toMatchObject({ baseVersionNo: 0, added: 6, modified: 0, removed: 0 });
    expect(new Set(first.diff.lines.map((l) => l.change))).toEqual(new Set(['THEM']));
  });

  it('trả lại cần ý kiến và If-Match; kế hoạch thành TRA_LAI', async () => {
    const api = setup();
    const v = (await firstValueFrom(api.review(Q4))).version;
    expect((await errorOf(firstValueFrom(api.returnPlan(Q4, 2, v.rowVersion, ' ')))).status).toBe(
      422,
    );
    const r = await firstValueFrom(api.returnPlan(Q4, 2, v.rowVersion, 'Làm rõ sản phẩm dòng 5'));
    expect(r).toMatchObject({ status: 'TRA_LAI', reviewComment: 'Làm rõ sản phẩm dòng 5' });
    expect((await errorOf(firstValueFrom(api.returnPlan(Q4, 2, r.rowVersion, 'x')))).status).toBe(
      409,
    );
  });

  it('duyệt: MFA bước lên (401), 202 Job, ký trên MySign rồi DA_DUYET; điều chỉnh tối đa 2 lần', async () => {
    const api = setup();
    const rv = (await firstValueFrom(api.version(Q4, 2))).rowVersion;
    const e = await errorOf(firstValueFrom(api.approve(Q4, 2, rv, { signMethod: 'MYSIGN' })));
    expect(e.status).toBe(401);
    expect(e.headers.get('WWW-Authenticate')).toContain('insufficient_user_authentication');
    mockStepUp();
    expect(
      (await errorOf(firstValueFrom(api.approve(Q4, 2, rv, { signMethod: 'USB_TOKEN' })))).status,
    ).toBe(422);
    const { location, job } = await firstValueFrom(
      api.approve(Q4, 2, rv, { signMethod: 'MYSIGN', comment: 'Đồng ý' }),
    );
    expect(job.status).toBe('DANG_CHAY');
    expect(location).toBe(`/api/v1/jobs/${job.id}`);
    const signId = (await firstValueFrom(api.version(Q4, 2))).signRequestId!;
    expect((await firstValueFrom(api.signRequest(signId))).status).toBe('CHO_XAC_NHAN');
    expect((await firstValueFrom(api.signRequest(signId))).status).toBe('DA_KY');
    const p = await plan(api, Q4);
    expect(p.effectiveVersionNo).toBe(2);
    const ws = await firstValueFrom(api.workspace(p));
    expect(ws.version).toMatchObject({ status: 'DA_DUYET', reviewComment: 'Đồng ý' });
    expect(ws.versions[1].approvedAt).toBeTruthy();
    // BR-04-10: lý do ghi ở từng dòng đổi
    expect((await errorOf(firstValueFrom(api.createAdjustment(Q4, ' ')))).status).toBe(422);
    const v3 = await firstValueFrom(api.createAdjustment(Q4, 'Bổ sung theo chỉ đạo mới'));
    expect(v3).toMatchObject({
      versionNo: 3,
      status: 'NHAP',
      changeReason: 'Bổ sung theo chỉ đạo mới',
    });
    expect(v3.items[0].originItemId).toBe('pi-q4-v2-01');
    const edited = v3.items.map((i, n) => (n === 0 ? { ...i, quantity: 20 } : i));
    const saved = await firstValueFrom(api.save(Q4, v3, edited));
    expect(saved.checks.find((c) => c.rule === 'BR-04-10')).toMatchObject({ lineNo: 1 });
    expect((await errorOf(firstValueFrom(api.submit(Q4, saved)))).status).toBe(422);
    // BR-04-10: bỏ dòng gốc cũng cần lý do, gửi qua removedItems
    const origin = v3.items[1].originItemId!;
    const rest = edited.map((i, n) => (n === 0 ? { ...i, changeReason: 'Tăng chỉ tiêu' } : i));
    const dropped = rest.filter((_, n) => n !== 1);
    const noReason = await firstValueFrom(api.check(Q4, 3, dropped));
    expect(noReason.find((c) => c.rule === 'BR-04-10')).toBeTruthy();
    const withReason = await firstValueFrom(
      api.save(Q4, saved, dropped, [{ originItemId: origin, changeReason: 'Không còn phù hợp' }]),
    );
    expect(withReason.checks.filter((c) => c.rule === 'BR-04-10')).toEqual([]);
    expect(withReason.removedItems).toEqual([
      { originItemId: origin, changeReason: 'Không còn phù hợp' },
    ]);
    expect((await errorOf(firstValueFrom(api.createAdjustment(Q4, 'Lần ba')))).status).toBe(409);
  });

  it('phiên bản: chức danh người gửi, người duyệt, Policy Pack, kỳ trước và so sánh với base=0', async () => {
    const api = setup();
    expect((await plan(api, Q1)).previousPeriod?.label).toBe('Quý IV/2026');
    const p = await plan(api, Q4);
    expect(p.previousPeriod).toBeNull();
    const ws = await firstValueFrom(api.workspace(p));
    expect(ws.versions[0]).toMatchObject({
      submittedByTitle: 'Phó Trưởng ban',
      approvedByName: 'Nguyễn Hữu Thắng',
    });
    expect(ws.version.groupWeights).toMatchObject({ flow: 'DANG', policyPackCode: 'PP-KHOI-DANG' });
    expect(ws.version.changeReason).toContain('518-CV/ĐUK');
    const v1 = await firstValueFrom(api.version(Q4, 1));
    const d = await firstValueFrom(api.diff(Q4, v1, 0));
    expect(d).toMatchObject({ baseVersionNo: 0, added: v1.items.length, removed: 0 });
    expect(d.lines.every((r) => r.change === 'THEM')).toBe(true);
  });

  it('BR-04-08: người lập không tự duyệt; esign gián đoạn thì yêu cầu ký báo lỗi', async () => {
    const api = setup();
    mockStepUp();
    const ws = await firstValueFrom(api.workspace(await plan(api, Q1)));
    const saved = await firstValueFrom(api.save(Q1, ws.version, ws.version.items.slice(0, 5)));
    const sent = await firstValueFrom(api.submit(Q1, saved));
    const self = await errorOf(
      firstValueFrom(api.approve(Q1, 1, sent.rowVersion, { signMethod: 'MYSIGN' })),
    );
    expect(self.status).toBe(403);
    setMockEsignOutage(true);
    const rv = (await firstValueFrom(api.version(Q4, 2))).rowVersion;
    const { job } = await firstValueFrom(api.approve(Q4, 2, rv, { signMethod: 'MYSIGN' }));
    expect(job.status).toBe('THAT_BAI');
    const signId = (await firstValueFrom(api.version(Q4, 2))).signRequestId!;
    expect((await firstValueFrom(api.signRequest(signId))).status).toBe('LOI');
  });

  it('BR-04-05: người duyệt định điểm việc ngoài danh mục của phiên bản chờ duyệt', async () => {
    const api = setup();
    const ws = await firstValueFrom(api.workspace(await plan(api, Q1)));
    const saved = await firstValueFrom(api.save(Q1, ws.version, ws.version.items.slice(0, 5)));
    const sent = await firstValueFrom(api.submit(Q1, saved));
    const off = sent.items.find((i) => !i.catalogItemId)!;
    const inCatalog = sent.items.find((i) => i.catalogItemId)!;
    expect(off.pendingScore).toBe(true);
    const body = { difficulty: 3, standardScore: 7.5 };
    const scored = await firstValueFrom(api.scoreItem(Q1, 1, off.id!, sent.rowVersion, body));
    expect(scored.items.find((i) => i.id === off.id)).toMatchObject({
      standardScore: 7.5,
      pendingScore: false,
    });
    expect(scored.rowVersion).toBe(sent.rowVersion + 1);
    expect(
      (await errorOf(firstValueFrom(api.scoreItem(Q1, 1, off.id!, sent.rowVersion, body)))).status,
    ).toBe(409);
    expect(
      (await errorOf(firstValueFrom(api.scoreItem(Q1, 1, inCatalog.id!, scored.rowVersion, body))))
        .status,
    ).toBe(422);
  });

  it('gợi ý từ OKR cho KR chưa có dòng, đủ trường bắt buộc của PlanItemInput', async () => {
    const api = setup();
    const s = await firstValueFrom(api.suggestions(Q1));
    expect(s.map((i) => i.keyResultId)).toEqual(['kr-2-1']);
    expect(s[0]).toMatchObject({ leadAssignee: { id: 'u-btc' }, quantity: 0 });
    expect(s[0].dueDate).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });
});
