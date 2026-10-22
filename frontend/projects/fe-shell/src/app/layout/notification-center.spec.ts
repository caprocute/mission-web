import { TestBed } from '@angular/core/testing';
import { AppNotification, NotificationApi, SseEnd, SseEvent } from '@vt/api-client';
import { Observable, Subject, of, throwError } from 'rxjs';
import { NotificationCenter } from './notification-center';

const N = (id: string, readAt: string | null = null): AppNotification => ({
  id,
  templateCode: 'X',
  title: `Thông báo ${id}`,
  priority: 'THUONG',
  createdAt: '2026-10-22T08:00:00Z',
  readAt,
});

describe('NotificationCenter (WEB-012)', () => {
  let streams: { lastId?: string; subject: Subject<SseEvent | SseEnd> }[];
  let api: Partial<NotificationApi>;

  beforeEach(() => {
    vi.useFakeTimers();
    streams = [];
    api = {
      list: () =>
        of({ items: [N('a'), N('b', '2026-10-22T09:00:00Z')], nextCursor: null, unreadCount: 1 }),
      markRead: (body) => of({ updated: 1, unreadCount: body.all ? 0 : 0 }),
      stream: (lastId?: string) => {
        const subject = new Subject<SseEvent | SseEnd>();
        streams.push({ lastId, subject });
        return subject as Observable<SseEvent | SseEnd>;
      },
    };
    TestBed.configureTestingModule({ providers: [{ provide: NotificationApi, useValue: api }] });
  });
  afterEach(() => vi.useRealTimers());

  it('tải hộp thông báo, nhận thông báo mới qua luồng và cộng số chưa đọc', async () => {
    const c = TestBed.inject(NotificationCenter);
    c.start();
    await vi.runOnlyPendingTimersAsync();
    expect(c.unread()).toBe(1);
    streams[0].subject.next({ event: 'notification', id: '7-0', data: JSON.stringify(N('c')) });
    expect(c.items()[0].id).toBe('c');
    expect(c.unread()).toBe(2);
    streams[0].subject.next({ event: 'unread', data: '{"unreadCount":5}' });
    expect(c.unread()).toBe(5);
    c.stop();
  });

  it('nối lại sau retry với Last-Event-ID, lỗi thì chờ tăng dần', async () => {
    const c = TestBed.inject(NotificationCenter);
    c.start();
    streams[0].subject.next({ event: 'unread', id: '9-0', data: '{"unreadCount":1}' });
    streams[0].subject.next({ lastEventId: '9-0', retryMs: 3000 });
    streams[0].subject.complete();
    await vi.advanceTimersByTimeAsync(2999);
    expect(streams).toHaveLength(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(streams[1].lastId).toBe('9-0');
    streams[1].subject.error(new Error('mất kết nối'));
    await vi.advanceTimersByTimeAsync(4999);
    expect(streams).toHaveLength(2);
    await vi.advanceTimersByTimeAsync(1);
    expect(streams).toHaveLength(3);
    c.stop();
    streams[2].subject.complete();
    await vi.advanceTimersByTimeAsync(120_000);
    expect(streams).toHaveLength(3);
  });

  it('đánh dấu một thông báo và tất cả đã đọc', async () => {
    const c = TestBed.inject(NotificationCenter);
    await c.refresh();
    await c.markRead('a');
    expect(c.items().find((i) => i.id === 'a')?.readAt).toBeTruthy();
    expect(c.unread()).toBe(0);
    await c.markAllRead();
    expect(c.items().every((i) => i.readAt)).toBe(true);
  });

  it('dữ liệu sự kiện hỏng không làm đứt luồng; lỗi tải hộp thì báo trạng thái lỗi', async () => {
    api.list = () => throwError(() => new Error('503'));
    const c = TestBed.inject(NotificationCenter);
    c.start();
    await vi.runOnlyPendingTimersAsync();
    expect(c.status()).toBe('error');
    streams[0].subject.next({ event: 'notification', data: '{hỏng' });
    expect(streams[0].subject.observed).toBe(true);
    c.stop();
  });
});
