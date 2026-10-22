import { Component, DestroyRef, computed, effect, inject, input, output } from '@angular/core';
import { NavigationEnd, Router, RouterOutlet } from '@angular/router';
import { filter } from 'rxjs';
import { toSignal } from '@angular/core/rxjs-interop';
import { SessionNoticeComponent, SessionService, StepUpDialogComponent } from '@vt/auth';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { Toast } from 'primeng/toast';
import { EMBED_APP_ROOT } from './memory-location';
import { normalizeRoute } from './embed-config';

/**
 * Phần tử `<vt-okr-app tenant="..." route="tasks">` (WEB-013, TL-01 SVC-17 "Phục vụ và nhúng"):
 * ứng dụng chạy ngay trong trang của cổng văn phòng số, đăng nhập một lần với IdP chung. Thuộc tính
 * `route` chọn màn hình mở đầu; mỗi lần đổi màn hình, phần tử phát sự kiện `routeChange` (chi tiết là
 * đường dẫn nội bộ) để cổng tự ghi vào địa chỉ của mình nếu muốn.
 */
@Component({
  selector: 'vt-okr-app-root',
  imports: [RouterOutlet, ButtonModule, Toast, SessionNoticeComponent, StepUpDialogComponent],
  host: {
    class: 'vt-okr-app',
    '[attr.data-mode]': 'theme.mode()',
  },
  template: `
    <div class="bar">
      <button
        pButton
        type="button"
        class="p-button-text p-button-sm"
        [icon]="elderly() ? 'pi pi-eye-slash' : 'pi pi-eye'"
        [label]="elderly() ? 'Chữ thường' : 'Chữ lớn'"
        [attr.aria-pressed]="elderly()"
        (click)="theme.toggleElderly()"
      ></button>
      <a class="full" [href]="fullAppUrl()" target="_blank" rel="noopener">
        Mở ứng dụng đầy đủ<span class="sr-only"> (mở thẻ mới)</span>
        <i class="pi pi-external-link" aria-hidden="true"></i>
      </a>
    </div>
    <vt-session-notice />
    <div class="content">
      @if (session.user()) {
        <router-outlet />
      }
    </div>
    <vt-step-up-dialog />
    <p-toast position="top-right" />
  `,
  styles: `
    :host {
      display: block;
    }
    .bar {
      display: flex;
      justify-content: flex-end;
      align-items: center;
      gap: 8px;
      padding: 4px 8px;
      border-bottom: 1px solid var(--vt-color-border);
    }
    .bar button,
    .full {
      min-height: var(--vt-min-touch, 40px);
    }
    .full {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 0 8px;
      color: var(--vt-color-primary-strong);
    }
    .full:focus-visible {
      outline: 2px solid var(--vt-color-primary-strong);
      outline-offset: 2px;
    }
    .content {
      padding: var(--vt-space-lg, 16px);
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
export class OkrAppElement {
  /** Mã tenant; chỉ có tác dụng lúc ứng dụng khởi động (bootstrap.ts đọc trước khi dựng). */
  readonly tenant = input<string>();
  /** Màn hình mở đầu, ví dụ `tasks` hoặc `tasks/<mã>`; mặc định "Việc của tôi". */
  readonly route = input<string>();
  readonly routeChange = output<string>();

  protected readonly theme = inject(ThemeService);
  protected readonly session = inject(SessionService);
  private readonly router = inject(Router);
  private readonly root = inject(EMBED_APP_ROOT);
  protected readonly elderly = computed(() => this.theme.mode() === 'elderly');

  private readonly url = toSignal(
    this.router.events.pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd)),
  );
  protected readonly fullAppUrl = computed(() => {
    const u = this.url()?.urlAfterRedirects ?? normalizeRoute(this.route());
    return new URL(u.replace(/^\/+/, ''), this.root).href;
  });

  constructor() {
    effect(() => {
      const target = normalizeRoute(this.route());
      if (this.router.url !== target) void this.router.navigateByUrl(target);
    });
    const sub = this.router.events
      .pipe(filter((e): e is NavigationEnd => e instanceof NavigationEnd))
      .subscribe((e) => this.routeChange.emit(e.urlAfterRedirects));
    inject(DestroyRef).onDestroy(() => sub.unsubscribe());
  }
}
