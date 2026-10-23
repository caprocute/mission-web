import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { JobAccepted } from './job-tracker';
import { TenantOnboarding } from './models/tenant-onboarding';
import { PolicyApi } from './policy-api';
import { TenantApi } from './tenant-api';
import { UiPackApi } from './ui-pack-api';
import { WorkflowTemplateApi } from './workflow-template-api';

/**
 * WEB-072: lời gọi quản trị, cấu hình khớp hợp đồng (`config.yaml`, `policies.yaml`,
 * `platform.yaml`, `jobs.yaml`): tác vụ dài trả 202 + `Location: /api/v1/jobs/{id}` được đọc
 * thành job để `JobTracker` theo dõi; header `If-Match`, kiểu merge-patch đúng như hợp đồng.
 */
function setup() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting()],
  });
  return {
    ctrl: TestBed.inject(HttpTestingController),
    policy: TestBed.inject(PolicyApi),
    ui: TestBed.inject(UiPackApi),
    wf: TestBed.inject(WorkflowTemplateApi),
    tenant: TestBed.inject(TenantApi),
  };
}

const job = (id: string, type: string) => ({ id, type, status: 'CHO', progress: 0 });

describe('lời gọi quản trị, cấu hình khớp hợp đồng (WEB-072)', () => {
  it('chạy kiểm thử và phê duyệt Policy Pack trả job theo Location, dựng lại khi thiếu header', () => {
    const { ctrl, policy } = setup();
    const ref = { id: 'pk1', v: '2.0' };
    const out: JobAccepted[] = [];
    policy.runTests(ref, '"3"').subscribe((a) => out.push(a));
    policy.approve(ref, '"4"').subscribe((a) => out.push(a));
    const run = ctrl.expectOne('/api/v1/policies/packs/pk1/versions/2.0/test-runs');
    expect(run.request.headers.get('If-Match')).toBe('"3"');
    run.flush(job('j1', 'TEST_RUN'), {
      status: 202,
      statusText: 'Accepted',
      headers: { Location: '/api/v1/jobs/j1' },
    });
    const ap = ctrl.expectOne('/api/v1/policies/packs/pk1/versions/2.0/approve');
    expect(ap.request.headers.get('If-Match')).toBe('"4"');
    ap.flush(job('j2', 'POLICY_PUBLISH'), { status: 202, statusText: 'Accepted' });
    expect(out.map((a) => a.location)).toEqual(['/api/v1/jobs/j1', '/api/v1/jobs/j2']);
    expect(out[1].job.type).toBe('POLICY_PUBLISH');
    ctrl.verify();
  });

  it('lưu tham số luồng duyệt lần đầu gửi If-Match "0"', () => {
    const { ctrl, policy } = setup();
    policy.saveWorkflowBinding({ id: 'pk1', v: '2.0' }, 'DANH_GIA', null, {} as never).subscribe();
    policy.saveWorkflowBinding({ id: 'pk1', v: '2.0' }, 'DUYET', 5, {} as never).subscribe();
    const [first, second] = ctrl.match((r) => r.url.includes('/workflow-bindings/'));
    expect(first.request.method).toBe('PUT');
    expect(first.request.headers.get('If-Match')).toBe('"0"');
    expect(second.request.headers.get('If-Match')).toBe('"5"');
    first.flush({});
    second.flush({});
    ctrl.verify();
  });

  it('gửi duyệt, phát hành bộ giao diện và mẫu luồng trả job; kích hoạt không gửi thân', () => {
    const { ctrl, ui, wf } = setup();
    const out: JobAccepted[] = [];
    const ref = { id: 'ui1', v: '1.2.0' };
    ui.submit(ref, 2).subscribe((a) => out.push(a));
    ui.publish(ref, 3).subscribe((a) => out.push(a));
    ui.activate(ref, 4).subscribe();
    wf.runTests('wf1').subscribe((a) => out.push(a));
    wf.approve('wf1', 6).subscribe((a) => out.push(a));
    const accept = (url: string, id: string, type: string) => {
      const req = ctrl.expectOne(url);
      req.flush(job(id, type), {
        status: 202,
        statusText: 'Accepted',
        headers: { Location: `/api/v1/jobs/${id}` },
      });
      return req.request;
    };
    expect(accept('/api/v1/ui/packs/ui1/versions/1.2.0/submit', 'a', 'TEST_RUN').body).toBeNull();
    expect(
      accept('/api/v1/ui/packs/ui1/versions/1.2.0/publish', 'b', 'RENDER').headers.get('If-Match'),
    ).toBe('"3"');
    const act = ctrl.expectOne('/api/v1/ui/packs/ui1/versions/1.2.0/activate');
    expect(act.request.body).toBeNull();
    expect(act.request.headers.get('If-Match')).toBe('"4"');
    act.flush({});
    expect(accept('/api/v1/workflow/templates/wf1/test', 'c', 'TEST_RUN').body).toBeNull();
    accept('/api/v1/workflow/templates/wf1/approve', 'd', 'PUBLISH');
    expect(out.map((a) => a.location)).toEqual(
      ['a', 'b', 'c', 'd'].map((i) => `/api/v1/jobs/${i}`),
    );
    ctrl.verify();
  });

  it('lưu nháp tenant dùng merge-patch, cấp phát gửi If-Match', () => {
    const { ctrl, tenant } = setup();
    const t = { id: 't1', rowVersion: 9 } as TenantOnboarding;
    tenant.saveDraft(t, { domain: 'a.okr.viettel.vn' }).subscribe();
    let location = '';
    tenant.provision(t).subscribe((l) => (location = l));
    const patch = ctrl.expectOne('/api/v1/config/tenants/t1');
    expect(patch.request.method).toBe('PATCH');
    expect(patch.request.headers.get('Content-Type')).toBe('application/merge-patch+json');
    expect(patch.request.headers.get('If-Match')).toBe('"9"');
    patch.flush(t);
    const prov = ctrl.expectOne('/api/v1/config/tenants/t1/provision');
    expect(prov.request.headers.get('If-Match')).toBe('"9"');
    prov.flush(job('p1', 'PROVISION'), {
      status: 202,
      statusText: 'Accepted',
      headers: { Location: '/api/v1/jobs/p1' },
    });
    expect(location).toBe('/api/v1/jobs/p1');
    ctrl.verify();
  });

  it('khởi tạo tenant: khai báo, lấy hồ sơ, huỷ, kích hoạt theo đường dẫn hợp đồng', () => {
    const { ctrl, tenant } = setup();
    const t = { id: 't1', rowVersion: 4 } as TenantOnboarding;
    tenant
      .create({
        code: 'hagiang',
        name: 'Tỉnh Hà Giang',
        sector: 'CHINH_QUYEN',
        contractRef: '91/2026',
        flows: ['DANG'],
        expectedAccounts: 60_000,
        adminContact: { fullName: 'Nguyễn Văn A', email: 'a@hagiang.gov.vn' },
      })
      .subscribe();
    tenant.get('t1').subscribe();
    tenant.cancel(t).subscribe();
    tenant.activate(t).subscribe();
    const create = ctrl.expectOne({ method: 'POST', url: '/api/v1/config/tenants' });
    expect(create.request.body.adminContact).toEqual({
      fullName: 'Nguyễn Văn A',
      email: 'a@hagiang.gov.vn',
    });
    expect(Object.keys(create.request.body)).not.toContain('isolationRequired');
    create.flush({});
    ctrl.expectOne({ method: 'GET', url: '/api/v1/config/tenants/t1' }).flush({});
    const del = ctrl.expectOne({ method: 'DELETE', url: '/api/v1/config/tenants/t1' });
    expect(del.request.headers.get('If-Match')).toBe('"4"');
    del.flush(null);
    const act = ctrl.expectOne('/api/v1/config/tenants/t1/activate');
    expect(act.request.headers.get('If-Match')).toBe('"4"');
    act.flush({});
    // không còn các đường dẫn ngoài hợp đồng
    ctrl.expectNone((r) => /\/(onboarding|checks|domain-check|cell-options)$/.test(r.url));
    ctrl.verify();
  });

  it('cell ứng viên từ /registry/cells theo mô hình chữ hoa; thiếu số tài khoản thì lấy cellLoads', () => {
    const { ctrl, tenant } = setup();
    let out: { code: string; loadAfter: number; eligible: boolean }[] = [];
    tenant.cellOptions('bridge', 100_000).subscribe((c) => (out = c));
    const cells = ctrl.expectOne((r) => r.url === '/api/v1/registry/cells');
    expect(cells.request.params.get('tenancyModel')).toBe('BRIDGE');
    const cell = (code: string, extra = {}) => ({
      id: code,
      code,
      name: code,
      primaryDc: 'HOALAC',
      tenancyModel: 'BRIDGE',
      apiBaseUrl: 'https://x',
      capacityAccounts: 1_000_000,
      status: 'HOAT_DONG',
      ...extra,
    });
    cells.flush({
      items: [cell('CELL-A'), cell('CELL-B', { status: 'BAO_TRI' })],
      nextCursor: null,
    });
    const list = ctrl.expectOne((r) => r.url === '/api/v1/config/tenants');
    expect(list.request.params.get('limit')).toBe('1');
    list.flush({
      items: [],
      nextCursor: null,
      summary: {
        cellLoads: [
          {
            cellCode: 'CELL-A',
            accountCount: 800_000,
            capacityAccounts: 1_000_000,
            loadPercent: 80,
          },
        ],
      },
    });
    expect(out.map((c) => [c.code, c.eligible])).toEqual([
      ['CELL-B', false],
      ['CELL-A', false],
    ]);
    expect(out[1].loadAfter).toBeCloseTo(0.9);
    ctrl.verify();
  });

  it('Policy Pack gốc theo luồng và UI Pack đang phát hành thành lựa chọn của bước 3', () => {
    const { ctrl, tenant } = setup();
    let packs: { id: string; goldenPassRate: number | null }[] = [];
    let uis: { id: string; version: string }[] = [];
    tenant.policyPacks('DANG').subscribe((p) => (packs = p));
    tenant.uiPacks().subscribe((u) => (uis = u));
    const req = ctrl.expectOne((r) => r.url === '/api/v1/policies/packs');
    expect(req.request.params.get('flow')).toBe('DANG');
    expect(req.request.params.get('origin')).toBe('GOC_TOAN_CUC');
    const v = (id: string, status: string, goldenPassRate: number | null) => ({
      id,
      policyPackId: 'p1',
      version: '2.0.0',
      status,
      goldenPassRate,
      rowVersion: 1,
    });
    req.flush({
      items: [
        {
          id: 'p1',
          code: 'khoi-dang',
          name: 'Khối Đảng',
          flow: 'DANG',
          origin: 'GOC_TOAN_CUC',
          versions: [v('v1', 'NHAP', null), v('v2', 'DA_PHAT_HANH', 0.96)],
        },
      ],
      nextCursor: null,
    });
    ctrl
      .expectOne((r) => r.url === '/api/v1/ui/packs')
      .flush({
        items: [
          { id: 'u1', code: 'a', name: 'A', currentVersionId: 'uv1', currentVersion: '1.7.0' },
          { id: 'u2', code: 'b', name: 'B', currentVersionId: null },
        ],
        nextCursor: null,
      });
    expect(packs).toEqual([expect.objectContaining({ id: 'v2', goldenPassRate: 0.96 })]);
    expect(uis).toEqual([{ id: 'uv1', name: 'A', version: '1.7.0' }]);
    ctrl.verify();
  });

  it('kiểm tra kết nối một IdP gửi idpAlias; job cấp phát đọc tại /jobs/{id}', () => {
    const { ctrl, tenant } = setup();
    let ok: boolean | undefined;
    tenant.testIdp('t1', 'sso-tinh').subscribe((r) => (ok = r.success));
    tenant.provisionJob('j9').subscribe();
    const test = ctrl.expectOne('/api/v1/config/tenants/t1/idp/test');
    expect(test.request.body).toEqual({ idpAlias: 'sso-tinh' });
    test.flush({ success: true, checkedAt: '2026-10-19T10:12:00+07:00' });
    ctrl.expectOne('/api/v1/jobs/j9').flush(job('j9', 'PROVISION'));
    expect(ok).toBe(true);
    ctrl.verify();
  });
});
