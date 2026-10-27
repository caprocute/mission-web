/*
 * SCR-0107 "Uỷ quyền khi vắng mặt" (QT-01, WEB-058). Tác nhân: AC-04 khai báo và thu hồi trong cây đơn
 * vị của mình; AC-02, AC-10 xem. Đường vào: SCR-0105 hoặc trang quản trị (`/admin/access/delegations`).
 * Vùng: 1 cây đơn vị; 2 bảng uỷ quyền (loại, người uỷ quyền, người nhận, đối tượng, thời hạn, trạng thái).
 * Chức năng: F1 xem (GET /config/delegations); F2 khai báo (POST, step-up); F3 thu hồi (POST .../revoke,
 *   If-Match, lý do bắt buộc).
 * Quy tắc: BR-01-13 không tự uỷ quyền cho mình; BR-01-16 bắt buộc thời hạn; BR-01-17 không uỷ quyền lại
 *   (MSG-01-18); BR-01-18 người nhận vẫn chịu phân tách nhiệm vụ.
 * Quyền (wiki/15a): SCR-0107.xem, SCR-0107.tao, SCR-0107.thu-hoi.
 */
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  DELEGATION_KIND_LABEL,
  DELEGATION_STATUS_LABEL,
  Delegation,
  OrgTreeApi,
  RoleAdminApi,
} from '@vt/api-client/domains';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { ADMIN_CAN } from '../../admin-permissions';
import { fmtDate } from '../../admin-ui';
import { OrgTreeView } from '../org/org-tree-view';
import { ACCESS_ME, ACCESS_STEP_UP, ACCESS_STYLES, accessError, statusTone } from './access-ui';
import { DelegationDialog } from './delegation.dialog';
import { ReasonDialog } from './reason.dialog';

@Component({
  selector: 'vt-delegations-page',
  imports: [
    VtSkeletonComponent,
    RouterLink,
    ButtonModule,
    MessageModule,
    OrgTreeView,
    DelegationDialog,
    ReasonDialog,
  ],
  template: `
    @if (can('SCR-0105.xem')) {
      <p><a routerLink="../access" class="link">← Vai trò theo đơn vị</a></p>
    }
    <header class="head">
      <div>
        <h1>Uỷ quyền khi vắng mặt</h1>
        <p class="muted">Giao người khác xử lý thay công việc trong thời gian đồng chí vắng mặt.</p>
      </div>
      @if (can('SCR-0107.tao')) {
        <p-button
          label="Khai báo uỷ quyền"
          icon="pi pi-plus"
          [disabled]="!selected()"
          (onClick)="createOpen.set(true)"
        />
      }
    </header>

    @for (n of notices(); track n.text) {
      <p-message [severity]="n.kind" [text]="n.text" styleClass="msg" />
    }

    <div class="cols">
      <section class="box" aria-labelledby="dl-z1">
        <h2 id="dl-z1" class="h">Đơn vị</h2>
        <vt-org-tree-view [time]="{}" [simple]="simple()" [(selected)]="selected" />
      </section>

      <section class="box" aria-labelledby="dl-z2" aria-live="polite">
        <h2 id="dl-z2" class="h">
          @if (selected() && unit.isLoading()) {
            <vt-skeleton variant="text" [rows]="1" label="Đang tải tên đơn vị" />
          } @else {
            {{ selected() ? 'Uỷ quyền tại ' + unitName() : 'Uỷ quyền' }}
          }
        </h2>
        @if (!selected()) {
          <p class="muted">Chọn một đơn vị trên cây để xem uỷ quyền.</p>
        } @else {
          @if (page.error(); as e) {
            <p class="err" role="alert">{{ err(e) }}</p>
          }
          <div class="scroll">
            <table class="tbl">
              <thead>
                <tr>
                  <th scope="col">Loại</th>
                  <th scope="col">Người vắng mặt</th>
                  <th scope="col">Người nhận</th>
                  <th scope="col">Thời hạn</th>
                  <th scope="col">Trạng thái</th>
                  <th scope="col"><span class="sr-only">Thao tác</span></th>
                </tr>
              </thead>
              <tbody>
                @for (d of items(); track d.id) {
                  <tr>
                    <td>
                      {{ kindLabel[d.kind] }}
                      @if (d.subject) {
                        <span class="muted small block">{{ d.subject.name }}</span>
                      }
                    </td>
                    <td>{{ d.delegator.name }}</td>
                    <td>{{ d.delegate.name }}</td>
                    <td>
                      {{ date(d.validFrom) }} → {{ date(d.validTo) }}
                      <span class="muted small block">{{ d.reason }}</span>
                    </td>
                    <td>
                      <span [class]="'pill ' + tone(d.status)">{{ statusLabel[d.status] }}</span>
                      @if (d.endReason) {
                        <span class="muted small block">{{ d.endReason }}</span>
                      }
                    </td>
                    <td>
                      @if (canRevoke(d)) {
                        <p-button
                          label="Thu hồi"
                          size="small"
                          severity="danger"
                          [text]="true"
                          (onClick)="ask(d)"
                        />
                      }
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    @if (page.isLoading()) {
                      <td colspan="6">
                        <vt-skeleton
                          variant="table"
                          [header]="false"
                          [columns]="6"
                          label="Đang tải danh sách uỷ quyền"
                        />
                      </td>
                    } @else {
                      <td colspan="6" class="muted">Đơn vị chưa có uỷ quyền nào.</td>
                    }
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>

    @if (selected(); as unitId) {
      <vt-delegation-dialog
        [unitId]="unitId"
        [unitName]="unitName()"
        [(visible)]="createOpen"
        (created)="onCreated($event)"
      />
    }
    <vt-reason-dialog
      [(visible)]="reasonOpen"
      header="Thu hồi uỷ quyền"
      [lead]="reasonLead()"
      confirmLabel="Thu hồi"
      [busy]="busy()"
      [error]="reasonError()"
      (confirmed)="revoke($event)"
    />
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
      .cols {
        display: grid;
        grid-template-columns: minmax(260px, 1fr) minmax(0, 2.6fr);
        gap: 16px;
        align-items: start;
      }
      @media (max-width: 1100px) {
        .cols {
          grid-template-columns: 1fr;
        }
      }
      .block {
        display: block;
      }
      .sr-only {
        position: absolute;
        width: 1px;
        height: 1px;
        overflow: hidden;
        clip: rect(0 0 0 0);
      }
    `,
  ],
})
export class DelegationsPage {
  private readonly api = inject(RoleAdminApi);
  private readonly units = inject(OrgTreeApi);
  private readonly theme = inject(ThemeService);
  private readonly stepUp = inject(ACCESS_STEP_UP);
  private readonly me = inject(ACCESS_ME);
  protected readonly can = inject(ADMIN_CAN);

  protected readonly kindLabel = DELEGATION_KIND_LABEL;
  protected readonly statusLabel = DELEGATION_STATUS_LABEL;
  protected readonly date = fmtDate;
  protected readonly tone = statusTone;
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected readonly selected = signal<string | null>(null);
  protected readonly createOpen = signal(false);
  protected readonly reasonOpen = signal(false);
  protected readonly target = signal<Delegation | null>(null);
  protected readonly reasonError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly notices = signal<{ kind: 'success' | 'error'; text: string }[]>([]);

  protected readonly unit = rxResource({
    params: () => this.selected() ?? undefined,
    stream: ({ params }) => this.units.unit(params),
  });
  protected readonly unitName = computed(() =>
    this.unit.hasValue() ? (this.unit.value().shortName ?? this.unit.value().name) : '',
  );
  protected readonly page = rxResource({
    params: () => this.selected() ?? undefined,
    stream: ({ params }) => this.api.delegations({ orgUnitId: params }),
  });
  protected readonly items = computed(() => (this.page.hasValue() ? this.page.value().items : []));
  protected readonly reasonLead = computed(() => {
    const d = this.target();
    return d
      ? `Thu hồi uỷ quyền ${DELEGATION_KIND_LABEL[d.kind].toLowerCase()} từ đồng chí ${d.delegator.name} cho đồng chí ${d.delegate.name}. Bản ghi không bị xoá.`
      : '';
  });

  protected canRevoke(d: Delegation) {
    return (
      this.can('SCR-0107.thu-hoi') &&
      (d.status === 'HIEU_LUC' || d.status === 'SAP_HIEU_LUC') &&
      d.delegate.id !== this.me()
    );
  }
  protected err(e: unknown) {
    return accessError(e, 'Không tải được danh sách uỷ quyền.').text;
  }

  protected onCreated(d: Delegation) {
    this.notices.set([
      {
        kind: 'success',
        text: `Đã uỷ quyền ${DELEGATION_KIND_LABEL[d.kind].toLowerCase()} từ đồng chí ${d.delegator.name} cho đồng chí ${d.delegate.name} đến hết ${fmtDate(d.validTo)}.`,
      },
    ]);
    this.page.reload();
  }

  protected ask(d: Delegation) {
    this.target.set(d);
    this.reasonError.set(null);
    this.reasonOpen.set(true);
  }

  protected async revoke(reason: string) {
    const d = this.target();
    if (!d) return;
    if (!(await this.stepUp('Thu hồi uỷ quyền cần xác thực nhiều lớp.'))) return;
    this.busy.set(true);
    this.api.revokeDelegation(d, reason).subscribe({
      next: () => {
        this.busy.set(false);
        this.reasonOpen.set(false);
        this.notices.set([{ kind: 'success', text: 'Đã thu hồi uỷ quyền.' }]);
        this.page.reload();
      },
      error: (e: unknown) => {
        this.busy.set(false);
        this.reasonError.set(accessError(e, 'Thu hồi uỷ quyền không thành công.').text);
      },
    });
  }
}
