import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import type { MockInstance } from 'vitest';
import {
  ComponentRegistry,
  SDUI_TELEMETRY_SINK,
  SduiTelemetryRecord,
  UiPack,
  UiPackLoader,
  sriSha256,
} from '@vt/sdui-renderer';
import { PluginHost, PluginWidgetUnavailable } from './plugin-host';
import { FederationUrlPolicy } from './federation-url-policy';

const loadRemoteModule = vi.hoisted(() => vi.fn());
vi.mock('@angular-architects/native-federation', () => ({ loadRemoteModule }));

@Component({ selector: 'vt-test-widget', template: '' })
class TestWidget {}

const ENTRY = JSON.stringify({
  name: 'fe-plugin-giaoduc',
  exposes: [{ key: './plugin', outFileName: 'plugin-X.js' }],
});

function descriptor(over: Record<string, unknown> = {}) {
  return {
    name: 'fe-plugin-giaoduc',
    title: 'Chuyên môn giáo dục',
    icon: 'pi pi-book',
    routes: async () => [],
    widgets: [
      {
        name: 'giaoduc.widget.tienDo',
        since: '2.4.0',
        propsSchema: { type: 'object' },
        load: async () => TestWidget,
      },
      {
        name: 'vt.widget.myTasks',
        since: '2.4.0',
        propsSchema: { type: 'object' },
        load: async () => TestWidget,
      },
      {
        name: 'giaoduc.widget.loi',
        since: '2.4.0',
        propsSchema: { type: 'object' },
        load: () => Promise.reject(new Error('mạng')),
      },
    ],
    ...over,
  };
}

/** WEB-051: shell nạp plugin theo UI Pack, kiểm băm, kiểm tên và cô lập lỗi. */
describe('PluginHost', () => {
  let fetchSpy: MockInstance<typeof fetch>;
  let events: SduiTelemetryRecord[];

  beforeEach(() => {
    events = [];
    loadRemoteModule.mockReset();
    fetchSpy = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(ENTRY));
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });
  afterEach(() => vi.restoreAllMocks());

  function setup(plugins: UiPack['plugins'], verified = false) {
    const pack = {
      pack: 't',
      version: '1.0.0',
      requires: { registry: '^2.0.0' },
      tokens: {},
      layouts: {},
      plugins,
    } as UiPack;
    TestBed.configureTestingModule({
      providers: [
        { provide: UiPackLoader, useValue: { pack: signal(pack), verified: signal(verified) } },
        { provide: SDUI_TELEMETRY_SINK, useValue: (r: SduiTelemetryRecord) => events.push(r) },
      ],
    });
    return TestBed.inject(PluginHost);
  }

  it('không có plugin trong UI Pack thì không tải gì', async () => {
    const host = setup(undefined);
    await host.init();
    expect(host.plugins()).toEqual([]);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('nạp plugin, gắn tuyến và chỉ đăng ký widget đúng tiền tố ngành', async () => {
    loadRemoteModule.mockResolvedValue({ VT_PLUGIN: descriptor() });
    const integrity = await sriSha256(ENTRY);
    const host = setup(
      [
        {
          name: 'fe-plugin-giaoduc',
          remoteEntry: '/plugins/fe-plugin-giaoduc/remoteEntry.json',
          integrity,
        },
      ],
      true,
    );
    await host.init();
    expect(host.navItems()).toEqual([
      expect.objectContaining({
        label: 'Chuyên môn giáo dục',
        link: '/plugin/giaoduc',
        section: 'workspace',
      }),
    ]);
    expect((await host.routes()).map((r) => r.path)).toEqual(['giaoduc']);
    const registry = TestBed.inject(ComponentRegistry);
    expect(registry.get('giaoduc.widget.tienDo')).toBeDefined();
    expect(await registry.get('giaoduc.widget.loi')!.load()).toBe(PluginWidgetUnavailable);
    expect(events).toContainEqual(
      expect.objectContaining({
        event: 'plugin.widget.rejected',
        attributes: expect.objectContaining({ component: 'vt.widget.myTasks' }),
      }),
    );
  });

  it('từ chối plugin khi băm remoteEntry không khớp', async () => {
    loadRemoteModule.mockResolvedValue({ VT_PLUGIN: descriptor() });
    const host = setup(
      [{ name: 'fe-plugin-giaoduc', remoteEntry: '/p/remoteEntry.json', integrity: 'sha256-AAAA' }],
      true,
    );
    await host.init();
    expect(host.plugins()).toEqual([]);
    expect(loadRemoteModule).not.toHaveBeenCalled();
    expect(events).toContainEqual(
      expect.objectContaining({
        event: 'plugin.rejected',
        attributes: expect.objectContaining({ reason: 'integrity' }),
      }),
    );
  });

  it('môi trường có khoá bắt buộc khai integrity', async () => {
    const host = setup([{ name: 'fe-plugin-giaoduc', remoteEntry: '/p/remoteEntry.json' }], true);
    await host.init();
    expect(host.plugins()).toEqual([]);
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it('từ chối remoteEntry trỏ tệp ra ngoài thư mục plugin', async () => {
    fetchSpy.mockImplementation(
      async () => new Response(JSON.stringify({ exposes: [{ outFileName: 'https://evil/x.js' }] })),
    );
    const host = setup([{ name: 'fe-plugin-giaoduc', remoteEntry: '/p/remoteEntry.json' }]);
    await host.init();
    expect(host.plugins()).toEqual([]);
    expect(loadRemoteModule).not.toHaveBeenCalled();
  });

  it('từ chối mô tả sai tên, plugin lỗi không làm hỏng plugin khác', async () => {
    loadRemoteModule.mockImplementation(async ({ remoteEntry }: { remoteEntry: string }) =>
      remoteEntry.includes('yte')
        ? { VT_PLUGIN: descriptor({ name: 'fe-plugin-khac' }) }
        : { VT_PLUGIN: descriptor() },
    );
    const host = setup([
      { name: 'fe-plugin-yte', remoteEntry: '/plugins/yte/remoteEntry.json' },
      { name: 'fe-plugin-giaoduc', remoteEntry: '/plugins/giaoduc/remoteEntry.json' },
    ]);
    await host.init();
    expect(host.plugins().map((p) => p.short)).toEqual(['giaoduc']);
    expect(events).toContainEqual(
      expect.objectContaining({
        event: 'plugin.rejected',
        attributes: expect.objectContaining({ reason: 'descriptor' }),
      }),
    );
  });
});

describe('FederationUrlPolicy', () => {
  it('chỉ cho URL có trong danh sách plugin', () => {
    const events: SduiTelemetryRecord[] = [];
    TestBed.configureTestingModule({
      providers: [
        { provide: SDUI_TELEMETRY_SINK, useValue: (r: SduiTelemetryRecord) => events.push(r) },
      ],
    });
    const policy = TestBed.inject(FederationUrlPolicy);
    policy.allow(['/plugins/a/remoteEntry.json']);
    expect(policy.checked('/plugins/a/remoteEntry.json#x')).toBe(
      new URL('/plugins/a/remoteEntry.json', document.baseURI).href,
    );
    expect(() => policy.checked('https://evil.example/remoteEntry.json')).toThrow();
    expect(events).toContainEqual(expect.objectContaining({ event: 'plugin.url.blocked' }));
  });
});
