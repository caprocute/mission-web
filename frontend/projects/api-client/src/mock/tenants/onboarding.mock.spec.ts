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
  adminContact: { fullName: 'Nguyễn Văn A', email: 'a@hagiang.gov.vn' },
  ...over,
});

/** Id phiên bản Policy Pack gốc của giả lập `/policies/packs` (đạt và chưa đạt ca vàng). */
const PK = (n: number) => `0192a1b0-3c4d-7e5f-8a9b-${String(n).padStart(12, '0')}`;
const BOTH = [
  { flow: 'DANG' as const, packVersionId: PK(4120) },
  { flow: 'NHA_NUOC' as const, packVersionId: PK(4213) },
];

describe('mock onboarding – SCR-0102 (WEB-017, hợp đồng WEB-072)', () => {
  beforeEach(() => {
    resetMockData();
    setProvisionDuration(30);
  });

  it('gợi ý mô hình theo quy mô và BR-01-01', () => {
    expect(suggestTenancyModel(1_200, false)).toBe('pool');
    expect(suggestTenancyModel(98_420, false)).toBe('bridge');
    expect(suggestTenancyModel(1_200_000, false)).toBe('silo');
    expect(suggestTenancyModel(3_000, true)).toBe('silo');
    expect(modelAllowed('bridge', 'silo')).toBe(false);
    expect(modelAllowed('pool', 'bridge')).toBe(true);
  });

  it('khai báo sinh mã ONB, bước do máy chủ tính; trùng mã 409, thiếu luồng 422, trường lạ 422', async () => {
    const api = setup();
    const t = await firstValueFrom(api.create(input()));
    expect(t).toMatchObject({
      status: 'KHOI_TAO',
      onboardingStep: 2,
      tenancyModel: 'bridge',
      suggestedTenancyModel: 'bridge',
      adminContact: { fullName: 'Nguyễn Văn A' },
    });
    expect(t.onboardingCode).toMatch(/^ONB-\d{4}-\d{4}$/);
    await expect(firstValueFrom(api.create(input()))).rejects.toMatchObject({ status: 409 });
    await expect(
      firstValueFrom(api.create(input({ code: 'khac', flows: [] }))),
    ).rejects.toMatchObject({ status: 422 });
    await expect(
      firstValueFrom(api.create({ ...input({ code: 'khac2' }), isolationRequired: true } as never)),
    ).rejects.toMatchObject({ status: 422 });
    await expect(firstValueFrom(api.saveDraft(t, { step: 3 } as never))).rejects.toMatchObject({
      status: 422,
    });
  });

  it('bước 2: cell từ /registry/cells, vượt ngưỡng không chọn được, tên miền trùng 409, If-Match cũ 409', async () => {
    const api = setup();
    let t = await firstValueFrom(api.create(input()));
    const cells = await firstValueFrom(api.cellOptions('bridge', 60_000));
    expect(cells.every((c) => c.tenancyModel === 'BRIDGE')).toBe(true);
    expect(cells.find((c) => c.code === 'CELL-NB')?.eligible).toBe(false);
    expect((await firstValueFrom(api.cellOptions('silo', 60_000))).some((c) => c.eligible)).toBe(
      false,
    );
    await expect(
      firstValueFrom(api.saveDraft(t, { domain: 'hcm.okr.viettel.vn' })),
    ).rejects.toMatchObject({
      status: 409,
      error: {
        code: 'CONFIG-409-DOMAIN-TAKEN',
        errors: [{ field: 'domain', message: 'Tên miền hcm.okr.viettel.vn đã được sử dụng.' }],
      },
    });
    const stale = t;
    t = await firstValueFrom(api.saveDraft(t, { cellCode: 'CELL-G2', kekMode: 'RIENG' }));
    expect(t).toMatchObject({ cellCode: 'CELL-G2', onboardingStep: 3 });
    await expect(firstValueFrom(api.saveDraft(stale, { name: 'X' }))).rejects.toMatchObject({
      status: 409,
      error: { code: 'CONFIG-409-VERSION-CONFLICT' },
    });
  });

  it('bước 3: gói chưa đạt 100% test vàng trả MSG-01-05; cấp phát 202 job PROVISION chạy hết bước con', async () => {
    const api = setup();
    let t = await firstValueFrom(api.create(input()));
    t = await firstValueFrom(api.saveDraft(t, { cellCode: 'CELL-G2', kekMode: 'RIENG' }));
    await expect(
      firstValueFrom(
        api.saveDraft(t, { policyBindings: [{ flow: 'DANG', packVersionId: PK(4121) }] }),
      ),
    ).rejects.toMatchObject({
      error: {
        code: 'CONFIG-422-POLICY-PACK-NOT-READY',
        title:
          'Bộ quy định đánh giá Khối Đảng · Quy định mới v2.1 chưa đạt 100% bộ ca kiểm thử chuẩn nên chưa gắn được.',
      },
    });
    t = await firstValueFrom(api.saveDraft(t, { policyBindings: BOTH }));
    expect(t.policyBindings?.map((b) => b.version)).toEqual(['2.0.0', '1.3.0']);
    const location = await firstValueFrom(api.provision(t));
    expect(location).toMatch(/^\/api\/v1\/jobs\/[0-9a-f-]+$/);
    const job = await lastValueFrom(TestBed.inject(JobTracker).track(location));
    expect(job).toMatchObject({ status: 'HOAN_TAT', type: 'PROVISION' });
    const after = await firstValueFrom(api.get(t.id));
    expect(after).toMatchObject({ status: 'DANG_CAU_HINH', onboardingStep: 4 });
    expect(api.provisionLocation(after.provisionJobId!)).toBe(location);
    expect(after.statusLog?.[0]).toMatchObject({ toStatus: 'DANG_CAU_HINH' });
  });

  it('BR-01-04: thiếu gói của một luồng thì không cấp phát', async () => {
    const api = setup();
    let t = await firstValueFrom(api.create(input()));
    t = await firstValueFrom(api.saveDraft(t, { cellCode: 'CELL-G2', policyBindings: [BOTH[1]] }));
    await expect(firstValueFrom(api.provision(t))).rejects.toMatchObject({
      status: 422,
      error: { code: 'CONFIG-422-FLOW-PACK-MISSING' },
    });
  });

  it('saga lỗi bù trừ về KHOI_TAO với MSG-01-07, job gần nhất đọc lại được', async () => {
    const api = setup();
    let t = await firstValueFrom(api.create(input()));
    t = await firstValueFrom(
      api.saveDraft(t, {
        cellCode: 'CELL-G2',
        domain: 'loi-tls.okr.viettel.vn',
        policyBindings: BOTH,
      }),
    );
    const job = await lastValueFrom(
      TestBed.inject(JobTracker).track(await firstValueFrom(api.provision(t))),
    );
    expect(job.status).toBe('THAT_BAI');
    expect(job.problem?.title).toContain('Cấp phát lỗi ở bước Cấp chứng chỉ TLS');
    const after = await firstValueFrom(api.get(t.id));
    expect(after.status).toBe('KHOI_TAO');
    const last = await firstValueFrom(api.provisionJob(after.provisionJobId!));
    expect(last.problem?.title).toBe(job.problem?.title);
  });

  it('kiểm tra từng IdP theo idpAlias, kiểm tra tổng thể và kích hoạt', async () => {
    const api = setup();
    const tn = await firstValueFrom(api.get('t-thainguyen'));
    expect(tn.idps?.find((i) => i.idpAlias === 'saml-so-tttt')?.lastTestOk).toBeUndefined();
    const res = await firstValueFrom(api.testIdp(tn.id, 'saml-so-tttt'));
    expect(res.success).toBe(true);
    await expect(firstValueFrom(api.testIdp(tn.id, 'khong-co'))).rejects.toMatchObject({
      status: 422,
    });
    const tn2 = await firstValueFrom(api.get(tn.id));
    expect(tn2.idps?.every((i) => i.lastTestOk)).toBe(true);
    expect(tn2.readinessChecks?.find((c) => c.code === 'HIGH_DISCREPANCY')?.passed).toBe(false);
    await expect(firstValueFrom(api.activate(tn2))).rejects.toMatchObject({
      status: 422,
      error: { code: 'CONFIG-422-HIGH-DISCREPANCY' },
    });

    const qn = await firstValueFrom(api.get('t-quangngai'));
    expect(qn.status).toBe('SAN_SANG');
    expect(qn.readinessChecks?.every((c) => c.passed)).toBe(true);
    expect((await firstValueFrom(api.activate(qn))).status).toBe('DANG_VAN_HANH');
  });

  it('lưu IdP và nguồn nhân sự theo hợp đồng; đổi địa chỉ thì phải kiểm tra lại', async () => {
    const api = setup();
    const tn = await firstValueFrom(api.get('t-thainguyen'));
    const idps = (tn.idps ?? []).map(({ idpAlias, protocol, issuer, isVneid }) => ({
      idpAlias,
      protocol,
      issuer: idpAlias === 'vneid' ? 'https://sso2.vneid.gov.vn' : issuer,
      isVneid,
    }));
    const saved = await firstValueFrom(
      api.saveDraft(tn, { idps, staffSources: [{ type: 'CSDL_CBCCVC' }] }),
    );
    expect(saved.idps?.find((i) => i.idpAlias === 'vneid')?.lastTestOk).toBeUndefined();
    expect(saved.staffSources).toEqual([
      expect.objectContaining({ type: 'CSDL_CBCCVC', connected: true }),
    ]);
    expect(saved.onboardingStep).toBe(6);
    await expect(
      firstValueFrom(api.saveDraft(saved, { idps: [{ ...idps[0], kind: 'LDAP' } as never] })),
    ).rejects.toMatchObject({ status: 422 });
  });

  it('nguồn nhân sự tuỳ chọn (ADR-34): có IdP, chưa chọn nguồn vẫn sang bước 6', async () => {
    const api = setup();
    const tn = await firstValueFrom(api.get('t-thainguyen'));
    const saved = await firstValueFrom(api.saveDraft(tn, { staffSources: [] }));
    expect(saved.staffSources).toEqual([]);
    expect(saved.onboardingStep).toBe(6);
  });

  it('huỷ chỉ khi KHOI_TAO', async () => {
    const api = setup();
    const t = await firstValueFrom(api.get('t-truong-thpt-hue'));
    await firstValueFrom(api.cancel(t));
    await expect(firstValueFrom(api.get(t.id))).rejects.toMatchObject({ status: 404 });
    const qn = await firstValueFrom(api.get('t-quangngai'));
    await expect(firstValueFrom(api.cancel(qn))).rejects.toMatchObject({ status: 409 });
  });
});
