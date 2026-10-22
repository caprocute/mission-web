import { HttpClient, HttpDownloadProgressEvent, HttpEventType } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-path';
import {
  NotificationPage,
  NotificationReadRequest,
  NotificationReadResult,
  SearchResultPage,
  SearchResultType,
} from './models/notification';
import { SseEvent, SseParser } from './sse';

/** Kết thúc một lượt luồng SSE: mã sự kiện cuối và khoảng chờ máy chủ đề nghị trước khi nối lại. */
export interface SseEnd {
  lastEventId?: string;
  retryMs: number | null;
}

/**
 * Client thông báo trong ứng dụng (SVC-12, `platform.yaml`): hộp thông báo, đánh dấu đã đọc và
 * luồng SSE thời gian thực. Luồng đọc qua HttpClient (fetch) chứ không qua `EventSource`, để mang
 * `Authorization` và đi qua chuỗi interceptor của shell (traceparent, ô giả lập).
 */
@Injectable({ providedIn: 'root' })
export class NotificationApi {
  private readonly http = inject(HttpClient);

  list(
    opts: { unread?: boolean; limit?: number; cursor?: string } = {},
  ): Observable<NotificationPage> {
    const params: Record<string, string> = { limit: String(opts.limit ?? 10) };
    if (opts.unread) params['unread'] = 'true';
    if (opts.cursor) params['cursor'] = opts.cursor;
    return this.http.get<NotificationPage>(apiUrl('/notifications'), { params });
  }

  markRead(body: NotificationReadRequest): Observable<NotificationReadResult> {
    return this.http.post<NotificationReadResult>(apiUrl('/notifications/read'), body);
  }

  /**
   * Mở một lượt luồng SSE. Phát từng sự kiện; hoàn tất khi máy chủ đóng luồng, kèm mã sự kiện cuối
   * và `retry` để người gọi tự nối lại (`Last-Event-ID`). Huỷ đăng ký thì đóng kết nối.
   */
  stream(lastEventId?: string): Observable<SseEvent | SseEnd> {
    return new Observable<SseEvent | SseEnd>((sub) => {
      const parser = new SseParser();
      let seen = 0;
      let last = lastEventId;
      const feed = (text: string | null | undefined) => {
        if (!text || text.length <= seen) return;
        for (const e of parser.push(text.slice(seen))) {
          if (e.id) last = e.id;
          sub.next(e);
        }
        seen = text.length;
      };
      const headers: Record<string, string> = { Accept: 'text/event-stream' };
      if (lastEventId) headers['Last-Event-ID'] = lastEventId;
      const inner = this.http
        .get(apiUrl('/notifications/stream'), {
          headers,
          observe: 'events',
          reportProgress: true,
          responseType: 'text',
        })
        .subscribe({
          next: (ev) => {
            if (ev.type === HttpEventType.DownloadProgress) {
              feed((ev as HttpDownloadProgressEvent).partialText);
            } else if (ev.type === HttpEventType.Response) {
              feed(ev.body);
            }
          },
          error: (err) => sub.error(err),
          complete: () => {
            parser.push('\n\n');
            sub.next({ lastEventId: last, retryMs: parser.retryMs });
            sub.complete();
          },
        });
      return () => inner.unsubscribe();
    });
  }
}

/** Client tìm kiếm toàn văn (SVC-16, `GET /api/v1/search`); kết quả đã được kiểm quyền ở máy chủ. */
@Injectable({ providedIn: 'root' })
export class SearchApi {
  private readonly http = inject(HttpClient);

  search(
    q: string,
    opts: { type?: SearchResultType; limit?: number } = {},
  ): Observable<SearchResultPage> {
    const params: Record<string, string> = { q, limit: String(opts.limit ?? 8) };
    if (opts.type) params['type'] = opts.type;
    return this.http.get<SearchResultPage>(apiUrl('/search'), { params });
  }
}
