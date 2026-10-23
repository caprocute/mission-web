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
    prov.flush(job('p1', 'TENANT_PROVISION'), {
      status: 202,
      statusText: 'Accepted',
      headers: { Location: '/api/v1/jobs/p1' },
    });
    expect(location).toBe('/api/v1/jobs/p1');
    ctrl.verify();
  });
});
