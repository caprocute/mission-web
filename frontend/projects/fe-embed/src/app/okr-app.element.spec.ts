import { Component } from '@angular/core';
import { LocationStrategy } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '@vt/api-client';
import { SessionService } from '@vt/auth';
import { ThemeService } from '@vt/design-system';
import { MessageService } from 'primeng/api';
import { EMBED_APP_ROOT, MemoryLocationStrategy } from './memory-location';
import { OkrAppElement } from './okr-app.element';

@Component({ template: '<h1>Màn hình thử</h1>' })
class StubPage {}

describe('Phần tử <vt-okr-app> (WEB-013)', () => {
  function setup() {
    TestBed.configureTestingModule({
      providers: [
        provideRouter([{ path: '**', component: StubPage }]),
        { provide: EMBED_APP_ROOT, useValue: 'https://okr.example.vn/' },
        { provide: LocationStrategy, useClass: MemoryLocationStrategy },
        MessageService,
        { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
        {
          provide: SessionService,
          useValue: {
            user: () => ({ name: 'Thử' }),
            notice: () => null,
            stepUpRequest: () => null,
            clearNotice: () => undefined,
          },
        },
      ],
    });
    const f = TestBed.createComponent(OkrAppElement);
    const changes: string[] = [];
    f.componentInstance.routeChange.subscribe((u) => changes.push(u));
    return { f, changes, router: TestBed.inject(Router) };
  }

  it('mở màn hình theo thuộc tính route, phát routeChange, không đổi địa chỉ trang', async () => {
    const before = location.href;
    const { f, changes, router } = setup();
    f.componentRef.setInput('route', 'tasks/7');
    f.detectChanges();
    await f.whenStable();
    expect(router.url).toBe('/tasks/7');
    expect(changes).toEqual(['/tasks/7']);
    expect(location.href).toBe(before);
    f.detectChanges();
    const link = f.nativeElement.querySelector('a.full') as HTMLAnchorElement;
    expect(link.href).toBe('https://okr.example.vn/tasks/7');
    expect(link.target).toBe('_blank');
  });

  it('nút "Chữ lớn" bật chế độ cao tuổi, có aria-pressed', async () => {
    const { f } = setup();
    f.detectChanges();
    await f.whenStable();
    const theme = TestBed.inject(ThemeService);
    theme.mode.set('default');
    f.detectChanges();
    const btn = f.nativeElement.querySelector('button[aria-pressed]') as HTMLButtonElement;
    expect(btn.getAttribute('aria-pressed')).toBe('false');
    btn.click();
    f.detectChanges();
    expect(theme.mode()).toBe('elderly');
    expect(btn.getAttribute('aria-pressed')).toBe('true');
    expect(f.nativeElement.getAttribute('data-mode')).toBe('elderly');
    theme.mode.set('default');
  });
});
