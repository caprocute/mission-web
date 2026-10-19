import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, lastValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { JOB_TRACKER_OPTIONS, JobTracker } from '../../lib/job-tracker';
import { cronToTime, timeToCron } from '../../lib/models/org-sync';
import { OrgSyncApi } from '../../lib/org-sync-api';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';

/** WEB-019: giả lập đồng bộ và đối soát nhân sự SCR-0104 theo `org.yaml`. */
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
  return TestBed.inject(OrgSyncApi);
}

const ID = (n: number) => `01928f3e-d100-7000-8000-${String(n).padStart(12, '0')}`;

describe('mock org-sync – SCR-0104 (WEB-019)', () => {
  beforeEach(() => resetMockData());

  it('nhật ký theo nguồn, 412 mục lệch, đếm theo mức và trường, phân trang con trỏ', async () => {
    const api = setup();
    const runs = await firstValueFrom(api.runs());
    expect(runs.items.map((r) => [r.connector, r.status]).slice(0, 4)).toEqual([
      ['SCIM', 'THANH_CONG_MOT_PHAN'],
      ['DANG_VIEN', 'THANH_CONG'],
      ['CBCCVC', 'THANH_CONG'],
      ['CBCCVC', 'LOI'],
    ]);
    expect((await firstValueFrom(api.runs({ connector: 'SCIM' }))).items).toHaveLength(1);
    const page = await firstValueFrom(api.discrepancies({ limit: 20 }));
    expect(page.counts.CAO + page.counts.VUA + page.counts.THAP).toBe(412);
    expect(page.items[0]).toMatchObject({
      personName: 'Nguyễn Thị Lan',
      maskedNationalId: '***4521',
    });
    expect(page.countsByField).toMatchObject({
      job_title_id: 168,
      org_unit_id: 121,
      person_match: 74,
    });
    const next = await firstValueFrom(api.discrepancies({ limit: 20, cursor: page.nextCursor! }));
    expect(next.items[0].id).not.toBe(page.items[0].id);
    const high = await firstValueFrom(
      api.discrepancies({ severity: 'CAO', field: 'job_title_id' }),
    );
    expect(high.items.every((d) => d.severity === 'CAO' && d.field === 'job_title_id')).toBe(true);
  });

  it('lấy theo nguồn trả kết quả từng mục; không khớp người bị chặn MSG-01-15; xử lý rồi thì 409', async () => {
    const api = setup();
    const res = await firstValueFrom(api.resolve([ID(1), ID(2), ID(3)], 'NHAN_NGUON'));
    expect(res.results.map((r) => r.status)).toEqual([200, 200, 422]);
    expect(res.results[2].problem?.title).toBe(
      'Hai hồ sơ trùng tên khác ngày sinh. Vui lòng đối soát thủ công.',
    );
    expect(res.assignmentsUpdated).toBe(2);
    const again = await firstValueFrom(api.resolve([ID(1)], 'GIU_HIEN_TAI'));
    expect(again.results[0].status).toBe(409);
    const page = await firstValueFrom(api.discrepancies());
    expect(page.counts.CAO + page.counts.VUA + page.counts.THAP).toBe(410);
  });

  it('ánh xạ thủ công chỉ nhận hồ sơ cán bộ có thật; giao xử lý ghi người nhận', async () => {
    const api = setup();
    await expect(firstValueFrom(api.map(ID(3), 'khong-co'))).rejects.toMatchObject({ status: 422 });
    const mapped = await firstValueFrom(api.map(ID(3), 'p-an'));
    expect(mapped).toMatchObject({ personId: 'p-an', resolution: 'SUA_TAY' });
    const d = await firstValueFrom(api.assign(ID(5), 'p-chau', 'Kiểm tra quyết định nghỉ hưu'));
    expect(d.assigneeName).toBe('Lê Minh Châu');
  });

  it('chạy lại lần lỗi qua job 202; lịch đồng bộ ghi cả bộ bằng If-Match', async () => {
    const api = setup();
    const loc = await firstValueFrom(api.retry('01928f3e-5a00-7000-8000-000000008838'));
    expect(loc).toMatch(/^\/api\/v1\/jobs\//);
    const job = await lastValueFrom(TestBed.inject(JobTracker).track(loc));
    expect(job.status).toBe('HOAN_TAT');
    const runs = (await firstValueFrom(api.runs())).items;
    expect(runs.find((r) => r.id.endsWith('8838'))?.deadLetters).toBe(0);
    const set = await firstValueFrom(api.schedules());
    expect(cronToTime(set.items[1].cron)).toBe('02:30');
    const items = set.items.map((s, i) => (i ? s : { ...s, cron: timeToCron('03:15') }));
    const saved = await firstValueFrom(api.saveSchedules(set, items));
    expect(saved.items[0].cron).toBe('15 3 * * *');
    await expect(firstValueFrom(api.saveSchedules(set, items))).rejects.toMatchObject({
      status: 409,
    });
  });
});
