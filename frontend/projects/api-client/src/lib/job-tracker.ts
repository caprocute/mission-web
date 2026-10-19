import { HttpClient, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { Injectable, InjectionToken, inject } from '@angular/core';
import {
  EMPTY,
  Observable,
  defer,
  distinctUntilChanged,
  expand,
  map,
  merge,
  retry,
  switchMap,
  takeWhile,
  throwError,
  timer,
} from 'rxjs';
import { JOB_TERMINAL_STATUSES, Job } from './models/job';

export interface JobTrackerOptions {
  /** Khoảng chờ thăm dò đầu tiên (ms), nhân đôi sau mỗi lần tới `maxDelayMs`. */
  initialDelayMs: number;
  maxDelayMs: number;
  /** Số lần thử lại khi lỗi mạng hoặc 5xx, 429 trước khi báo lỗi. */
  maxRetries: number;
}

export const JOB_TRACKER_OPTIONS = new InjectionToken<JobTrackerOptions>('vt.jobTrackerOptions', {
  providedIn: 'root',
  factory: () => ({ initialDelayMs: 1000, maxDelayMs: 10_000, maxRetries: 3 }),
});

export interface TrackOptions {
  /**
   * Nguồn đẩy cập nhật (SSE `GET /api/v1/notifications/stream` của SVC-12, khi WEB-012 có).
   * Khi có, cập nhật từ nguồn này được trộn với thăm dò; thăm dò vẫn chạy làm dự phòng.
   */
  events$?: Observable<Job>;
}

/** Lấy `Location` của phản hồi 202; `null` nếu không phải tác vụ nền. */
export function jobLocation(res: HttpResponse<unknown>): string | null {
  if (res.status !== 202) return null;
  return res.headers.get('Location');
}

/** Đọc `Retry-After` (giây hoặc ngày HTTP) thành mili giây. */
export function retryAfterMs(value: string | null, now = Date.now()): number | null {
  if (!value) return null;
  const secs = Number(value);
  if (Number.isFinite(secs)) return Math.max(0, secs * 1000);
  const at = Date.parse(value);
  return Number.isNaN(at) ? null : Math.max(0, at - now);
}

const isTerminal = (j: Job) => JOB_TERMINAL_STATUSES.includes(j.status);

/**
 * Theo dõi tác vụ dài trả `202 Accepted` + `Location: /api/v1/jobs/{id}` (baseline mục 13,
 * NFR-03, SLO-ASYNC). Thăm dò có giãn cách (1 s, 2 s, 4 s… tối đa 10 s, theo `Retry-After`
 * nếu máy chủ gửi), dừng ở trạng thái kết thúc. Người dùng đóng màn hình thì huỷ đăng ký,
 * tác vụ vẫn chạy ở máy chủ (MSG-09-15).
 */
@Injectable({ providedIn: 'root' })
export class JobTracker {
  private readonly http = inject(HttpClient);
  private readonly opts = inject(JOB_TRACKER_OPTIONS);

  /** Phát mỗi trạng thái mới của job, hoàn tất sau khi phát trạng thái kết thúc. */
  track(location: string, options: TrackOptions = {}): Observable<Job> {
    const url = location.replace(/^https?:\/\/[^/]+/, '');
    const fetchOnce = () =>
      this.http.get<Job>(url, { observe: 'response' }).pipe(
        retry({
          count: this.opts.maxRetries,
          delay: (err: unknown, attempt: number) => {
            const transient =
              err instanceof HttpErrorResponse &&
              (err.status === 0 || err.status === 429 || err.status >= 500);
            if (!transient) return throwError(() => err);
            const wait =
              retryAfterMs(err.headers?.get('Retry-After') ?? null) ??
              Math.min(this.opts.initialDelayMs * 2 ** attempt, this.opts.maxDelayMs);
            return timer(wait);
          },
        }),
        map((res) => ({
          job: res.body as Job,
          retryAfter: retryAfterMs(res.headers.get('Retry-After')),
        })),
      );

    let attempt = 0;
    const polled$ = defer(fetchOnce).pipe(
      expand(({ job, retryAfter }) => {
        if (isTerminal(job)) return EMPTY;
        const wait =
          retryAfter ?? Math.min(this.opts.initialDelayMs * 2 ** attempt++, this.opts.maxDelayMs);
        return timer(wait).pipe(switchMap(fetchOnce));
      }),
      map(({ job }) => job),
    );

    const source$ = options.events$ ? merge(polled$, options.events$) : polled$;
    return source$.pipe(
      distinctUntilChanged(
        (a, b) => a.status === b.status && a.progress === b.progress && a.message === b.message,
      ),
      takeWhile((j) => !isTerminal(j), true),
    );
  }
}
