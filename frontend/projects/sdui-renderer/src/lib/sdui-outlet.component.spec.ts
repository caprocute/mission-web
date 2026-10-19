import { Component, Type, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '@vt/api-client';
import { SessionService } from '@vt/auth';
import { RegistryEntry, provideSduiComponents } from './component-registry';
import { SduiOutletComponent } from './sdui-outlet.component';
import { UiPackLoader } from './ui-pack-loader';

@Component({ selector: 'vt-test-box', template: '<p>hộp</p>' })
class Box {}

/** WEB-052: bố cục vừa có thì tải trước mã của các nút luôn hiện, không tải nút có visibleWhen. */
describe('SduiOutletComponent', () => {
  it('gọi prefetch cho nút luôn hiện và bỏ qua nút phụ thuộc visibleWhen', async () => {
    const shown = { load: vi.fn(() => Promise.resolve(Box as Type<unknown>)), prefetch: vi.fn() };
    const hidden = { load: vi.fn(() => Promise.resolve(Box as Type<unknown>)), prefetch: vi.fn() };
    const entries: RegistryEntry[] = [
      {
        name: 'vt.test.shown',
        since: '2.0.0',
        propsSchema: { type: 'object', properties: {} },
        ...shown,
      },
      {
        name: 'vt.test.hidden',
        since: '2.0.0',
        propsSchema: { type: 'object', properties: {} },
        ...hidden,
      },
    ];
    TestBed.configureTestingModule({
      providers: [
        { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
        {
          provide: SessionService,
          useValue: { user: signal({ id: 'u', roles: ['member'], tenant: 't', unitId: 'd' }) },
        },
        provideSduiComponents(entries),
      ],
    });
    TestBed.inject(UiPackLoader).pack.set({
      pack: 'p',
      version: '1',
      requires: { registry: '^2.0.0' },
      tokens: {},
      layouts: {
        home: {
          component: 'vt.test.shown',
          children: [{ component: 'vt.test.hidden', visibleWhen: "'manager' in user.roles" }],
        },
      },
    });
    const fixture = TestBed.createComponent(SduiOutletComponent);
    fixture.componentRef.setInput('layout', 'home');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(shown.prefetch).toHaveBeenCalledTimes(1);
    expect(hidden.prefetch).not.toHaveBeenCalled();
    expect(hidden.load).not.toHaveBeenCalled();
    await vi.waitFor(() => expect(fixture.nativeElement.textContent).toContain('hộp'));
  });
});
