import { HttpErrorResponse, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { Injectable, InjectionToken, inject } from '@angular/core';
import { retry, throwError, timer } from 'rxjs';

/**
 * Xử lý cắt tải (WEB-073, TL-04 "Hành vi khi bão hoà"): 429 do vượt hạn mức và 503 do quá tải
 * mang `Retry-After`. Client tự thử lại có lùi thời gian kèm nhiễu, không chờ ít hơn `Retry-After`,
 * và chỉ thử lại lời gọi an toàn: GET/HEAD hoặc lệnh ghi có `Idempotency-Key` (máy chủ khử trùng
 * theo khoá, request gửi lại giữ nguyên khoá vì chính đối tượng request được gửi lại).
 */
export const LOAD_SHED_STATUSES = new Set([429, 503]);

export interface LoadShedPolicy {
  /** Số lần thử lại tối đa sau lần gọi đầu. */
  maxRetries: number;
  /** Thời gian chờ gốc của lần thử lại thứ nhất (ms); lần sau gấp đôi. */
  baseDelayMs: number;
  /** Chờ quá ngưỡng này (ms) thì không tự thử lại mà trả lỗi để người dùng gửi lại sau. */
  maxWaitMs: number;
  /** Nguồn ngẫu nhiên cho nhiễu, thay được trong kiểm thử. */
  random: () => number;
  /** Đồng hồ (ms) để đọc `Retry-After` dạng ngày giờ HTTP. */
  now: () => number;
}

export const LOAD_SHED_POLICY = new InjectionToken<LoadShedPolicy>('vt.loadShedPolicy', {
  providedIn: 'root',
  factory: () => ({
    maxRetries: 3,
    baseDelayMs: 1000,
    maxWaitMs: 20_000,
    random: Math.random,
    now: Date.now,
  }),
});

/** Sự kiện cắt tải gửi cho telemetry: chỉ số liệu, không kèm dữ liệu biểu mẫu hay đường dẫn có tham số. */
export interface LoadShedEvent {
  status: number;
  attempt: number;
  waitMs: number;
  gaveUp: boolean;
}

export const LOAD_SHED_REPORTER = new InjectionToken<(e: LoadShedEvent) => void>(
  'vt.loadShedReporter',
  { providedIn: 'root', factory: () => () => undefined },
);

/** Đọc `Retry-After` (số giây hoặc ngày giờ HTTP) ra mili giây; `null` nếu không có hoặc sai dạng. */
export function retryAfterMs(value: string | null, now: number): number | null {
  if (!value) return null;
  const v = value.trim();
  if (/^\d+$/.test(v)) return Number(v) * 1000;
  const at = Date.parse(v);
  return Number.isNaN(at) ? null : Math.max(0, at - now);
}

/** Lời gọi được tự thử lại: đọc, hoặc ghi có `Idempotency-Key`. */
export function isSafeToRetry(req: HttpRequest<unknown>): boolean {
  return req.method === 'GET' || req.method === 'HEAD' || req.headers.has('Idempotency-Key');
}

/** Thời gian chờ trước lần thử lại thứ `attempt` (từ 1): lùi cấp số nhân, nhiễu tới 50%, không dưới `Retry-After`. */
export function backoffMs(attempt: number, retryAfter: number | null, p: LoadShedPolicy): number {
  const exp = p.baseDelayMs * 2 ** (attempt - 1);
  const jittered = exp + Math.floor(exp * 0.5 * p.random());
  return Math.max(jittered, retryAfter ?? 0);
}

export const loadSheddingInterceptor: HttpInterceptorFn = (req, next) => {
  const isApi = req.url.startsWith('/api/') || req.url.includes('/api/v1/');
  if (!isApi || !isSafeToRetry(req)) return next(req);
  const policy = inject(LOAD_SHED_POLICY);
  const report = inject(LOAD_SHED_REPORTER);
  return next(req).pipe(
    retry({
      count: policy.maxRetries,
      delay: (err: unknown, attempt: number) => {
        if (!(err instanceof HttpErrorResponse) || !LOAD_SHED_STATUSES.has(err.status)) {
          return throwError(() => err);
        }
        const wait = backoffMs(
          attempt,
          retryAfterMs(err.headers.get('Retry-After'), policy.now()),
          policy,
        );
        if (wait > policy.maxWaitMs) {
          report({ status: err.status, attempt, waitMs: wait, gaveUp: true });
          return throwError(() => err);
        }
        report({ status: err.status, attempt, waitMs: wait, gaveUp: false });
        return timer(wait);
      },
    }),
  );
};

/** Lỗi cắt tải: hệ thống bận, nội dung được giữ và người dùng có thể gửi lại. */
export function isLoadShed(err: unknown): boolean {
  const status = (err as { status?: number } | null)?.status;
  return status !== undefined && LOAD_SHED_STATUSES.has(status);
}

/**
 * Giữ bản nháp trong bộ nhớ của phiên (WEB-073): biểu mẫu đăng ký nội dung đang soạn theo khoá,
 * khi lệnh ghi thất bại vì cắt tải thì đọc lại để người dùng gửi lại. Không ghi xuống trình duyệt
 * nên không lưu dữ liệu C2 dạng rõ (AGENTS.md); mất khi tải lại trang (giả định, ghi ở wiki/08).
 */
@Injectable({ providedIn: 'root' })
export class DraftKeeper {
  private readonly drafts = new Map<string, unknown>();

  keep<T>(key: string, value: T): void {
    this.drafts.set(key, structuredClone(value));
  }

  restore<T>(key: string): T | undefined {
    const v = this.drafts.get(key);
    return v === undefined ? undefined : (structuredClone(v) as T);
  }

  clear(key: string): void {
    this.drafts.delete(key);
  }
}
