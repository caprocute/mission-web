import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { Router } from '@angular/router';
import { AppNotification } from '@vt/api-client';
import { NotificationCenter } from './notification-center';
import { relativeTime, safeInternalLink } from './relative-time';

/**
 * Chuông thông báo trên thanh trên (SCR-0002 "Đánh dấu đã đọc", QT-00): số chưa đọc, hộp thông báo
 * mới nhất, mở thông báo thì đánh dấu đã đọc và chuyển tới đối tượng. Bàn phím: Enter hoặc Space mở,
 * Esc đóng và trả tiêu điểm về chuông.
 */
@Component({
  selector: 'vt-notification-bell',
  host: {
    '(document:keydown.escape)': 'close(true)',
    '(document:click)': 'onDocumentClick($event)',
  },
  template: `
    <button
      #bell
      type="button"
      class="bell"
      [attr.aria-expanded]="open()"
      aria-controls="vt-notification-panel"
      [attr.aria-label]="label()"
      (click)="toggle()"
    >
      <i class="pi pi-bell" aria-hidden="true"></i>
      @if (center.unread() > 0) {
        <span class="badge" aria-hidden="true">{{
          center.unread() > 99 ? '99+' : center.unread()
        }}</span>
      }
    </button>
    @if (open()) {
      <section
        id="vt-notification-panel"
        class="panel"
        role="region"
        aria-labelledby="vt-notification-title"
      >
        <header>
          <h2 id="vt-notification-title">Thông báo</h2>
          @if (center.unread() > 0) {
            <button type="button" class="link" (click)="markAll()">Đánh dấu tất cả đã đọc</button>
          }
        </header>
        @switch (center.status()) {
          @case ('loading') {
            <p class="muted" role="status">Đang tải thông báo…</p>
          }
          @case ('error') {
            <p class="muted" role="status">
              Chưa tải được thông báo.
              <button type="button" class="link" (click)="center.refresh()">Thử lại</button>
            </p>
          }
          @default {
            @if (center.items().length === 0) {
              <p class="muted">Chưa có thông báo nào.</p>
            } @else {
              <ul>
                @for (n of center.items(); track n.id) {
                  <li>
                    <button
                      type="button"
                      class="item"
                      [class.unread]="!n.readAt"
                      (click)="openItem(n)"
                    >
                      <span class="title">
                        @if (!n.readAt) {
                          <span class="dot" aria-hidden="true"></span>
                          <span class="sr-only">Chưa đọc: </span>
                        }
                        @if (n.priority === 'CAO') {
                          <span class="tag">Quan trọng</span>
                        }
                        {{ n.title }}
                      </span>
                      @if (n.body) {
                        <span class="body">{{ n.body }}</span>
                      }
                      <span class="time">{{ when(n.createdAt) }}</span>
                    </button>
                  </li>
                }
              </ul>
            }
          }
        }
      </section>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: inline-flex;
    }
    .bell {
      position: relative;
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-width: var(--vt-min-touch, 44px);
      min-height: var(--vt-min-touch, 44px);
      border: 0;
      border-radius: 999px;
      background: transparent;
      color: var(--vt-color-text, #0f172a);
      font-size: 1.15rem;
      cursor: pointer;
    }
    .bell:hover {
      background: var(--p-content-hover-background, #f1f5f9);
    }
    .bell:focus-visible,
    .link:focus-visible,
    .item:focus-visible {
      outline: 3px solid var(--vt-color-primary-strong, #b91c1c);
      outline-offset: 2px;
    }
    .badge {
      position: absolute;
      top: 4px;
      right: 2px;
      min-width: 1.25rem;
      height: 1.25rem;
      padding: 0 0.3rem;
      border-radius: 999px;
      background: var(--vt-color-primary-strong, #b91c1c);
      color: var(--vt-color-on-primary, #fff);
      font-size: 0.72rem;
      font-weight: 700;
      line-height: 1.25rem;
    }
    .panel {
      position: absolute;
      right: 0;
      top: calc(100% + 6px);
      z-index: 50;
      width: min(24rem, calc(100vw - 2rem));
      max-height: min(32rem, 75vh);
      overflow: auto;
      background: var(--vt-color-surface, #fff);
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      box-shadow: 0 12px 32px rgb(15 23 42 / 18%);
    }
    header {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 0.5rem;
      padding: 0.75rem 1rem;
      border-bottom: 1px solid #e2e8f0;
    }
    h2 {
      margin: 0;
      font-size: 1rem;
    }
    ul {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .item {
      display: grid;
      gap: 0.2rem;
      width: 100%;
      text-align: left;
      padding: 0.75rem 1rem;
      border: 0;
      border-bottom: 1px solid #f1f5f9;
      background: transparent;
      color: inherit;
      font: inherit;
      cursor: pointer;
      min-height: var(--vt-min-touch, 44px);
    }
    .item:hover {
      background: var(--p-content-hover-background, #f8fafc);
    }
    .item.unread .title {
      font-weight: 700;
    }
    .dot {
      display: inline-block;
      width: 0.5rem;
      height: 0.5rem;
      margin-right: 0.35rem;
      border-radius: 50%;
      background: var(--vt-color-primary-strong, #b91c1c);
      vertical-align: middle;
    }
    .tag {
      margin-right: 0.35rem;
      padding: 0 0.35rem;
      border-radius: 4px;
      background: #fef3c7;
      color: #78350f;
      font-size: 0.75rem;
      font-weight: 600;
    }
    .body {
      color: var(--p-text-muted-color, #475569);
      font-size: 0.9rem;
    }
    .time {
      color: var(--p-text-muted-color, #475569);
      font-size: 0.8rem;
    }
    .muted {
      margin: 0;
      padding: 1rem;
      color: var(--p-text-muted-color, #475569);
    }
    .link {
      border: 0;
      background: transparent;
      color: var(--vt-color-primary-strong, #b91c1c);
      font: inherit;
      font-weight: 600;
      cursor: pointer;
      padding: 0.25rem;
      min-height: 2rem;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class NotificationBell {
  protected readonly center = inject(NotificationCenter);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly bell = viewChild.required<ElementRef<HTMLButtonElement>>('bell');
  protected readonly open = signal(false);
  protected readonly label = computed(() => {
    const n = this.center.unread();
    return n > 0 ? `Thông báo, ${n} chưa đọc` : 'Thông báo';
  });

  constructor() {
    this.center.start();
  }

  protected toggle(): void {
    const next = !this.open();
    this.open.set(next);
    if (next && this.center.status() !== 'loading') void this.center.refresh();
  }

  close(returnFocus = false): void {
    if (!this.open()) return;
    this.open.set(false);
    if (returnFocus) this.bell().nativeElement.focus();
  }

  protected onDocumentClick(e: MouseEvent): void {
    if (this.open() && !this.host.nativeElement.contains(e.target as Node)) this.close();
  }

  protected when(iso: string): string {
    return relativeTime(iso);
  }

  protected async openItem(n: AppNotification): Promise<void> {
    this.close();
    await this.center.markRead(n.id).catch(() => undefined);
    const link = safeInternalLink(n.deepLink);
    if (link) await this.router.navigateByUrl(link);
  }

  protected markAll(): void {
    void this.center.markAllRead().catch(() => undefined);
  }
}
