import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { ROUTES } from './admin.routes';
import { AdminHomePage } from './pages/admin-home.page';
import { ADMIN_CAN } from './admin-permissions';

describe('fe-admin', () => {
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
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: ADMIN_CAN, useValue: () => true }],
    });
    const fixture = TestBed.createComponent(AdminHomePage);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('h1')?.textContent).toBe(
      'Quản trị',
    );
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('a strong')?.textContent?.trim(),
    ).toBe('Cây tổ chức');
  });
});
