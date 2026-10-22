import { Component, Type, input, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '@vt/api-client';
import { SessionService } from '@vt/auth';
import { provideSduiComponents } from './component-registry';
import { SDUI_TELEMETRY_SINK, SduiTelemetryRecord } from './sdui-telemetry';
import { SduiNodeComponent } from './sdui-node.component';
import { SduiNode } from './ui-pack';

@Component({ selector: 'vt-test-link', template: '<a [attr.data-href]="href()">liên kết</a>' })
class Link {
  readonly href = input<string>();
}

/** WEB-009: renderer chỉ dựng component có trong registry và không truyền thuộc tính nguy hiểm. */
describe('SduiNodeComponent (WEB-009)', () => {
  let events: SduiTelemetryRecord[];

  async function render(node: SduiNode) {
    events = [];
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      providers: [
        { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
        { provide: SessionService, useValue: { user: signal(null) } },
        { provide: SDUI_TELEMETRY_SINK, useValue: (r: SduiTelemetryRecord) => events.push(r) },
        provideSduiComponents([
          {
            name: 'vt.test.link',
            since: '2.0.0',
            load: () => Promise.resolve(Link as Type<unknown>),
            propsSchema: { type: 'object', properties: { href: { type: 'string' } } },
          },
        ]),
      ],
    });
    const fixture = TestBed.createComponent(SduiNodeComponent);
    fixture.componentRef.setInput('node', node);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('bỏ component lạ và ghi telemetry', async () => {
    const el = await render({ component: 'vt.evil.iframe', props: { src: 'https://x.test' } });
    expect(el.children.length).toBe(0);
    expect(events).toEqual([
      { event: 'sdui.component.unknown', attributes: { component: 'vt.evil.iframe' } },
    ]);
  });

  it('dựng component đã đăng ký nhưng loại thuộc tính javascript:', async () => {
    const el = await render({ component: 'vt.test.link', props: { href: 'javascript:alert(1)' } });
    await vi.waitFor(() =>
      expect(el.parentElement?.textContent ?? document.body.textContent).toContain('liên kết'),
    );
    expect(el.parentElement?.querySelector('a')?.getAttribute('data-href') ?? null).toBeNull();
    expect(events[0]).toMatchObject({
      event: 'sdui.prop.dropped',
      attributes: { prop: 'href', reason: 'unsafe-url' },
    });
  });
});
