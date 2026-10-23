import type { MockInstance } from 'vitest';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '@vt/api-client';
import { UI_PACK_PREFETCH, UiPackLoader, UiPackPrefetch } from './ui-pack-loader';
import { UiPack } from './ui-pack';
import { PackCache } from './pack-cache';
import { signedContent } from './pack-signature';
import { SDUI_TELEMETRY_SINK, SduiTelemetryRecord } from './sdui-telemetry';

const PACK: UiPack = {
  pack: 'default',
  version: '1.0.0',
  requires: { registry: '^2.0.0' },
  tokens: {},
  layouts: { home: { component: 'vt.layout.grid' } },
};

function json(body: unknown): Response {
  return new Response(JSON.stringify(body), { status: 200 });
}

/** WEB-052: UiPackLoader dùng lại UI Pack main.ts đã tải sớm nếu khớp gốc và tenant. */
describe('UiPackLoader', () => {
  let fetchSpy: MockInstance<typeof fetch>;

  beforeEach(() => {
    fetchSpy = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(async (url) =>
        String(url).endsWith('current.json') ? json({ version: '1.0.0' }) : json(PACK),
      );
  });
  afterEach(() => fetchSpy.mockRestore());

  function setup(prefetch?: Promise<UiPackPrefetch | null>) {
    TestBed.configureTestingModule({
      providers: [
        { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
        ...(prefetch ? [{ provide: UI_PACK_PREFETCH, useValue: prefetch }] : []),
      ],
    });
    return TestBed.inject(UiPackLoader);
  }

  it('không gửi lại yêu cầu khi bản tải sớm khớp cấu hình', async () => {
    const loader = setup(
      Promise.resolve({ base: '/ui-packs/default', pointer: { version: '1.0.0' }, pack: PACK }),
    );
    await loader.load();
    expect(fetchSpy).not.toHaveBeenCalled();
    expect(loader.layout('home')?.component).toBe('vt.layout.grid');
  });

  it('bỏ qua bản tải sớm của gốc khác và tự tải như cũ', async () => {
    const loader = setup(
      Promise.resolve({ base: '/khac/default', pointer: { version: '9.9.9' }, pack: null }),
    );
    await loader.load();
    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect(loader.pack()?.version).toBe('1.0.0');
  });

  it('tải sớm thất bại thì tự tải; bản tải sớm chỉ dùng một lần', async () => {
    const loader = setup(Promise.resolve(null));
    await loader.load();
    expect(fetchSpy).toHaveBeenCalledTimes(2);
    expect(loader.pack()?.version).toBe('1.0.0');
  });

  it('không có bản tải sớm thì tải current.json rồi pack.json', async () => {
    const loader = setup();
    await loader.load();
    expect(fetchSpy.mock.calls.map((c) => String(c[0]))).toEqual([
      '/ui-packs/default/current.json',
      '/ui-packs/default/1.0.0/pack.json',
    ]);
  });
});

/** WEB-009: chỉ áp gói tương thích registry và, khi có khoá nền tảng, gói đúng chữ ký. */
describe('UiPackLoader kiểm gói', () => {
  const KEY_ID = 'k-thu';
  let signed: UiPack;
  let keys: Record<string, string>;
  let events: SduiTelemetryRecord[];

  beforeAll(async () => {
    const pair = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, [
      'sign',
      'verify',
    ]);
    const b64 = (buf: ArrayBuffer) => btoa(String.fromCharCode(...new Uint8Array(buf)));
    const pack: UiPack = { ...PACK, pack: 'tinh-mau/ui', requires: { registry: '^2.4.0' } };
    const sig = await crypto.subtle.sign(
      { name: 'ECDSA', hash: 'SHA-256' },
      pair.privateKey,
      signedContent(pack),
    );
    signed = { ...pack, signature: b64(sig) };
    keys = { [KEY_ID]: b64(await crypto.subtle.exportKey('spki', pair.publicKey)) };
  });

  function setup(pack: UiPack, keys?: Record<string, string>) {
    events = [];
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (url) =>
      String(url).endsWith('current.json')
        ? json({ version: pack.version, keyId: KEY_ID })
        : json(pack),
    );
    TestBed.configureTestingModule({
      providers: [
        {
          provide: RUNTIME_CONFIG,
          useValue: { ...DEFAULT_RUNTIME_CONFIG, uiPackPublicKeys: keys },
        },
        { provide: SDUI_TELEMETRY_SINK, useValue: (r: SduiTelemetryRecord) => events.push(r) },
      ],
    });
    return TestBed.inject(UiPackLoader);
  }
  afterEach(() => vi.restoreAllMocks());

  it('áp gói đúng chữ ký của khoá nền tảng', async () => {
    const loader = setup(signed, keys);
    await loader.load();
    expect(loader.pack()?.pack).toBe('tinh-mau/ui');
    expect(events).toEqual([]);
  });

  it('từ chối gói sai chữ ký và giữ giao diện mặc định', async () => {
    const tampered = structuredClone(signed);
    tampered.i18n = { vi: { 'home.notice': 'Nội dung bị chèn' } };
    const loader = setup(tampered, keys);
    await loader.load();
    expect(loader.pack()).toBeNull();
    expect(events.map((e) => e.event)).toEqual(['ui-pack.signature.invalid']);
  });

  it('từ chối gói không có chữ ký khi đã cấu hình khoá', async () => {
    const loader = setup(PACK, keys);
    await loader.load();
    expect(loader.pack()).toBeNull();
    expect(events.map((e) => e.event)).toEqual(['ui-pack.signature.missing']);
  });

  it('từ chối gói đòi registry mới hơn của shell', async () => {
    const loader = setup({ ...PACK, requires: { registry: '^3.0.0' } });
    await loader.load();
    expect(loader.pack()).toBeNull();
    expect(events[0]).toMatchObject({
      event: 'ui-pack.registry.incompatible',
      attributes: { requires: '^3.0.0', registry: '2.4.0' },
    });
  });

  it('chưa cấu hình khoá (dev, dữ liệu giả lập) thì áp gói không chữ ký', async () => {
    const loader = setup(PACK);
    await loader.load();
    expect(loader.pack()?.version).toBe('1.0.0');
  });
});

/** WEB-072: CDN không phục vụ được thì đọc `GET /ui/packs/current` với `If-None-Match`. */
describe('UiPackLoader nguồn dự phòng qua API', () => {
  let http: HttpTestingController;

  function setup() {
    vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    TestBed.configureTestingModule({
      providers: [
        { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpTestingController);
    return TestBed.inject(UiPackLoader);
  }
  afterEach(() => vi.restoreAllMocks());

  it('lần đầu không có bản lưu: lấy gói qua API rồi áp', async () => {
    const loader = setup();
    const done = loader.load();
    const req = await expectApi();
    expect(req.request.headers.has('If-None-Match')).toBe(false);
    req.flush({ ...PACK, version: '2.0.0', contentHash: 'abc' });
    await done;
    expect(loader.pack()?.version).toBe('2.0.0');
  });

  it('có bản lưu: gửi If-None-Match theo băm, 304 thì giữ bản lưu', async () => {
    const loader = setup();
    vi.spyOn(PackCache, 'get').mockResolvedValue({ ...PACK, contentHash: 'h-cu' });
    const done = loader.load();
    const req = await expectApi();
    expect(req.request.headers.get('If-None-Match')).toBe('"h-cu"');
    req.flush(null, { status: 304, statusText: 'Not Modified' });
    await done;
    expect(loader.pack()?.contentHash).toBe('h-cu');
  });

  async function expectApi() {
    for (let i = 0; i < 50; i++) {
      const found = http.match('/api/v1/ui/packs/current');
      if (found.length) return found[0];
      await new Promise((r) => setTimeout(r, 10));
    }
    throw new Error('Không thấy yêu cầu /api/v1/ui/packs/current');
  }
});
