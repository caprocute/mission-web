import type { MockInstance } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG } from '@vt/api-client';
import { UI_PACK_PREFETCH, UiPackLoader, UiPackPrefetch } from './ui-pack-loader';
import { UiPack } from './ui-pack';

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
