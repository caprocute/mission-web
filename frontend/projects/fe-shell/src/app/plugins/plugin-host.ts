import { Component, Injectable, Type, computed, inject, signal } from '@angular/core';
import { Routes } from '@angular/router';
import { loadRemoteModule } from '@angular-architects/native-federation';
import {
  ComponentRegistry,
  PLUGIN_EXPOSED_MODULE,
  SduiTelemetry,
  UiPackLoader,
  UiPackPluginRef,
  VtPlugin,
  pluginShortName,
  pluginWidgetAllowed,
  remoteEntryFilesSafe,
  sriSha256,
  validPluginDescriptor,
} from '@vt/sdui-renderer';
import type { NavItem } from '../layout/nav';
import { FederationUrlPolicy } from './federation-url-policy';

/** Khung giữ chỗ khi widget của plugin không tải được (TL-02 QT-18 ngoại lệ). */
@Component({
  selector: 'vt-plugin-widget-unavailable',
  template: `<div class="placeholder" role="status">
    Nội dung này tạm thời chưa hiển thị được.
  </div>`,
  styles: `
    .placeholder {
      min-height: 120px;
      display: grid;
      place-items: center;
      padding: 16px;
      border: 1px dashed var(--vt-color-border);
      border-radius: var(--vt-radius-card);
      color: var(--vt-color-text-muted);
      text-align: center;
    }
  `,
})
export class PluginWidgetUnavailable {}

export interface LoadedPlugin {
  name: string;
  short: string;
  title: string;
  subtitle?: string;
  icon: string;
  routes: () => Promise<Routes>;
}

/**
 * Nạp plugin chuyên ngành theo phần `plugins` của UI Pack (ADR-05 lớp 4, BR-18-12, WEB-051).
 *
 * Mỗi plugin chỉ được nạp khi: UI Pack đã kiểm chữ ký với khoá nền tảng (môi trường chưa cấu hình
 * khoá thì cho nạp kèm cảnh báo, như WEB-009); URL qua chính sách Trusted Types `vt-federation` (chỉ
 * URL có trong danh sách plugin của gói); `remoteEntry.json` khớp băm `integrity` và chỉ trỏ tệp
 * trong thư mục của plugin; mô tả `VT_PLUGIN` đúng tên. Plugin lỗi bị bỏ qua, shell vẫn chạy.
 */
@Injectable({ providedIn: 'root' })
export class PluginHost {
  private readonly loader = inject(UiPackLoader);
  private readonly registry = inject(ComponentRegistry);
  private readonly telemetry = inject(SduiTelemetry);
  private readonly urls = inject(FederationUrlPolicy);

  private readonly _plugins = signal<LoadedPlugin[]>([]);
  readonly plugins = this._plugins.asReadonly();
  private ready: Promise<void> | null = null;

  /** Mục menu "Không gian làm việc" cho từng plugin đã nạp. */
  readonly navItems = computed<NavItem[]>(() =>
    this._plugins().map((p) => ({
      label: p.title,
      icon: p.icon,
      link: `/plugin/${p.short}`,
      section: 'workspace',
      subtitle: p.subtitle,
    })),
  );

  /** Gọi sau `UiPackLoader.load()`; gọi lại trả cùng lời hứa. */
  init(): Promise<void> {
    this.ready ??= this.loadAll();
    return this.ready;
  }

  /** Tuyến con của `/plugin`: mỗi plugin một nhánh `<ngành>`, nạp lười tuyến của plugin. */
  async routes(): Promise<Routes> {
    await this.init();
    return this._plugins().map((p) => ({ path: p.short, title: p.title, loadChildren: p.routes }));
  }

  private async loadAll(): Promise<void> {
    const refs = this.loader.pack()?.plugins ?? [];
    if (!refs.length) return;
    const verified = this.loader.verified();
    if (!verified)
      console.warn(
        '[plugin] UI Pack chưa kiểm chữ ký (chưa cấu hình khoá); chỉ dùng khi phát triển',
      );
    this.urls.allow(refs.map((r) => this.absolute(r.remoteEntry)));
    const loaded = await Promise.all(refs.map((r) => this.loadOne(r, verified)));
    this._plugins.set(loaded.filter((p): p is LoadedPlugin => !!p));
  }

  private async loadOne(ref: UiPackPluginRef, verified: boolean): Promise<LoadedPlugin | null> {
    const short = pluginShortName(ref.name);
    try {
      if (!short) throw new PluginRejected('name');
      if (verified && !ref.integrity) throw new PluginRejected('integrity-missing');
      const url = this.urls.checked(this.absolute(ref.remoteEntry));
      const res = await fetch(url, { cache: 'no-cache' });
      if (!res.ok) throw new Error(`HTTP ${res.status}`);
      const text = await res.text();
      if (ref.integrity && (await sriSha256(text)) !== ref.integrity) {
        throw new PluginRejected('integrity');
      }
      if (!remoteEntryFilesSafe(JSON.parse(text))) throw new PluginRejected('files');
      const mod = await loadRemoteModule<{ VT_PLUGIN?: unknown }>({
        remoteEntry: url,
        exposedModule: PLUGIN_EXPOSED_MODULE,
      });
      if (!validPluginDescriptor(mod.VT_PLUGIN, ref.name)) throw new PluginRejected('descriptor');
      this.registerWidgets(short, mod.VT_PLUGIN);
      return {
        name: ref.name,
        short,
        title: mod.VT_PLUGIN.title,
        subtitle: mod.VT_PLUGIN.subtitle,
        icon: mod.VT_PLUGIN.icon ?? 'pi pi-th-large',
        routes: mod.VT_PLUGIN.routes,
      };
    } catch (err) {
      const reason = err instanceof PluginRejected ? err.reason : 'load';
      console.error(`[plugin] Không nạp ${ref.name}`, err);
      this.telemetry.record('plugin.rejected', { plugin: String(ref.name), reason });
      return null;
    }
  }

  private registerWidgets(short: string, plugin: VtPlugin): void {
    const ok = (plugin.widgets ?? []).filter((w) => {
      const allowed = typeof w?.name === 'string' && pluginWidgetAllowed(short, w.name);
      if (!allowed) {
        this.telemetry.record('plugin.widget.rejected', {
          plugin: plugin.name,
          component: String(w?.name),
        });
      }
      return allowed;
    });
    this.registry.register(
      ok.map((w) => ({
        name: w.name,
        since: w.since,
        propsSchema: w.propsSchema,
        load: () =>
          w.load().catch((err): Type<unknown> => {
            console.error(`[plugin] Không tải được widget ${w.name}`, err);
            return PluginWidgetUnavailable;
          }),
      })),
    );
  }

  private absolute(url: string): string {
    return new URL(url, document.baseURI).href;
  }
}

class PluginRejected extends Error {
  constructor(readonly reason: string) {
    super(`Plugin bị từ chối: ${reason}`);
  }
}
