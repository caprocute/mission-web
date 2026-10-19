import { HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { JobTracker } from '../../lib/job-tracker';
import { jobLocation } from '../../lib/job-tracker';
import { OkrApi } from '../../lib/okr-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { OKR_LARGE_UNIT_ID } from './okr.mock';

/** WEB-021: giả lập cây mục tiêu SCR-0201, lịch sử check-in và xuất mẫu. */
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
  return TestBed.inject(OkrApi);
}

describe('mock okr – SCR-0201 (WEB-021)', () => {
  beforeEach(() => resetMockData());

  it('mặc định trả kỳ đang mở với cây ba tầng, tiến độ tính từ KR như mockup', async () => {
    const t = await firstValueFrom(setup().tree());
    expect(t.period.label).toBe('Quý IV/2026');
    expect(t.parent?.code).toBe('O-ĐUK-1');
    const o1 = t.objectives.find((o) => o.code === 'O1')!;
    const o2 = t.objectives.find((o) => o.code === 'O2')!;
    expect(o1.progressPct).toBe(64);
    expect(o2.progressPct).toBe(51);
    expect(new Set(t.objectives.map((o) => o.tier))).toEqual(new Set([1, 2, 3]));
    expect(t.peerLinks).toHaveLength(1);
  });

  it('kỳ chưa có mục tiêu trả cây rỗng; đơn vị ngoài phạm vi trả 403', async () => {
    const api = setup();
    const t = await firstValueFrom(api.tree({ period: 'p-2027-q1' }));
    expect(t.objectives).toEqual([]);
    const err = await firstValueFrom(api.tree({ unit: 'u-khac' })).catch((e) => e);
    expect((err as HttpErrorResponse).status).toBe(403);
  });

  it('đơn vị lớn sinh hàng trăm nút để thử ảo hoá', async () => {
    const t = await firstValueFrom(setup().tree({ unit: OKR_LARGE_UNIT_ID }));
    expect(t.objectives.length).toBeGreaterThan(300);
  });

  it('lịch sử check-in phân trang con trỏ, mới nhất trước', async () => {
    const api = setup();
    const p1 = await firstValueFrom(api.checkins('kr-1-3', undefined, 2));
    expect(p1.items.map((c) => c.weekLabel)).toEqual(['Tuần 4', 'Tuần 3']);
    expect(p1.nextCursor).not.toBeNull();
    const p2 = await firstValueFrom(api.checkins('kr-1-3', p1.nextCursor!, 2));
    expect(p2.items.map((c) => c.weekLabel)).toEqual(['Tuần 2']);
    expect(p2.nextCursor).toBeNull();
  });

  it('xuất mẫu trả 202 + Location và job hoàn tất có đường dẫn kết quả', async () => {
    const api = setup();
    const res = await firstValueFrom(
      api.exportObjectives({ unitId: 'u-btc', periodId: 'p-2026-q4', view: 'tree' }),
    );
    expect(res.status).toBe(202);
    const loc = jobLocation(res)!;
    const job = await firstValueFrom(TestBed.inject(JobTracker).track(loc));
    expect(job.status).toBe('HOAN_TAT');
    expect(job.resultLocation).toMatch(/\/file$/);
  });
});
