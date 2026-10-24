import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EXTERNAL_SYNC_FLAG, FeatureFlagService } from '@vt/api-client';
import { ADMIN_CAN } from '../admin-permissions';

/** Trang đầu của remote fe-admin cho vai trò không vào thẳng Danh sách tenant (quản trị tenant, thẩm định). */
@Component({
  selector: 'vt-admin-home-page',
  imports: [RouterLink],
  template: `
    <header class="vt-page-head">
      <div>
        <h1>Quản trị</h1>
        <p>Tổ chức, người dùng, vai trò và quyền, uỷ quyền và kỳ đánh giá của đơn vị.</p>
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
      @if (can('SCR-0108.xem')) {
        <li>
          <a routerLink="users" class="vt-hub-card">
            <i class="pi pi-users" aria-hidden="true"></i>
            <div>
              <strong>Người dùng</strong>
              <span>Tạo tài khoản, giao đơn vị công tác, đặt lại mật khẩu, khoá tài khoản.</span>
            </div>
          </a>
        </li>
      }
      @if (can('SCR-0110.xem')) {
        <li>
          <a routerLink="roles" class="vt-hub-card">
            <i class="pi pi-key" aria-hidden="true"></i>
            <div>
              <strong>Vai trò và quyền</strong>
              <span>Tạo vai trò riêng của đơn vị từ vai trò có sẵn.</span>
            </div>
          </a>
        </li>
      }
      @if (sync() && can('SCR-0104.xem')) {
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
  private readonly flags = inject(FeatureFlagService);
  /** Đồng bộ nhân sự từ hệ thống ngoài là tính năng tuỳ chọn, mặc định tắt (ADR-34). */
  protected readonly sync = computed(() => this.flags.isEnabled(EXTERNAL_SYNC_FLAG));
}
