import {
  HttpClient,
  HttpContext,
  HttpContextToken,
  HttpErrorResponse,
  HttpInterceptorFn,
} from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { Observable, catchError, map, of, throwError } from 'rxjs';
import { components } from './generated/platform';
import { RUNTIME_CONFIG } from './runtime-config';

export type TenantResolution = components['schemas']['TenantResolution'];

/** Khoá `sessionStorage` giữ gốc API của cell (không phải dữ liệu C2) để lần tải sau dùng ngay. */
export const CELL_STORAGE_KEY = 'vt.cellApiBase';

/** Request đi qua điểm vào toàn cục (`apiBaseUrl` của `config.json`), không qua cell của phiên. */
export const GLOBAL_ENTRY = new HttpContextToken<boolean>(() => false);

function readStored(): string | null {
  try {
    return globalThis.sessionStorage?.getItem(CELL_STORAGE_KEY) ?? null;
  } catch {
    return null;
  }
}

function store(base: string): void {
  try {
    globalThis.sessionStorage?.setItem(CELL_STORAGE_KEY, base);
  } catch {
    // trình duyệt chặn sessionStorage: chỉ giữ trong bộ nhớ
  }
}

/**
 * Khám phá cell (WEB-075, TL-01 "Định tuyến tới cell", ADR-07): sau đăng nhập shell gọi
 * `GET /registry/resolve` qua điểm vào toàn cục, lấy URL cổng API của cell làm gốc API cho phiên
 * (signal, không ghi đè `config.json`). Khi bất kỳ lời gọi nào nhận 421, đổi sang cell mới theo
 * `Location` rồi gửi lại đúng một lần. Registry trả 503 thì tiếp tục dùng cell đã lưu.
 */
@Injectable({ providedIn: 'root' })
export class CellRouter {
  private readonly cfg = inject(RUNTIME_CONFIG);
  private readonly http = inject(HttpClient);
  readonly apiBase = signal(readStored() ?? this.cfg.apiBaseUrl);

  /** Gốc API toàn cục của `config.json`. */
  get globalBase(): string {
    return this.cfg.apiBaseUrl;
  }

  use(base: string): void {
    this.apiBase.set(base);
    store(base);
  }

  resolve(tenant: string): Observable<TenantResolution | null> {
    return this.http
      .get<TenantResolution>('/api/v1/registry/resolve', {
        params: { tenant },
        context: new HttpContext().set(GLOBAL_ENTRY, true),
      })
      .pipe(
        map((r) => {
          if (r.cell.apiBaseUrl) this.use(r.cell.apiBaseUrl);
          return r;
        }),
        // 503 (registry ngừng) hay lỗi khác: giữ cell đã lưu, phiên vẫn chạy
        catchError(() => of(null)),
      );
  }
}

function withBase(url: string, base: string): string {
  return base === '/api' ? url : base.replace(/\/$/, '') + url.slice(4);
}

/** Ghép gốc API của cell hiện hành vào đường dẫn `/api/v1/...`; thay `apiBaseUrlInterceptor` cũ. */
export const cellBaseUrlInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.startsWith('/api/v1/')) return next(req);
  const router = inject(CellRouter);
  const base = req.context.get(GLOBAL_ENTRY) ? router.globalBase : router.apiBase();
  const out = req.clone({ url: withBase(req.url, base) });
  return next(out).pipe(
    catchError((err: unknown) => {
      if (
        !(err instanceof HttpErrorResponse) ||
        err.status !== 421 ||
        req.context.get(GLOBAL_ENTRY)
      ) {
        return throwError(() => err);
      }
      const moved = err.headers.get('Location');
      if (!moved) return throwError(() => err);
      router.use(moved);
      // Gửi lại đúng một lần tới cell mới; lần gửi lại đi thẳng xuống chuỗi sau nên không lặp
      return next(req.clone({ url: withBase(req.url, moved) }));
    }),
  );
};
