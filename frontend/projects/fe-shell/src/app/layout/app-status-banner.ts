import { Component, inject } from '@angular/core';
import { AppStatus } from './app-status';

/**
 * Dải thông báo dưới thanh trên: mất mạng, có mạng lại, lỗi chưa xử lý (WEB-012). Dùng `role="status"`
 * và `role="alert"` để trình đọc màn hình đọc ngay; văn phong theo wiki trang 16.
 */
@Component({
  selector: 'vt-app-status-banner',
  template: `
    <div role="status" aria-live="polite">
      @if (!status.online()) {
        <p class="banner offline">
          <i class="pi pi-wifi" aria-hidden="true"></i>
          Mất kết nối mạng. Thông tin đang hiển thị có thể chưa mới nhất; đồng chí vui lòng chờ có
          mạng trở lại rồi thực hiện thao tác.
        </p>
      } @else if (status.reconnected()) {
        <p class="banner online">
          <i class="pi pi-check-circle" aria-hidden="true"></i>
          Đã kết nối mạng trở lại.
        </p>
      }
    </div>
    @if (status.error(); as kind) {
      <div class="banner error" role="alert">
        <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>
        <span>
          @if (kind === 'chunk') {
            Hệ thống vừa được cập nhật. Đồng chí vui lòng tải lại trang để tiếp tục sử dụng.
          } @else {
            Có lỗi khi hiển thị nội dung. Đồng chí có thể tải lại trang; nếu vẫn gặp lỗi, vui lòng
            báo cán bộ hỗ trợ.
          }
        </span>
        <span class="actions">
          <button type="button" class="primary" (click)="reload()">Tải lại trang</button>
          <button type="button" (click)="status.dismissError()">Đóng</button>
        </span>
      </div>
    }
  `,
  styles: `
    .banner {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem 0.75rem;
      margin: 0;
      padding: 0.6rem 1.1rem;
      font-size: 0.95rem;
      line-height: 1.4;
    }
    .offline {
      background: #fef3c7;
      color: #78350f;
      border-bottom: 1px solid #fcd34d;
    }
    .online {
      background: #dcfce7;
      color: #14532d;
      border-bottom: 1px solid #86efac;
    }
    .error {
      background: #fee2e2;
      color: #7f1d1d;
      border-bottom: 1px solid #fca5a5;
    }
    .error > span:first-of-type {
      flex: 1 1 18rem;
    }
    .actions {
      display: inline-flex;
      gap: 0.5rem;
    }
    button {
      min-height: var(--vt-min-touch, 40px);
      padding: 0.3rem 0.9rem;
      border-radius: 8px;
      border: 1px solid #7f1d1d;
      background: #fff;
      color: #7f1d1d;
      font: inherit;
      font-weight: 600;
      cursor: pointer;
    }
    button.primary {
      background: #7f1d1d;
      color: #fff;
    }
    button:focus-visible {
      outline: 3px solid #1d4ed8;
      outline-offset: 2px;
    }
  `,
})
export class AppStatusBanner {
  protected readonly status = inject(AppStatus);

  protected reload(): void {
    globalThis.location?.reload();
  }
}
