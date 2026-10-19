import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom, lastValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../../lib/interceptors';
import { JOB_TRACKER_OPTIONS, JobTracker } from '../../lib/job-tracker';
import {
  TenantOnboardingInput,
  suggestTenancyModel,
  modelAllowed,
} from '../../lib/models/tenant-onboarding';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '../../lib/runtime-config';
import { TenantApi } from '../../lib/tenant-api';
import { MOCK_API_OPTIONS, mockApiInterceptor } from '../mock-api.interceptor';
import { resetMockData } from '../mock-registry';
import { setProvisionDuration } from './onboarding.mock';

/** WEB-017: giả lập trình khởi tạo tenant SCR-0102. */
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
  return TestBed.inject(TenantApi);
}

const input = (over: Partial<TenantOnboardingInput> = {}): TenantOnboardingInput => ({
  name: 'Tỉnh Hà Giang',
  code: 'hagiang',
  sector: 'CHINH_QUYEN',
  contractRef: '91/2026/HĐ-VTS',
  flows: ['DANG', 'NHA_NUOC'],
  expectedAccounts: 60_000,
  expectedUnits: 700,
  isolationRequired: false,
  adminName: 'Nguyễn Văn A',
  adminEmail: 'a@hagiang.gov.vn',
  ...over,
});

describe('mock onboarding – SCR-0102 (WEB-017)', () => {
  beforeEach(() => {
    resetMockData();
    setProvisionDuration(30);
  });

  it('gợi ý mô hình theo quy mô và BR-01-01', () => {
    expect(suggestTenancyModel(1_200, false)).toBe('pool');
    expect(suggestTenancyModel(98_420, false)).toBe('bridge');
    expect(suggestTenancyModel(1_200_000, false)).toBe('silo');
    expect(suggestTenancyModel(3_000, true)).toBe('silo');
    expect(modelAllowed('bridge', 1_200_000, false)).toBe(false);
    expect(modelAllowed('pool', 98_420, false)).toBe(true);
  });

  it('khai báo sinh mã ONB, trùng mã trả 409, thiếu luồng trả 422', async () => {
    const api = setup();
    const t = await firstValueFrom(api.create(input()));
    expect(t).toMatchObject({ status: 'KHOI_TAO', step: 1, tenancyModel: 'bridge' });
    expect(t.onboardingCode).toMatch(/^ONB-\d{4}-\d{4}$/);
    await expect(firstValueFrom(api.create(input()))).rejects.toMatchObject({ status: 409 });
    await expect(
      firstValueFrom(api.create(input({ code: 'khac', flows: [] }))),
    ).rejects.toMatchObject({ status: 422 });
  });

  it('bước 2: cell vượt ngưỡng không chọn được, tên miền trùng MSG-01-04, If-Match cũ 409', async () => {
    const api = setup();
    let t = await firstValueFrom(api.create(input()));
    const cells = await firstValueFrom(api.cellOptions('bridge', 60_000));
    expect(cells.find((c) => c.code === 'CELL-NB')?.eligible).toBe(false);
    expect((await firstValueFrom(api.cellOptions('silo', 60_000))).some((c) => c.eligible)).toBe(
      false,
    );
    await expect(
      firstValueFrom(api.saveDraft(t, { domain: 'hcm.okr.viettel.vn' })),
    ).rejects.toMatchObject({
      error: {
        errors: [{ field: 'domain', message: 'Tên miền hcm.okr.viettel.vn đã được sử dụng.' }],
      },
    });
    expect(await firstValueFrom(api.domainAvailable('hcm.okr.viettel.vn'))).toBe(false);
    const stale = t;
    t = await firstValueFrom(api.saveDraft(t, { cellCode: 'CELL-G2', step: 2 }));
    expect(t).toMatchObject({ cellCode: 'CELL-G2', step: 2 });
    await expect(firstValueFrom(api.saveDraft(stale, { step: 3 }))).rejects.toMatchObject({
      status: 409,
    });
  });

  it('bước 3: gói chưa đạt 100% test vàng trả MSG-01-05; cấp phát 202 chạy hết bước con', async () => {
    const api = setup();
    let t = await firstValueFrom(api.create(input()));
    t = await firstValueFrom(api.saveDraft(t, { cellCode: 'CELL-G2', step: 3 }));
    await expect(
      firstValueFrom(api.saveDraft(t, { policyPacks: { DANG: 'pk-dang-qdm-2.1' } })),
    ).rejects.toMatchObject({
      error: {
        title:
          'Bộ quy định đánh giá Khối Đảng · Quy định mới v2.1 chưa đạt 100% bộ ca kiểm thử chuẩn nên chưa gắn được.',
      },
    });
    t = await firstValueFrom(
      api.saveDraft(t, { policyPacks: { DANG: 'pk-dang-qdm-2.0', NHA_NUOC: 'pk-nn-335-1.3' } }),
    );
    const location = await firstValueFrom(api.provision(t.id));
    expect(location).toContain(`/api/v1/config/tenants/${t.id}/provision/`);
    const job = await lastValueFrom(TestBed.inject(JobTracker).track(location));
    expect(job.status).toBe('HOAN_TAT');
    const after = await firstValueFrom(api.onboarding(t.id));
    expect(after).toMatchObject({ status: 'DANG_CAU_HINH', step: 4, provisionedMinutes: 74 });
  });

  it('BR-01-04: thiếu gói của một luồng thì không cấp phát', async () => {
    const api = setup();
    let t = await firstValueFrom(api.create(input()));
    t = await firstValueFrom(
      api.saveDraft(t, { cellCode: 'CELL-G2', policyPacks: { NHA_NUOC: 'pk-nn-335-1.3' } }),
    );
    await expect(firstValueFrom(api.provision(t.id))).rejects.toMatchObject({ status: 422 });
  });

  it('saga lỗi bù trừ về KHOI_TAO với MSG-01-07', async () => {
    const api = setup();
    let t = await firstValueFrom(api.create(input()));
    t = await firstValueFrom(
      api.saveDraft(t, {
        cellCode: 'CELL-G2',
        domain: 'loi-tls.okr.viettel.vn',
        policyPacks: { DANG: 'pk-dang-qdm-2.0', NHA_NUOC: 'pk-nn-335-1.3' },
      }),
    );
    const job = await lastValueFrom(
      TestBed.inject(JobTracker).track(await firstValueFrom(api.provision(t.id))),
    );
    expect(job.status).toBe('THAT_BAI');
    expect(job.problem?.title).toContain('Cấp phát lỗi ở bước Cấp chứng chỉ TLS');
    expect((await firstValueFrom(api.onboarding(t.id))).status).toBe('KHOI_TAO');
  });

  it('kiểm tra IdP, kiểm tra tổng thể và kích hoạt', async () => {
    const api = setup();
    const tn = await firstValueFrom(api.onboarding('t-thainguyen'));
    const idps = await firstValueFrom(api.testIdp(tn.id, tn.idps));
    expect(idps.every((i) => i.status === 'DAT')).toBe(true);
    const checks = await firstValueFrom(api.checks(tn.id));
    expect(checks.find((c) => c.code === 'LECH_CAO')?.result).toBe('LOI');
    await expect(
      firstValueFrom(api.activate(await firstValueFrom(api.onboarding(tn.id)))),
    ).rejects.toMatchObject({ status: 409 });

    const qn = await firstValueFrom(api.onboarding('t-quangngai'));
    expect(qn.status).toBe('SAN_SANG');
    expect((await firstValueFrom(api.activate(qn))).status).toBe('DANG_VAN_HANH');
  });

  it('huỷ chỉ khi KHOI_TAO', async () => {
    const api = setup();
    const t = await firstValueFrom(api.onboarding('t-truong-thpt-hue'));
    await firstValueFrom(api.cancel(t));
    await expect(firstValueFrom(api.onboarding(t.id))).rejects.toMatchObject({ status: 404 });
    const qn = await firstValueFrom(api.onboarding('t-quangngai'));
    await expect(firstValueFrom(api.cancel(qn))).rejects.toMatchObject({ status: 409 });
  });
});
