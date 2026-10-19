import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { ROUTES } from './__SHORT__.routes';
import { __CLASS__HomePage } from './pages/__SHORT__-home.page';

describe('__PROJECT__', () => {
  it('dựng router-outlet cho tuyến', async () => {
    await TestBed.configureTestingModule({ imports: [App], providers: [provideRouter([])] }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('router-outlet')).not.toBeNull();
  });

  it('tuyến gốc trỏ tới trang khung có tiêu đề tiếng Việt', async () => {
    expect(ROUTES[0].path).toBe('');
    const fixture = TestBed.createComponent(__CLASS__HomePage);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('h1')?.textContent).toBe('__TITLE__');
  });
});
