import { TestBed } from '@angular/core/testing';
import { EMBED_APP_ROOT, MemoryLocationStrategy } from './memory-location';

describe('Định tuyến trong bộ nhớ của phần nhúng (WEB-013)', () => {
  function make(): MemoryLocationStrategy {
    TestBed.configureTestingModule({
      providers: [
        MemoryLocationStrategy,
        { provide: EMBED_APP_ROOT, useValue: 'https://okr.example.vn/' },
      ],
    });
    return TestBed.inject(MemoryLocationStrategy);
  }

  it('không đổi địa chỉ trang của cổng; lùi, tiến báo popstate cho router', () => {
    const s = make();
    const before = location.href;
    const seen: unknown[] = [];
    s.onPopState((e) => seen.push(e.state));
    s.pushState({ a: 1 }, '', '/tasks', '');
    s.pushState({ a: 2 }, '', '/tasks/7', 'tab=progress');
    expect(s.path()).toBe('/tasks/7?tab=progress');
    s.back();
    expect(s.path()).toBe('/tasks');
    expect(seen).toEqual([{ a: 1 }]);
    s.forward();
    expect(s.path()).toBe('/tasks/7?tab=progress');
    s.back();
    s.pushState(null, '', '/tasks/9', '');
    s.forward();
    expect(s.path()).toBe('/tasks/9');
    s.replaceState(null, '', '/tasks/10', '');
    expect(s.path()).toBe('/tasks/10');
    expect(location.href).toBe(before);
  });

  it('href của liên kết trỏ tới cùng màn hình ở ứng dụng đầy đủ', () => {
    expect(make().prepareExternalUrl('/tasks/7')).toBe('https://okr.example.vn/tasks/7');
  });
});
