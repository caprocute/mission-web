import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ADMIN_CAN } from '../admin-permissions';

/** Trang đầu của remote fe-admin cho vai trò không vào thẳng Danh sách tenant (quản trị tenant, thẩm định). */
@Component({
  selector: 'vt-admin-home-page',
  imports: [RouterLink],
  template: `
    <header class="vt-page-head">
      <div>
        <h1>Quản trị</h1>
        <p>Tổ chức, nhân sự, phân quyền và kỳ đánh giá của đơn vị.</p>
      </div>
    </header>

    <ul class="vt-hub">
      @if (can('SCR-0103.xem')) {
        <li>
          <a routerLink="org" class="vt-hub-card">
            <i class="pi pi-sitemap" aria-hidden="true"></i>
            <div>
              <strong>Cây tổ chức</strong>
              <span>Cơ cấu tổ chức theo thời gian hiệu lực.</span>
            </div>
          </a>
        </li>
      }
      @if (can('SCR-0104.xem')) {
        <li>
          <a routerLink="org/sync" class="vt-hub-card">
            <i class="pi pi-sync" aria-hidden="true"></i>
            <div>
              <strong>Đồng bộ nhân sự</strong>
              <span>Theo dõi đồng bộ và đối soát dữ liệu nhân sự.</span>
            </div>
          </a>
        </li>
      }
      @if (can('SCR-0105.xem')) {
        <li>
          <a routerLink="access" class="vt-hub-card">
            <i class="pi pi-shield" aria-hidden="true"></i>
            <div>
              <strong>Vai trò theo đơn vị</strong>
              <span>Cấp, duyệt và thu hồi vai trò có thời hạn.</span>
            </div>
          </a>
        </li>
      }
      @if (can('SCR-0107.xem')) {
        <li>
          <a routerLink="access/delegations" class="vt-hub-card">
            <i class="pi pi-user-edit" aria-hidden="true"></i>
            <div>
              <strong>Uỷ quyền khi vắng mặt</strong>
              <span>Uỷ quyền duyệt, nghiệm thu thay có thời hạn.</span>
            </div>
          </a>
        </li>
      }
      @if (can('SCR-0801.xem')) {
        <li>
          <a routerLink="periods" class="vt-hub-card">
            <i class="pi pi-calendar" aria-hidden="true"></i>
            <div>
              <strong>Kỳ đánh giá</strong>
              <span>Lịch kỳ đánh giá và khoá kỳ.</span>
            </div>
          </a>
        </li>
      }
    </ul>
  `,
})
export class AdminHomePage {
  protected readonly can = inject(ADMIN_CAN);
}
