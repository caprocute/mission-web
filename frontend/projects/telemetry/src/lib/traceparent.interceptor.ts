import { HttpInterceptorFn } from '@angular/common/http';
import { InjectionToken, inject } from '@angular/core';

/** Ngữ cảnh span trình duyệt hiện hành (Faro/OTel web SDK khi WEB-011 bật). */
export interface ActiveSpanContext {
  traceId: string;
  spanId: string;
  sampled: boolean;
}

/**
 * Tuỳ chọn truy vết của shell (WEB-074, TL-04 "Trace: OpenTelemetry và lấy mẫu theo đuôi").
 * `activeSpan` trả span hiện hành khi có SDK; không có thì interceptor tự sinh id.
 */
export interface TraceOptions {
  /** Tỷ lệ lấy mẫu phía trình duyệt (`telemetry.sampleRatio` của RuntimeConfig), mặc định 1. */
  sampleRatio: number;
  /** Mã tenant đưa vào W3C Baggage; không đưa dữ liệu cá nhân. */
  tenant?: string;
  /** Bật `X-Debug-Trace` cho hỗ trợ kỹ thuật (tham số trang `?debug-trace=1`, suy luận). */
  debugTrace: boolean;
  activeSpan?: () => ActiveSpanContext | null;
  random: () => number;
}

export const TRACE_OPTIONS = new InjectionToken<TraceOptions>('vt.traceOptions', {
  providedIn: 'root',
  factory: () => ({ sampleRatio: 1, debugTrace: false, random: Math.random }),
});

/** Đọc `?debug-trace=1` của trang, dùng khi dựng `TRACE_OPTIONS` ở shell. */
export function debugTraceRequested(search = globalThis.location?.search ?? ''): boolean {
  return new URLSearchParams(search).get('debug-trace') === '1';
}

function hex(bytes: number): string {
  const a = crypto.getRandomValues(new Uint8Array(bytes));
  return Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('');
}

const TRACEPARENT = /^00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]$/;

/** Kiểm định dạng W3C `traceparent` phiên bản 00. */
export function isValidTraceparent(v: string | null): boolean {
  return !!v && TRACEPARENT.test(v) && !/^00-0{32}-/.test(v) && !/-0{16}-0[01]$/.test(v);
}

/** Dựng `traceparent` từ span hiện hành nếu có, nếu không thì sinh id mới và lấy mẫu theo tỷ lệ. */
export function buildTraceparent(opts: TraceOptions): string {
  const span = opts.activeSpan?.();
  if (span) return `00-${span.traceId}-${span.spanId}-${span.sampled ? '01' : '00'}`;
  const sampled = opts.random() < opts.sampleRatio;
  return `00-${hex(16)}-${hex(8)}-${sampled ? '01' : '00'}`;
}

/**
 * Gắn header W3C `traceparent` (và Baggage `tenant`, `X-Debug-Trace` khi bật) cho mỗi request API
 * để trace nối từ trình duyệt qua APISIX tới mission-core (NFR-10, ADR-12). Giữ nguyên
 * `traceparent` có sẵn. Remote dùng chung HttpClient của shell nên mọi remote đi qua đây.
 */
export const traceparentInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.includes('/api/')) return next(req);
  const opts = inject(TRACE_OPTIONS);
  const headers: Record<string, string> = {};
  if (!req.headers.has('traceparent')) headers['traceparent'] = buildTraceparent(opts);
  if (opts.tenant && !req.headers.has('baggage')) {
    // ui-text-ok: giá trị header W3C Baggage gửi máy chủ, không hiển thị cho người dùng
    headers['baggage'] = `tenant=${encodeURIComponent(opts.tenant)}`;
  }
  if (opts.debugTrace) headers['X-Debug-Trace'] = '1';
  return Object.keys(headers).length ? next(req.clone({ setHeaders: headers })) : next(req);
};
