/*
 * SCR-0109 "Chi tiết người dùng" (QT-01, BS-04, WEB-078). Tác nhân: AC-02 sửa, giao kiêm nhiệm, đặt lại
 * mật khẩu, khoá; AC-10 xem. Đường vào: bấm tên trên SCR-0108 (`/admin/users/:id`).
 * Vùng: 1 thông tin tài khoản (họ tên, thư điện tử, trạng thái); 2 đơn vị công tác (phân công chính,
 *   kiêm nhiệm); 3 bảo mật (đặt lại mật khẩu, khoá, mở khoá); liên kết hồ sơ quyền SCR-0106.
 * Chức năng: F1 xem (GET /org/users/{id}, /org/persons/{id}/assignments); F2 sửa (PATCH, If-Match);
 *   F3 giao kiêm nhiệm (POST /org/assignments), kết thúc (POST .../end, If-Match, lý do); F4 đặt lại mật
 *   khẩu (POST .../reset-password, step-up, mật khẩu tạm hiện một lần); F5 khoá, mở khoá.
 * Quyền (wiki/15a): SCR-0109.xem, SCR-0109.sua, SCR-0109.phan-cong, SCR-0109.dat-lai-mat-khau,
 *   SCR-0108.khoa; liên kết SCR-0106.xem.
 */
import { Component, computed, effect, inject, signal, untracked } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  ASSIGNMENT_KIND_LABEL,
  PersonAssignment,
  USER_ACCOUNT_STATUS_LABEL,
  UserAccount,
  UserAdminApi,
} from '@vt/api-client/domains';
import { permittedOn } from '@vt/auth';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { map } from 'rxjs';
import { ADMIN_CAN } from '../../admin-permissions';
import { fmtDate, fmtDateTime } from '../../admin-ui';
import { ACCESS_STEP_UP, ACCESS_STYLES, isoDay } from '../access/access-ui';
import { ReasonDialog } from '../access/reason.dialog';
import { AssignmentDialog } from './assignment.dialog';
import { PasswordDialog } from './password.dialog';
import { PAGE_STYLES, adminError } from './users-ui';

type Notice = { kind: 'success' | 'warn' | 'error'; text: string };
type Pending =
  { kind: 'lock' } | { kind: 'unlock' } | { kind: 'end'; assignment: PersonAssignment } | null;

@Component({
  selector: 'vt-user-detail-page',
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    InputTextModule,
    MessageModule,
    AssignmentDialog,
    PasswordDialog,
    ReasonDialog,
  ],
  template: `
    <nav class="crumb" aria-label="Đường dẫn">
      <a routerLink="../../users">Người dùng</a>
    </nav>
    @if (account.error(); as e) {
      <p class="err" role="alert">{{ err(e) }}</p>
    }
    @if (user(); as u) {
      <header class="head">
        <div>
          <h1>{{ u.fullName }}</h1>
          <p class="muted">
            {{ u.username }} ·
            <span class="pill" [class]="'pill ' + (u.status === 'HOAT_DONG' ? 'ok' : 'off')">{{
              statusLabel[u.status]
            }}</span>
          </p>
        </div>
        <div class="tools">
          @if (can('SCR-0106.xem')) {
            <a [routerLink]="['../../access/people', u.id]" class="link">Vai trò và hồ sơ quyền</a>
          }
        </div>
      </header>

      @for (n of notices(); track n.text) {
        <p-message [severity]="n.kind" [text]="n.text" styleClass="msg" />
      }

      <section class="box" aria-labelledby="ud-z1">
        <h2 id="ud-z1" class="h">Thông tin tài khoản</h2>
        <div class="grid">
          <div>
            <label class="f" for="ud-name">Họ và tên</label>
            <input
              id="ud-name"
              pInputText
              class="full"
              maxlength="200"
              [readonly]="!canEdit()"
              [ngModel]="name()"
              (ngModelChange)="name.set($event)"
            />
            @if (fe()['fullName']) {
              <small class="err block">{{ fe()['fullName'] }}</small>
            }
          </div>
          <div>
            <label class="f" for="ud-mail">Thư điện tử</label>
            <input
              id="ud-mail"
              pInputText
              type="email"
              class="full"
              maxlength="200"
              [readonly]="!canEdit()"
              [ngModel]="email()"
              (ngModelChange)="email.set($event)"
            />
            @if (fe()['email']) {
              <small class="err block">{{ fe()['email'] }}</small>
            }
          </div>
        </div>
        <p class="muted small">
          @if (u.createdAt) {
            Tạo ngày {{ date(u.createdAt) }}.
          }
          @if (u.passwordResetAt) {
            Đặt lại mật khẩu lần gần nhất lúc {{ dateTime(u.passwordResetAt) }}.
          }
          @if (u.status === 'KHOA' && u.lockReason) {
            Lý do khoá: {{ u.lockReason }}.
          }
        </p>
        @if (canEdit()) {
          <p-button
            label="Lưu thay đổi"
            icon="pi pi-check"
            [disabled]="!dirty()"
            [loading]="busy() === 'save'"
            (onClick)="save(u)"
          />
        }
      </section>

      <section class="box" aria-labelledby="ud-z2">
        <div class="row">
          <h2 id="ud-z2" class="h">Đơn vị công tác</h2>
          @if (can('SCR-0109.phan-cong') && allowed(u, 'SCR-0109.phan-cong')) {
            <p-button
              label="Thêm kiêm nhiệm"
              icon="pi pi-plus"
              severity="secondary"
              [outlined]="true"
              (onClick)="assignOpen.set(true)"
            />
          }
        </div>
        <div class="scroll">
          <table class="tbl">
            <caption class="sr-only">
              Đơn vị công tác đang hiệu lực
            </caption>
            <thead>
              <tr>
                <th scope="col">Đơn vị</th>
                <th scope="col">Chức danh</th>
                <th scope="col">Hình thức</th>
                <th scope="col">Hiệu lực</th>
                <th scope="col"><span class="sr-only">Thao tác</span></th>
              </tr>
            </thead>
            <tbody>
              @for (a of assignments(); track a.id) {
                <tr>
                  <td>{{ a.orgUnitName }}</td>
                  <td>{{ a.jobTitleName }}</td>
                  <td>{{ kindLabel[a.kind] }}</td>
                  <td>{{ date(a.validFrom) }}{{ a.validTo ? ' → ' + date(a.validTo) : '' }}</td>
                  <td>
                    @if (
                      a.kind !== 'CHINH' &&
                      can('SCR-0109.phan-cong') &&
                      allowed(u, 'SCR-0109.phan-cong')
                    ) {
                      <p-button
                        label="Kết thúc"
                        severity="danger"
                        [text]="true"
                        size="small"
                        [ariaLabel]="
                          'Kết thúc ' + kindLabel[a.kind].toLowerCase() + ' tại ' + a.orgUnitName
                        "
                        (onClick)="ask({ kind: 'end', assignment: a })"
                      />
                    }
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="5" class="muted">
                    {{ assignmentList.isLoading() ? 'Đang tải…' : 'Chưa có đơn vị công tác.' }}
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      </section>

      @if (can('SCR-0109.dat-lai-mat-khau') || can('SCR-0108.khoa')) {
        <section class="box" aria-labelledby="ud-z3">
          <h2 id="ud-z3" class="h">Bảo mật</h2>
          <div class="acts">
            @if (can('SCR-0109.dat-lai-mat-khau') && allowed(u, 'SCR-0109.dat-lai-mat-khau')) {
              <p-button
                label="Đặt lại mật khẩu"
                icon="pi pi-key"
                severity="secondary"
                [outlined]="true"
                [loading]="busy() === 'reset'"
                (onClick)="resetPassword(u)"
              />
            }
            @if (can('SCR-0108.khoa') && allowed(u, 'SCR-0108.khoa')) {
              @if (u.status === 'HOAT_DONG') {
                <p-button
                  label="Khoá tài khoản"
                  icon="pi pi-lock"
                  severity="danger"
                  [outlined]="true"
                  (onClick)="ask({ kind: 'lock' })"
                />
              } @else {
                <p-button
                  label="Mở khoá tài khoản"
                  icon="pi pi-lock-open"
                  severity="secondary"
                  [outlined]="true"
                  (onClick)="ask({ kind: 'unlock' })"
                />
              }
            }
          </div>
          <p class="muted small">
            Đặt lại mật khẩu hoặc khoá tài khoản sẽ đăng xuất người dùng khỏi mọi thiết bị.
          </p>
        </section>
      }

      <vt-assignment-dialog
        [personId]="u.id"
        [personName]="u.fullName"
        [(visible)]="assignOpen"
        (added)="onAssigned()"
      />
      <vt-password-dialog
        [(visible)]="passwordOpen"
        [username]="u.username"
        [password]="password()"
      />
    } @else if (account.isLoading()) {
      <p class="muted">Đang tải thông tin người dùng…</p>
    }
    <vt-reason-dialog
      [(visible)]="reasonOpen"
      [header]="reasonHeader()"
      [lead]="reasonLead()"
      [confirmLabel]="reasonConfirm()"
      [busy]="busy() === 'reason'"
      [error]="reasonError()"
      (confirmed)="confirm($event)"
    />
  `,
  styles: [
    ACCESS_STYLES,
    PAGE_STYLES,
    `
      .crumb {
        margin-bottom: 8px;
      }
      .row {
        display: flex;
        justify-content: space-between;
        align-items: center;
        gap: 12px;
        flex-wrap: wrap;
        margin-bottom: 8px;
      }
      .row .h {
        margin: 0;
      }
    `,
  ],
})
export class UserDetailPage {
  private readonly api = inject(UserAdminApi);
  private readonly stepUp = inject(ACCESS_STEP_UP);
  protected readonly can = inject(ADMIN_CAN);
  private readonly id = toSignal(inject(ActivatedRoute).paramMap.pipe(map((p) => p.get('id'))), {
    initialValue: null,
  });

  protected readonly statusLabel = USER_ACCOUNT_STATUS_LABEL;
  protected readonly kindLabel = ASSIGNMENT_KIND_LABEL;
  protected readonly date = fmtDate;
  protected readonly dateTime = fmtDateTime;

  private readonly reload = signal(0);
  protected readonly account = rxResource({
    params: () => {
      const id = this.id();
      return id ? { id, n: this.reload() } : undefined;
    },
    stream: ({ params }) => this.api.get(params.id),
  });
  protected readonly assignmentList = rxResource({
    params: () => {
      const id = this.id();
      return id ? { id, n: this.reload() } : undefined;
    },
    stream: ({ params }) => this.api.assignments(params.id),
  });
  protected readonly user = computed(() => (this.account.hasValue() ? this.account.value() : null));
  protected readonly assignments = computed(() =>
    this.assignmentList.hasValue() ? this.assignmentList.value() : [],
  );

  protected readonly name = signal('');
  protected readonly email = signal('');
  protected readonly fe = signal<Record<string, string>>({});
  protected readonly notices = signal<Notice[]>([]);
  protected readonly busy = signal<string | null>(null);
  protected readonly assignOpen = signal(false);
  protected readonly passwordOpen = signal(false);
  protected readonly password = signal('');
  protected readonly reasonOpen = signal(false);
  protected readonly reasonError = signal<string | null>(null);
  protected readonly pending = signal<Pending>(null);

  protected readonly canEdit = computed(() => {
    const u = this.user();
    return !!u && this.can('SCR-0109.sua') && this.allowed(u, 'SCR-0109.sua');
  });
  protected readonly dirty = computed(() => {
    const u = this.user();
    return (
      !!u &&
      (this.name().trim() !== u.fullName || (this.email().trim() || null) !== (u.email ?? null))
    );
  });

  protected readonly reasonHeader = computed(() => {
    const p = this.pending();
    return p?.kind === 'lock'
      ? 'Khoá tài khoản'
      : p?.kind === 'unlock'
        ? 'Mở khoá tài khoản'
        : 'Kết thúc kiêm nhiệm';
  });
  protected readonly reasonConfirm = computed(() => {
    const p = this.pending();
    return p?.kind === 'lock' ? 'Khoá' : p?.kind === 'unlock' ? 'Mở khoá' : 'Kết thúc';
  });
  protected readonly reasonLead = computed(() => {
    const p = this.pending();
    const u = this.user();
    if (!p || !u) return '';
    if (p.kind === 'lock')
      return `Khoá tài khoản của đồng chí ${u.fullName}. Người dùng bị đăng xuất và không đăng nhập được cho đến khi mở khoá.`;
    if (p.kind === 'unlock') return `Mở khoá tài khoản của đồng chí ${u.fullName}.`;
    return `Kết thúc ${this.kindLabel[p.assignment.kind].toLowerCase()} của đồng chí ${u.fullName} tại ${p.assignment.orgUnitName}; phân công còn hiệu lực đến hết hôm nay.`;
  });

  constructor() {
    effect(() => {
      const u = this.user();
      if (u)
        untracked(() => {
          this.name.set(u.fullName);
          this.email.set(u.email ?? '');
        });
    });
  }

  protected allowed(u: UserAccount, key: string) {
    return permittedOn(u, key);
  }

  protected err(e: unknown) {
    return adminError(e, 'Không tải được thông tin người dùng.').text;
  }

  private done(text: string) {
    this.notices.set([{ kind: 'success', text }]);
    this.reload.update((n) => n + 1);
  }

  private fail(e: unknown, fallback: string) {
    const r = adminError(e, fallback);
    this.fe.set(r.fields);
    this.notices.set([{ kind: 'error', text: r.text }]);
  }

  protected save(u: UserAccount) {
    this.busy.set('save');
    this.fe.set({});
    this.api
      .update(u, { fullName: this.name().trim(), email: this.email().trim() || null })
      .subscribe({
        next: () => {
          this.busy.set(null);
          this.done('Đã lưu thông tin tài khoản.');
        },
        error: (e: unknown) => {
          this.busy.set(null);
          this.fail(e, 'Không lưu được thông tin tài khoản.');
        },
      });
  }

  protected async resetPassword(u: UserAccount) {
    if (!(await this.stepUp('Đặt lại mật khẩu cần xác thực nhiều lớp.'))) return;
    this.busy.set('reset');
    this.api.resetPassword(u).subscribe({
      next: (r) => {
        this.busy.set(null);
        this.password.set(r.temporaryPassword);
        this.passwordOpen.set(true);
        this.done(`Đã đặt lại mật khẩu cho đồng chí ${u.fullName}.`);
      },
      error: (e: unknown) => {
        this.busy.set(null);
        this.fail(e, 'Không đặt lại được mật khẩu.');
      },
    });
  }

  protected onAssigned() {
    this.done('Đã giao kiêm nhiệm.');
  }

  protected ask(p: Pending) {
    this.pending.set(p);
    this.reasonError.set(null);
    this.reasonOpen.set(true);
  }

  protected async confirm(reason: string) {
    const p = this.pending();
    const u = this.user();
    if (!p || !u) return;
    if (!(await this.stepUp('Thao tác này cần xác thực nhiều lớp.'))) return;
    this.busy.set('reason');
    const call =
      p.kind === 'lock'
        ? this.api.lock(u, reason).pipe(map(() => `Đã khoá tài khoản của đồng chí ${u.fullName}.`))
        : p.kind === 'unlock'
          ? this.api
              .unlock(u, reason)
              .pipe(map(() => `Đã mở khoá tài khoản của đồng chí ${u.fullName}.`))
          : this.api
              .endAssignment(p.assignment, isoDay(1), reason)
              .pipe(map(() => `Đã kết thúc kiêm nhiệm tại ${p.assignment.orgUnitName}.`));
    call.subscribe({
      next: (text) => {
        this.busy.set(null);
        this.reasonOpen.set(false);
        this.done(text);
      },
      error: (e: unknown) => {
        this.busy.set(null);
        this.reasonError.set(adminError(e, 'Không thực hiện được.').text);
      },
    });
  }
}
