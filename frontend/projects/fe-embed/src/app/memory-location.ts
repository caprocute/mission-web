import { Injectable, InjectionToken, inject } from '@angular/core';
import { LocationChangeListener, LocationStrategy } from '@angular/common';

/** Gốc ứng dụng đầy đủ (trang chạy riêng), dùng làm `href` của liên kết trong phần nhúng. */
export const EMBED_APP_ROOT = new InjectionToken<string>('vt.embedAppRoot');

/**
 * Định tuyến trong bộ nhớ cho chế độ nhúng: router của ứng dụng không đọc, không ghi thanh địa chỉ
 * của cổng đang chứa (cổng có router riêng). `href` của liên kết trỏ tới cùng màn hình ở ứng dụng
 * đầy đủ, để mở thẻ mới vẫn đúng chỗ.
 */
@Injectable()
export class MemoryLocationStrategy extends LocationStrategy {
  private readonly root = inject(EMBED_APP_ROOT);
  private readonly stack: { url: string; state: unknown }[] = [{ url: '', state: null }];
  private index = 0;
  private readonly listeners: LocationChangeListener[] = [];

  path(): string {
    return this.stack[this.index].url;
  }

  prepareExternalUrl(internal: string): string {
    return new URL(internal.replace(/^\/+/, ''), this.root).href;
  }

  getState(): unknown {
    return this.stack[this.index].state;
  }

  pushState(state: unknown, _title: string, url: string, queryParams: string): void {
    this.stack.splice(this.index + 1);
    this.stack.push({ url: join(url, queryParams), state });
    this.index = this.stack.length - 1;
  }

  replaceState(state: unknown, _title: string, url: string, queryParams: string): void {
    this.stack[this.index] = { url: join(url, queryParams), state };
  }

  forward(): void {
    this.historyGo(1);
  }

  back(): void {
    this.historyGo(-1);
  }

  override historyGo(delta = 0): void {
    const next = this.index + delta;
    if (delta === 0 || next < 0 || next >= this.stack.length) return;
    this.index = next;
    const ev = { type: 'popstate', state: this.getState() } as unknown as PopStateEvent;
    this.listeners.forEach((fn) => fn(ev));
  }

  onPopState(fn: LocationChangeListener): void {
    this.listeners.push(fn);
  }

  getBaseHref(): string {
    return '';
  }
}

function join(url: string, query: string): string {
  return query ? `${url}${query.startsWith('?') ? '' : '?'}${query}` : url;
}
