import { Component } from '@angular/core';
import { MessageModule } from 'primeng/message';

/** Trang khung của remote fe-dashboard (WEB-015); màn hình thật do các task QT-16 dựng. */
@Component({
  selector: 'vt-dashboard-home-page',
  imports: [MessageModule],
  template: `
    <h1>Bảng điều hành</h1>
    <p-message severity="info">Chức năng này đang được chuẩn bị đưa vào sử dụng.</p-message>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
  `,
})
export class DashboardHomePage {}
