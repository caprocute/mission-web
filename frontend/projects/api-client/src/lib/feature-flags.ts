import { HttpClient } from '@angular/common/http';
import {
  Directive,
  Injectable,
  TemplateRef,
  ViewContainerRef,
  computed,
  effect,
  inject,
  input,
  signal,
} from '@angular/core';
import type { CanMatchFn } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { apiUrl } from './api-path';
import { components } from './generated/config';

export type FeatureFlag = components['schemas']['FeatureFlag'];
export type FeatureFlagPage = components['schemas']['FeatureFlagPage'];
/** Bản đồ cờ hiệu lực của người dùng hiện hành (`GET /ui/feature-flags`), máy chủ đã tính. */
export type EffectiveFeatureFlagMap = components['schemas']['EffectiveFeatureFlagMap'];

/** Cờ còn hiệu lực tại thời điểm `now`: bật và nằm trong khoảng `validFrom`–`validTo` nếu có. */
export function flagActive(f: FeatureFlag, now: number): boolean {
  if (!f.enabled) return false;
  if (f.validFrom && Date.parse(f.validFrom) > now) return false;
  if (f.validTo && Date.parse(f.validTo) <= now) return false;
  return true;
}

/**
 * Cờ tính năng phía web (WEB-076, TL-04 "Phát hành": tính năng mới bật dần theo tenant). Shell nạp
 * bản đồ cờ hiệu lực `GET /ui/feature-flags` (config.yaml `getEffectiveFeatureFlags`, mọi vai trò;
 * máy chủ đã tính theo đơn vị, vai trò, phần trăm triển khai và thời hạn) khi khởi động và nạp lại
 * khi đổi phân công. `GET /config/feature-flags` chỉ dành cho màn hình quản trị cờ (`tenant_admin`).
 * Mọi lỗi (mạng, 5xx) coi như mọi cờ tắt, nên tính năng chưa phát hành không bao giờ lộ ra vì lỗi.
 * Máy chủ vẫn tự kiểm cờ ở API; cờ phía web chỉ quyết định ẩn hay hiện.
 */
@Injectable({ providedIn: 'root' })
export class FeatureFlagService {
  private readonly http = inject(HttpClient);
  /** Khoá các cờ đang bật; dùng trong `computed` để giao diện tự cập nhật khi nạp lại. */
  private readonly flags = signal<ReadonlySet<string>>(new Set());
  readonly enabledKeys = computed(() => this.flags());

  isEnabled(key: string): boolean {
    return this.enabledKeys().has(key);
  }

  async load(): Promise<void> {
    try {
      const map = await firstValueFrom(
        this.http.get<EffectiveFeatureFlagMap>(apiUrl('/ui/feature-flags')),
      );
      this.flags.set(
        new Set(
          Object.entries(map.flags ?? {})
            .filter(([, on]) => on === true)
            .map(([k]) => k),
        ),
      );
    } catch {
      this.flags.set(new Set());
    }
  }
}

/** Chặn tuyến khi cờ tắt: `canMatch: [featureFlagGuard('ai.weeklySummary')]`. */
export function featureFlagGuard(key: string): CanMatchFn {
  return () => inject(FeatureFlagService).isEnabled(key);
}

/** Chỉ hiện khối khi cờ bật: `<section *vtFeature="'ai.weeklySummary'">`. */
@Directive({ selector: '[vtFeature]' })
export class FeatureFlagDirective {
  readonly vtFeature = input.required<string>();
  private readonly flags = inject(FeatureFlagService);
  private readonly tpl = inject(TemplateRef<unknown>);
  private readonly vcr = inject(ViewContainerRef);
  private shown = false;

  constructor() {
    effect(() => {
      const on = this.flags.isEnabled(this.vtFeature());
      if (on && !this.shown) this.vcr.createEmbeddedView(this.tpl);
      if (!on && this.shown) this.vcr.clear();
      this.shown = on;
    });
  }
}
