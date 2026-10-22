/** Một sự kiện Server-Sent Events đã phân tích (WHATWG HTML "event stream interpretation"). */
export interface SseEvent {
  /** Tên sự kiện; mặc định `message`. */
  event: string;
  data: string;
  /** Mã sự kiện, dùng làm `Last-Event-ID` khi nối lại. */
  id?: string;
}

/**
 * Bộ phân tích luồng SSE tăng dần: nhận từng đoạn văn bản (có thể cắt giữa dòng), trả các sự kiện
 * đã đủ. Dòng chú thích (`: ping`) bị bỏ; `retry:` cập nhật `retryMs`.
 */
export class SseParser {
  retryMs: number | null = null;
  private buffer = '';
  private data: string[] = [];
  private event = '';
  private id: string | undefined;

  push(chunk: string): SseEvent[] {
    this.buffer += chunk;
    const out: SseEvent[] = [];
    let nl: number;
    while ((nl = this.buffer.search(/\r\n|\r|\n/)) >= 0) {
      const line = this.buffer.slice(0, nl);
      this.buffer = this.buffer.slice(nl + (this.buffer.startsWith('\r\n', nl) ? 2 : 1));
      if (line === '') {
        if (this.data.length) {
          out.push({ event: this.event || 'message', data: this.data.join('\n'), id: this.id });
        }
        this.data = [];
        this.event = '';
        continue;
      }
      if (line.startsWith(':')) continue;
      const colon = line.indexOf(':');
      const field = colon < 0 ? line : line.slice(0, colon);
      const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
      if (field === 'data') this.data.push(value);
      else if (field === 'event') this.event = value;
      else if (field === 'id' && !value.includes('\0')) this.id = value;
      else if (field === 'retry' && /^\d+$/.test(value)) this.retryMs = Number(value);
    }
    return out;
  }
}
