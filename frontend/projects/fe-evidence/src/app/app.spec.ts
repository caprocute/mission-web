import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { ROUTES } from './evidence.routes';

describe('fe-evidence', () => {
  it('dựng router-outlet cho tuyến', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('router-outlet')).not.toBeNull();
  });

  it('tuyến gốc là danh sách minh chứng, `:id` là SCR-0701', () => {
    expect(ROUTES.map((r) => [r.path, r.title])).toEqual([
      ['', 'Sản phẩm và minh chứng'],
      [':id', 'Nộp minh chứng'],
    ]);
  });
});
