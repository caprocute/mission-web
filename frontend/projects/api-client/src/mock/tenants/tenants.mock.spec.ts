import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, lastValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { JOB_TRACKER_OPTIONS, JobTracker } from '../../lib/job-tracker';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { TenantApi } from '../../lib/tenant-api';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';

/** WEB-016: giả lập danh bạ tenant SCR-0101 (lọc, con trỏ, tải cell, xuất 202). */
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
        useValue: { initialDelayMs: 1, maxDelayMs: 1, maxRetries: 0 },
      },
    ],
  });
  return TestBed.inject(TenantApi);
}

describe('mock tenants – SCR-0101 (WEB-016)', () => {
  beforeEach(() => resetMockData());

  it('trang đầu 8 dòng, con trỏ sang trang sau, tổng số khớp', async () => {
    const api = setup();
    const p1 = await firstValueFrom(api.list());
    expect(p1.items).toHaveLength(8);
    expect(p1.total).toBe(35);
    expect(p1.items[0].name).toBe('Đảng uỷ các cơ quan Đảng Trung ương');
    const p2 = await firstValueFrom(api.list({ cursor: p1.nextCursor! }));
    expect(p2.items[0].id).not.toBe(p1.items[0].id);
  });

  it('lọc theo cell, mô hình, trạng thái và tìm không dấu', async () => {
    const api = setup();
    expect((await firstValueFrom(api.list({ cell: 'CELL-G2' }))).items.map((t) => t.code)).toEqual([
      'thainguyen',
    ]);
    const silo = await firstValueFrom(api.list({ model: 'silo' }));
    expect(silo.total).toBe(3);
    expect((await firstValueFrom(api.list({ status: 'SAN_SANG' }))).items[0].code).toBe(
      'quangngai',
    );
    expect((await firstValueFrom(api.list({ q: 'ca mau' }))).items[0].code).toBe('bvdk_camau');
  });

  it('tổng quan và tải theo cell: CELL-HCM vượt 85%', async () => {
    const api = setup();
    const o = await firstValueFrom(api.overview());
    expect(o.onboardingCount).toBe(3);
    expect(o.onboarding.find((t) => t.id === 't-thainguyen')?.step).toBe(4);
    expect(o.cellCount).toBe(9);
    const cells = await firstValueFrom(api.cells());
    const hcm = cells.find((c) => c.code === 'CELL-HCM')!;
    expect(hcm.accountCount / hcm.capacityAccounts).toBeGreaterThan(0.85);
    expect(cells.find((c) => c.code === 'CELL-SH')!.tenantCount).toBe(27);
  });

  it('xuất danh sách trả 202 + Location, tác vụ hoàn tất có tệp CSV', async () => {
    const api = setup();
    const location = await firstValueFrom(api.export({ model: 'silo' }));
    expect(location).toMatch(/^\/api\/v1\/config\/tenants\/exports\//);
    const job = await lastValueFrom(TestBed.inject(JobTracker).track(location));
    expect(job.status).toBe('HOAN_TAT');
    const blob = await firstValueFrom(api.download(job.resultLocation!));
    const text = await blob.text();
    expect(text.split('\r\n')).toHaveLength(4);
    expect(text).toContain('Thành phố Hồ Chí Minh');
  });

  it('chi tiết có nhật ký trạng thái; mã lạ trả 404', async () => {
    const api = setup();
    const t = await firstValueFrom(api.get('t-thainguyen'));
    expect(t.statusLog[0].to).toBe('DANG_CAU_HINH');
    await expect(firstValueFrom(api.get('khong-co'))).rejects.toMatchObject({ status: 404 });
  });
});
