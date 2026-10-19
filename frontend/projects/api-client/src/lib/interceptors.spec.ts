import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { apiBaseUrlInterceptor, idempotencyInterceptor } from './interceptors';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from './runtime-config';
import { TaskApi } from './task-api';
import { TaskSummary } from './models/task';

/** WEB-007: Idempotency-Key, If-Match, ghép gốc API (baseline mục 13). */
function setup(apiBaseUrl = '/api') {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([apiBaseUrlInterceptor, idempotencyInterceptor])),
      provideHttpClientTesting(),
      { provide: RUNTIME_CONFIG, useValue: { ...DEFAULT_RUNTIME_CONFIG, apiBaseUrl } },
    ],
  });
  return {
    http: TestBed.inject(HttpClient),
    ctrl: TestBed.inject(HttpTestingController),
    api: TestBed.inject(TaskApi),
  };
}

const TASK = { id: 'a/b', rowVersion: 7 } as TaskSummary;

describe('idempotencyInterceptor', () => {
  it('gắn Idempotency-Key cho mọi lệnh ghi, mỗi ý định một khoá', () => {
    const { http, ctrl } = setup();
    for (const m of ['POST', 'PUT', 'PATCH', 'DELETE']) {
      http.request(m, '/api/v1/x', { body: {} }).subscribe();
    }
    const reqs = ctrl.match('/api/v1/x');
    const keys = reqs.map((r) => r.request.headers.get('Idempotency-Key'));
    expect(keys.every((k) => /^[0-9a-f-]{36}$/.test(k ?? ''))).toBe(true);
    expect(new Set(keys).size).toBe(4);
    ctrl.verify();
  });

  it('không gắn cho GET và giữ khoá có sẵn khi thử lại cùng ý định', () => {
    const { http, ctrl } = setup();
    http.get('/api/v1/x').subscribe();
    http.post('/api/v1/y', {}, { headers: { 'Idempotency-Key': 'giu-nguyen' } }).subscribe();
    expect(ctrl.expectOne('/api/v1/x').request.headers.has('Idempotency-Key')).toBe(false);
    expect(ctrl.expectOne('/api/v1/y').request.headers.get('Idempotency-Key')).toBe('giu-nguyen');
  });

  it('không gắn cho request ngoài API, ví dụ đổi mã lấy token của Keycloak', () => {
    const { http, ctrl } = setup();
    const token = 'http://localhost:8180/realms/mission/protocol/openid-connect/token';
    http.post(token, 'grant_type=authorization_code').subscribe();
    expect(ctrl.expectOne(token).request.headers.has('Idempotency-Key')).toBe(false);
    ctrl.verify();
  });
});

describe('If-Match của TaskApi', () => {
  it('updateProgress gửi If-Match bằng rowVersion và mã hoá id trong đường dẫn', async () => {
    const { api, ctrl } = setup();
    const done = firstValueFrom(api.updateProgress(TASK, { percent: 10, note: 'x' }));
    const req = ctrl.expectOne('/api/v1/tasks/a%2Fb/progress');
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('If-Match')).toBe('"7"');
    expect(req.request.headers.has('Idempotency-Key')).toBe(true);
    req.flush({ id: 'a/b', rowVersion: 8 });
    expect((await done).rowVersion).toBe(8);
  });
});

describe('apiBaseUrlInterceptor', () => {
  it('ghép gốc API của cell, bỏ qua đường dẫn ngoài /api/v1/', () => {
    const { http, ctrl } = setup('https://api.g4.example/');
    http.get('/api/v1/tasks/mine').subscribe();
    http.get('/config.json').subscribe();
    ctrl.expectOne('https://api.g4.example/v1/tasks/mine');
    ctrl.expectOne('/config.json');
    ctrl.verify();
  });
});
