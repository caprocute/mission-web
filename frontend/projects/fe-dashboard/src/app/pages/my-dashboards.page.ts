/*
 * SCR-1602, đường vào "Bảng điều hành › Dashboard của tôi" (QT-16 bước 6, 7; WEB-048): dashboard mình tạo
 * và dashboard được chia sẻ (`GET /analytics/dashboards`), nút "Tạo mới". Dashboard chỉ là định nghĩa;
 * khi mở, số liệu tính theo phạm vi của chính người xem (MSG-16-05). Quyền: SCR-1602.dung.
 */
import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AnalyticsApi, SHARE_SCOPE_LABEL } from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { DASHBOARD_CAN, DASHBOARD_USER_ID } from '../dashboard-permissions';
import { errorText, fmtDateTime } from '../dashboard-ui';

@Component({
  selector: 'vt-my-dashboards-page',
  imports: [RouterLink, ButtonModule, MessageModule],
  template: `
    <p class="crumb">
      @if (can('SCR-1601.xem')) {
        <a routerLink="..">Bảng điều hành</a>
      }
    </p>
    <div class="head">
      <div>
        <h1>Dashboard của tôi</h1>
        <p class="muted">
          Dashboard tự dựng từ các chỉ số chuẩn. Người được chia sẻ chỉ thấy số liệu trong phạm vi
          quản lý của chính họ.
        </p>
      </div>
      <a pButton routerLink="new" icon="pi pi-plus" label="Tạo mới"></a>
    </div>
    @if (res.error()) {
      <p-message severity="error" role="alert">{{ err(res.error()) }}</p-message>
    } @else if (res.hasValue()) {
      <div class="box">
        <div class="table-wrap">
          <table class="tbl">
            <caption class="sr-only">
              Danh sách dashboard
            </caption>
            <thead>
              <tr>
                <th scope="col">Tên dashboard</th>
                <th scope="col">Nguồn</th>
                <th scope="col">Chia sẻ</th>
                <th scope="col">Số biểu đồ</th>
                <th scope="col">Cập nhật</th>
              </tr>
            </thead>
            <tbody>
              @for (d of items(); track d.id) {
                <tr [attr.data-dashboard-id]="d.id">
                  <td>
                    <a [routerLink]="[d.id]">{{ d.title }}</a>
                  </td>
                  <td>{{ d.mine ? 'Của tôi' : 'Được chia sẻ' }}</td>
                  <td>{{ scope[d.shareScope] }}</td>
                  <td>{{ d.widgetCount ?? '—' }}</td>
                  <td>{{ dt(d.updatedAt) }}</td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="5">Đồng chí chưa có dashboard nào. Bấm "Tạo mới" để bắt đầu.</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </div>
    } @else {
      <p class="muted">Đang tải…</p>
    }
  `,
  styleUrls: ['../dashboard.css'],
})
export class MyDashboardsPage {
  private readonly api = inject(AnalyticsApi);
  protected readonly can = inject(DASHBOARD_CAN);
  private readonly me = inject(DASHBOARD_USER_ID);
  protected readonly scope = SHARE_SCOPE_LABEL;
  protected readonly res = rxResource({ stream: () => this.api.dashboards() });
  protected readonly items = computed(() =>
    this.res.hasValue()
      ? this.res.value().items.map((d) => ({ ...d, mine: d.ownerUserId === this.me() }))
      : [],
  );
  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected err(e: unknown) {
    return errorText(e, 'Không tải được danh sách dashboard.');
  }
}
