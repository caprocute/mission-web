import { TestBed } from '@angular/core/testing';
import { attachTelemetryApi } from '@vt/telemetry';
import { AppStatus, GlobalErrorHandler, isChunkLoadError } from './app-status';
import { relativeTime, safeInternalLink } from './relative-time';

describe('Trạng thái ứng dụng và xử lý lỗi (WEB-012)', () => {
  afterEach(() => attachTelemetryApi(null));

  it('nhận ra lỗi tải mã nạp lười sau khi triển khai bản mới', () => {
    expect(
      isChunkLoadError(new TypeError('Failed to fetch dynamically imported module: /x.js')),
    ).toBe(true);
    expect(isChunkLoadError(new Error('Cannot read properties of undefined'))).toBe(false);
  });

  it('lỗi chưa xử lý vào telemetry và bật lời nhắn, lỗi HTTP thì bỏ qua', () => {
    const errors: [string, Record<string, string> | undefined][] = [];
    attachTelemetryApi({
      pushEvent: () => undefined,
      pushError: (e, c) => errors.push([e.message, c]),
    });
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    TestBed.configureTestingModule({ providers: [GlobalErrorHandler] });
    const handler = TestBed.inject(GlobalErrorHandler);
    const status = TestBed.inject(AppStatus);
    handler.handleError({ name: 'HttpErrorResponse', status: 500 });
    expect(status.error()).toBeNull();
    handler.handleError(new Error('hỏng'));
    expect(status.error()).toBe('unexpected');
    expect(errors[0][0]).toBe('hỏng');
    expect(errors[0][1]?.['kind']).toBe('unexpected');
  });

  it('theo dõi mất mạng và có mạng lại', () => {
    vi.useFakeTimers();
    const status = TestBed.inject(AppStatus);
    window.dispatchEvent(new Event('offline'));
    expect(status.online()).toBe(false);
    window.dispatchEvent(new Event('online'));
    expect(status.online()).toBe(true);
    expect(status.reconnected()).toBe(true);
    vi.advanceTimersByTime(4000);
    expect(status.reconnected()).toBe(false);
    vi.useRealTimers();
  });

  it('thời gian tương đối và chỉ cho đường dẫn nội bộ', () => {
    const now = new Date(2026, 9, 22, 10, 0, 0);
    expect(relativeTime(new Date(2026, 9, 22, 9, 58).toISOString(), now)).toBe('2 phút trước');
    expect(relativeTime(new Date(2026, 9, 22, 7, 0).toISOString(), now)).toBe('3 giờ trước');
    expect(relativeTime(new Date(2026, 9, 21, 7, 0).toISOString(), now)).toBe('Hôm qua');
    expect(relativeTime(new Date(2026, 9, 1, 7, 0).toISOString(), now)).toBe('01/10/2026');
    expect(safeInternalLink('/tasks/1')).toBe('/tasks/1');
    expect(safeInternalLink('//ngoai.test')).toBeNull();
    expect(safeInternalLink('javascript:alert(1)')).toBeNull();
  });
});
