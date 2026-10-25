/*
 * SCR-0108 "Người dùng" (QT-01, BS-04, WEB-077). Tác nhân: AC-02 tạo, khoá, mở khoá tài khoản; AC-10
 * xem. Đường vào: trang quản trị "Người dùng" (`/admin/users`).
 * Vùng: 1 cây đơn vị (như SCR-0105); 2 tìm kiếm, thẻ lọc theo trạng thái, gồm cấp dưới; 3 bảng người
 *   dùng (họ tên, tên đăng nhập, đơn vị và chức danh, kiêm nhiệm, trạng thái, thao tác).
 * Chức năng: F1 xem (GET /org/users); F2 thêm (POST /org/users, step-up, mật khẩu tạm hiện một lần);
 *   F3 khoá, mở khoá (POST .../lock, .../unlock, If-Match, lý do bắt buộc, không tự khoá).
 * Quyền (wiki/15a): SCR-0108.xem, SCR-0108.them, SCR-0108.khoa; liên kết SCR-0109.xem, SCR-0110.xem.
 */
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { EXTERNAL_SYNC_FLAG, FeatureFlagService } from '@vt/api-client';
import {
  ASSIGNMENT_KIND_LABEL,
  OrgTreeApi,
  USER_ACCOUNT_STATUS_LABEL,
  UserAccount,
  UserAccountCreated,
  UserAccountStatus,
  UserAdminApi,
} from '@vt/api-client/domains';
import { permittedOn } from '@vt/auth';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { debounceTime } from 'rxjs';
import { ADMIN_CAN } from '../../admin-permissions';
import { fmtDate } from '../../admin-ui';
import { ACCESS_STEP_UP, ACCESS_STYLES } from '../access/access-ui';
import { ReasonDialog } from '../access/reason.dialog';
import { OrgTreeView } from '../org/org-tree-view';
import { PasswordDialog } from './password.dialog';
import { UserCreateDialog } from './user-create.dialog';
import { PAGE_STYLES, adminError } from './users-ui';

type Quick = 'all' | UserAccountStatus;

@Component({
  selector: 'vt-users-page',
  imports: [
    VtSkeletonComponent,
    FormsModule,
    RouterLink,
    ButtonModule,
    CheckboxModule,
    InputTextModule,
    MessageModule,
    OrgTreeView,
    PasswordDialog,
    ReasonDialog,
    UserCreateDialog,
  ],
  template: `
    <header class="head">
      <div>
        <h1>Người dùng</h1>
        <p class="muted">
          Tạo tài khoản, giao đơn vị công tác, khoá và mở khoá tài khoản cán bộ của đơn vị.
        </p>
      </div>
      <div class="tools">
        @if (can('SCR-0110.xem')) {
          <a routerLink="../roles" class="link">Vai trò và quyền</a>
        }
        @if (can('SCR-0105.xem')) {
          <a routerLink="../access" class="link">Vai trò theo đơn vị</a>
        }
        @if (can('SCR-0108.them')) {
          <p-button
            label="Thêm người dùng"
            icon="pi pi-user-plus"
            [disabled]="!selected()"
            (onClick)="createOpen.set(true)"
          />
        }
      </div>
    </header>
    @if (syncOn()) {
      <p-message
        severity="info"
        styleClass="msg"
        text="Đơn vị đang đồng bộ nhân sự từ hệ thống ngoài; thông tin đồng bộ về có thể ghi đè chỉnh sửa tại đây."
      />
    }
    @if (can('SCR-0108.them') && !selected()) {
      <p class="muted small">Chọn đơn vị trên cây để thêm người dùng vào đơn vị đó.</p>
    }

    @for (n of notices(); track n.text) {
      <p-message [severity]="n.kind" [text]="n.text" styleClass="msg" />
    }

    <div class="cols">
      <section class="box" aria-labelledby="us-z1">
        <h2 id="us-z1" class="h">Đơn vị</h2>
        <vt-org-tree-view [time]="{}" [simple]="simple()" [(selected)]="selected" />
      </section>

      <section class="box" aria-labelledby="us-z3" aria-live="polite">
        <h2 id="us-z3" class="h">
          {{ selected() ? 'Người dùng tại ' + unitName() : 'Tất cả người dùng' }}
        </h2>
        <div class="filters" role="group" aria-label="Lọc người dùng">
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
          <label for="us-q" class="sr-only">Tìm theo họ tên hoặc tên đăng nhập</label>
          <input
            id="us-q"
            pInputText
            type="search"
            placeholder="Tìm theo họ tên, tên đăng nhập"
            [ngModel]="search()"
            (ngModelChange)="search.set($event)"
          />
          @if (selected()) {
            <span class="inline">
              <p-checkbox
                inputId="us-sub"
                [binary]="true"
                [ngModel]="subunits()"
                (ngModelChange)="subunits.set($event)"
              />
              <label for="us-sub">Gồm đơn vị cấp dưới</label>
            </span>
          }
        </div>
        @if (page.error(); as e) {
          <p class="err" role="alert">{{ err(e) }}</p>
        }
        <div class="scroll">
          <table class="tbl">
            <caption class="sr-only">
              Danh sách người dùng
            </caption>
            <thead>
              <tr>
                <th scope="col">Họ và tên</th>
                <th scope="col">Đơn vị, chức danh</th>
                @if (!simple()) {
                  <th scope="col">Kiêm nhiệm</th>
                }
                <th scope="col">Trạng thái</th>
                <th scope="col"><span class="sr-only">Thao tác</span></th>
              </tr>
            </thead>
            <tbody>
              @for (u of rows(); track u.id) {
                <tr>
                  <td>
                    @if (can('SCR-0109.xem')) {
                      <a [routerLink]="['../users', u.id]">{{ u.fullName }}</a>
                    } @else {
                      {{ u.fullName }}
                    }
                    <span class="muted small block">{{ u.username }}</span>
                  </td>
                  <td>
                    {{ u.primaryUnitName || 'Chưa có đơn vị' }}
                    @if (u.jobTitleName) {
                      <span class="muted small block">{{ u.jobTitleName }}</span>
                    }
                  </td>
                  @if (!simple()) {
                    <td>
                      {{
                        u.concurrentCount
                          ? u.concurrentCount + ' ' + kindLabel['KIEM_NHIEM'].toLowerCase()
                          : ''
                      }}
                    </td>
                  }
                  <td>
                    <span
                      class="pill"
                      [class]="'pill ' + (u.status === 'HOAT_DONG' ? 'ok' : 'off')"
                      >{{ statusLabel[u.status] }}</span
                    >
                    @if (u.status === 'KHOA' && u.lockedAt) {
                      <span class="muted small block">Từ {{ date(u.lockedAt) }}</span>
                    }
                  </td>
                  <td>
                    <div class="acts">
                      @if (can('SCR-0108.khoa') && allowed(u)) {
                        @if (u.status === 'HOAT_DONG') {
                          <p-button
                            label="Khoá"
                            severity="danger"
                            [text]="true"
                            size="small"
                            [ariaLabel]="'Khoá tài khoản của ' + u.fullName"
                            [loading]="busy() === u.id"
                            (onClick)="ask(u)"
                          />
                        } @else {
                          <p-button
                            label="Mở khoá"
                            severity="secondary"
                            [text]="true"
                            size="small"
                            [ariaLabel]="'Mở khoá tài khoản của ' + u.fullName"
                            [loading]="busy() === u.id"
                            (onClick)="ask(u)"
                          />
                        }
                      }
                    </div>
                  </td>
                </tr>
              } @empty {
                <tr>
                  @if (page.isLoading()) {
                    <td [attr.colspan]="simple() ? 4 : 5">
                      <vt-skeleton
                        variant="table"
                        [header]="false"
                        [columns]="5"
                        label="Đang tải danh sách người dùng"
                      />
                    </td>
                  } @else {
                    <td [attr.colspan]="simple() ? 4 : 5" class="muted">
                      Không có người dùng phù hợp.
                    </td>
                  }
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>
    </div>

    @if (selected(); as unitId) {
      <vt-user-create-dialog
        [unitId]="unitId"
        [unitName]="unitName()"
        [(visible)]="createOpen"
        (created)="onCreated($event)"
      />
    }
    <vt-password-dialog
      [(visible)]="passwordOpen"
      [username]="password()?.username ?? ''"
      [password]="password()?.value ?? ''"
    />
    <vt-reason-dialog
      [(visible)]="reasonOpen"
      [header]="pending()?.status === 'HOAT_DONG' ? 'Khoá tài khoản' : 'Mở khoá tài khoản'"
      [lead]="reasonLead()"
      [confirmLabel]="pending()?.status === 'HOAT_DONG' ? 'Khoá' : 'Mở khoá'"
      [busy]="!!busy()"
      [error]="reasonError()"
      (confirmed)="toggleLock($event)"
    />
  `,
  styles: [ACCESS_STYLES, PAGE_STYLES],
})
export class UsersPage {
  private readonly api = inject(UserAdminApi);
  private readonly units = inject(OrgTreeApi);
  private readonly theme = inject(ThemeService);
  private readonly flags = inject(FeatureFlagService);
  private readonly stepUp = inject(ACCESS_STEP_UP);
  protected readonly can = inject(ADMIN_CAN);

  protected readonly statusLabel = USER_ACCOUNT_STATUS_LABEL;
  protected readonly kindLabel = ASSIGNMENT_KIND_LABEL;
  protected readonly date = fmtDate;
  protected readonly syncOn = computed(() => this.flags.isEnabled(EXTERNAL_SYNC_FLAG));

  /** Chế độ Đảng viên cao tuổi rút gọn: bỏ cột kiêm nhiệm (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected readonly selected = signal<string | null>(null);
  protected readonly quick = signal<Quick>('all');
  protected readonly search = signal('');
  protected readonly subunits = signal(true);
  protected readonly createOpen = signal(false);
  protected readonly passwordOpen = signal(false);
  protected readonly password = signal<{ username: string; value: string } | null>(null);
  protected readonly reasonOpen = signal(false);
  protected readonly pending = signal<UserAccount | null>(null);
  protected readonly reasonError = signal<string | null>(null);
  protected readonly busy = signal<string | null>(null);
  protected readonly notices = signal<{ kind: 'success' | 'warn' | 'error'; text: string }[]>([]);
  private readonly q = toSignal(toObservable(this.search).pipe(debounceTime(300)), {
    initialValue: '',
  });
  private readonly reload = signal(0);

  private readonly unit = rxResource({
    params: () => this.selected() ?? undefined,
    stream: ({ params }) => this.units.unit(params),
  });
  protected readonly unitName = computed(() =>
    this.unit.hasValue() ? (this.unit.value().shortName ?? this.unit.value().name) : '',
  );

  protected readonly page = rxResource({
    params: () => ({
      unit: this.selected() ?? undefined,
      includeSubunits: this.selected() ? this.subunits() : undefined,
      q: this.q().trim() || undefined,
      n: this.reload(),
    }),
    stream: ({ params: { n: _n, ...query } }) => this.api.list(query),
  });
  private readonly all = computed(() => (this.page.hasValue() ? this.page.value().items : []));
  protected readonly quicks = computed(() => {
    const c = this.page.hasValue() ? this.page.value().counts : null;
    return [
      { key: 'all' as Quick, label: 'Tất cả', count: c?.total ?? 0 },
      { key: 'HOAT_DONG' as Quick, label: 'Đang hoạt động', count: c?.active ?? 0 },
      { key: 'KHOA' as Quick, label: 'Đã khoá', count: c?.locked ?? 0 },
    ];
  });
  protected readonly rows = computed(() => {
    const q = this.quick();
    return this.all().filter((u) => q === 'all' || u.status === q);
  });

  protected readonly reasonLead = computed(() => {
    const u = this.pending();
    if (!u) return '';
    return u.status === 'HOAT_DONG'
      ? `Khoá tài khoản của đồng chí ${u.fullName}. Người dùng bị đăng xuất khỏi mọi thiết bị và không đăng nhập được cho đến khi mở khoá.`
      : `Mở khoá tài khoản của đồng chí ${u.fullName}.`;
  });

  protected allowed(u: UserAccount) {
    return permittedOn(u, 'SCR-0108.khoa');
  }

  protected err(e: unknown) {
    return adminError(e, 'Không tải được danh sách người dùng.').text;
  }

  protected onCreated(u: UserAccountCreated) {
    this.password.set({ username: u.username, value: u.temporaryPassword });
    this.passwordOpen.set(true);
    this.notices.set([
      {
        kind: 'success',
        text: `Đã tạo tài khoản cho đồng chí ${u.fullName} tại ${u.primaryUnitName ?? this.unitName()}.`,
      },
    ]);
    this.reload.update((n) => n + 1);
  }

  protected ask(u: UserAccount) {
    this.pending.set(u);
    this.reasonError.set(null);
    this.reasonOpen.set(true);
  }

  protected async toggleLock(reason: string) {
    const u = this.pending();
    if (!u) return;
    const locking = u.status === 'HOAT_DONG';
    if (
      !(await this.stepUp(
        locking
          ? 'Khoá tài khoản cần xác thực nhiều lớp.'
          : 'Mở khoá tài khoản cần xác thực nhiều lớp.',
      ))
    )
      return;
    this.busy.set(u.id);
    (locking ? this.api.lock(u, reason) : this.api.unlock(u, reason)).subscribe({
      next: (r) => {
        this.busy.set(null);
        this.reasonOpen.set(false);
        this.notices.set([
          {
            kind: 'success',
            text: locking
              ? `Đã khoá tài khoản của đồng chí ${r.fullName}.`
              : `Đã mở khoá tài khoản của đồng chí ${r.fullName}.`,
          },
        ]);
        this.reload.update((n) => n + 1);
      },
      error: (e: unknown) => {
        this.busy.set(null);
        this.reasonError.set(adminError(e, 'Không thực hiện được.').text);
      },
    });
  }
}
