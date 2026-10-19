import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, lastValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { JOB_TRACKER_OPTIONS, JobTracker } from '../../lib/job-tracker';
import { OrgTreeApi } from '../../lib/org-tree-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';

/** WEB-018: giả lập cây tổ chức có lịch sử SCR-0103. */
function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      {
        provide: JOB_TRACKER_OPTIONS,
        useValue: { initialDelayMs: 5, maxDelayMs: 5, maxRetries: 0 },
      },
    ],
  });
  return TestBed.inject(OrgTreeApi);
}

const day = (offset: number) =>
  new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

describe('mock org-tree – SCR-0103 (WEB-018)', () => {
  beforeEach(() => resetMockData());

  it('nạp lười theo nhánh; Hiện tại có cả đơn vị đã kết thúc, xem tại ngày chỉ có đơn vị hiệu lực', async () => {
    const api = setup();
    const root = await firstValueFrom(api.tree(null));
    expect(root.items.map((n) => n.name)).toEqual(['Đảng bộ tỉnh Thái Nguyên']);
    const lvl1 = await firstValueFrom(api.tree('u-tn'));
    const pb = lvl1.items.find((n) => n.id === 'u-pb')!;
    expect(pb.validTo).toBe('2025-07-01');
    expect(pb.assignmentCount).toBe(0);
    const ubnd = (await firstValueFrom(api.tree('u-ubnd'))).items;
    expect(ubnd.length).toBeGreaterThan(90);
    expect(ubnd.find((n) => n.id === 'u-snv')?.mergedOn).toBe('2025-03-01');

    const past = await firstValueFrom(api.tree('u-tn', { asOf: '2025-06-30' }));
    expect(past.items.some((n) => n.id === 'u-pb' && !n.validTo)).toBe(false);
    expect(past.items.find((n) => n.id === 'u-pb')).toBeTruthy();
    expect(past.items.find((n) => n.id === 'u-dux-pb')).toBeUndefined();
    await expect(firstValueFrom(api.tree(null, { asOf: day(3) }))).rejects.toMatchObject({
      status: 422,
    });
  });

  it('trục ghi nhận: đổi tên ghi muộn chưa thấy khi xem đã ghi nhận đến trước ngày ghi', async () => {
    const api = setup();
    expect((await firstValueFrom(api.unit('u-snv-ccvc'))).name).toBe('Phòng Công chức, viên chức');
    const before = await firstValueFrom(api.unit('u-snv-ccvc', { recordedAsOf: day(-34) }));
    expect(before.name).toBe('Phòng Công chức');
    const hist = await firstValueFrom(api.history('u-snv', { recordedAsOf: day(-34) }));
    expect(hist.map((h) => h.kind)).toEqual(['HOP_NHAT']);
  });

  it('chi tiết, tìm kiếm, phân công gồm kiêm nhiệm, điều động đi và biệt phái', async () => {
    const api = setup();
    const snv = await firstValueFrom(api.unit('u-snv'));
    expect(snv).toMatchObject({ externalCode: '000.20.21.H56', parent: { name: 'UBND tỉnh' } });
    expect(snv.predecessors.map((p) => p.name)).toEqual([
      'Sở Nội vụ (cũ)',
      'Sở Lao động – Thương binh và Xã hội',
    ]);
    const found = await firstValueFrom(api.search('so noi vu'));
    expect(found.find((m) => m.id === 'u-snv')?.ancestorIds).toEqual(['u-tn', 'u-ubnd']);
    const rows = await firstValueFrom(api.assignments('u-snv'));
    expect(rows.slice(0, 5).map((a) => a.kind)).toEqual([
      'CHINH',
      'KIEM_NHIEM',
      'CHINH',
      'DIEU_DONG',
      'BIET_PHAI',
    ]);
  });

  it('sửa tạo phiên bản mới có If-Match, đơn vị đồng bộ không sửa được', async () => {
    const api = setup();
    const cur = await firstValueFrom(api.unit('so-tp'));
    const body = {
      name: 'Sở Tư pháp tỉnh',
      level: cur.level,
      unitType: cur.unitType,
      subjectGroupId: cur.subjectGroupId,
      flows: cur.flows,
      validFrom: day(-1),
    };
    const after = await firstValueFrom(api.update(cur, body));
    expect(after).toMatchObject({ name: 'Sở Tư pháp tỉnh', rowVersion: 1 });
    expect((await firstValueFrom(api.unit('so-tp', { asOf: day(-2) }))).name).toBe('Sở Tư pháp');
    await expect(firstValueFrom(api.update(cur, body))).rejects.toMatchObject({ status: 409 });
    const thpt = await firstValueFrom(api.unit('u-thpt-1'));
    await expect(
      firstValueFrom(api.update(thpt, { ...body, subjectGroupId: thpt.subjectGroupId })),
    ).rejects.toMatchObject({ error: { code: 'ORG-409-SYNCED' } });
  });

  it('sáp nhập thiếu tiền thân MSG-01-09; sáp nhập chuyển phân công MSG-01-11, kỳ đã khoá MSG-01-10', async () => {
    const api = setup();
    await expect(
      firstValueFrom(
        api.change('so-tc', { kind: 'SAP_NHAP', effectiveOn: day(-1), orderNo: '1/QĐ' }),
      ),
    ).rejects.toMatchObject({
      error: {
        errors: [
          {
            field: 'predecessorIds',
            message: 'Sáp nhập cần khai báo ít nhất một đơn vị tiền thân.',
          },
        ],
      },
    });
    const res = await firstValueFrom(
      api.change('so-tc', {
        kind: 'SAP_NHAP',
        effectiveOn: '2026-06-01',
        orderNo: '2001/QĐ-UBND',
        predecessorIds: ['so-ct'],
      }),
    );
    expect(res.movedAssignments).toBeGreaterThan(0);
    expect(res.message).toContain('theo quyết định 2001/QĐ-UBND');
    expect(res.warning).toContain('Quý II/2026');
    const ct = (await firstValueFrom(api.tree('u-ubnd'))).items.find((n) => n.id === 'so-ct');
    expect(ct?.validTo).toBe('2026-06-01');
    expect((await firstValueFrom(api.unit('so-tc'))).predecessors[0].id).toBe('so-ct');
  });

  it('giải thể bị chặn khi còn đơn vị trực thuộc; nhập Excel 202 có báo cáo lỗi', async () => {
    const api = setup();
    await expect(
      firstValueFrom(
        api.change('so-gd', { kind: 'GIAI_THE', effectiveOn: day(-1), orderNo: '3/QĐ' }),
      ),
    ).rejects.toMatchObject({ status: 409 });
    const loc = await firstValueFrom(api.import(new File(['x'], 'cay-to-chuc-loi.xlsx')));
    const job = await lastValueFrom(TestBed.inject(JobTracker).track(loc));
    expect(job).toMatchObject({ status: 'HOAN_TAT' });
    expect(job.message).toContain('2 dòng lỗi');
    const blob = await firstValueFrom(api.report(job.resultLocation!));
    expect(await blob.text()).toContain('Phụ lục 2A');
  });
});
