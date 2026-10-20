import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { DASHBOARD_CAN } from '../dashboard-permissions';
import { LeaderDashboardPage } from './leader-dashboard.page';

/**
 * Trang gốc `/dashboard` (QT-16): người có quyền xem bảng điều hành (SCR-1601.xem) thấy ngay bảng; người chỉ
 * có quyền truy vết (ví dụ thẩm định viên, SCR-1603.xem) được hướng dẫn mở chuỗi truy vết từ hồ sơ thẩm định
 * hoặc từ cảnh báo, vì SCR-1603 luôn cần một kết quả cụ thể làm điểm xuất phát.
 */
@Component({
  selector: 'vt-dashboard-home-page',
  imports: [RouterLink, LeaderDashboardPage],
  template: `
    @if (can('SCR-1601.xem')) {
      <vt-leader-dashboard-page />
    } @else {
      <h1>Bảng điều hành</h1>
      <p class="muted">
        Đồng chí mở chuỗi truy vết của một kết quả đánh giá từ hồ sơ thẩm định hoặc từ cảnh báo bất
        thường.
      </p>
      @if (can('SCR-1602.dung')) {
        <p><a routerLink="dashboards">Dashboard của tôi</a></p>
      }
    }
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 8px;
    }
    .muted {
      color: #4b5563;
    }
  `,
})
export class DashboardHomePage {
  protected readonly can = inject(DASHBOARD_CAN);
}
