import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { providePrimeNG } from 'primeng/config';
import { VtPreset } from './vt-preset';

/**
 * Nonce CSP của trang (WEB-014): nginx ghi vào thuộc tính `ngCspNonce` của phần tử gốc mỗi yêu
 * cầu; PrimeNG gắn nonce này cho các thẻ `<style>` nó tạo, nếu không CSP `style-src` sẽ chặn.
 */
function pageNonce(): string | undefined {
  if (typeof document === 'undefined') return undefined;
  return document.querySelector('[ngCspNonce]')?.getAttribute('ngCspNonce') ?? undefined;
}

/** Đăng ký PrimeNG với preset của nền tảng; dùng chung cho shell và mọi remote. */
export function provideDesignSystem(): EnvironmentProviders {
  return makeEnvironmentProviders([
    providePrimeNG({
      csp: { nonce: pageNonce() },
      theme: { preset: VtPreset, options: { cssLayer: { name: 'primeng', order: 'base, primeng, app' }, darkModeSelector: false } },
      ripple: false,
    }),
  ]);
}
