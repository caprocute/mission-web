import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { vi } from 'vitest';
import {
  DraftKeeper,
  LOAD_SHED_POLICY,
  LOAD_SHED_REPORTER,
  LoadShedEvent,
  backoffMs,
  isLoadShed,
  loadSheddingInterceptor,
} from './load-shedding';
import { retryAfterMs } from './job-tracker';

const NOW = Date.parse('2026-10-21T08:00:00Z');
const policy = {
  maxRetries: 3,
  baseDelayMs: 1000,
  maxWaitMs: 20_000,
  random: () => 0,
  now: () => NOW,
};

describe('Cắt tải (WEB-073)', () => {
  let http: HttpClient;
  let ctrl: HttpTestingController;
  const events: LoadShedEvent[] = [];

  beforeEach(() => {
    vi.useFakeTimers();
    events.length = 0;
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([loadSheddingInterceptor])),
        provideHttpClientTesting(),
        { provide: LOAD_SHED_POLICY, useValue: policy },
        { provide: LOAD_SHED_REPORTER, useValue: (e: LoadShedEvent) => events.push(e) },
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    ctrl.verify();
    vi.useRealTimers();
  });

  const shed = (status: number, retryAfter?: string) => ({
    status,
    statusText: 'busy',
    headers: (retryAfter ? { 'Retry-After': retryAfter } : {}) as Record<string, string>,
  });

  it('đọc Retry-After dạng giây và dạng ngày giờ HTTP', () => {
    expect(retryAfterMs('5', NOW)).toBe(5000);
    expect(retryAfterMs(new Date(NOW + 7000).toUTCString(), NOW)).toBe(7000);
    expect(retryAfterMs('không hợp lệ', NOW)).toBeNull();
    expect(retryAfterMs(null, NOW)).toBeNull();
  });

  it('lùi cấp số nhân và không chờ ít hơn Retry-After', () => {
    expect(backoffMs(1, null, policy)).toBe(1000);
    expect(backoffMs(3, null, policy)).toBe(4000);
    expect(backoffMs(1, 6000, policy)).toBe(6000);
  });

  it('thử lại GET sau đúng Retry-After rồi thành công', () => {
    let body: unknown;
    http.get('/api/v1/tasks').subscribe((b) => (body = b));
    ctrl.expectOne('/api/v1/tasks').flush(null, shed(503, '3'));
    vi.advanceTimersByTime(2999);
    ctrl.expectNone('/api/v1/tasks');
    vi.advanceTimersByTime(1);
    ctrl.expectOne('/api/v1/tasks').flush({ items: [] });
    expect(body).toEqual({ items: [] });
    expect(events).toEqual([{ status: 503, attempt: 1, waitMs: 3000, gaveUp: false }]);
  });

  it('giữ nguyên Idempotency-Key khi gửi lại lệnh ghi', () => {
    http.post('/api/v1/tasks', {}, { headers: { 'Idempotency-Key': 'k-1' } }).subscribe();
    ctrl.expectOne('/api/v1/tasks').flush(null, shed(429, '1'));
    vi.advanceTimersByTime(1000);
    const again = ctrl.expectOne('/api/v1/tasks');
    expect(again.request.headers.get('Idempotency-Key')).toBe('k-1');
    again.flush({});
  });

  it('không tự thử lại POST không có khoá', () => {
    let error: unknown;
    http.post('/api/v1/tasks', {}).subscribe({ error: (e) => (error = e) });
    ctrl.expectOne('/api/v1/tasks').flush(null, shed(503, '1'));
    vi.advanceTimersByTime(60_000);
    ctrl.expectNone('/api/v1/tasks');
    expect(isLoadShed(error)).toBe(true);
  });

  it('dừng sau 3 lần thử lại', () => {
    let error: unknown;
    http.get('/api/v1/tasks').subscribe({ error: (e) => (error = e) });
    for (let i = 0; i < 4; i++) {
      ctrl.expectOne('/api/v1/tasks').flush(null, shed(503));
      vi.advanceTimersByTime(10_000);
    }
    ctrl.expectNone('/api/v1/tasks');
    expect(isLoadShed(error)).toBe(true);
    expect(events.length).toBe(3);
  });

  it('Retry-After quá ngưỡng chờ thì trả lỗi ngay để người dùng gửi lại sau', () => {
    let error: unknown;
    http.get('/api/v1/tasks').subscribe({ error: (e) => (error = e) });
    ctrl.expectOne('/api/v1/tasks').flush(null, shed(429, '120'));
    expect(isLoadShed(error)).toBe(true);
    expect(events[0].gaveUp).toBe(true);
  });

  it('không thử lại lỗi khác 429 và 503', () => {
    let error: unknown;
    http.get('/api/v1/tasks').subscribe({ error: (e) => (error = e) });
    ctrl.expectOne('/api/v1/tasks').flush(null, { status: 500, statusText: 'x' });
    expect(isLoadShed(error)).toBe(false);
    expect(events.length).toBe(0);
  });

  it('giữ bản nháp trong bộ nhớ, đọc lại là bản sao', () => {
    const keeper = TestBed.inject(DraftKeeper);
    const draft = { note: 'Đã hoàn thành 80%' };
    keeper.keep('progress:1', draft);
    draft.note = 'đổi';
    expect(keeper.restore('progress:1')).toEqual({ note: 'Đã hoàn thành 80%' });
    keeper.clear('progress:1');
    expect(keeper.restore('progress:1')).toBeUndefined();
  });
});
