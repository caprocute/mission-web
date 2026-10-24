/*
 * SCR-0110 "Vai trò và quyền" (QT-01, BS-04, WEB-078). Tác nhân: AC-02 tạo, sửa, ngừng vai trò tuỳ
 * chỉnh; AC-10 xem. Đường vào: trang quản trị "Vai trò và quyền" (`/admin/roles`).
 * Vùng: 1 danh sách vai trò tuỳ chỉnh (tên, dựa trên vai trò, số chức năng, số người đang giữ, trạng
 *   thái, thao tác); 2 hộp tạo, sửa với bảng chọn chức năng theo nhóm màn hình.
 * Chức năng: F1 xem (GET /config/custom-roles, /config/permission-catalog); F2 tạo (POST, step-up);
 *   F3 sửa (PUT, If-Match); F4 ngừng sử dụng (POST .../retire, lý do, thu hồi mọi bản ghi cấp đang mở).
 * Quy tắc: BR-01-23 chỉ thu hẹp vai trò gốc; BR-01-24 ngừng thì thu hồi.
 * Quyền (wiki/15a): SCR-0110.xem, SCR-0110.sua; liên kết SCR-0105.xem.
 */
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  CUSTOM_ROLE_STATUS_LABEL,
  CustomRole,
  CustomRoleApi,
  ROLE_CODE_LABEL,
  RoleCode,
} from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { ADMIN_CAN } from '../../admin-permissions';
import { fmtDate } from '../../admin-ui';
import { PAGE_STYLES, adminError } from '../users/users-ui';
import { ACCESS_STEP_UP, ACCESS_STYLES } from './access-ui';
import { CustomRoleDialog } from './custom-role.dialog';
import { ReasonDialog } from './reason.dialog';

@Component({
  selector: 'vt-custom-roles-page',
  imports: [RouterLink, ButtonModule, MessageModule, CustomRoleDialog, ReasonDialog],
  template: `
    <header class="head">
      <div>
        <h1>Vai trò và quyền</h1>
        <p class="muted">
          Tạo vai trò riêng của đơn vị bằng cách chọn bớt chức năng và thu hẹp phạm vi của một vai
          trò có sẵn. Sau khi tạo, cấp vai trò cho cán bộ tại màn hình Vai trò theo đơn vị.
        </p>
      </div>
      <div class="tools">
        @if (can('SCR-0105.xem')) {
          <a routerLink="../access" class="link">Vai trò theo đơn vị</a>
        }
        @if (can('SCR-0108.xem')) {
          <a routerLink="../users" class="link">Người dùng</a>
        }
        @if (can('SCR-0110.sua')) {
          <p-button
            label="Tạo vai trò"
            icon="pi pi-plus"
            [disabled]="!catalog.hasValue()"
            (onClick)="open(null)"
          />
        }
      </div>
    </header>

    @for (n of notices(); track n.text) {
      <p-message [severity]="n.kind" [text]="n.text" styleClass="msg" />
    }

    <section class="box" aria-labelledby="cr-z1" aria-live="polite">
      <h2 id="cr-z1" class="h">Vai trò tuỳ chỉnh</h2>
      @if (list.error(); as e) {
        <p class="err" role="alert">{{ err(e) }}</p>
      }
      <div class="scroll">
        <table class="tbl">
          <caption class="sr-only">
            Danh sách vai trò tuỳ chỉnh
          </caption>
          <thead>
            <tr>
              <th scope="col">Tên vai trò</th>
              <th scope="col">Dựa trên</th>
              <th scope="col">Số chức năng</th>
              <th scope="col">Đang giữ</th>
              <th scope="col">Trạng thái</th>
              <th scope="col"><span class="sr-only">Thao tác</span></th>
            </tr>
          </thead>
          <tbody>
            @for (r of roles(); track r.id) {
              <tr>
                <td>
                  {{ r.name }}
                  @if (r.description) {
                    <span class="muted small block">{{ r.description }}</span>
                  }
                </td>
                <td>{{ baseLabel(r.baseRole) }}</td>
                <td>{{ r.permissions.length }}</td>
                <td>{{ r.holderCount }} người</td>
                <td>
                  <span class="pill" [class]="'pill ' + (r.status === 'HIEU_LUC' ? 'ok' : 'off')">{{
                    statusLabel[r.status]
                  }}</span>
                  @if (r.updatedAt) {
                    <span class="muted small block">Cập nhật {{ date(r.updatedAt) }}</span>
                  }
                </td>
                <td>
                  @if (can('SCR-0110.sua') && r.status === 'HIEU_LUC') {
                    <div class="acts">
                      <p-button
                        label="Sửa"
                        severity="secondary"
                        [text]="true"
                        size="small"
                        [ariaLabel]="'Sửa vai trò ' + r.name"
                        [disabled]="!catalog.hasValue()"
                        (onClick)="open(r)"
                      />
                      <p-button
                        label="Ngừng sử dụng"
                        severity="danger"
                        [text]="true"
                        size="small"
                        [ariaLabel]="'Ngừng sử dụng vai trò ' + r.name"
                        (onClick)="askRetire(r)"
                      />
                    </div>
                  }
                </td>
              </tr>
            } @empty {
              <tr>
                <td colspan="6" class="muted">
                  {{ list.isLoading() ? 'Đang tải…' : 'Chưa có vai trò tuỳ chỉnh nào.' }}
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    </section>

    @if (catalog.hasValue()) {
      <vt-custom-role-dialog
        [catalog]="catalog.value()"
        [role]="editing()"
        [(visible)]="dialogOpen"
        (saved)="onSaved($event)"
      />
    }
    <vt-reason-dialog
      [(visible)]="reasonOpen"
      header="Ngừng sử dụng vai trò"
      [lead]="retireLead()"
      confirmLabel="Ngừng sử dụng"
      [busy]="busy()"
      [error]="reasonError()"
      (confirmed)="retire($event)"
    />
  `,
  styles: [ACCESS_STYLES, PAGE_STYLES],
})
export class CustomRolesPage {
  private readonly api = inject(CustomRoleApi);
  private readonly stepUp = inject(ACCESS_STEP_UP);
  protected readonly can = inject(ADMIN_CAN);

  protected readonly statusLabel = CUSTOM_ROLE_STATUS_LABEL;
  protected readonly date = fmtDate;

  private readonly reload = signal(0);
  protected readonly list = rxResource({
    params: () => ({ n: this.reload() }),
    stream: () => this.api.list(),
  });
  protected readonly catalog = rxResource({ stream: () => this.api.catalog() });
  protected readonly roles = computed(() =>
    (this.list.hasValue() ? this.list.value() : [])
      .slice()
      .sort((a, b) =>
        a.status === b.status
          ? a.name.localeCompare(b.name, 'vi')
          : a.status === 'HIEU_LUC'
            ? -1
            : 1,
      ),
  );

  protected readonly dialogOpen = signal(false);
  protected readonly editing = signal<CustomRole | null>(null);
  protected readonly reasonOpen = signal(false);
  protected readonly retiring = signal<CustomRole | null>(null);
  protected readonly reasonError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly notices = signal<{ kind: 'success' | 'warn' | 'error'; text: string }[]>([]);

  protected readonly retireLead = computed(() => {
    const r = this.retiring();
    if (!r) return '';
    return r.holderCount
      ? `Ngừng sử dụng vai trò "${r.name}". ${r.holderCount} người đang giữ vai trò này sẽ bị thu hồi ngay.`
      : `Ngừng sử dụng vai trò "${r.name}".`;
  });

  protected baseLabel(code: string) {
    return ROLE_CODE_LABEL[code as RoleCode] ?? code;
  }

  protected err(e: unknown) {
    return adminError(e, 'Không tải được danh sách vai trò.').text;
  }

  protected open(r: CustomRole | null) {
    this.editing.set(r);
    this.dialogOpen.set(true);
  }

  protected onSaved(r: CustomRole) {
    this.notices.set([
      {
        kind: 'success',
        text: this.editing()
          ? `Đã lưu vai trò "${r.name}". Thay đổi có hiệu lực với người đang giữ vai trò trong khoảng một phút.`
          : `Đã tạo vai trò "${r.name}". Có thể cấp vai trò này cho cán bộ tại màn hình Vai trò theo đơn vị.`,
      },
    ]);
    this.reload.update((n) => n + 1);
  }

  protected askRetire(r: CustomRole) {
    this.retiring.set(r);
    this.reasonError.set(null);
    this.reasonOpen.set(true);
  }

  protected async retire(reason: string) {
    const r = this.retiring();
    if (!r) return;
    if (!(await this.stepUp('Ngừng sử dụng vai trò cần xác thực nhiều lớp.'))) return;
    this.busy.set(true);
    this.api.retire(r, reason).subscribe({
      next: () => {
        this.busy.set(false);
        this.reasonOpen.set(false);
        this.notices.set([{ kind: 'success', text: `Đã ngừng sử dụng vai trò "${r.name}".` }]);
        this.reload.update((n) => n + 1);
      },
      error: (e: unknown) => {
        this.busy.set(false);
        this.reasonError.set(adminError(e, 'Không ngừng được vai trò.').text);
      },
    });
  }
}
