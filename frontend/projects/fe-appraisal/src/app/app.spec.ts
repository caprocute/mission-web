import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { ROUTES } from './appraisal.routes';
import { AppraisalHomePage } from './pages/appraisal-home.page';

describe('fe-appraisal', () => {
  it('dựng router-outlet cho tuyến', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('router-outlet')).not.toBeNull();
  });

  it('tuyến gốc trỏ tới trang khung có tiêu đề tiếng Việt', async () => {
    expect(ROUTES[0].path).toBe('');
    const fixture = TestBed.createComponent(AppraisalHomePage);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('h1')?.textContent).toBe(
      'Đánh giá, xếp loại',
    );
  });
});
