import { AssignCheckItem } from '../../lib/models/task-assign';
import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { TaskApi } from '../../lib/task-api';
import { CatalogApi } from '../../lib/catalog-api';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { coefficientC, workingDaysBetween } from './tasks.mock';

/** WEB-030: hành vi giả lập SCR-0503 (bộ lọc nhanh, nhận việc, hàng loạt, đăng ký, xuất Excel). */
function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      provideHttpClientTesting(),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
    ],
  });
  return { api: TestBed.inject(TaskApi), catalog: TestBed.inject(CatalogApi) };
}

async function statusOf(p: Promise<unknown>): Promise<{ status: number; code?: string }> {
  try {
    await p;
  } catch (e) {
    const err = e as HttpErrorResponse;
    return { status: err.status, code: (err.error as { code?: string })?.code };
  }
  throw new Error('Mong đợi lỗi HTTP');
}

const ID_DA_GIAO = '0192a0f0-0001-7000-8000-000000000002';
const ID_PHOI_HOP = '0192a0f0-0001-7000-8000-000000000003';
const ID_157 = '0192a0f0-0001-7000-8000-000000000157';
const ID_142 = '0192a0f0-0001-7000-8000-000000000142';

describe('mock tasks – SCR-0503', () => {
  beforeEach(() => resetMockData());

  it('trả số đếm bộ lọc nhanh, kỳ và danh sách KR; bộ lọc Chậm khớp số đếm', async () => {
    const { api } = setup();
    const all = await firstValueFrom(api.myTasks());
    expect(all.period.label).toBe('Quý IV/2026');
    expect(all.counts.TAT_CA).toBe(10);
    expect(all.counts.CHAM).toBe(2);
    expect(all.counts.CHO_NGHIEM_THU).toBe(1);
    expect(all.counts.HOAN_THANH).toBe(1);
    expect(all.keyResults.map((k) => k.id).sort()).toEqual(['kr-1-1', 'kr-1-2', 'kr-2-1']);
    const slow = await firstValueFrom(api.myTasks({ filter: 'CHAM' }));
    expect(slow.items.length).toBe(all.counts.CHAM);
    expect(slow.items.every((t) => t.overdue && t.workingDaysLeft <= 0)).toBe(true);
    const soon = await firstValueFrom(api.myTasks({ filter: 'SAP_HAN' }));
    expect(soon.items.every((t) => !t.overdue && t.workingDaysLeft <= 3)).toBe(true);
  });

  it('lọc theo vai trò, nhóm, KR; số đếm theo cùng điều kiện', async () => {
    const { api } = setup();
    const ph = await firstValueFrom(api.myTasks({ role: 'PHOI_HOP' }));
    expect(ph.items.every((t) => t.role === 'PHOI_HOP')).toBe(true);
    expect(ph.counts.TAT_CA).toBe(ph.items.length);
    const kr = await firstValueFrom(api.myTasks({ keyResultId: 'kr-2-1', group: 'THEN_CHOT' }));
    expect(kr.items.map((t) => t.code).sort()).toEqual(['NV-2026-Q4-0001', 'NV-2026-Q4-0004']);
  });

  it('nhận việc: DA_GIAO → DANG_THUC_HIEN; người phối hợp bị 403', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.detail(ID_DA_GIAO));
    const u = await firstValueFrom(api.accept(t));
    expect(u.status).toBe('DANG_THUC_HIEN');
    expect(u.rowVersion).toBe(t.rowVersion + 1);
    const again = await statusOf(firstValueFrom(api.accept(u)));
    expect(again.status).toBe(422);
    const ph = await firstValueFrom(api.detail(ID_PHOI_HOP));
    expect((await statusOf(firstValueFrom(api.accept(ph)))).status).toBe(403);
  });

  it('BR-06-02: giảm tiến độ không ghi chú trả 422 MSG-06-01; BR-06-03: phối hợp không đổi phần trăm', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.detail(ID_157));
    expect(
      await statusOf(firstValueFrom(api.updateProgress(t, { percent: 30, note: ' ' }))),
    ).toEqual({
      status: 422,
      code: 'MSG-06-01',
    });
    const ok = await firstValueFrom(
      api.updateProgress(t, { percent: 30, note: 'Trả lại 3 hồ sơ thiếu' }),
    );
    expect(ok.progress).toBe(30);
    const ph = await firstValueFrom(api.detail(ID_PHOI_HOP));
    expect(
      (await statusOf(firstValueFrom(api.updateProgress(ph, { percent: 50, note: 'x' })))).status,
    ).toBe(403);
    const log = await firstValueFrom(
      api.updateProgress(ph, { percent: ph.progress, note: 'Đã gửi dự thảo' }),
    );
    expect(log.progressLog[0].note).toBe('Đã gửi dự thảo');
  });

  it('cập nhật hàng loạt xử lý độc lập từng dòng, dòng xung đột trả 409', async () => {
    const { api } = setup();
    const a = await firstValueFrom(api.detail(ID_157));
    const b = await firstValueFrom(api.detail(ID_142));
    const res = await firstValueFrom(
      api.updateProgressBatch([
        { taskId: a.id, rowVersion: a.rowVersion, percent: 60, note: '' },
        { taskId: b.id, rowVersion: b.rowVersion - 1, percent: 80, note: '' },
      ]),
    );
    expect(res[0]).toMatchObject({ status: 200, task: { progress: 60 } });
    expect(res[1].status).toBe(409);
  });

  it('đăng ký nhiệm vụ tạo NHAP có nguồn DANG_KY, hiện trong danh sách; hạn ngoài kỳ trả 422', async () => {
    const { api, catalog } = setup();
    const items = await firstValueFrom(catalog.items({ applicableToMe: true }));
    const ci = items.items.find((i) => i.code === 'TC-DV-05')!;
    const due = new Date(Date.now() + 10 * 86_400_000).toISOString().slice(0, 10);
    const t = await firstValueFrom(
      api.register({ catalogItemId: ci.id, quantity: 2, proposedDueDate: due }),
    );
    expect(t).toMatchObject({
      status: 'NHAP',
      source: 'DANG_KY',
      plannedQuantity: 2,
      unit: 'chi bộ',
    });
    const list = await firstValueFrom(api.myTasks());
    expect(list.items.some((x) => x.id === t.id)).toBe(true);
    const bad = await statusOf(
      firstValueFrom(
        api.register({ catalogItemId: ci.id, quantity: 1, proposedDueDate: '2099-01-01' }),
      ),
    );
    expect(bad.status).toBe(422);
  });

  it('xuất Excel trả 202 kèm Location của tác vụ', async () => {
    const { api } = setup();
    const job = await firstValueFrom(api.exportMyTasks({ filter: 'CHAM' }));
    expect(job.location).toMatch(/^\/api\/v1\/jobs\//);
  });
});

describe('workingDaysBetween', () => {
  it('bỏ thứ Bảy, Chủ nhật; âm khi đã qua hạn', () => {
    // 2026-10-16 là thứ Sáu, 2026-10-19 là thứ Hai
    expect(workingDaysBetween('2026-10-16', '2026-10-19')).toBe(1);
    expect(workingDaysBetween('2026-10-19', '2026-10-19')).toBe(0);
    expect(workingDaysBetween('2026-10-19', '2026-10-16')).toBe(-1);
    expect(workingDaysBetween('2026-10-19', '2026-10-26')).toBe(5);
  });
});

describe('mock tasks – SCR-0601, SCR-0602 (WEB-031)', () => {
  beforeEach(() => resetMockData());

  it('chi tiết có lịch sử trạng thái, sản phẩm, dự báo trễ, số lần gia hạn', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.detail(ID_157));
    expect(t.stateLog.map((s) => s.to)).toEqual(['DA_GIAO', 'DANG_THUC_HIEN']);
    expect(t.products).toEqual({ confirmed: 8, pendingReview: 3, required: 18 });
    expect(['THAP', 'VUA', 'CAO']).toContain(t.delayRisk?.level);
    expect(t).toMatchObject({ extensionsUsed: 0, extensionsMax: 2, acceptorName: 'Lê Minh Châu' });
    expect(t.blockers[0]).toMatchObject({ code: 'BN-0157-01', status: 'MO' });
  });

  it('nhận việc ghi thêm một dòng lịch sử trạng thái', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.accept(await firstValueFrom(api.detail(ID_DA_GIAO))));
    expect(t.stateLog.at(-1)).toMatchObject({
      from: 'DA_GIAO',
      to: 'DANG_THUC_HIEN',
      transition: 'NHAN_VIEC',
    });
  });

  it('báo điểm nghẽn tăng số điểm nghẽn đang mở; thiếu nội dung trả 422', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.detail(ID_157));
    const bad = await statusOf(
      firstValueFrom(
        api.reportBlocker(t, {
          content: ' ',
          causeCategory: 'THU_TUC',
          causeClaimed: 'CHU_QUAN',
          impactLevel: 'THAP',
        }),
      ),
    );
    expect(bad.status).toBe(422);
    const u = await firstValueFrom(
      api.reportBlocker(t, {
        content: 'Thiếu biểu mẫu mới',
        causeCategory: 'THU_TUC',
        causeClaimed: 'KHACH_QUAN',
        impactLevel: 'CAO',
      }),
    );
    expect(u.openBlockerCount).toBe(t.openBlockerCount + 1);
    expect(u.blockers.at(-1)?.code).toBe('BN-0157-02');
  });

  it('xem trước hệ quả: C khách quan 1, chủ quan giảm 0,05 mỗi ngày làm việc (BR-06-01)', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.detail(ID_157));
    const due = new Date(Date.parse(t.dueDate) + 7 * 86_400_000).toISOString().slice(0, 10);
    const n = workingDaysBetween(t.dueDate, due); // 5 khi hạn cũ là ngày làm việc
    const p = await firstValueFrom(
      api.previewChangeRequest(t, {
        type: 'GIA_HAN',
        newDueDate: due,
        causeClaimed: 'KHACH_QUAN',
        reason: '',
      }),
    );
    expect(p).toMatchObject({
      extraWorkingDays: n,
      objective: { c: 1, scoreDueDate: due },
      subjective: { delayDays: n },
    });
    expect(p.subjective.c).toBeCloseTo(1 - 0.05 * n, 5);
    expect(coefficientC(5)).toBe(0.75);
    expect(coefficientC(30)).toBe(0);
    expect(p.overThreshold).toBe(false);
  });

  it('gửi đề nghị: CHO_DUYET, trùng loại đang chờ trả 409, rút lại thành DA_RUT', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.detail(ID_157));
    const due = new Date(Date.parse(t.dueDate) + 7 * 86_400_000).toISOString().slice(0, 10);
    const draft = {
      type: 'GIA_HAN' as const,
      newDueDate: due,
      causeClaimed: 'KHACH_QUAN' as const,
      blockerId: t.blockers[0].id,
      reason: '4 hồ sơ chờ kết quả thẩm tra lý lịch từ địa phương',
    };
    expect(
      (await statusOf(firstValueFrom(api.submitChangeRequest(t, { ...draft, reason: 'ngắn' }))))
        .status,
    ).toBe(422);
    const u = await firstValueFrom(api.submitChangeRequest(t, draft));
    expect(u.changeRequests[0]).toMatchObject({
      status: 'CHO_DUYET',
      approverName: 'Lê Minh Châu',
      newDueDate: due,
    });
    expect((await statusOf(firstValueFrom(api.submitChangeRequest(u, draft)))).status).toBe(409);
    const w = await firstValueFrom(api.withdrawChangeRequest(u, u.changeRequests[0].id));
    expect(w.changeRequests[0].status).toBe('DA_RUT');
  });

  it('huỷ nhiệm vụ đã có sản phẩm được nghiệm thu trả 422 MSG-06-09 (BR-06-09)', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.detail(ID_157));
    const r = await statusOf(
      firstValueFrom(
        api.submitChangeRequest(t, {
          type: 'HUY',
          causeClaimed: 'KHACH_QUAN',
          reason: 'Nhiệm vụ không còn cần thiết theo chỉ đạo mới',
        }),
      ),
    );
    expect(r).toEqual({ status: 422, code: 'MSG-06-09' });
  });
});

describe('mock tasks – SCR-0501 Giao việc (WEB-028)', () => {
  const ref = (items: AssignCheckItem[], rule: string) => items.filter((i) => i.rule === rule);

  it('kiểm tra trước khi giao: chủ trì, người nghiệm thu, nhiệm vụ gần giống, khối lượng', async () => {
    const { api } = setup();
    const { draft } = await firstValueFrom(api.draft('0192a0f0-0002-7000-8000-000000000201'));
    let r = await firstValueFrom(api.assignCheck(draft));
    expect(ref(r.items, 'BR-05-01')[0].passed).toBe(false);
    expect(ref(r.items, 'BR-05-04')[0].passed).toBe(true);
    expect(r.similar[0].code).toBe('NV-2026-Q3-0211');
    expect(r.workingDays).toBeGreaterThan(0);
    r = await firstValueFrom(
      api.assignCheck({ ...draft, leadPersonId: 'p-an', acceptorId: 'p-an' }),
    );
    expect(ref(r.items, 'BR-05-01')[0].passed).toBe(true);
    expect(ref(r.items, 'BR-05-02')[0].message).toBe(
      'Người nghiệm thu phải khác người thực hiện nhiệm vụ.',
    );
    expect(ref(r.items, 'BR-05-11')[0].message).toContain('Nguyễn Văn An đang chủ trì 4 việc');
    expect(r.workload?.leadCount).toBe(5);
  });

  it('giao: 422 khi thiếu lý do trùng, thành công khi đủ; lưu nháp kèm If-Match', async () => {
    const { api } = setup();
    const { draft } = await firstValueFrom(api.draft('0192a0f0-0002-7000-8000-000000000201'));
    const saved = await firstValueFrom(api.saveDraft({ ...draft, leadPersonId: 'p-an' }));
    expect(saved.rowVersion).toBe(1);
    await expect(
      firstValueFrom(api.saveDraft({ ...draft, leadPersonId: 'p-ha' })),
    ).rejects.toMatchObject({ status: 409 });
    await expect(firstValueFrom(api.assign(saved, {}))).rejects.toMatchObject({ status: 422 });
    const res = await firstValueFrom(
      api.assign(saved, { similarReason: 'Khác phạm vi: 46 tổ chức đảng' }),
    );
    expect(res).toMatchObject({
      code: 'NV-2026-Q4-0201',
      status: 'DA_GIAO',
      leadName: 'Nguyễn Văn An',
    });
  });

  it('tạo nháp mới, huỷ nháp và giao đồng loạt trả 202', async () => {
    const { api } = setup();
    const { draft } = await firstValueFrom(api.newDraft());
    expect(draft.assignerName).toBe('Lê Minh Châu');
    const created = await firstValueFrom(api.createDraft({ ...draft, title: 'Việc thử' }));
    expect(created.code).toMatch(/^NV-2026-Q4-03\d\d$/);
    await firstValueFrom(api.deleteDraft(created));
    await expect(firstValueFrom(api.draft(created.id!))).rejects.toMatchObject({ status: 404 });
    const job = await firstValueFrom(
      api.bulkAssign({ draft: { ...draft, title: 'Việc chung' }, unitIds: ['u-cs-01'] }),
    );
    expect(job.location).toMatch(/^\/api\/v1\/jobs\//);
  });
});
