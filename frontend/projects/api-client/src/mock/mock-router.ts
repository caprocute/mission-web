import { HttpHeaders, HttpParams } from '@angular/common/http';
import { ProblemDetails } from '../lib/problem-details';

/** Request đã chuẩn hoá mà handler giả lập nhận được. */
export interface MockRequest {
  method: string;
  /** Đường dẫn dạng `/api/v1/<module>/...`, không kèm gốc API của cell và query. */
  path: string;
  params: HttpParams;
  headers: HttpHeaders;
  body: unknown;
  /** Tham số lấy từ mẫu đường dẫn, ví dụ `:id`. */
  pathParams: Record<string, string>;
}

export interface MockResponse {
  status: number;
  body?: unknown;
  headers?: Record<string, string>;
}

/** Một tuyến giả lập: `pattern` dạng `/api/v1/tasks/:id/progress`. */
export interface MockRoute {
  method: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  pattern: string;
  handle(req: MockRequest): MockResponse;
}

/** Fixture của một module API (baseline mục 13), ví dụ `tasks`. */
export interface MockModule {
  name: string;
  routes: MockRoute[];
  /** Đưa dữ liệu về trạng thái ban đầu của fixture (dùng trong kiểm thử). */
  reset(): void;
}

export const ok = (body: unknown, headers?: Record<string, string>): MockResponse => ({
  status: 200,
  body,
  headers,
});

/** Lỗi theo RFC 9457 như máy chủ thật trả (xem `ProblemDetails`). */
export function problem(
  status: number,
  title: string,
  extra: Partial<ProblemDetails> = {},
): MockResponse {
  const body: ProblemDetails = {
    type: 'about:blank',
    title,
    status,
    traceId: randomHex(32),
    ...extra,
  };
  return { status, body, headers: { 'Content-Type': 'application/problem+json' } };
}

/** Khớp `path` với `pattern`; trả tham số đường dẫn hoặc `null`. */
export function matchPath(pattern: string, path: string): Record<string, string> | null {
  const a = pattern.split('/');
  const b = path.split('/');
  if (a.length !== b.length) return null;
  const out: Record<string, string> = {};
  for (let i = 0; i < a.length; i++) {
    if (a[i].startsWith(':')) out[a[i].slice(1)] = decodeURIComponent(b[i]);
    else if (a[i] !== b[i]) return null;
  }
  return out;
}

const RELATIVE_DATE = /^@today([+-]\d+)?(T.*)?$/;

/**
 * Quy đổi chuỗi ngày tương đối trong fixture (`@today+2`, `@today-3T08:30:00Z`) sang ngày thật,
 * để dữ liệu "quá hạn", "còn N ngày" luôn đúng ngữ cảnh khi chạy.
 */
export function resolveRelativeDates<T>(value: T, today = new Date()): T {
  if (typeof value === 'string') {
    const m = RELATIVE_DATE.exec(value);
    if (!m) return value;
    const day = new Date(today.getTime() + Number(m[1] ?? 0) * 86_400_000)
      .toISOString()
      .slice(0, 10);
    return (day + (m[2] ?? '')) as T;
  }
  if (Array.isArray(value)) return value.map((v) => resolveRelativeDates(v, today)) as T;
  if (value && typeof value === 'object') {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value)) {
      if (k !== '$comment') out[k] = resolveRelativeDates(v, today);
    }
    return out as T;
  }
  return value;
}

function randomHex(len: number): string {
  const bytes = new Uint8Array(len / 2);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (x) => x.toString(16).padStart(2, '0')).join('');
}
