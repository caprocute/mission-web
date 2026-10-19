import {
  HttpErrorResponse,
  HttpHeaders,
  HttpInterceptorFn,
  HttpRequest,
  HttpResponse,
} from '@angular/common/http';
import { InjectionToken, inject } from '@angular/core';
import { Observable, delay, from, mergeMap, of, throwError } from 'rxjs';
import { RUNTIME_CONFIG } from '../lib/runtime-config';
import { MockResponse, problem } from './mock-router';

export interface MockApiOptions {
  /** Độ trễ giả lập tối thiểu và tối đa (ms) cho mỗi phản hồi. */
  minDelayMs: number;
  maxDelayMs: number;
  /** Nguồn query của trang để đọc `mock-error`, `mock-delay`; mặc định `location.search`. */
  pageQuery?: () => string;
}

export const MOCK_API_OPTIONS = new InjectionToken<MockApiOptions>('vt.mockApiOptions', {
  providedIn: 'root',
  factory: () => ({ minDelayMs: 100, maxDelayMs: 200 }),
});

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
/**
 * Lỗi có nghĩa với lệnh ghi (bước lên MFA, xung đột `If-Match`, dữ liệu không hợp lệ) chỉ áp
 * cho lệnh ghi. 401 giả lập là yêu cầu bước lên RFC 9470 (BR-00-02, WEB-006).
 */
const WRITE_ONLY_ERRORS = new Set([401, 409, 412, 422]);
const FORCED_HEADERS: Record<number, Record<string, string>> = {
  401: {
    'WWW-Authenticate':
      'Bearer error="insufficient_user_authentication", error_description="Cần xác thực nhiều lớp", acr_values="aal2"',
  },
};
const FORCED_TITLES: Record<number, string> = {
  401: 'Thao tác yêu cầu xác thực nhiều lớp',
  403: 'Đồng chí không có quyền thực hiện thao tác này',
  409: 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại',
  412: 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại',
  422: 'Dữ liệu không hợp lệ',
  500: 'Lỗi máy chủ, vui lòng thử lại sau',
  503: 'Hệ thống đang quá tải, vui lòng thử lại sau',
};

const stepUpDone = new Set<string>();

function withHeaders(res: MockResponse, extra?: Record<string, string>): MockResponse {
  return extra ? { ...res, headers: { ...res.headers, ...extra } } : res;
}

/**
 * Trả đường dẫn `/api/v1/...` nếu request gọi API của cell, ngược lại `null`.
 * Chạy sau `apiBaseUrlInterceptor`, nên chấp nhận cả URL đã ghép gốc API.
 */
export function apiPath(url: string, apiBaseUrl: string): string | null {
  const u = url.split(/[?#]/)[0];
  if (u.startsWith('/api/v1/')) return u;
  const base = apiBaseUrl.replace(/\/$/, '');
  if (base && u.startsWith(base + '/v1/')) return '/api' + u.slice(base.length);
  return null;
}

/** Đọc mã lỗi ép từ `?mock-error=` của request hoặc của trang; `null` nếu không áp dụng. */
export function forcedError(req: HttpRequest<unknown>, pageQuery: string): number | null {
  const raw = req.params.get('mock-error') ?? new URLSearchParams(pageQuery).get('mock-error');
  const status = Number(raw);
  if (!raw || !Number.isInteger(status) || status < 400 || status > 599) return null;
  if (WRITE_ONLY_ERRORS.has(status) && !WRITE_METHODS.has(req.method)) return null;
  return status;
}

/**
 * Interceptor giả lập API (WEB-008): khi `useMockApi` bật, trả fixture JSON theo đường dẫn
 * thay vì gọi mạng, nên client và component luôn đi đúng đường thật (HttpClient, interceptor,
 * `If-Match`, `Idempotency-Key`, lỗi RFC 9457). Đặt cuối chuỗi interceptor.
 * Không áp cho đường dẫn ngoài `/api/v1/` (config.json, UI Pack, remoteEntry).
 */
export const mockApiInterceptor: HttpInterceptorFn = (req, next) => {
  const cfg = inject(RUNTIME_CONFIG);
  if (!cfg.useMockApi) return next(req);
  const path = apiPath(req.url, cfg.apiBaseUrl);
  if (!path) return next(req);
  const opts = inject(MOCK_API_OPTIONS);
  const query = (opts.pageQuery ?? (() => globalThis.location?.search ?? ''))();
  let forced = forcedError(req, query);
  if (forced === 401) {
    // Bước lên chỉ đòi một lần cho mỗi ý định ghi: gửi lại cùng Idempotency-Key sau MFA thì qua
    const key = req.headers.get('Idempotency-Key') ?? '';
    if (stepUpDone.has(key)) forced = null;
    else stepUpDone.add(key);
  }
  const pageDelay = Number(new URLSearchParams(query).get('mock-delay'));
  const ms =
    Number.isFinite(pageDelay) && pageDelay > 0
      ? pageDelay
      : opts.minDelayMs + Math.random() * Math.max(0, opts.maxDelayMs - opts.minDelayMs);

  const response$: Observable<MockResponse> =
    forced !== null
      ? of(
          withHeaders(
            problem(forced, FORCED_TITLES[forced] ?? `Lỗi giả lập ${forced}`, {
              code: `MOCK-${forced}`,
            }),
            FORCED_HEADERS[forced],
          ),
        )
      : from(import('./mock-registry')).pipe(
          mergeMap(({ handleMockRequest }) =>
            of(
              handleMockRequest({
                method: req.method,
                path,
                params: req.params,
                headers: req.headers,
                body: req.body,
              }),
            ),
          ),
        );

  return response$.pipe(
    delay(ms),
    mergeMap((res) => {
      const headers = new HttpHeaders(res.headers ?? {});
      if (res.status >= 200 && res.status < 300) {
        return of(
          new HttpResponse({
            status: res.status,
            body: res.body ?? null,
            headers,
            url: req.urlWithParams,
          }),
        );
      }
      return throwError(
        () =>
          new HttpErrorResponse({
            status: res.status,
            statusText: String((res.body as { title?: string } | undefined)?.title ?? ''),
            error: res.body,
            headers,
            url: req.urlWithParams,
          }),
      );
    }),
  );
};
