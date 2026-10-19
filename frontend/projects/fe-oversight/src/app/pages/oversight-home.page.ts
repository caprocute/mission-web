import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { MessageModule } from 'primeng/message';
import { OVERSIGHT_CAN } from '../oversight-permissions';

/**
 * Trang đầu của remote fe-oversight: lối vào các màn hình giám sát, hậu kiểm theo quyền (wiki/15a).
 * SCR-1301, SCR-1302 do WEB-043 dựng; khiếu nại (SCR-1201, SCR-1202) và cảnh báo (SCR-1401) còn chờ.
 */
@Component({
  selector: 'vt-oversight-home-page',
  imports: [MessageModule, RouterLink],
  template: `
    <h1>Giám sát, hậu kiểm</h1>
    <ul class="cards">
      @if (canReopen()) {
        <li>
          <a routerLink="reopen-requests">Hậu kiểm, mở lại kỳ</a>
          <span>Lập, tính thử và phê duyệt yêu cầu mở lại kỳ đã khoá với hai người duyệt.</span>
        </li>
      }
      @if (can('SCR-1302.tra-cuu')) {
        <li>
          <a routerLink="ledger">Sổ cái và kiểm tra toàn vẹn</a>
          <span>Tra cứu lịch sử thay đổi, kiểm tra chuỗi băm, niêm phong Merkle và bản neo.</span>
        </li>
      }
    </ul>
    <p-message severity="info">
      Khiếu nại, giải trình (SCR-1201, SCR-1202) và cảnh báo bất thường (SCR-1401) đang được phát
      triển.
    </p-message>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
    .cards {
      list-style: none;
      padding: 0;
      margin: 0 0 16px;
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
      gap: 12px;
    }
    .cards li {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      padding: 16px;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .cards a {
      font-weight: 600;
      font-size: 1.05rem;
    }
  `,
})
export class OversightHomePage {
  protected readonly can = inject(OVERSIGHT_CAN);
  protected canReopen() {
    return (['tao', 'duyet-1', 'duyet-2', 'tu-choi', 'huy'] as const).some((k) =>
      this.can(`SCR-1301.${k}`),
    );
  }
}
