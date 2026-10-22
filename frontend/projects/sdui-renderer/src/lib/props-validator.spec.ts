import { TestBed } from '@angular/core/testing';
import { RegistryEntry } from './component-registry';
import { PropsValidator } from './props-validator';
import { SDUI_TELEMETRY_SINK, SduiTelemetryRecord } from './sdui-telemetry';

const ENTRY: RegistryEntry = {
  name: 'vt.widget.link',
  load: async () => class {},
  since: '2.0.0',
  propsSchema: {
    type: 'object',
    properties: {
      href: { type: 'string' },
      limit: { type: 'integer', minimum: 1, maximum: 20 },
      items: { type: 'array' },
    },
  },
};

describe('PropsValidator (WEB-009)', () => {
  let events: SduiTelemetryRecord[];
  let validator: PropsValidator;

  beforeEach(() => {
    events = [];
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      providers: [
        { provide: SDUI_TELEMETRY_SINK, useValue: (r: SduiTelemetryRecord) => events.push(r) },
      ],
    });
    validator = TestBed.inject(PropsValidator);
  });

  it('loại thuộc tính javascript:, kể cả khi chèn khoảng trắng, ký tự điều khiển hoặc lồng trong mảng', () => {
    const out = validator.sanitize(ENTRY, {
      href: ' java\tscript:alert(1)',
      items: [{ url: 'JavaScript:void(0)' }],
      limit: 5,
    });
    expect(out).toEqual({ limit: 5 });
    expect(events.map((e) => e.attributes['reason'])).toEqual(['unsafe-url', 'unsafe-url']);
    expect(JSON.stringify(events)).not.toContain('alert');
  });

  it('giữ URL an toàn, bỏ thuộc tính lạ và sai giới hạn', () => {
    const out = validator.sanitize(ENTRY, {
      href: '/tasks',
      limit: 99,
      onclick: 'x',
      constructor: 1,
    });
    expect(out).toEqual({ href: '/tasks' });
    expect(events.map((e) => `${e.attributes['prop']}:${e.attributes['reason']}`)).toEqual([
      'limit:maximum',
      'onclick:unknown',
      'constructor:unknown',
    ]);
  });
});
