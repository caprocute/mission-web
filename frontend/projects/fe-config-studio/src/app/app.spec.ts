import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { ROUTES } from './config-studio.routes';

describe('fe-config-studio', () => {
  it('dựng router-outlet cho tuyến', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('router-outlet')).not.toBeNull();
  });

  it('tuyến gốc là danh sách chính sách, phiên bản có hai thẻ công thức và bảng quyết định', () => {
    expect(ROUTES[0].path).toBe('');
    expect(ROUTES[0].title).toBe('Chính sách đánh giá');
    expect(ROUTES[1].children?.map((r) => r.path)).toEqual(['', 'cong-thuc', 'bang-quyet-dinh']);
  });
});
