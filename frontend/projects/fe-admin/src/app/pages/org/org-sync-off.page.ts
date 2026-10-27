import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Tuyến "Đồng bộ và đối soát nhân sự" khi cờ đồng bộ từ hệ thống ngoài của tenant đang tắt (ADR-34):
 * mở bằng đường dẫn trực tiếp thì báo tính năng chưa bật ngay trong khung ứng dụng (vẫn còn menu), thay
 * cho trang "Không tìm thấy trang" ngoài khung.
 */
@Component({
  selector: 'vt-org-sync-off-page',
  imports: [RouterLink],
  template: `
    <header class="vt-page-head">
      <div>
        <h1>Đồng bộ và đối soát nhân sự</h1>
        <p>
          Đơn vị chưa bật đồng bộ nhân sự từ hệ thống ngoài. Tài khoản và cơ cấu tổ chức đang được quản
          trị trực tiếp trong ứng dụng.
        </p>
      </div>
    </header>
    <p>
      Khi cần đồng bộ từ phần mềm quản lý nhân sự hoặc cơ sở dữ liệu cán bộ, đồng chí đề nghị quản
      trị nền tảng bật tính năng này cho đơn vị.
    </p>
    <p><a routerLink="/admin/users">Quản lý người dùng trực tiếp</a></p>
  `,
})
export class OrgSyncOffPage {}
