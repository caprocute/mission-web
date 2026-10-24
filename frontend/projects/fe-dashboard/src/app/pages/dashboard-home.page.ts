import { Component, inject } from '@angular/core';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { AnalyticsApi } from '@vt/api-client/domains';
import { catchError, map, of, switchMap } from 'rxjs';
import { DASHBOARD_CAN } from '../dashboard-permissions';
import { DefaultDashboardStore } from '../default-dashboard';
import { LeaderDashboardPage } from './leader-dashboard.page';

/**
 * Trang gốc `/dashboard` (QT-16): người có quyền xem bảng điều hành (SCR-1601.xem) thấy ngay bảng; người chỉ
 * có quyền truy vết (ví dụ thẩm định viên, SCR-1603.xem) được hướng dẫn mở chuỗi truy vết từ hồ sơ thẩm định
 * hoặc từ cảnh báo, vì SCR-1603 luôn cần một kết quả cụ thể làm điểm xuất phát.
 *
 * CNH-03: lần đầu vào bảng điều hành trong phiên làm việc, người đã chọn dashboard mặc định (khoá
 * `ui.dashboard.default`) được mở thẳng dashboard đó nếu còn quyền xem; các lần sau
 * vẫn thấy bảng điều hành chung để không mất lối vào SCR-1601 (suy luận).
 */
let openedDefault = false;

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

  constructor() {
    if (openedDefault || !this.can('SCR-1602.dung')) return;
    openedDefault = true;
    const router = inject(Router);
    const route = inject(ActivatedRoute);
    const analytics = inject(AnalyticsApi);
    inject(DefaultDashboardStore)
      .load()
      .pipe(
        switchMap((id) =>
          id
            ? // còn quyền xem: máy chủ vẫn trả dashboard (không 403/404)
              analytics.dashboard(id, { includeData: false }).pipe(map(() => id))
            : of(null),
        ),
        catchError(() => of(null)),
      )
      .subscribe((id) => {
        if (id) void router.navigate(['dashboards', id], { relativeTo: route });
      });
  }
}

/** Chỉ dùng trong kiểm thử: cho phép mở lại dashboard mặc định. */
export function resetDefaultDashboardRedirect(): void {
  openedDefault = false;
}
