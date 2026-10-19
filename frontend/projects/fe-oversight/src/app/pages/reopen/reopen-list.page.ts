/*
 * SCR-1301 "Yêu cầu mở lại kỳ", phần danh sách (QT-13 bước 4–7; đường vào "Menu Giám sát › Hậu kiểm, mở
 * lại kỳ" và thông báo task duyệt). Danh sách là suy luận của hợp đồng (`GET /oversight/reopen-requests`,
 * TL-02 không vẽ). Cột: mã MLK-yyyy-nnn, kỳ, căn cứ, phạm vi, trạng thái, người tạo, ngày tạo. Lọc: chờ tôi
 * duyệt (`awaitingMyApproval`), đang chờ duyệt, đã xong, tất cả. Nút "Tạo yêu cầu mở lại kỳ" theo quyền
 * `SCR-1301.tao` (AC-07, AC-10). Quyền xem: một trong các khoá của SCR-1301 (wiki/15a).
 */
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  REOPEN_BASIS_LABEL,
  REOPEN_STATUS_LABEL,
  ReopenApi,
  ReopenRequest,
  ReopenRequestFilter,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TagModule } from 'primeng/tag';
import { OVERSIGHT_CAN } from '../../oversight-permissions';
import { errorText, fmtDateTime } from '../../oversight-ui';
import { reopenSeverity, scopeSummary } from './reopen-ui';

type View = 'mine' | 'pending' | 'done' | 'all';

const FILTERS: Record<View, ReopenRequestFilter> = {
  mine: { awaitingMyApproval: true },
  pending: { status: ['CHO_DUYET_1', 'CHO_DUYET_2'] },
  done: { status: ['DA_DUYET', 'DA_THUC_HIEN', 'TU_CHOI', 'DA_DONG'] },
  all: {},
};

@Component({
  selector: 'vt-reopen-list-page',
  imports: [FormsModule, RouterLink, ButtonModule, MessageModule, SelectButtonModule, TagModule],
  template: `
    <div class="head">
      <div>
        <h1>Hậu kiểm, mở lại kỳ</h1>
        @if (!simple()) {
          <p class="muted">
            Kỳ đã khoá chỉ được mở lại trong phạm vi giới hạn khi hai người có thẩm quyền khác nhau
            cùng phê duyệt.
          </p>
        }
      </div>
      <div class="actions">
        @if (can('SCR-1302.tra-cuu')) {
          <a routerLink="../ledger" class="p-button p-button-outlined p-button-contrast">Sổ cái</a>
        }
        @if (can('SCR-1301.tao')) {
          <a routerLink="new" class="p-button">Tạo yêu cầu mở lại kỳ</a>
        }
      </div>
    </div>

    <p-selectbutton
      [options]="views()"
      optionLabel="label"
      optionValue="value"
      [ngModel]="view()"
      (ngModelChange)="view.set($event)"
      [allowEmpty]="false"
      ariaLabelledBy="list-h"
    />
    <h2 id="list-h" class="sr-only">Danh sách yêu cầu mở lại kỳ</h2>

    @if (rows.error()) {
      <p-message severity="error">{{ loadError() }}</p-message>
    } @else if (rows.isLoading() && !rows.hasValue()) {
      <p class="muted">Đang tải danh sách…</p>
    } @else {
      <div class="table-wrap">
        <table class="tbl">
          <thead>
            <tr>
              <th scope="col">Mã yêu cầu</th>
              <th scope="col">Kỳ</th>
              <th scope="col">Căn cứ</th>
              @if (!simple()) {
                <th scope="col">Phạm vi</th>
              }
              <th scope="col">Trạng thái</th>
              <th scope="col">Người tạo</th>
              @if (!simple()) {
                <th scope="col">Ngày tạo</th>
              }
            </tr>
          </thead>
          <tbody>
            @for (r of rows.value() ?? []; track r.id) {
              <tr>
                <td>
                  <a [routerLink]="r.id" class="code">{{ r.code }}</a>
                </td>
                <td>{{ r.period.label }}</td>
                <td>{{ basis(r) }}</td>
                @if (!simple()) {
                  <td>{{ scope(r) }}</td>
                }
                <td>
                  <p-tag [value]="statusLabel[r.status]" [severity]="severity(r)" />
                </td>
                <td>{{ r.requestedBy.name }}</td>
                @if (!simple()) {
                  <td class="nowrap">{{ dt(r.createdAt) }}</td>
                }
              </tr>
            } @empty {
              <tr>
                <td [attr.colspan]="simple() ? 5 : 7" class="muted">Không có yêu cầu nào.</td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }
  `,
  styles: `
    :host {
      --p-togglebutton-color: #374151;
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      flex-wrap: wrap;
      margin-bottom: 12px;
    }
    h1 {
      font-size: 1.4rem;
      margin: 4px 0;
    }
    .muted {
      color: #4b5563;
    }
    .actions {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    a.p-button {
      text-decoration: none;
    }
    .table-wrap {
      overflow-x: auto;
      margin-top: 12px;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
    }
    .tbl th,
    .tbl td {
      text-align: left;
      padding: 8px 10px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
      vertical-align: top;
    }
    .tbl th {
      font-weight: 600;
      background: var(--p-content-hover-background, #f9fafb);
    }
    .code {
      font-weight: 600;
    }
    .nowrap {
      white-space: nowrap;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class ReopenListPage {
  private readonly api = inject(ReopenApi);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(OVERSIGHT_CAN);
  protected readonly statusLabel = REOPEN_STATUS_LABEL;

  /** Người duyệt mở thẳng "Chờ tôi duyệt"; người tạo, kiểm tra xem "Đang chờ duyệt". */
  protected readonly view = signal<View>(
    this.can('SCR-1301.duyet-1') || this.can('SCR-1301.duyet-2') ? 'mine' : 'pending',
  );
  protected readonly views = computed(() =>
    [
      { value: 'mine' as View, label: 'Chờ tôi duyệt' },
      { value: 'pending' as View, label: 'Đang chờ duyệt' },
      { value: 'done' as View, label: 'Đã xong' },
      { value: 'all' as View, label: 'Tất cả' },
    ].filter(
      (v) => v.value !== 'mine' || this.can('SCR-1301.duyet-1') || this.can('SCR-1301.duyet-2'),
    ),
  );
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected readonly rows = rxResource({
    params: () => FILTERS[this.view()],
    stream: ({ params }) => this.api.list(params),
  });
  protected readonly loadError = computed(() => errorText(this.rows.error()));

  protected basis(r: ReopenRequest) {
    return r.basis ? `${REOPEN_BASIS_LABEL[r.basis.kind]} ${r.basis.code ?? ''}`.trim() : '—';
  }
  protected scope(r: ReopenRequest) {
    return scopeSummary(r);
  }
  protected severity(r: ReopenRequest) {
    return reopenSeverity(r.status);
  }
  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
}
