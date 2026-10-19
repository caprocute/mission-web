import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { App } from './app';
import { ROUTES } from './okr.routes';

describe('fe-okr', () => {
  it('dựng router-outlet cho tuyến', async () => {
    await TestBed.configureTestingModule({
      imports: [App],
      providers: [provideRouter([])],
    }).compileComponents();
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect((fixture.nativeElement as HTMLElement).querySelector('router-outlet')).not.toBeNull();
  });

  it('tuyến gốc là SCR-0201 Cây mục tiêu OKR, có chặn theo ma trận', () => {
    expect(ROUTES[0].path).toBe('');
    expect(ROUTES[0].title).toBe('Cây mục tiêu OKR');
    expect(ROUTES[0].canActivate?.length).toBe(1);
  });
});
