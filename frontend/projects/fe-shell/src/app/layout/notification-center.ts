import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { firstValueFrom, Subscription } from 'rxjs';
import {
  AppNotification,
  NotificationApi,
  NotificationUnreadEvent,
  SseEnd,
  SseEvent,
} from '@vt/api-client';

/** Số thông báo giữ trong hộp của chuông; xem đầy đủ ở trang thông báo (chưa có). */
const KEEP = 10;
/** Thời gian chờ nối lại khi luồng lỗi: tăng gấp đôi từ 5 giây tới tối đa 60 giây. */
const BACKOFF_MS = [5_000, 10_000, 20_000, 40_000, 60_000];

/**
 * Hộp thông báo của chuông trên thanh trên (SCR-0002, SVC-12): tải danh sách, nghe luồng SSE và
 * tự nối lại với `Last-Event-ID`, đánh dấu đã đọc. Khi mất mạng thì dừng, có mạng lại thì nối ngay.
 */
@Injectable({ providedIn: 'root' })
export class NotificationCenter {
  private readonly api = inject(NotificationApi);

  private readonly _items = signal<AppNotification[]>([]);
  private readonly _unread = signal(0);
  private readonly _status = signal<'idle' | 'loading' | 'ready' | 'error'>('idle');
  readonly items = this._items.asReadonly();
  readonly unread = this._unread.asReadonly();
  readonly status = this._status.asReadonly();
  readonly hasUnread = computed(() => this._unread() > 0);

  private running = false;
  private sub: Subscription | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private lastEventId: string | undefined;
  private failures = 0;
  private readonly onOnline = () => {
    if (this.running && !this.sub) this.connect();
  };

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  /** Bắt đầu tải hộp thông báo và nghe luồng; gọi lại không có tác dụng. */
  start(): void {
    if (this.running) return;
    this.running = true;
    globalThis.addEventListener?.('online', this.onOnline);
    void this.refresh();
    this.connect();
  }

  stop(): void {
    this.running = false;
    globalThis.removeEventListener?.('online', this.onOnline);
    this.sub?.unsubscribe();
    this.sub = null;
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  async refresh(): Promise<void> {
    this._status.set('loading');
    try {
      const page = await firstValueFrom(this.api.list({ limit: KEEP }));
      this._items.set(page.items);
      this._unread.set(page.unreadCount);
      this._status.set('ready');
    } catch {
      this._status.set('error');
    }
  }

  async markRead(id: string): Promise<void> {
    const n = this._items().find((i) => i.id === id);
    if (!n || n.readAt) return;
    const res = await firstValueFrom(this.api.markRead({ ids: [id] }));
    const at = new Date().toISOString();
    this._items.update((list) => list.map((i) => (i.id === id ? { ...i, readAt: at } : i)));
    this._unread.set(res.unreadCount);
  }

  async markAllRead(): Promise<void> {
    const res = await firstValueFrom(this.api.markRead({ all: true }));
    const at = new Date().toISOString();
    this._items.update((list) => list.map((i) => (i.readAt ? i : { ...i, readAt: at })));
    this._unread.set(res.unreadCount);
  }

  private connect(): void {
    if (!this.running || this.sub) return;
    if (globalThis.navigator?.onLine === false) return; // chờ sự kiện online
    this.sub = this.api.stream(this.lastEventId).subscribe({
      next: (e) => ('event' in e ? this.handle(e) : this.ended(e)),
      error: () => {
        this.sub = null;
        this.schedule(BACKOFF_MS[Math.min(this.failures++, BACKOFF_MS.length - 1)]);
      },
    });
  }

  private ended(end: SseEnd): void {
    this.sub = null;
    this.failures = 0;
    if (end.lastEventId) this.lastEventId = end.lastEventId;
    this.schedule(end.retryMs ?? BACKOFF_MS[0]);
  }

  private schedule(ms: number): void {
    if (!this.running) return;
    if (this.timer) clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      this.timer = null;
      this.connect();
    }, ms);
  }

  private handle(e: SseEvent): void {
    if (e.id) this.lastEventId = e.id;
    this.failures = 0;
    try {
      if (e.event === 'notification') {
        const n = JSON.parse(e.data) as AppNotification;
        this._items.update((list) => [n, ...list.filter((i) => i.id !== n.id)].slice(0, KEEP));
        if (!n.readAt) this._unread.update((c) => c + 1);
      } else if (e.event === 'unread') {
        const u = JSON.parse(e.data) as NotificationUnreadEvent;
        this._unread.set(u.unreadCount);
        if (u.reload) void this.refresh();
      }
      // sự kiện `job` do JobTracker xử lý; sự kiện lạ bỏ qua
    } catch {
      /* dữ liệu hỏng: bỏ qua sự kiện, không làm đứt luồng */
    }
  }
}
