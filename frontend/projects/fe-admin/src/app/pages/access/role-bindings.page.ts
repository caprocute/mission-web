/*
 * SCR-0105 "Vai trò theo đơn vị" (QT-01, WEB-058; đề xuất docs/phan-quyen/de-xuat-quan-tri-vai-tro.md
 * đã duyệt 19/10/2026). Tác nhân: AC-02 cấp, thu hồi, duyệt thứ hai; AC-04 xem và duyệt thứ hai trong
 * cây đơn vị của mình; AC-10 xem. Đường vào: trang quản trị "Vai trò theo đơn vị" (`/admin/access`).
 * Vùng: 1 cây đơn vị (như SCR-0103); 2 thẻ lọc nhanh (đang hiệu lực, chờ duyệt, chờ tôi duyệt, sắp hết
 *   hạn) và lọc vai trò, gồm cấp dưới; 3 bảng vai trò (người, vai trò, phạm vi, luồng, hiệu lực, người cấp,
 *   người duyệt, trạng thái, thao tác).
 * Chức năng: F1 xem (GET /config/role-bindings); F3 cấp (POST, step-up, EVT-27); F4 duyệt, từ chối
 *   (POST .../approve, .../reject, If-Match, bốn mắt); F5 thu hồi (POST .../revoke, lý do bắt buộc).
 * Quy tắc: BR-01-13 không tự cấp, tự duyệt, tự thu hồi (MSG-01-16); BR-01-14 vai trò nhạy cảm chờ duyệt
 *   thứ hai (MSG-01-17); BR-01-15 step-up; BR-01-16 bắt buộc thời hạn.
 * Quyền (wiki/15a): SCR-0105.xem, SCR-0105.cap, SCR-0105.duyet, SCR-0105.thu-hoi; liên kết SCR-0106.xem.
 */
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  MSG_01_17,
  OrgTreeApi,
  ROLE_BINDING_STATUS_LABEL,
  ROLE_CODE_LABEL,
  ROLE_SCOPE_LABEL,
  RoleAdminApi,
  RoleBinding,
  RoleBindingStatus,
  RoleCode,
} from '@vt/api-client/domains';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { ADMIN_CAN } from '../../admin-permissions';
import { fmtDate } from '../../admin-ui';
import { OrgTreeView } from '../org/org-tree-view';
import { ACCESS_ME, ACCESS_STEP_UP, ACCESS_STYLES, accessError, statusTone } from './access-ui';
import { GrantDialog } from './grant.dialog';
import { ReasonDialog } from './reason.dialog';

type Quick = 'all' | 'pending' | 'mine' | 'soon';
type Pending = { kind: 'reject' | 'revoke'; binding: RoleBinding } | null;

const FLOW_LABEL: Record<string, string> = { DANG: 'Khối Đảng', NHA_NUOC: 'Khối Nhà nước' };

@Component({
  selector: 'vt-role-bindings-page',
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    CheckboxModule,
    MessageModule,
    SelectModule,
    OrgTreeView,
    GrantDialog,
    ReasonDialog,
  ],
  template: `
    <header class="head">
      <div>
        <h1>Vai trò theo đơn vị</h1>
        <p class="muted">
          Cấp, phê duyệt và thu hồi vai trò của cán bộ theo đơn vị, có thời hạn hiệu lực.
        </p>
      </div>
      <div class="tools">
        @if (can('SCR-0107.xem')) {
          <a routerLink="../access/delegations" class="link">Uỷ quyền khi vắng mặt</a>
        }
        @if (can('SCR-0105.cap')) {
          <p-button
            label="Cấp vai trò"
            icon="pi pi-plus"
            [disabled]="!selected()"
            (onClick)="grantOpen.set(true)"
          />
        }
      </div>
    </header>

    @for (n of notices(); track n.text) {
      <p-message [severity]="n.kind" [text]="n.text" styleClass="msg" />
    }

    <div class="cols">
      <section class="box" aria-labelledby="rb-z1">
        <h2 id="rb-z1" class="h">Đơn vị</h2>
        <vt-org-tree-view [time]="{}" [simple]="simple()" [(selected)]="selected" />
      </section>

      <section class="box" aria-labelledby="rb-z3" aria-live="polite">
        @if (!selected()) {
          <h2 id="rb-z3" class="h">Vai trò đã cấp</h2>
          <p class="muted">Chọn một đơn vị trên cây để xem vai trò đã cấp.</p>
        } @else {
          <h2 id="rb-z3" class="h">Vai trò tại {{ unitName() }}</h2>
          <div class="filters" role="group" aria-label="Lọc nhanh">
            @for (q of quicks(); track q.key) {
              <button
                type="button"
                class="chip"
                [attr.aria-pressed]="quick() === q.key"
                (click)="quick.set(q.key)"
              >
                {{ q.label }} <span class="n">{{ q.count }}</span>
              </button>
            }
            <span class="spacer"></span>
            <label for="rb-role" class="sr-only">Lọc vai trò</label>
            <p-select
              inputId="rb-role"
              [options]="roleOptions"
              optionLabel="label"
              optionValue="value"
              [ngModel]="role()"
              (ngModelChange)="role.set($event)"
            />
            <span class="inline">
              <p-checkbox
                inputId="rb-sub"
                [binary]="true"
                [ngModel]="subunits()"
                (ngModelChange)="subunits.set($event)"
              />
              <label for="rb-sub">Gồm đơn vị cấp dưới</label>
            </span>
          </div>
          @if (page.error(); as e) {
            <p class="err" role="alert">{{ err(e) }}</p>
          }
          <div class="scroll">
            <table class="tbl">
              <caption class="sr-only">
                Vai trò đã cấp tại đơn vị
              </caption>
              <thead>
                <tr>
                  <th scope="col">Cán bộ</th>
                  <th scope="col">Vai trò</th>
                  <th scope="col">Phạm vi</th>
                  <th scope="col">Hiệu lực</th>
                  @if (!simple()) {
                    <th scope="col">Người cấp, người duyệt</th>
                  }
                  <th scope="col">Trạng thái</th>
                  <th scope="col"><span class="sr-only">Thao tác</span></th>
                </tr>
              </thead>
              <tbody>
                @for (b of rows(); track b.id) {
                  <tr>
                    <td>
                      @if (can('SCR-0106.xem')) {
                        <a [routerLink]="['../access/people', b.person.id]">{{ b.person.name }}</a>
                      } @else {
                        {{ b.person.name }}
                      }
                      @if (b.personTitle) {
                        <span class="muted small block">{{ b.personTitle }}</span>
                      }
                    </td>
                    <td>
                      {{ roleLabel[b.roleCode] }}
                    </td>
                    <td>
                      {{ scopeLabel[b.scope] }}
                      <span class="muted small block"
                        >{{ b.orgUnit.name }}{{ b.flow ? ' · ' + flowLabel[b.flow] : '' }}</span
                      >
                    </td>
                    <td>{{ date(b.validFrom) }} → {{ date(b.validTo) }}</td>
                    @if (!simple()) {
                      <td>
                        {{ b.grantedBy.name }}
                        @if (b.approvedBy) {
                          <span class="muted small block">Duyệt: {{ b.approvedBy.name }}</span>
                        } @else if (b.sensitive && b.status === 'CHO_DUYET') {
                          <span class="muted small block">Chờ người duyệt thứ hai</span>
                        }
                      </td>
                    }
                    <td>
                      <span class="pill" [class]="'pill ' + tone(b.status)">{{
                        statusLabel[b.status]
                      }}</span>
                      @if (b.endReason) {
                        <span class="muted small block">{{ b.endReason }}</span>
                      }
                    </td>
                    <td>
                      <div class="acts">
                        @if (canDecide(b)) {
                          <p-button
                            label="Duyệt"
                            size="small"
                            [loading]="busy() === b.id"
                            (onClick)="approve(b)"
                          />
                          <p-button
                            label="Từ chối"
                            size="small"
                            severity="secondary"
                            [outlined]="true"
                            (onClick)="ask('reject', b)"
                          />
                        } @else if (b.status === 'CHO_DUYET' && isMine(b)) {
                          <span class="muted small">Chờ người khác duyệt</span>
                        }
                        @if (canRevoke(b)) {
                          <p-button
                            label="Thu hồi"
                            size="small"
                            severity="danger"
                            [text]="true"
                            (onClick)="ask('revoke', b)"
                          />
                        }
                      </div>
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td [attr.colspan]="simple() ? 6 : 7" class="muted">
                      {{ page.isLoading() ? 'Đang tải…' : 'Không có vai trò phù hợp bộ lọc.' }}
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      </section>
    </div>

    @if (selected(); as unitId) {
      <vt-grant-dialog
        [unitId]="unitId"
        [unitName]="unitName()"
        [(visible)]="grantOpen"
        (granted)="onGranted($event)"
      />
    }
    <vt-reason-dialog
      [(visible)]="reasonOpen"
      [header]="pending()?.kind === 'reject' ? 'Từ chối đề nghị cấp vai trò' : 'Thu hồi vai trò'"
      [lead]="reasonLead()"
      [confirmLabel]="pending()?.kind === 'reject' ? 'Từ chối' : 'Thu hồi'"
      [busy]="!!busy()"
      [error]="reasonError()"
      (confirmed)="end($event)"
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
      .tools {
        display: flex;
        gap: 12px;
        align-items: center;
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
      .filters {
        display: flex;
        flex-wrap: wrap;
        gap: 8px;
        align-items: center;
        margin-bottom: 12px;
      }
      .chip {
        border: 1px solid #cbd5e1;
        background: #fff;
        border-radius: 999px;
        padding: 4px 12px;
        font: inherit;
        cursor: pointer;
      }
      .chip[aria-pressed='true'] {
        background: #b8002a;
        border-color: #b8002a;
        color: #fff;
      }
      .chip .n {
        font-weight: 600;
        margin-left: 4px;
      }
      .spacer {
        flex: 1;
      }
      .inline {
        display: inline-flex;
        gap: 6px;
        align-items: center;
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
export class RoleBindingsPage {
  private readonly api = inject(RoleAdminApi);
  private readonly units = inject(OrgTreeApi);
  private readonly theme = inject(ThemeService);
  private readonly stepUp = inject(ACCESS_STEP_UP);
  private readonly me = inject(ACCESS_ME);
  protected readonly can = inject(ADMIN_CAN);

  protected readonly roleLabel = ROLE_CODE_LABEL;
  protected readonly scopeLabel = ROLE_SCOPE_LABEL;
  protected readonly statusLabel = ROLE_BINDING_STATUS_LABEL;
  protected readonly flowLabel = FLOW_LABEL;
  protected readonly date = fmtDate;
  protected readonly tone = statusTone;
  protected readonly roleOptions = [
    { value: null, label: 'Mọi vai trò' },
    ...(Object.keys(ROLE_CODE_LABEL) as RoleCode[]).map((value) => ({
      value,
      label: ROLE_CODE_LABEL[value],
    })),
  ];

  /** Chế độ Đảng viên cao tuổi rút gọn: bỏ cột người cấp, người duyệt (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected readonly selected = signal<string | null>(null);
  protected readonly quick = signal<Quick>('all');
  protected readonly role = signal<RoleCode | null>(null);
  protected readonly subunits = signal(false);
  protected readonly grantOpen = signal(false);
  protected readonly reasonOpen = signal(false);
  protected readonly pending = signal<Pending>(null);
  protected readonly reasonError = signal<string | null>(null);
  protected readonly busy = signal<string | null>(null);
  protected readonly notices = signal<{ kind: 'success' | 'warn' | 'error'; text: string }[]>([]);

  private readonly unit = rxResource({
    params: () => this.selected() ?? undefined,
    stream: ({ params }) => this.units.unit(params),
  });
  protected readonly unitName = computed(() =>
    this.unit.hasValue() ? (this.unit.value().shortName ?? this.unit.value().name) : '',
  );

  protected readonly page = rxResource({
    params: () => {
      const id = this.selected();
      return id
        ? { orgUnitId: id, includeSubunits: this.subunits(), role: this.role() ?? undefined }
        : undefined;
    },
    stream: ({ params }) => this.api.bindings(params),
  });
  private readonly all = computed(() => (this.page.hasValue() ? this.page.value().items : []));
  protected readonly quicks = computed(() => {
    const c = this.page.hasValue() ? this.page.value().counts : null;
    const list: { key: Quick; label: string; count: number }[] = [
      { key: 'all', label: 'Tất cả', count: this.all().length },
      { key: 'pending', label: 'Chờ duyệt', count: c?.pending ?? 0 },
      { key: 'soon', label: 'Sắp hết hạn', count: c?.expiringSoon ?? 0 },
    ];
    if (this.can('SCR-0105.duyet'))
      list.splice(2, 0, { key: 'mine', label: 'Chờ tôi duyệt', count: c?.awaitingMe ?? 0 });
    return list;
  });
  protected readonly rows = computed(() => {
    const soon = Date.now() + 30 * 86_400_000;
    const q = this.quick();
    return this.all().filter((b) => {
      if (q === 'pending') return b.status === 'CHO_DUYET';
      if (q === 'mine') return this.awaitingMe(b);
      if (q === 'soon') return b.status === 'HIEU_LUC' && Date.parse(b.validTo) <= soon;
      return true;
    });
  });
  protected readonly reasonLead = computed(() => {
    const p = this.pending();
    if (!p) return '';
    const b = p.binding;
    const what = `vai trò ${ROLE_CODE_LABEL[b.roleCode]} của đồng chí ${b.person.name} tại ${b.orgUnit.name}`;
    return p.kind === 'reject'
      ? `Từ chối đề nghị cấp ${what}. Bản ghi được giữ lại để tra cứu.`
      : `Thu hồi ${what} ngay bây giờ. Bản ghi không bị xoá.`;
  });

  protected isMine(b: RoleBinding) {
    return b.grantedBy.id === this.me();
  }
  private awaitingMe(b: RoleBinding) {
    const me = this.me();
    return b.status === 'CHO_DUYET' && b.grantedBy.id !== me && b.person.id !== me;
  }
  protected canDecide(b: RoleBinding) {
    return this.can('SCR-0105.duyet') && this.awaitingMe(b);
  }
  protected canRevoke(b: RoleBinding) {
    const live: RoleBindingStatus[] = ['HIEU_LUC', 'SAP_HIEU_LUC'];
    return this.can('SCR-0105.thu-hoi') && live.includes(b.status) && b.person.id !== this.me();
  }
  protected err(e: unknown) {
    return accessError(e, 'Không tải được danh sách vai trò.').text;
  }

  protected onGranted(b: RoleBinding) {
    this.notices.set([
      {
        kind: 'success',
        text: b.sensitive
          ? MSG_01_17(ROLE_CODE_LABEL[b.roleCode], b.person.name)
          : `Đã cấp vai trò ${ROLE_CODE_LABEL[b.roleCode]} cho đồng chí ${b.person.name}, hiệu lực từ ${fmtDate(b.validFrom)}.`,
      },
    ]);
    this.page.reload();
  }

  protected async approve(b: RoleBinding) {
    if (!(await this.stepUp('Duyệt cấp vai trò cần xác thực nhiều lớp.'))) return;
    this.busy.set(b.id);
    this.api.approve(b).subscribe({
      next: (r) => {
        this.busy.set(null);
        this.notices.set([
          {
            kind: 'success',
            text: `Đã duyệt vai trò ${ROLE_CODE_LABEL[r.roleCode]} của đồng chí ${r.person.name}; vai trò có hiệu lực từ ${fmtDate(r.validFrom)}.`,
          },
        ]);
        this.page.reload();
      },
      error: (e: unknown) => {
        this.busy.set(null);
        this.notices.set([{ kind: 'error', text: accessError(e, 'Không duyệt được.').text }]);
        this.page.reload();
      },
    });
  }

  protected ask(kind: 'reject' | 'revoke', binding: RoleBinding) {
    this.pending.set({ kind, binding });
    this.reasonError.set(null);
    this.reasonOpen.set(true);
  }

  protected async end(reason: string) {
    const p = this.pending();
    if (!p) return;
    const action = p.kind === 'reject' ? 'Từ chối cấp vai trò' : 'Thu hồi vai trò';
    if (!(await this.stepUp(`${action} cần xác thực nhiều lớp.`))) return;
    this.busy.set(p.binding.id);
    const call =
      p.kind === 'reject' ? this.api.reject(p.binding, reason) : this.api.revoke(p.binding, reason);
    call.subscribe({
      next: (r) => {
        this.busy.set(null);
        this.reasonOpen.set(false);
        this.notices.set([
          {
            kind: 'success',
            text: `${p.kind === 'reject' ? 'Đã từ chối đề nghị cấp' : 'Đã thu hồi'} vai trò ${ROLE_CODE_LABEL[r.roleCode]} của đồng chí ${r.person.name}.`,
          },
        ]);
        this.page.reload();
      },
      error: (e: unknown) => {
        this.busy.set(null);
        this.reasonError.set(accessError(e, `${action} không thành công.`).text);
      },
    });
  }
}
