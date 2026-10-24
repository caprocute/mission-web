import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PermissionKey } from '@vt/auth';
import { OVERSIGHT_CAN } from '../oversight-permissions';

/**
 * Trang đầu của remote fe-oversight: lối vào các màn hình giám sát, hậu kiểm theo quyền (wiki/15a).
 * SCR-1201, SCR-1202 do WEB-042 dựng, SCR-1301, SCR-1302 do WEB-043 dựng, SCR-1401 do WEB-044 dựng.
 */
@Component({
  selector: 'vt-oversight-home-page',
  imports: [RouterLink],
  template: `
    <header class="vt-page-head">
      <div>
        <h1>Giám sát, hậu kiểm</h1>
        <p>Khiếu nại, hậu kiểm và cảnh báo bất thường trong đánh giá cán bộ.</p>
      </div>
    </header>
    <ul class="vt-hub">
      @if (canAny('SCR-1201', ['soan', 'ky-gui', 'rut-bao-luu'])) {
        <li>
          <a routerLink="my-complaints" class="vt-hub-card">
            <i class="pi pi-comment" aria-hidden="true"></i>
            <div>
              <strong>Khiếu nại, giải trình của tôi</strong>
              <span>Gửi khiếu nại, giải trình về kết quả đánh giá.</span>
            </div>
          </a>
        </li>
      }
      @if (canAny('SCR-1202', ['tiep-nhan', 'giai-trinh', 'tinh-thu', 'ky-tra-loi', 'mo-lai'])) {
        <li>
          <a routerLink="complaints" class="vt-hub-card">
            <i class="pi pi-inbox" aria-hidden="true"></i>
            <div>
              <strong>Xử lý khiếu nại</strong>
              <span>Tiếp nhận, giải quyết và trả lời khiếu nại.</span>
            </div>
          </a>
        </li>
      }
      @if (canReopen()) {
        <li>
          <a routerLink="reopen-requests" class="vt-hub-card">
            <i class="pi pi-replay" aria-hidden="true"></i>
            <div>
              <strong>Hậu kiểm, mở lại kỳ</strong>
              <span>Đề nghị và phê duyệt mở lại kỳ đã khoá.</span>
            </div>
          </a>
        </li>
      }
      @if (can('SCR-1401.xem')) {
        <li>
          <a routerLink="alerts" class="vt-hub-card">
            <i class="pi pi-exclamation-triangle" aria-hidden="true"></i>
            <div>
              <strong>Cảnh báo bất thường</strong>
              <span>Xác minh và kết luận các dấu hiệu bất thường.</span>
            </div>
          </a>
        </li>
      } @else if (can('SCR-1401.nguong')) {
        <li>
          <a routerLink="alert-thresholds" class="vt-hub-card">
            <i class="pi pi-sliders-h" aria-hidden="true"></i>
            <div>
              <strong>Ngưỡng phát hiện bất thường</strong>
              <span>Điều chỉnh ngưỡng phát hiện bất thường.</span>
            </div>
          </a>
        </li>
      }
      @if (can('SCR-1302.tra-cuu')) {
        <li>
          <a routerLink="ledger" class="vt-hub-card">
            <i class="pi pi-book" aria-hidden="true"></i>
            <div>
              <strong>Sổ cái và kiểm tra toàn vẹn</strong>
              <span>Tra cứu lịch sử thay đổi dữ liệu đánh giá.</span>
            </div>
          </a>
        </li>
      }
    </ul>
  `,
})
export class OversightHomePage {
  protected readonly can = inject(OVERSIGHT_CAN);
  protected canAny(scr: 'SCR-1201' | 'SCR-1202', keys: string[]) {
    return keys.some((k) => this.can(`${scr}.${k}` as PermissionKey));
  }
  protected canReopen() {
    return (['tao', 'duyet-1', 'duyet-2', 'tu-choi', 'huy'] as const).some((k) =>
      // ui-text-ok: khoá quyền, không hiện lên màn hình
      this.can(`SCR-1301.${k}`),
    );
  }
}
