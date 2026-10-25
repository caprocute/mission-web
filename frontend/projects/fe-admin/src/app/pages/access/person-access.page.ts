/*
 * SCR-0106 "Hồ sơ quyền của một người" (QT-01, WEB-058). Tác nhân: AC-02, AC-04 (người trong cây đơn vị
 * của mình), AC-10 xem và xuất phục vụ kiểm tra. Đường vào: tên cán bộ trên SCR-0105
 * (`/admin/access/people/:personId`).
 * Vùng: 1 vai trò đang có và đã kết thúc; 2 uỷ quyền đã giao và đang nhận; 3 dòng thời gian lịch sử cấp,
 *   duyệt, thu hồi, hết hạn (ai, lúc nào, căn cứ).
 * Chức năng: F1 xem (GET /config/role-bindings?personId, /config/delegations?personId,
 *   /config/access-history?personId); F2 xuất CSV (GET /config/access-history/export, step-up BR-01-15).
 * Quyền (wiki/15a): SCR-0106.xem, SCR-0106.xuat.
 */
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  ACCESS_EVENT_KIND_LABEL,
  DELEGATION_KIND_LABEL,
  DELEGATION_STATUS_LABEL,
  ROLE_BINDING_STATUS_LABEL,
  ROLE_SCOPE_LABEL,
  RoleAdminApi,
  RoleBinding,
} from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { map } from 'rxjs';
import { ADMIN_CAN } from '../../admin-permissions';
import { fmtDate, fmtDateTime } from '../../admin-ui';
import { ACCESS_STEP_UP, ACCESS_STYLES, accessError, statusTone } from './access-ui';
import { RoleNames } from './role-names';
import { VtSkeletonComponent } from '@vt/design-system';

const LIVE = ['HIEU_LUC', 'SAP_HIEU_LUC', 'CHO_DUYET'];

@Component({
  selector: 'vt-person-access-page',
  imports: [VtSkeletonComponent, RouterLink, ButtonModule, MessageModule],
  template: `
    <p><a routerLink="../access" class="link">← Vai trò theo đơn vị</a></p>
    <header class="head">
      <div>
        <h1>Hồ sơ quyền{{ name() ? ': ' + name() : '' }}</h1>
        <p class="muted">Vai trò, uỷ quyền và lịch sử thay đổi quyền của một cán bộ.</p>
      </div>
      @if (can('SCR-0106.xuat')) {
        <p-button
          label="Xuất lịch sử (CSV)"
          icon="pi pi-download"
          severity="secondary"
          [outlined]="true"
          [loading]="exporting()"
          (onClick)="exportCsv()"
        />
      }
    </header>
    @if (error(); as e) {
      <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
    }

    <section class="box" aria-labelledby="pa-z1">
      <h2 id="pa-z1" class="h">Vai trò</h2>
      @if (bindings.error(); as e) {
        <p class="err" role="alert">{{ err(e) }}</p>
      }
      <div class="scroll">
        <table class="tbl">
          <thead>
            <tr>
              <th scope="col">Vai trò</th>
              <th scope="col">Đơn vị, phạm vi</th>
              <th scope="col">Hiệu lực</th>
              <th scope="col">Người cấp, người duyệt</th>
              <th scope="col">Trạng thái</th>
            </tr>
          </thead>
          <tbody>
            @for (b of sorted(); track b.id) {
              <tr [class.ended]="!live(b)">
                <td>
                  {{ names.label(b.roleCode) }}
                </td>
                <td>
                  {{ b.orgUnit.name }}
                  <span class="muted small block">{{ scopeLabel[b.scope] }}</span>
                </td>
                <td>{{ date(b.validFrom) }} → {{ date(b.validTo) }}</td>
                <td>
                  {{ b.grantedBy.name }}
                  @if (b.approvedBy) {
                    <span class="muted small block">Duyệt: {{ b.approvedBy.name }}</span>
                  }
                </td>
                <td>
                  <span [class]="'pill ' + tone(b.status)">{{ statusLabel[b.status] }}</span>
                  @if (b.endReason) {
                    <span class="muted small block">{{ b.endReason }}</span>
                  }
                </td>
              </tr>
            } @empty {
              <tr>
                @if (bindings.isLoading()) {
                  <td colspan="5">
                    <vt-skeleton
                      variant="table"
                      [header]="false"
                      [columns]="5"
                      label="Đang tải vai trò đã cấp"
                    />
                  </td>
                } @else {
                  <td colspan="5" class="muted">Chưa có vai trò nào được cấp.</td>
                }
              </tr>
            }
          </tbody>
        </table>
      </div>
    </section>

    <section class="box" aria-labelledby="pa-z2">
      <h2 id="pa-z2" class="h">Uỷ quyền</h2>
      <ul class="list">
        @for (d of delegations(); track d.id) {
          <li>
            <strong>{{ kindLabel[d.kind] }}</strong>
            @if (d.delegator.id === personId()) {
              giao cho đồng chí {{ d.delegate.name }}
            } @else {
              nhận từ đồng chí {{ d.delegator.name }}
            }
            tại {{ d.orgUnit.name }}{{ d.subject ? ' · ' + d.subject.name : '' }},
            {{ date(d.validFrom) }} → {{ date(d.validTo) }}
            <span [class]="'pill ' + tone(d.status)">{{ delegationStatus[d.status] }}</span>
          </li>
        } @empty {
          @if (delegationPage.isLoading()) {
            <li>
              <vt-skeleton variant="list" [rows]="2" label="Đang tải danh sách uỷ quyền" />
            </li>
          } @else {
            <li class="muted">Không có uỷ quyền nào.</li>
          }
        }
      </ul>
    </section>

    <section class="box" aria-labelledby="pa-z3">
      <h2 id="pa-z3" class="h">Lịch sử thay đổi quyền</h2>
      <ol class="timeline">
        @for (e of events(); track e.id) {
          <li>
            <time [attr.datetime]="e.at">{{ dateTime(e.at) }}</time>
            <strong>{{ eventLabel[e.kind] }}</strong
            >: {{ e.summary }}
            <span class="muted small">
              · {{ e.actor ? 'bởi ' + e.actor.name : 'tự hết hiệu lực khi hết thời hạn' }}</span
            >
          </li>
        } @empty {
          @if (history.isLoading()) {
            <li>
              <vt-skeleton variant="list" [rows]="3" label="Đang tải lịch sử thay đổi quyền" />
            </li>
          } @else {
            <li class="muted">Chưa có sự kiện nào.</li>
          }
        }
      </ol>
    </section>
  `,
  styles: [
    ACCESS_STYLES,
    `
      .head {
        display: flex;
        justify-content: space-between;
        gap: 12px;
        flex-wrap: wrap;
        align-items: flex-start;
      }
      h1 {
        font-size: 1.4rem;
        margin: 0 0 4px;
      }
      .box {
        margin-bottom: 16px;
      }
      .block {
        display: block;
      }
      tr.ended td {
        color: #64748b;
      }
      .list,
      .timeline {
        margin: 0;
        padding-left: 20px;
        display: grid;
        gap: 8px;
      }
      time {
        font-variant-numeric: tabular-nums;
        margin-right: 8px;
      }
    `,
  ],
})
export class PersonAccessPage {
  private readonly api = inject(RoleAdminApi);
  private readonly stepUp = inject(ACCESS_STEP_UP);
  protected readonly can = inject(ADMIN_CAN);

  protected readonly names = inject(RoleNames);
  protected readonly scopeLabel = ROLE_SCOPE_LABEL;
  protected readonly statusLabel = ROLE_BINDING_STATUS_LABEL;
  protected readonly kindLabel = DELEGATION_KIND_LABEL;
  protected readonly delegationStatus = DELEGATION_STATUS_LABEL;
  protected readonly eventLabel = ACCESS_EVENT_KIND_LABEL;
  protected readonly date = fmtDate;
  protected readonly dateTime = fmtDateTime;
  protected readonly tone = statusTone;

  protected readonly personId = toSignal(
    inject(ActivatedRoute).paramMap.pipe(map((p) => p.get('personId') ?? '')),
    { initialValue: '' },
  );
  protected readonly exporting = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly bindings = rxResource({
    params: () => this.personId() || undefined,
    stream: ({ params }) => this.api.bindings({ personId: params }),
  });
  protected readonly delegationPage = rxResource({
    params: () => this.personId() || undefined,
    stream: ({ params }) => this.api.delegations({ personId: params }),
  });
  protected readonly history = rxResource({
    params: () => this.personId() || undefined,
    stream: ({ params }) => this.api.history({ personId: params }),
  });

  protected readonly sorted = computed(() => {
    const items = this.bindings.hasValue() ? [...this.bindings.value().items] : [];
    return items.sort(
      (a, b) =>
        Number(this.live(b)) - Number(this.live(a)) || b.validFrom.localeCompare(a.validFrom),
    );
  });
  protected readonly delegations = computed(() =>
    this.delegationPage.hasValue() ? this.delegationPage.value().items : [],
  );
  protected readonly events = computed(() =>
    this.history.hasValue() ? this.history.value().items : [],
  );
  protected readonly name = computed(
    () => this.sorted()[0]?.person.name ?? this.events()[0]?.person.name ?? '',
  );

  protected live(b: RoleBinding) {
    return LIVE.includes(b.status);
  }
  protected err(e: unknown) {
    return accessError(e, 'Không tải được hồ sơ quyền.').text;
  }

  protected async exportCsv() {
    if (!(await this.stepUp('Xuất lịch sử quyền cần xác thực nhiều lớp.'))) return;
    this.exporting.set(true);
    this.error.set(null);
    this.api.exportHistory(this.personId()).subscribe({
      next: (data) => {
        this.exporting.set(false);
        // máy chủ giả lập có thể trả chuỗi thay cho Blob
        const blob = data instanceof Blob ? data : new Blob([String(data)], { type: 'text/csv' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `lich-su-quyen-${this.personId()}.csv`;
        a.click();
        URL.revokeObjectURL(url);
      },
      error: (e: unknown) => {
        this.exporting.set(false);
        this.error.set(accessError(e, 'Không xuất được lịch sử quyền.').text);
      },
    });
  }
}
