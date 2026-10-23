import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { Job } from './models/job';
import { OrgTreeApi, orgImportErrors, recordedAsOfParam } from './org-tree-api';

/** WEB-072: client cây tổ chức SCR-0103 khớp hợp đồng `contracts/openapi/modules/org.yaml`. */
function setup() {
  TestBed.configureTestingModule({
    providers: [provideHttpClient(), provideHttpClientTesting()],
  });
  return { api: TestBed.inject(OrgTreeApi), http: TestBed.inject(HttpTestingController) };
}

describe('OrgTreeApi (WEB-072)', () => {
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('cây gốc gọi /org/units/tree kèm hai trục thời gian, nhánh gọi /org/units/{id}/subtree', async () => {
    const { api, http } = setup();
    const tree = firstValueFrom(api.tree({ asOf: '2025-06-30' }, 3));
    const req = http.expectOne((r) => r.url === '/api/v1/org/units/tree');
    expect(req.request.method).toBe('GET');
    expect(req.request.params.get('asOf')).toBe('2025-06-30');
    expect(req.request.params.get('depth')).toBe('3');
    expect(req.request.params.has('parent')).toBe(false);
    req.flush({
      asOf: '2025-06-30',
      recordedAsOf: '2026-01-01T00:00:00Z',
      readOnly: true,
      roots: [],
    });
    expect((await tree).readOnly).toBe(true);

    const sub = firstValueFrom(api.subtree('u 1'));
    const sreq = http.expectOne((r) => r.url === '/api/v1/org/units/u%201/subtree');
    expect(sreq.request.params.get('depth')).toBe('1');
    sreq.flush({
      asOf: '2026-01-01',
      recordedAsOf: '2026-01-01T00:00:00Z',
      readOnly: false,
      roots: [],
    });
    await sub;
  });

  it('recordedAsOf dạng ngày được đổi thành date-time cuối ngày', () => {
    const { api, http } = setup();
    const iso = recordedAsOfParam('2026-03-10');
    expect(Date.parse(iso)).toBe(new Date('2026-03-10T23:59:59.999').getTime());
    expect(recordedAsOfParam('2026-03-10T08:00:00Z')).toBe('2026-03-10T08:00:00Z');
    api.unit('x', { recordedAsOf: '2026-03-10' }).subscribe();
    const req = http.expectOne((r) => r.url === '/api/v1/org/units/x');
    expect(req.request.params.get('recordedAsOf')).toBe(iso);
    expect(req.request.params.has('asOf')).toBe(false);
    req.flush({});
  });

  it('tìm đơn vị gửi q và limit, trả mảng items', async () => {
    const { api, http } = setup();
    const res = firstValueFrom(api.search('so noi vu'));
    const req = http.expectOne((r) => r.url === '/api/v1/org/units/search');
    expect(req.request.params.get('q')).toBe('so noi vu');
    expect(req.request.params.get('limit')).toBe('50');
    req.flush({ items: [{ id: 'a', code: 'A', name: 'A', unitType: 'CO_QUAN', ancestors: [] }] });
    expect((await res).map((m) => m.id)).toEqual(['a']);
  });

  it('lịch sử chỉ gửi recordedAsOf và phân trang con trỏ; phân công tối đa 200', () => {
    const { api, http } = setup();
    api.history('x', { recordedAsOf: '2026-01-01T00:00:00Z' }, { cursor: 'c1' }).subscribe();
    const h = http.expectOne((r) => r.url === '/api/v1/org/units/x/history');
    expect(h.request.params.keys().sort()).toEqual(['cursor', 'limit', 'recordedAsOf']);
    expect(h.request.params.get('cursor')).toBe('c1');
    h.flush({ items: [], nextCursor: null });

    api.assignments('x', { asOf: '2025-01-01' }, { limit: 500 }).subscribe();
    const a = http.expectOne((r) => r.url === '/api/v1/org/units/x/assignments');
    expect(a.request.params.get('limit')).toBe('200');
    expect(a.request.params.get('asOf')).toBe('2025-01-01');
    a.flush({ items: [], nextCursor: null });
  });

  it('sửa gửi PUT OrgUnitInput kèm If-Match theo rowVersion', () => {
    const { api, http } = setup();
    const body = {
      code: 'A',
      name: 'Đơn vị A',
      level: 'TINH',
      unitType: 'CO_QUAN' as const,
      subjectGroupId: 'g',
      flows: ['NHA_NUOC' as const],
      validFrom: '2026-01-01',
    };
    api.update({ id: 'x', rowVersion: 7 }, body).subscribe();
    const req = http.expectOne('/api/v1/org/units/x');
    expect(req.request.method).toBe('PUT');
    expect(req.request.headers.get('If-Match')).toBe('"7"');
    expect(req.request.body).toEqual(body);
    req.flush({});
  });

  it('thay đổi tổ chức gửi OrgChangeRequest, nhận OrgChangeOrder', async () => {
    const { api, http } = setup();
    const res = firstValueFrom(
      api.change('x', {
        changeType: 'SAP_NHAP',
        effectiveOn: '2026-01-01',
        orderNo: '1/QĐ',
        orderDate: '2025-12-20',
        relatedUnitIds: ['y'],
      }),
    );
    const req = http.expectOne('/api/v1/org/units/x/changes');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toMatchObject({ changeType: 'SAP_NHAP', orderDate: '2025-12-20' });
    req.flush(
      {
        id: 'o',
        changeType: 'SAP_NHAP',
        orderNo: '1/QĐ',
        effectiveOn: '2026-01-01',
        status: 'NHAP',
      },
      { status: 201, statusText: 'Created' },
    );
    expect((await res).status).toBe('NHAP');
  });

  it('nhập Excel đọc 202 + Location thành job theo dõi; dựng lại Location từ id khi thiếu', async () => {
    const { api, http } = setup();
    const job: Job = { id: 'j1', type: 'IMPORT', status: 'CHO', progress: 0 };
    const first = firstValueFrom(api.import(new File(['x'], 'a.xlsx'), '2026-01-01'));
    const req = http.expectOne('/api/v1/org/imports');
    const form = req.request.body as FormData;
    expect((form.get('file') as File).name).toBe('a.xlsx');
    expect(form.get('effectiveFrom')).toBe('2026-01-01');
    req.flush(job, {
      status: 202,
      statusText: 'Accepted',
      headers: { Location: '/api/v1/jobs/j1' },
    });
    expect(await first).toEqual({ location: '/api/v1/jobs/j1', job });

    const second = firstValueFrom(api.import(new File(['x'], 'b.xlsx')));
    const req2 = http.expectOne('/api/v1/org/imports');
    expect((req2.request.body as FormData).has('effectiveFrom')).toBe(false);
    req2.flush(job, { status: 202, statusText: 'Accepted' });
    expect((await second).location).toBe('/api/v1/jobs/j1');
  });

  it('lỗi từng dòng của job nhập lấy từ problem.errors', () => {
    setup();
    const job: Job = {
      id: 'j',
      type: 'IMPORT',
      status: 'THAT_BAI',
      problem: {
        type: 'about:blank',
        title: 'Tệp nhập chưa hợp lệ',
        status: 422,
        errors: [
          { field: 'rows.12.subjectGroup', message: 'Dòng 12: thiếu khối' },
          { field: 'rows.0.file', message: 'Tệp không đúng mẫu' },
          { field: 'file', message: 'Tệp quá lớn' },
        ],
      },
    };
    expect(orgImportErrors(job)).toEqual([
      { row: 12, message: 'Dòng 12: thiếu khối' },
      { row: null, message: 'Tệp không đúng mẫu' },
      { row: null, message: 'Tệp quá lớn' },
    ]);
    expect(orgImportErrors(null)).toEqual([]);
  });
});
