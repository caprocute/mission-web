import {
  HttpClient,
  HttpErrorResponse,
  provideHttpClient,
  withInterceptors,
} from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { idempotencyInterceptor } from '../lib/interceptors';
import { ProblemDetails } from '../lib/problem-details';
import {
  DEFAULT_RUNTIME_CONFIG,
  RUNTIME_CONFIG,
  RuntimeConfig,
  isMockedApiPath,
} from '../lib/runtime-config';
import { TaskApi } from '../lib/task-api';
import { MOCK_API_OPTIONS, apiPath, mockApiInterceptor } from './mock-api.interceptor';
import { resetMockData } from './mock-registry';
import { resolveRelativeDates } from './mock-router';

/** WEB-008: interceptor giả lập và fixture module tasks (QT-05, QT-06; SCR-0503, SCR-0601). */
function setup(cfg: Partial<RuntimeConfig> = {}, pageQuery = '') {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      provideHttpClientTesting(),
      { provide: RUNTIME_CONFIG, useValue: { ...DEFAULT_RUNTIME_CONFIG, ...cfg } },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => pageQuery },
      },
    ],
  });
  return { api: TestBed.inject(TaskApi), http: TestBed.inject(HttpClient) };
}

async function problemOf(p: Promise<unknown>): Promise<ProblemDetails & { httpStatus: number }> {
  try {
    await p;
  } catch (e) {
    expect(e).toBeInstanceOf(HttpErrorResponse);
    const err = e as HttpErrorResponse;
    return { ...(err.error as ProblemDetails), httpStatus: err.status };
  }
  throw new Error('Mong đợi lỗi HTTP');
}

const ID1 = '0192a0f0-0001-7000-8000-000000000001';
const ID2 = '0192a0f0-0001-7000-8000-000000000002';

describe('mockApiInterceptor', () => {
  beforeEach(() => resetMockData());

  it('trả danh sách Việc của tôi từ fixture, lọc theo trạng thái và từ khoá', async () => {
    const { api } = setup();
    const all = await firstValueFrom(api.myTasks());
    expect(all.items.length).toBe(10);
    expect(all.nextCursor).toBeNull();
    const open = await firstValueFrom(api.myTasks({ status: ['DA_GIAO', 'DANG_THUC_HIEN'] }));
    expect(open.items.map((t) => t.code).sort()).toEqual([
      'NV-2026-Q4-0001',
      'NV-2026-Q4-0002',
      'NV-2026-Q4-0003',
      'NV-2026-Q4-0142',
      'NV-2026-Q4-0157',
      'NV-2026-Q4-0163',
      'NV-2026-Q4-0171',
      'NV-2026-Q4-0178',
    ]);
    const overdue = await firstValueFrom(api.myTasks({ overdueOnly: true }));
    expect(overdue.items.map((t) => t.code)).toEqual(['NV-2026-Q4-0142', 'NV-2026-Q4-0003']);
    const q = await firstValueFrom(api.myTasks({ q: 'nghị định' }));
    expect(q.items.map((t) => t.code)).toEqual(['NV-2026-Q4-0002']);
  });

  it('phân trang con trỏ, limit không vượt 200', async () => {
    const { api } = setup();
    const p1 = await firstValueFrom(api.myTasks({ limit: 2 }));
    expect(p1.items.length).toBe(2);
    expect(p1.nextCursor).toBe('2');
    const p5 = await firstValueFrom(api.myTasks({ limit: 2, cursor: '9' }));
    expect(p5.items.length).toBe(1);
    expect(p5.nextCursor).toBeNull();
  });

  it('quy đổi ngày tương đối của fixture theo ngày chạy', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.detail(ID1));
    const expected = new Date(Date.now() + 2 * 86_400_000).toISOString().slice(0, 10);
    expect(t.dueDate).toBe(expected);
    expect(t.progressLog[0].at).toMatch(/^\d{4}-\d{2}-\d{2}T08:30:00Z$/);
  });

  it('chi tiết không tồn tại trả 404 theo RFC 9457', async () => {
    const { api } = setup();
    const p = await problemOf(firstValueFrom(api.detail('khong-co')));
    expect(p.httpStatus).toBe(404);
    expect(p.title).toBe('Không tìm thấy nhiệm vụ');
    expect(p.traceId).toMatch(/^[0-9a-f]{32}$/);
  });

  it('cập nhật tiến độ: tăng rowVersion, DA_GIAO → DANG_THUC_HIEN, danh sách thấy thay đổi', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.detail(ID2));
    expect(t.status).toBe('DA_GIAO');
    const u = await firstValueFrom(api.updateProgress(t, { percent: 30, note: 'Bắt đầu' }));
    expect(u.rowVersion).toBe(t.rowVersion + 1);
    expect(u.status).toBe('DANG_THUC_HIEN');
    expect(u.progressLog[0]).toMatchObject({ percent: 30, note: 'Bắt đầu' });
    const list = await firstValueFrom(api.myTasks());
    expect(list.items.find((x) => x.id === ID2)?.progress).toBe(30);
  });

  it('If-Match cũ trả 409 xung đột ghi', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.detail(ID1));
    await firstValueFrom(api.updateProgress(t, { percent: 70, note: '' }));
    const p = await problemOf(firstValueFrom(api.updateProgress(t, { percent: 80, note: '' })));
    expect(p.httpStatus).toBe(409);
  });

  it('tiến độ ngoài 0–100 trả 422 kèm lỗi theo trường', async () => {
    const { api } = setup();
    const t = await firstValueFrom(api.detail(ID1));
    const p = await problemOf(firstValueFrom(api.updateProgress(t, { percent: 120, note: '' })));
    expect(p.httpStatus).toBe(422);
    expect(p.errors?.[0].field).toBe('percent');
  });

  it('cùng Idempotency-Key trả lại kết quả lần đầu, nội dung khác trả 422', async () => {
    const { http } = setup();
    const t = await firstValueFrom(TestBed.inject(TaskApi).detail(ID1));
    const opts = { headers: { 'If-Match': `"${t.rowVersion}"`, 'Idempotency-Key': 'k-1' } };
    const url = `/api/v1/tasks/${ID1}/progress`;
    const a = await firstValueFrom(
      http.post<{ rowVersion: number }>(url, { percent: 65, note: '' }, opts),
    );
    const b = await firstValueFrom(
      http.post<{ rowVersion: number }>(url, { percent: 65, note: '' }, opts),
    );
    expect(b.rowVersion).toBe(a.rowVersion);
    const p = await problemOf(firstValueFrom(http.post(url, { percent: 66, note: '' }, opts)));
    expect(p.httpStatus).toBe(422);
  });

  it('?mock-error=409 của trang chỉ áp cho lệnh ghi; 500 áp cho mọi request', async () => {
    const { api } = setup({}, '?mock-error=409');
    const t = await firstValueFrom(api.detail(ID1));
    const p = await problemOf(firstValueFrom(api.updateProgress(t, { percent: 70, note: '' })));
    expect(p).toMatchObject({ httpStatus: 409, code: 'MOCK-409' });
    TestBed.resetTestingModule();
    const s = setup({}, '?mock-error=500');
    expect((await problemOf(firstValueFrom(s.api.myTasks()))).httpStatus).toBe(500);
  });

  it('?mock-error=422 trên chính request', async () => {
    const { http } = setup();
    const p = await problemOf(
      firstValueFrom(
        http.post(`/api/v1/tasks/${ID1}/progress`, {}, { params: { 'mock-error': '422' } }),
      ),
    );
    expect(p.httpStatus).toBe(422);
  });

  it('tắt useMockApi thì request đi ra mạng thật', async () => {
    const { api } = setup({ useMockApi: false });
    const ctrl = TestBed.inject(HttpTestingController);
    const done = firstValueFrom(api.detail(ID1));
    const req = ctrl.expectOne(`/api/v1/tasks/${ID1}`);
    req.flush({ id: ID1 });
    expect((await done).id).toBe(ID1);
    ctrl.verify();
  });

  it('đường dẫn ngoài /api/v1/ không bị giả lập', async () => {
    const { http } = setup();
    const ctrl = TestBed.inject(HttpTestingController);
    const done = firstValueFrom(http.get('/config.json'));
    ctrl.expectOne('/config.json').flush({ ok: true });
    expect(await done).toEqual({ ok: true });
  });
});

describe('apiPath', () => {
  it('nhận cả đường dẫn tương đối và URL đã ghép gốc API của cell', () => {
    expect(apiPath('/api/v1/tasks/mine?x=1', '/api')).toBe('/api/v1/tasks/mine');
    expect(apiPath('https://api.g4.example/v1/tasks/1', 'https://api.g4.example/')).toBe(
      '/api/v1/tasks/1',
    );
    expect(apiPath('/ui-packs/default/current.json', '/api')).toBeNull();
  });
});

describe('resolveRelativeDates', () => {
  it('đổi @today±N, giữ phần giờ, bỏ $comment', () => {
    const today = new Date('2026-10-19T00:00:00Z');
    expect(
      resolveRelativeDates(
        { $comment: 'x', a: '@today-1', b: ['@today+2T08:00:00Z', 'khác'] },
        today,
      ),
    ).toEqual({
      a: '2026-10-18',
      b: ['2026-10-21T08:00:00Z', 'khác'],
    });
  });
});

describe('giả lập theo module (WEB-070)', () => {
  beforeEach(() => resetMockData());

  it('isMockedApiPath: useMockApi bật thì mọi module; tắt thì chỉ tiền tố trong mockApiModules', () => {
    expect(isMockedApiPath({ useMockApi: true }, '/api/v1/okr/objectives')).toBe(true);
    expect(isMockedApiPath({ useMockApi: false }, '/api/v1/okr/objectives')).toBe(false);
    const cfg = { useMockApi: false, mockApiModules: ['dossiers', '/tasks/source-docs/'] };
    expect(isMockedApiPath(cfg, '/api/v1/dossiers')).toBe(true);
    expect(isMockedApiPath(cfg, '/api/v1/dossiers/D-1/files')).toBe(true);
    expect(isMockedApiPath(cfg, '/api/v1/dossiers-x')).toBe(false);
    expect(isMockedApiPath(cfg, '/api/v1/tasks/source-docs/S-1')).toBe(true);
    expect(isMockedApiPath(cfg, '/api/v1/tasks/T-1')).toBe(false);
    const wild = { useMockApi: false, mockApiModules: ['tasks/*/secret-content'] };
    expect(isMockedApiPath(wild, '/api/v1/tasks/T-1/secret-content/shares/P-1')).toBe(true);
    expect(isMockedApiPath(wild, '/api/v1/tasks/T-1/progress')).toBe(false);
    expect(isMockedApiPath(wild, '/api/v1/tasks/T-1')).toBe(false);
    expect(isMockedApiPath({ useMockApi: false, mockApiModules: ['', ' '] }, '/api/v1/okr')).toBe(
      false,
    );
  });

  it('module thật đi ra mạng, module còn giả lập trả fixture', async () => {
    const { api, http } = setup({ useMockApi: false, mockApiModules: ['catalog'] });
    const ctrl = TestBed.inject(HttpTestingController);
    const real = firstValueFrom(api.myTasks());
    const req = ctrl.expectOne((r) => r.url.includes('/v1/tasks/mine'));
    req.flush({ items: [], nextCursor: null });
    expect((await real).items).toEqual([]);
    const mocked = await firstValueFrom(http.get<unknown>('/api/v1/catalog/items'));
    expect(mocked).toBeTruthy();
    ctrl.verify();
  });
});
