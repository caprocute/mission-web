import { HttpHeaders, HttpParams } from '@angular/common/http';
import { beforeEach, describe, expect, it } from 'vitest';
import { NotificationPage } from '../../lib/models/notification';
import { SseParser } from '../../lib/sse';
import { NOTIFICATIONS_MOCK } from './notifications.mock';

function call(method: string, path: string, body: unknown = null, params = new HttpParams()) {
  const route = NOTIFICATIONS_MOCK.routes.find((r) => r.method === method && r.pattern === path)!;
  return route.handle({ method, path, params, headers: new HttpHeaders(), body, pathParams: {} });
}

describe('NOTIFICATIONS_MOCK (WEB-012)', () => {
  beforeEach(() => NOTIFICATIONS_MOCK.reset());

  it('đếm chưa đọc, đánh dấu một hoặc tất cả, thân sai trả 422', () => {
    const page = call('GET', '/api/v1/notifications').body as NotificationPage;
    expect(page.unreadCount).toBe(3);
    expect(page.items[0].createdAt >= page.items[1].createdAt).toBe(true);
    expect(call('POST', '/api/v1/notifications/read', { ids: [page.items[0].id] }).body).toEqual({
      updated: 1,
      unreadCount: 2,
    });
    expect(call('POST', '/api/v1/notifications/read', { all: true }).body).toEqual({
      updated: 2,
      unreadCount: 0,
    });
    expect(call('POST', '/api/v1/notifications/read', {}).status).toBe(422);
  });

  it('luồng SSE có retry và sự kiện unread hiện hành', () => {
    const parser = new SseParser();
    const events = parser.push(call('GET', '/api/v1/notifications/stream').body as string);
    expect(parser.retryMs).toBe(60000);
    expect(events).toHaveLength(1);
    expect(events[0].event).toBe('unread');
    expect(JSON.parse(events[0].data)).toEqual({ unreadCount: 3 });
  });
});
