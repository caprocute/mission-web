import { Component, inject } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { SessionService } from '../session.service';

/** Thông báo của phiên: hết phiên, 403, MSG-00-06, MSG-00-07 (QT-00). Đặt ở đầu vùng nội dung. */
@Component({
  selector: 'vt-session-notice',
  imports: [ButtonModule],
  template: `
    @if (session.notice(); as n) {
      <div class="notice" [class]="'notice ' + n.severity" role="alert">
        <i
          [class]="n.severity === 'info' ? 'pi pi-info-circle' : 'pi pi-exclamation-triangle'"
          aria-hidden="true"
        ></i>
        <span class="text">{{ n.text }}</span>
        @if (n.relogin) {
          <p-button label="Đăng nhập lại" size="small" (onClick)="session.login()" />
        }
        <button
          type="button"
          class="close"
          aria-label="Đóng thông báo"
          (click)="session.clearNotice()"
        >
          <i class="pi pi-times" aria-hidden="true"></i>
        </button>
      </div>
    }
  `,
  styles: `
    .notice {
      display: flex;
      align-items: center;
      gap: 10px;
      padding: 10px 12px;
      margin-bottom: 16px;
      border-radius: 8px;
      border: 1px solid;
      background: var(--p-content-background, #fff);
    }
    .notice.error {
      border-color: #b91c1c;
      color: #7f1d1d;
    }
    .notice.warn {
      border-color: #b45309;
      color: #78350f;
    }
    .notice.info {
      border-color: #1d4ed8;
      color: #1e3a8a;
    }
    .text {
      flex: 1;
    }
    .close {
      border: 0;
      background: transparent;
      cursor: pointer;
      color: inherit;
      min-width: var(--vt-min-touch, 40px);
      min-height: var(--vt-min-touch, 40px);
    }
  `,
})
export class SessionNoticeComponent {
  protected readonly session = inject(SessionService);
}
