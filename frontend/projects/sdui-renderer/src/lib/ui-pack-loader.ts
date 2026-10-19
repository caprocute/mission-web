import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { RUNTIME_CONFIG } from '@vt/api-client';
import { ThemeService, tokensToCss } from '@vt/design-system';
import { PackCache } from './pack-cache';
import { UiPack, UiPackPointer } from './ui-pack';

/** UI Pack shell đã gửi yêu cầu từ trước khi bootstrap (WEB-052). */
export interface UiPackPrefetch {
  /** Gốc đã dùng, dạng `{uiPackBaseUrl}/{tenant}`; khác cấu hình thì bỏ qua bản tải sớm. */
  base: string;
  pointer: UiPackPointer;
  pack: UiPack | null;
}

/**
 * Kết quả tải sớm `current.json` và `pack.json` của tenant mặc định. Shell gửi hai yêu cầu này
 * ngay khi có `config.json`, song song với việc nạp thư viện dùng chung, để trang chủ không phải
 * chờ thêm hai lượt mạng sau bootstrap. Không cung cấp thì `UiPackLoader` tự tải như cũ.
 */
export const UI_PACK_PREFETCH = new InjectionToken<Promise<UiPackPrefetch | null>>(
  'vt.uiPackPrefetch',
);

/**
 * Nạp UI Pack của tenant theo cơ chế hai đường dẫn (TL-01 "Cơ chế bộ đệm"):
 * `current.json` (max-age=60, ETag) trỏ tới gói bất biến `{version}/pack.json`.
 */
@Injectable({ providedIn: 'root' })
export class UiPackLoader {
  private readonly cfg = inject(RUNTIME_CONFIG);
  private readonly theme = inject(ThemeService);

  private prefetch = inject(UI_PACK_PREFETCH, { optional: true });

  readonly pack = signal<UiPack | null>(null);

  async load(tenant = this.cfg.tenant): Promise<void> {
    const base = `${this.cfg.uiPackBaseUrl}/${tenant}`;
    // bản tải sớm chỉ dùng một lần, cho đúng gốc và tenant đã tải
    const prefetch = this.prefetch;
    this.prefetch = null;
    const cached = await PackCache.get(tenant);
    if (cached) this.apply(tenant, cached);
    const early = prefetch ? await prefetch.catch(() => null) : null;
    const usable = early?.base === base ? early : null;

    try {
      const ptr =
        usable?.pointer ??
        ((await (await fetch(`${base}/current.json`, { cache: 'no-cache' })).json()) as UiPackPointer);
      if (cached?.version === ptr.version) return;
      let fresh = usable?.pack?.version === ptr.version ? usable.pack : null;
      if (!fresh) {
        const res = await fetch(`${base}/${ptr.version}/pack.json`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        fresh = (await res.json()) as UiPack;
      }
      // TODO(ADR-05): kiểm chữ ký gói bằng khoá công khai của nền tảng trước khi áp
      await PackCache.put(tenant, fresh);
      // Lần đầu (chưa có bản lưu) áp ngay; nếu đã có bản lưu, để lần điều hướng sau mới đổi
      if (!cached) this.apply(tenant, fresh);
    } catch (err) {
      if (!cached) console.error('[ui-pack] Không nạp được UI Pack, dùng token mặc định', err);
    }
  }

  layout(name: string) {
    return this.pack()?.layouts[name] ?? null;
  }

  t(key: string, lang = 'vi'): string {
    return this.pack()?.i18n?.[lang]?.[key] ?? key;
  }

  private apply(tenant: string, pack: UiPack): void {
    const css = pack.compiledCss ?? tokensToCss(pack.tokens, pack.modes ?? {});
    this.theme.applyTenant(tenant, css);
    const logo = (pack.tokens['brand'] as { logo?: { $value: string } } | undefined)?.logo?.$value;
    this.theme.logoUrl.set(logo ? `${this.cfg.uiPackBaseUrl}/${tenant}/${pack.version}/${logo}` : null);
    const flags = pack.modes?.['elderly']?.flags as { reducedSteps?: boolean } | undefined;
    this.theme.reducedSteps.set(!!flags?.reducedSteps);
    this.pack.set(pack);
  }
}
