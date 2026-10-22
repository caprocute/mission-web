import { DestroyRef, ErrorHandler, Injectable, inject, signal } from '@angular/core';
import { pushTelemetryError } from '@vt/telemetry';

/** Lỗi khi tải mã nạp lười: thường do vừa triển khai bản mới, tải lại trang là hết. */
export function isChunkLoadError(err: unknown): boolean {
  const msg = err instanceof Error ? `${err.name} ${err.message}` : String(err ?? '');
  return /ChunkLoadError|Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module/i.test(
    msg,
  );
}

/** Trạng thái chung của ứng dụng hiển thị ở dải thông báo: mất mạng, lỗi chưa xử lý. */
@Injectable({ providedIn: 'root' })
export class AppStatus {
  readonly online = signal(globalThis.navigator?.onLine ?? true);
  /** Vừa có mạng lại; tự tắt sau vài giây. */
  readonly reconnected = signal(false);
  /** Loại lỗi chưa xử lý gần nhất, để hiện lời nhắn phù hợp; null là không có. */
  readonly error = signal<'chunk' | 'unexpected' | null>(null);

  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    const on = () => {
      this.online.set(true);
      this.reconnected.set(true);
      if (this.timer) clearTimeout(this.timer);
      this.timer = setTimeout(() => this.reconnected.set(false), 4000);
    };
    const off = () => {
      this.online.set(false);
      this.reconnected.set(false);
    };
    globalThis.addEventListener?.('online', on);
    globalThis.addEventListener?.('offline', off);
    inject(DestroyRef).onDestroy(() => {
      globalThis.removeEventListener?.('online', on);
      globalThis.removeEventListener?.('offline', off);
      if (this.timer) clearTimeout(this.timer);
    });
  }

  dismissError(): void {
    this.error.set(null);
  }
}

/**
 * ErrorHandler toàn cục (WEB-012): ghi console cho người phát triển, gửi telemetry (Faro, WEB-011) kèm
 * tuyến hiện tại không có truy vấn, và bật lời nhắn thân thiện trên dải thông báo. Lỗi HTTP đã được
 * từng màn hình trình bày theo RFC 9457 nên không lặp lại ở đây.
 */
@Injectable()
export class GlobalErrorHandler implements ErrorHandler {
  private readonly status = inject(AppStatus);

  handleError(error: unknown): void {
    const err = (error as { rejection?: unknown })?.rejection ?? error;
    console.error(err);
    if ((err as { name?: string })?.name === 'HttpErrorResponse') return;
    const route = (globalThis.location?.pathname ?? '').slice(0, 200);
    const chunk = isChunkLoadError(err);
    pushTelemetryError(err, { route, kind: chunk ? 'chunk' : 'unexpected' });
    this.status.error.set(chunk ? 'chunk' : 'unexpected');
  }
}
