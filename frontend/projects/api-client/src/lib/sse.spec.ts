import { SseParser } from './sse';

describe('SseParser (WEB-012)', () => {
  it('ghép sự kiện bị cắt giữa chừng, bỏ dòng ping, nhớ id và retry', () => {
    const p = new SseParser();
    expect(p.push('retry: 5000\n\n: ping\n\nid: 1-0\nevent: notifi')).toEqual([]);
    const out = p.push('cation\ndata: {"a":1}\ndata: {"b":2}\r\n\r\nid: 2-0\ndata: x\n\n');
    expect(out).toEqual([
      { event: 'notification', data: '{"a":1}\n{"b":2}', id: '1-0' },
      { event: 'message', data: 'x', id: '2-0' },
    ]);
    expect(p.retryMs).toBe(5000);
  });
});
