import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/**
 * Trang hiện khi `permissionGuard` chặn một tuyến theo ma trận phân quyền (WEB-055, wiki/15a).
 * Không nêu chi tiết vai trò cần có; người dùng liên hệ quản trị đơn vị nếu cho rằng thiếu quyền.
 */
@Component({
  selector: 'vt-forbidden-page',
  imports: [RouterLink],
  template: `
    <main class="forbidden" role="alert">
      <h1>Không có quyền truy cập</h1>
      <p>Vai trò hiện tại của đồng chí không có chức năng nào trong mục này.</p>
      <p>Nếu cho rằng đây là nhầm lẫn, vui lòng liên hệ quản trị viên của đơn vị.</p>
      <a routerLink="/">Về trang chủ</a>
    </main>
  `,
  styles: `
    .forbidden {
      padding: 48px 16px;
      text-align: center;
    }
    h1 {
      margin: 0 0 12px;
      font-size: 1.5rem;
    }
  `,
})
export class ForbiddenPage {}
