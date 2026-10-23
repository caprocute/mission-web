import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AppraisalApi } from './appraisal-api';
import { ComplaintApi } from './complaint-api';
import { ReopenApi } from './reopen-api';
import { AppraisalProfile } from './models/appraisal';

/**
 * WEB-071: tham số query và header của các lời gọi đánh giá, giám sát khớp hợp đồng
 * (`appraisals.yaml`, `oversight.yaml`): kỳ, đơn vị dùng tham số chung `period`, `unit` của
 * common.yaml; thao tác ghi lên hồ sơ gửi `If-Match` là ETag `"<row_version>"` (AP-05).
 */
function setup() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting()],
  });
  return {
    ctrl: TestBed.inject(HttpTestingController),
    appraisal: TestBed.inject(AppraisalApi),
    complaint: TestBed.inject(ComplaintApi),
    reopen: TestBed.inject(ReopenApi),
  };
}

describe('lời gọi khớp hợp đồng (WEB-071)', () => {
  it('hàng đợi hồ sơ gửi period, unit và lặp status', () => {
    const { ctrl, appraisal } = setup();
    appraisal
      .profiles({ periodId: 'p1', unitId: 'u1', status: ['CHO_THAM_DINH', 'DA_THAM_DINH'] })
      .subscribe();
    const req = ctrl.expectOne((r) => r.url === '/api/v1/appraisals/profiles');
    const p = req.request.params;
    expect(p.get('period')).toBe('p1');
    expect(p.get('unit')).toBe('u1');
    expect(p.has('periodId')).toBe(false);
    expect(p.has('unitId')).toBe(false);
    expect(p.getAll('status')).toEqual(['CHO_THAM_DINH', 'DA_THAM_DINH']);
    req.flush({ items: [], nextCursor: null });
    ctrl.verify();
  });

  it('danh sách khiếu nại và yêu cầu mở lại kỳ lọc kỳ bằng period', () => {
    const { ctrl, complaint, reopen } = setup();
    complaint.list({ periodId: 'p1', status: ['MOI', 'DA_TIEP_NHAN'] }).subscribe();
    reopen.list({ periodId: 'p2', status: ['CHO_DUYET_1'] }).subscribe();
    const c = ctrl.expectOne((r) => r.url === '/api/v1/oversight/complaints');
    const r = ctrl.expectOne((x) => x.url === '/api/v1/oversight/reopen-requests');
    expect(c.request.params.get('period')).toBe('p1');
    expect(c.request.params.get('status')).toBe('MOI,DA_TIEP_NHAN');
    expect(r.request.params.get('period')).toBe('p2');
    expect(c.request.params.has('periodId') || r.request.params.has('periodId')).toBe(false);
    c.flush({ items: [], nextCursor: null });
    r.flush({ items: [], nextCursor: null });
    ctrl.verify();
  });

  it('lưu nháp tự đánh giá gửi If-Match là ETag của hồ sơ', () => {
    const { ctrl, appraisal } = setup();
    const profile = { id: 'h1', rowVersion: 7 } as AppraisalProfile;
    appraisal.saveSelfAssessment(profile, {} as never).subscribe();
    const req = ctrl.expectOne('/api/v1/appraisals/profiles/h1/self-assessment');
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('If-Match')).toBe('"7"');
    req.flush(profile);
    ctrl.verify();
  });
});
