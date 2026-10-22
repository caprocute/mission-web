import { Injectable, InjectionToken, inject, signal } from '@angular/core';
import { RUNTIME_CONFIG, RuntimeConfig } from '@vt/api-client';
import { ThemeService, tokensToCss } from '@vt/design-system';
import { REGISTRY_VERSION } from './component-registry';
import { PackCache } from './pack-cache';
import { verifyPackSignature } from './pack-signature';
import { SduiTelemetry } from './sdui-telemetry';
import { satisfiesRange } from './semver-range';
import { UiPack, UiPackPointer } from './ui-pack';

/**
 * Trường `config.json` cho WEB-009: khoá công khai của nền tảng dùng kiểm chữ ký UI Pack, ánh xạ
 * mã khoá (`keyId` trong `current.json`) sang SubjectPublicKeyInfo base64 của khoá EC P-256.
 * Có ít nhất một khoá thì gói không có chữ ký hoặc sai chữ ký đều bị từ chối; không có khoá nào
 * (môi trường dev, dữ liệu giả lập) thì gói được áp không kiểm.
 */
export interface UiPackTrustConfig {
  uiPackPublicKeys?: Record<string, string>;
}

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
  private readonly telemetry = inject(SduiTelemetry);
  private readonly keys = (this.cfg as RuntimeConfig & UiPackTrustConfig).uiPackPublicKeys ?? {};

  private prefetch = inject(UI_PACK_PREFETCH, { optional: true });

  readonly pack = signal<UiPack | null>(null);
  /**
   * Gói đang áp đã kiểm chữ ký với khoá nền tảng (WEB-009). `false` khi `config.json` chưa có khoá
   * (môi trường phát triển); plugin chuyên ngành (WEB-051) dựa vào cờ này.
   */
  readonly verified = signal(false);

  async load(tenant = this.cfg.tenant): Promise<void> {
    const base = `${this.cfg.uiPackBaseUrl}/${tenant}`;
    // bản tải sớm chỉ dùng một lần, cho đúng gốc và tenant đã tải
    const prefetch = this.prefetch;
    this.prefetch = null;
    // bản lưu cũng kiểm lại: có thể được lưu trước khi cấu hình khoá, hoặc trước khi shell nâng registry
    let cached = await PackCache.get(tenant);
    if (cached && !(await this.accept(cached))) cached = undefined;
    if (cached) this.apply(tenant, cached);
    const early = prefetch ? await prefetch.catch(() => null) : null;
    const usable = early?.base === base ? early : null;

    try {
      const ptr =
        usable?.pointer ??
        ((await (
          await fetch(`${base}/current.json`, { cache: 'no-cache' })
        ).json()) as UiPackPointer);
      if (cached?.version === ptr.version) return;
      let fresh = usable?.pack?.version === ptr.version ? usable.pack : null;
      if (!fresh) {
        const res = await fetch(`${base}/${ptr.version}/pack.json`);
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        fresh = (await res.json()) as UiPack;
      }
      // gói không tương thích hoặc sai chữ ký: giữ bản đang dùng (hoặc token mặc định), không lưu
      if (!(await this.accept(fresh, ptr))) return;
      await PackCache.put(tenant, fresh);
      // Lần đầu (chưa có bản lưu) áp ngay; nếu đã có bản lưu, để lần điều hướng sau mới đổi
      if (!cached) this.apply(tenant, fresh);
    } catch (err) {
      if (!cached) console.error('[ui-pack] Không nạp được UI Pack, dùng token mặc định', err);
    }
  }

  /**
   * Gói có được áp không (ADR-05 lớp 2): `requires.registry` phải khớp `REGISTRY_VERSION` của shell,
   * và khi `config.json` có khoá nền tảng thì chữ ký ES256 phải hợp lệ.
   */
  private async accept(pack: UiPack, pointer?: UiPackPointer): Promise<boolean> {
    const range = pack.requires?.registry;
    if (!range || !satisfiesRange(REGISTRY_VERSION, range)) {
      this.telemetry.record('ui-pack.registry.incompatible', {
        pack: pack.pack,
        version: pack.version,
        requires: String(range ?? ''),
        registry: REGISTRY_VERSION,
      });
      return false;
    }
    if (Object.keys(this.keys).length === 0) return true;
    const check =
      pointer?.contentHash && pack.contentHash && pointer.contentHash !== pack.contentHash
        ? 'invalid'
        : await verifyPackSignature(pack, this.keys, pointer?.keyId);
    if (check === 'ok') return true;
    this.telemetry.record(
      check === 'missing' ? 'ui-pack.signature.missing' : 'ui-pack.signature.invalid',
      {
        pack: pack.pack,
        version: pack.version,
        reason: check,
      },
    );
    return false;
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
    this.theme.logoUrl.set(
      logo ? `${this.cfg.uiPackBaseUrl}/${tenant}/${pack.version}/${logo}` : null,
    );
    const flags = pack.modes?.['elderly']?.flags as { reducedSteps?: boolean } | undefined;
    this.theme.reducedSteps.set(!!flags?.reducedSteps);
    // accept() đã kiểm chữ ký khi có khoá, nên gói đã áp là gói đã ký
    this.verified.set(Object.keys(this.keys).length > 0);
    this.pack.set(pack);
  }
}
