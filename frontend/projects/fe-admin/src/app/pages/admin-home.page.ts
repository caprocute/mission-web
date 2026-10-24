import { Component, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EXTERNAL_SYNC_FLAG, FeatureFlagService } from '@vt/api-client';
import { ADMIN_CAN } from '../admin-permissions';

/** Trang đầu của remote fe-admin cho vai trò không vào thẳng Danh sách tenant (quản trị tenant, thẩm định). */
@Component({
  selector: 'vt-admin-home-page',
  imports: [RouterLink],
  template: `
    <header class="vt-hero-banner">
      <div>
        <span class="vt-hero-kicker">Khung nền tảng lõi · Quản trị hệ thống</span>
        <h1>Quản trị</h1>
        <p>Quản lý cơ cấu tổ chức, người dùng, vai trò và quyền, ủy quyền và kỳ đánh giá.</p>
      </div>
    </header>

    @if (
      can('SCR-0103.xem') ||
      can('SCR-0108.xem') ||
      can('SCR-0110.xem') ||
      (sync() && can('SCR-0104.xem')) ||
      can('SCR-0801.xem') ||
      can('SCR-0105.xem') ||
      can('SCR-0107.xem')
    ) {
      <ul class="links">
        @if (can('SCR-0103.xem')) {
          <li class="admin-card">
            <span class="admin-card__badge">Tổ chức</span>
            <a routerLink="org">Cây tổ chức</a>
            <p>Xem, nhập và thay đổi cơ cấu tổ chức theo thời gian hiệu lực.</p>
          </li>
        }
        @if (can('SCR-0108.xem')) {
          <li class="admin-card">
            <span class="admin-card__badge">Người dùng</span>
            <a routerLink="users">Người dùng</a>
            <p>
              Tạo tài khoản, giao đơn vị công tác và kiêm nhiệm, đặt lại mật khẩu, khoá tài khoản.
            </p>
          </li>
        }
        @if (can('SCR-0110.xem')) {
          <li class="admin-card">
            <span class="admin-card__badge">Phân quyền</span>
            <a routerLink="roles">Vai trò và quyền</a>
            <p>
              Tạo vai trò riêng của đơn vị bằng cách chọn chức năng và phạm vi từ vai trò có sẵn.
            </p>
          </li>
        }
        @if (sync() && can('SCR-0104.xem')) {
          <li class="admin-card">
            <span class="admin-card__badge">Nhân sự</span>
            <a routerLink="org/sync">Đồng bộ nhân sự</a>
            <p>Theo dõi các lần đồng bộ, đối soát dữ liệu nhân sự bị lệch.</p>
          </li>
        }
        @if (can('SCR-0105.xem')) {
          <li class="admin-card">
            <span class="admin-card__badge">Phân quyền</span>
            <a routerLink="access">Vai trò theo đơn vị</a>
            <p>Cấp, duyệt thứ hai và thu hồi vai trò có thời hạn theo ma trận phân quyền.</p>
          </li>
        }
        @if (can('SCR-0107.xem')) {
          <li class="admin-card">
            <span class="admin-card__badge">Ủy quyền động</span>
            <a routerLink="access/delegations">Uỷ quyền khi vắng mặt</a>
            <p>Duyệt thay, nghiệm thu thay và cập nhật tiến độ thay có thời hạn.</p>
          </li>
        }
        @if (can('SCR-0801.xem')) {
          <li class="admin-card">
            <span class="admin-card__badge">Chu kỳ</span>
            <a routerLink="periods">Kỳ đánh giá</a>
            <p>Thiết lập lịch kỳ đánh giá, lệnh khoá kỳ và theo dõi tiến trình khoá kỳ.</p>
          </li>
        }
      </ul>
    }
  `,
  styles: `
    .links {
      list-style: none;
      padding: 0;
      margin: 0 0 16px;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
      gap: 14px;
    }
    .admin-card {
      background: #ffffff;
      border: 1px solid #e2e8f0;
      border-radius: 12px;
      padding: 18px;
      box-shadow: 0 1px 3px rgba(15, 23, 42, 0.05);
      display: flex;
      flex-direction: column;
      gap: 6px;
      transition:
        transform 0.15s ease,
        box-shadow 0.15s ease,
        border-color 0.15s ease;
    }
    .admin-card:hover {
      transform: translateY(-2px);
      border-color: #fda4af;
      box-shadow: 0 10px 15px -3px rgba(15, 23, 42, 0.08);
    }
    .admin-card__badge {
      align-self: flex-start;
      font-size: 0.72rem;
      font-weight: 700;
      color: #9f1239;
      background: #ffe4e6;
      padding: 2px 8px;
      border-radius: 6px;
    }
    a {
      color: #0f172a;
      font-size: 1.05rem;
      font-weight: 800;
      text-decoration: none;
    }
    a:hover {
      color: #b8002a;
      text-decoration: underline;
    }
    .admin-card p {
      margin: 0;
      font-size: 0.88rem;
      color: #475569;
      line-height: 1.45;
    }
  `,
})
export class AdminHomePage {
  protected readonly can = inject(ADMIN_CAN);
  private readonly flags = inject(FeatureFlagService);
  /** Đồng bộ nhân sự từ hệ thống ngoài là tính năng tuỳ chọn, mặc định tắt (ADR-34). */
  protected readonly sync = computed(() => this.flags.isEnabled(EXTERNAL_SYNC_FLAG));
}
