import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ADMIN_CAN } from '../admin-permissions';

/** Trang đầu của remote fe-admin cho vai trò không vào thẳng Danh sách tenant (quản trị tenant, thẩm định). */
@Component({
  selector: 'vt-admin-home-page',
  imports: [RouterLink],
  template: `
    <h1>Quản trị</h1>
    @if (
      can('SCR-0103.xem') ||
      can('SCR-0104.xem') ||
      can('SCR-0801.xem') ||
      can('SCR-0105.xem') ||
      can('SCR-0107.xem')
    ) {
      <ul class="links">
        @if (can('SCR-0103.xem')) {
          <li>
            <a routerLink="org">Cây tổ chức</a>: xem, nhập và thay đổi cơ cấu tổ chức theo thời gian
            hiệu lực
          </li>
        }
        @if (can('SCR-0104.xem')) {
          <li>
            <a routerLink="org/sync">Đồng bộ nhân sự</a>: theo dõi các lần đồng bộ, đối soát dữ liệu
            nhân sự bị lệch
          </li>
        }
        @if (can('SCR-0105.xem')) {
          <li>
            <a routerLink="access">Vai trò theo đơn vị</a>: cấp, duyệt thứ hai, thu hồi vai trò có
            thời hạn
          </li>
        }
        @if (can('SCR-0107.xem')) {
          <li>
            <a routerLink="access/delegations">Uỷ quyền khi vắng mặt</a>: duyệt thay, nghiệm thu
            thay, check-in KR thay có thời hạn
          </li>
        }
        @if (can('SCR-0801.xem')) {
          <li>
            <a routerLink="periods">Kỳ đánh giá</a>: lịch kỳ đánh giá, lệnh khoá kỳ và tiến trình
            khoá kỳ
          </li>
        }
      </ul>
    }
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
    .links {
      padding-left: 20px;
      margin: 0 0 16px;
    }
    a {
      color: #b8002a;
    }
  `,
})
export class AdminHomePage {
  protected readonly can = inject(ADMIN_CAN);
}
