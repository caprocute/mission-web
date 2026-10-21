import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import {
  TRACE_OPTIONS,
  TraceOptions,
  buildTraceparent,
  debugTraceRequested,
  isValidTraceparent,
  traceparentInterceptor,
} from './traceparent.interceptor';

function setup(opts: Partial<TraceOptions> = {}) {
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([traceparentInterceptor])),
      provideHttpClientTesting(),
      {
        provide: TRACE_OPTIONS,
        useValue: { sampleRatio: 1, debugTrace: false, random: () => 0, ...opts },
      },
    ],
  });
  return { http: TestBed.inject(HttpClient), ctrl: TestBed.inject(HttpTestingController) };
}

describe('traceparent (WEB-074)', () => {
  it('sinh traceparent đúng định dạng W3C, lấy mẫu theo tỷ lệ', () => {
    const base = { debugTrace: false, random: () => 0.5 };
    const on = buildTraceparent({ ...base, sampleRatio: 1 });
    const off = buildTraceparent({ ...base, sampleRatio: 0.1 });
    expect(isValidTraceparent(on)).toBe(true);
    expect(on.endsWith('-01')).toBe(true);
    expect(off.endsWith('-00')).toBe(true);
    expect(isValidTraceparent('00-' + '0'.repeat(32) + '-' + 'a'.repeat(16) + '-01')).toBe(false);
  });

  it('dùng span trình duyệt hiện hành khi có', () => {
    const tp = buildTraceparent({
      sampleRatio: 0,
      debugTrace: false,
      random: () => 0,
      activeSpan: () => ({ traceId: 'a'.repeat(32), spanId: 'b'.repeat(16), sampled: true }),
    });
    expect(tp).toBe(`00-${'a'.repeat(32)}-${'b'.repeat(16)}-01`);
  });

  it('gắn traceparent và Baggage tenant cho request API, không ghi đè header có sẵn', () => {
    const { http, ctrl } = setup({ tenant: 'default' });
    http.get('/api/v1/tasks').subscribe();
    http
      .get('/api/v1/okr', {
        headers: { traceparent: '00-' + 'c'.repeat(32) + '-' + 'd'.repeat(16) + '-01' },
      })
      .subscribe();
    const a = ctrl.expectOne('/api/v1/tasks').request;
    expect(isValidTraceparent(a.headers.get('traceparent'))).toBe(true);
    expect(a.headers.get('baggage')).toBe('tenant=default');
    expect(a.headers.has('X-Debug-Trace')).toBe(false);
    expect(ctrl.expectOne('/api/v1/okr').request.headers.get('traceparent')).toContain(
      'c'.repeat(32),
    );
    ctrl.verify();
  });

  it('không gắn cho request ngoài API; bật X-Debug-Trace theo tham số trang', () => {
    const { http, ctrl } = setup({ debugTrace: debugTraceRequested('?debug-trace=1') });
    http.get('/config.json').subscribe();
    http.get('/api/v1/tasks').subscribe();
    expect(ctrl.expectOne('/config.json').request.headers.has('traceparent')).toBe(false);
    expect(ctrl.expectOne('/api/v1/tasks').request.headers.get('X-Debug-Trace')).toBe('1');
    expect(debugTraceRequested('')).toBe(false);
    ctrl.verify();
  });
});
