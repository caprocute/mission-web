import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { ROUTES } from './dashboard.routes';
import { DASHBOARD_CAN } from './dashboard-permissions';
import { DashboardHomePage } from './pages/dashboard-home.page';

describe('fe-dashboard', () => {
  it('dựng router-outlet cho tuyến', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('router-outlet')).not.toBeNull();
  });

  it('tuyến gốc là trang Bảng điều hành; người không có quyền xem bảng được hướng dẫn', async () => {
    expect(ROUTES[0].path).toBe('');
    TestBed.configureTestingModule({
      providers: [provideRouter([]), { provide: DASHBOARD_CAN, useValue: () => false }],
    });
    const fixture = TestBed.createComponent(DashboardHomePage);
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('h1')?.textContent).toBe(
      'Bảng điều hành',
    );
  });
});
