import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { GlobalSearch, highlightSegments } from './global-search';

describe('Tìm kiếm toàn cục (WEB-012)', () => {
  it('tách đoạn trích theo vị trí khớp, bỏ vị trí ngoài chuỗi', () => {
    expect(highlightSegments('Dự thảo hướng dẫn kiểm điểm', [{ start: 18, length: 9 }])).toEqual([
      { text: 'Dự thảo hướng dẫn ', mark: false },
      { text: 'kiểm điểm', mark: true },
    ]);
    expect(highlightSegments('abc', [{ start: 10, length: 2 }])).toEqual([
      { text: 'abc', mark: false },
    ]);
  });

  it('gõ từ khoá thì gợi ý, mũi tên chọn, Enter mở kết quả; 403 thì ẩn ô', async () => {
    vi.useFakeTimers();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    const nav = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);
    const http = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(GlobalSearch);
    f.detectChanges();
    const input = f.nativeElement.querySelector('input') as HTMLInputElement;
    input.value = 'kiem diem';
    input.dispatchEvent(new Event('input'));
    await vi.advanceTimersByTimeAsync(300);
    http
      .expectOne((r) => r.url === '/api/v1/search' && r.params.get('q') === 'kiem diem')
      .flush({
        items: [
          { type: 'TASK', id: '1', title: 'Nhiệm vụ một', deepLink: '/tasks/1', score: 2 },
          {
            type: 'SOURCE_DOC',
            id: '2',
            title: 'Văn bản hai',
            deepLink: 'https://ngoai.test',
            score: 1,
          },
        ],
        nextCursor: null,
      });
    f.detectChanges();
    expect(input.getAttribute('aria-expanded')).toBe('true');
    expect(f.nativeElement.querySelectorAll('[role=option]').length).toBe(2);
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown' }));
    f.detectChanges();
    expect(input.getAttribute('aria-activedescendant')).toBe('vt-search-opt-0');
    input.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }));
    expect(nav).toHaveBeenCalledWith('/tasks/1');

    input.value = 'van ban';
    input.dispatchEvent(new Event('input'));
    await vi.advanceTimersByTimeAsync(300);
    http.expectOne(() => true).flush({ title: 'Cấm' }, { status: 403, statusText: 'Forbidden' });
    f.detectChanges();
    expect((f.nativeElement as HTMLElement).hidden).toBe(true);
    vi.useRealTimers();
  });
});
