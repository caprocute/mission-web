import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { AnalyticsApi } from '../../lib/analytics-api';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { resultRows } from '../../lib/models/analytics';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { TaskApi } from '../../lib/task-api';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';

/** WEB-047, WEB-048: hành vi giả lập SVC-15 (QT-16) theo hợp đồng `platform.yaml`. */
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
  return { api: TestBed.inject(AnalyticsApi), tasks: TestBed.inject(TaskApi) };
}

async function errorOf(p: Promise<unknown>): Promise<HttpErrorResponse> {
  try {
    await p;
  } catch (e) {
    return e as HttpErrorResponse;
  }
  throw new Error('không có lỗi');
}

describe('mock analytics – QT-16 (WEB-047, WEB-048)', () => {
  beforeEach(() => resetMockData());

  it('bốn con số và bảng đơn vị có so với kỳ trước; kỳ đang khoá báo số liệu chậm', async () => {
    const { api } = setup();
    const totals = await firstValueFrom(
      api.query({
        measures: ['task.so_viec_cham', 'appraisal.cho_tham_dinh'],
        periodId: 'p-2026q4',
      }),
    );
    expect(totals.rows).toEqual([[14, 9]]);
    expect(totals.stale).toBe(false);
    const units = await firstValueFrom(
      api.query({
        measures: ['okr.tien_do_o'],
        dimensions: ['org_unit.id', 'org_unit.name'],
        periodId: 'p-2026q4',
        compareToPreviousPeriod: true,
      }),
    );
    const vp = resultRows(units).find((r) => r['org_unit.id'] === 'u-vp')!;
    expect(vp).toMatchObject({ 'okr.tien_do_o': 41, 'okr.tien_do_o.previous': 55 });
    const q3 = await firstValueFrom(
      api.query({ measures: ['okr.tien_do_o'], periodId: 'p-2026q3' }),
    );
    expect(q3.stale).toBe(true);
  });

  it('kết hợp chỉ số và chiều không hỗ trợ trả 422 với thông báo nghiệp vụ', async () => {
    const { api } = setup();
    const e = await errorOf(
      firstValueFrom(api.query({ measures: ['okr.tien_do_o'], dimensions: ['task.nhom'] })),
    );
    expect(e.status).toBe(422);
    expect(e.error.detail).toBe('Chỉ số Tiến độ mục tiêu O không hỗ trợ chiều Loại nhiệm vụ.');
  });

  it('dashboard: tạo, trùng tên 409, lưu cần If-Match, người khác không được sửa', async () => {
    const { api } = setup();
    const widget = {
      id: 'w1',
      x: 0,
      y: 0,
      w: 3,
      h: 2,
      measure: 'okr.tien_do_o',
      dimensions: [],
      chartType: 'THE_SO' as const,
      filters: [],
      drillDown: true,
    };
    const dup = await errorOf(
      firstValueFrom(api.createDashboard({ title: 'Bảng của Bí thư', layout: [widget] })),
    );
    expect(dup.status).toBe(409);
    const d = await firstValueFrom(api.createDashboard({ title: 'Bảng mới', layout: [widget] }));
    expect(d.rowVersion).toBe(0);
    const saved = await firstValueFrom(
      api.saveDashboard(d, { title: 'Bảng mới', layout: [widget, { ...widget, id: 'w2', x: 3 }] }),
    );
    expect(saved.layout.length).toBe(2);
    const stale = await errorOf(
      firstValueFrom(api.saveDashboard(d, { title: 'X', layout: [widget] })),
    );
    expect(stale.status).toBe(412);
    const other = await firstValueFrom(api.dashboard('db-khoi-tham-muu'));
    expect(other.data?.widgets?.[0].rows.length).toBe(4);
    const e = await errorOf(firstValueFrom(api.share(other, { shareScope: 'TENANT' })));
    expect(e.status).toBe(403);
  });

  it('chuỗi truy vết tám mắt xích; mắt xích ngoài phạm vi bị che', async () => {
    const { api } = setup();
    const c = await firstValueFrom(api.trace('DIEM', 'ss-2026q4-tran-minh-hoang'));
    expect(c.links.map((l) => l.objectType)).toEqual([
      'DIEM',
      'CAU_PHAN',
      'KPI_NHIEM_VU',
      'SAN_PHAM',
      'MINH_CHUNG',
      'KE_HOACH',
      'MUC_TIEU',
      'CAN_CU',
    ]);
    const ev = await firstValueFrom(api.trace('MINH_CHUNG', 'mc-2026q4-bc-so-ket'));
    expect(ev.ledgerRef).toBeTruthy();
    const m = await firstValueFrom(api.trace('DIEM', 'ss-2026q4-le-thu-trang'));
    expect(m.links.find((l) => l.objectType === 'MINH_CHUNG')).toMatchObject({ masked: true });
  });

  it('việc cần chỉ đạo: nhắc 202, giao lại cần lý do, việc người khác giao trả 403', async () => {
    const { tasks } = setup();
    const page = await firstValueFrom(tasks.unitTasks({ overdue: true, includeSubunits: true }));
    expect(page.items.map((t) => t.id)).toEqual(['t-cd-01', 't-cd-02', 't-cd-03']);
    await firstValueFrom(tasks.remind('t-cd-01'));
    const [t1, t2] = page.items;
    const r = await firstValueFrom(
      tasks.reassign(t1, { leadPersonId: 'p-dung', reason: 'Tăng tốc' }),
    );
    expect(r.rowVersion).toBe(t1.rowVersion + 1);
    const e = await errorOf(
      firstValueFrom(tasks.reassign(t2, { leadPersonId: 'p-dung', reason: 'Tăng tốc' })),
    );
    expect(e.status).toBe(403);
  });
});
