import { DOCUMENT } from '@angular/common';
import { CSP_NONCE, Injectable, effect, inject, signal } from '@angular/core';

export type DisplayMode = 'default' | 'elderly';

const MODE_KEY = 'vt.displayMode';

/**
 * Áp token của tenant và chế độ hiển thị lên phần tử gốc.
 * Chế độ "Đảng viên cao tuổi" (NFR-11) bật bằng `data-mode="elderly"`; cờ `reducedSteps`
 * cho các màn hình biết cần chuyển sang luồng ít bước.
 */
@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  private readonly nonce = inject(CSP_NONCE, { optional: true });
  private styleEl: HTMLStyleElement | null = null;

  readonly mode = signal<DisplayMode>(this.readStoredMode());
  readonly reducedSteps = signal(false);
  readonly logoUrl = signal<string | null>(null);

  constructor() {
    effect(() => {
      const mode = this.mode();
      this.doc.documentElement.setAttribute('data-mode', mode);
      try {
        localStorage.setItem(MODE_KEY, mode);
      } catch {
        /* trình duyệt chặn bộ nhớ cục bộ: chỉ áp trong phiên */
      }
    });
  }

  applyTenant(tenant: string, css: string): void {
    this.doc.documentElement.setAttribute('data-tenant', tenant);
    if (!this.styleEl) {
      this.styleEl = this.doc.createElement('style');
      this.styleEl.id = 'vt-tenant-tokens';
      if (this.nonce) this.styleEl.setAttribute('nonce', this.nonce);
      this.doc.head.appendChild(this.styleEl);
    }
    // textContent, không dùng innerHTML: nội dung CSS không bao giờ được hiểu là HTML
    this.styleEl.textContent = css;
  }

  toggleElderly(): void {
    this.mode.update((m) => (m === 'elderly' ? 'default' : 'elderly'));
  }

  private readStoredMode(): DisplayMode {
    try {
      return localStorage.getItem(MODE_KEY) === 'elderly' ? 'elderly' : 'default';
    } catch {
      return 'default';
    }
  }
}
