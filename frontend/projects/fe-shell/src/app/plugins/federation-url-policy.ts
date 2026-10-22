import { Injectable, inject } from '@angular/core';
import { SduiTelemetry } from '@vt/sdui-renderer';

interface TrustedTypePolicyLike {
  createScriptURL(url: string): unknown;
}
interface TrustedTypesLike {
  createPolicy(
    name: string,
    rules: { createScriptURL: (url: string) => string },
  ): TrustedTypePolicyLike;
}

/** Tên chính sách Trusted Types của TL-01 cho bộ nạp remote (CSP `trusted-types` của nginx đã có). */
export const VT_FEDERATION_POLICY = 'vt-federation';

/**
 * Chính sách `vt-federation` (TL-01 chương 7, WEB-051): chỉ nhận URL `remoteEntry.json` có trong
 * danh sách plugin của UI Pack đã ký. Shell đưa mọi URL plugin qua `checked()` trước khi tải; trình
 * duyệt có Trusted Types thì kiểm bằng chính sách đặt tên, trình duyệt không có thì kiểm cùng danh
 * sách. URL ngoài danh sách bị chặn và ghi telemetry.
 */
@Injectable({ providedIn: 'root' })
export class FederationUrlPolicy {
  private readonly telemetry = inject(SduiTelemetry);
  private readonly allowed = new Set<string>();
  private readonly policy = this.createPolicy();

  /** Bổ sung URL được phép (đường dẫn tuyệt đối, đã chuẩn hoá). */
  allow(urls: string[]): void {
    for (const u of urls) this.allowed.add(normalize(u));
  }

  /** Trả URL khi được phép; ném lỗi khi không. */
  checked(url: string): string {
    const u = normalize(url);
    if (this.policy) return String(this.policy.createScriptURL(u));
    return this.guard(u);
  }

  private guard(url: string): string {
    if (this.allowed.has(url)) return url;
    this.telemetry.record('plugin.url.blocked', { origin: originOf(url) });
    throw new Error('URL không có trong danh sách plugin của UI Pack');
  }

  private createPolicy(): TrustedTypePolicyLike | null {
    const tt = (globalThis as { trustedTypes?: TrustedTypesLike }).trustedTypes;
    if (!tt) return null;
    try {
      return tt.createPolicy(VT_FEDERATION_POLICY, { createScriptURL: (u) => this.guard(u) });
    } catch {
      // chính sách đã tạo (ví dụ khi kiểm thử dựng lại dịch vụ): kiểm bằng danh sách
      return null;
    }
  }
}

function normalize(url: string): string {
  const u = new URL(url, document.baseURI);
  u.hash = '';
  return u.href;
}

function originOf(url: string): string {
  try {
    return new URL(url).origin;
  } catch {
    return 'khong-hop-le';
  }
}
