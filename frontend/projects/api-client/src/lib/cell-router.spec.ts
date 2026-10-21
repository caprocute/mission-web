import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { CELL_STORAGE_KEY, CellRouter, cellBaseUrlInterceptor } from './cell-router';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from './runtime-config';

const GLOBAL = 'https://api.okrkpi.vn';
const HN = 'https://api.cell-hn.okrkpi.vn';
const HCM = 'https://api.cell-hcm.okrkpi.vn';

function setup() {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([cellBaseUrlInterceptor])),
      provideHttpClientTesting(),
      { provide: RUNTIME_CONFIG, useValue: { ...DEFAULT_RUNTIME_CONFIG, apiBaseUrl: GLOBAL } },
    ],
  });
  return {
    router: TestBed.inject(CellRouter),
    http: TestBed.inject(HttpClient),
    ctrl: TestBed.inject(HttpTestingController),
  };
}

describe('Khám phá cell (WEB-075)', () => {
  beforeEach(() => sessionStorage.removeItem(CELL_STORAGE_KEY));
  afterEach(() => TestBed.inject(HttpTestingController).verify());

  it('đặt gốc API của phiên từ kết quả phân giải, gọi registry qua điểm vào toàn cục', async () => {
    const { router, http, ctrl } = setup();
    const done = firstValueFrom(router.resolve('dukcqtw'));
    const req = ctrl.expectOne(`${GLOBAL}/v1/registry/resolve?tenant=dukcqtw`);
    req.flush({ cell: { apiBaseUrl: HN } });
    await done;
    expect(router.apiBase()).toBe(HN);
    expect(sessionStorage.getItem(CELL_STORAGE_KEY)).toBe(HN);
    http.get('/api/v1/tasks').subscribe();
    ctrl.expectOne(`${HN}/v1/tasks`).flush({});
  });

  it('registry 503 thì giữ cell đã lưu', async () => {
    sessionStorage.setItem(CELL_STORAGE_KEY, HN);
    const { router, ctrl } = setup();
    const done = firstValueFrom(router.resolve('dukcqtw'));
    ctrl
      .expectOne(`${GLOBAL}/v1/registry/resolve?tenant=dukcqtw`)
      .flush(null, { status: 503, statusText: 'x', headers: { 'Retry-After': '30' } });
    expect(await done).toBeNull();
    expect(router.apiBase()).toBe(HN);
  });

  it('421 thì đổi sang cell mới và gửi lại đúng một lần', () => {
    sessionStorage.setItem(CELL_STORAGE_KEY, HN);
    const { router, http, ctrl } = setup();
    let error: unknown;
    http.get('/api/v1/tasks').subscribe({ error: (e) => (error = e) });
    ctrl
      .expectOne(`${HN}/v1/tasks`)
      .flush(null, { status: 421, statusText: 'x', headers: { Location: HCM } });
    expect(router.apiBase()).toBe(HCM);
    ctrl
      .expectOne(`${HCM}/v1/tasks`)
      .flush(null, { status: 421, statusText: 'x', headers: { Location: HN } });
    expect((error as { status: number }).status).toBe(421);
    expect(router.apiBase()).toBe(HCM);
  });
});
