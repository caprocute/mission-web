import { Component, effect, inject, input, model, output, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { UserAccountCreated, UserAdminApi } from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { map } from 'rxjs';
import { ACCESS_STEP_UP, isoDay } from '../access/access-ui';
import { PAGE_STYLES, adminError, suggestUsername } from './users-ui';

interface Draft {
  fullName: string;
  username: string;
  email: string;
  jobTitleId: string | null;
  gender: 'NAM' | 'NU' | 'KHAC' | null;
  partyMember: boolean;
  validFrom: string;
}

const draft = (): Draft => ({
  fullName: '',
  username: '',
  email: '',
  jobTitleId: null,
  gender: null,
  partyMember: false,
  validFrom: isoDay(),
});

/**
 * Hộp "Thêm người dùng" của SCR-0108 (POST `/org/users`, BS-04): họ tên, tên đăng nhập (gợi ý từ họ
 * tên), thư điện tử, chức danh tại đơn vị đang chọn, giới tính, đảng viên, ngày bắt đầu công tác. Cần
 * xác thực bước lên. Thành công thì trang cha hiện mật khẩu tạm thời một lần.
 */
@Component({
  selector: 'vt-user-create-dialog',
  imports: [
    FormsModule,
    ButtonModule,
    CheckboxModule,
    DialogModule,
    InputTextModule,
    MessageModule,
    SelectModule,
  ],
  template: `
    <p-dialog
      header="Thêm người dùng"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(680px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p class="lead">
        Đơn vị công tác: <strong>{{ unitName() }}</strong>
      </p>
      <div class="grid">
        <div>
          <label class="f" for="uc-name">Họ và tên</label>
          <input
            id="uc-name"
            pInputText
            class="full"
            maxlength="200"
            autocomplete="off"
            [ngModel]="d().fullName"
            (ngModelChange)="setName($event)"
          />
          @if (fe()['fullName']) {
            <small class="err">{{ fe()['fullName'] }}</small>
          }
        </div>
        <div>
          <label class="f" for="uc-user">Tên đăng nhập</label>
          <input
            id="uc-user"
            pInputText
            class="full"
            maxlength="50"
            autocomplete="off"
            [ngModel]="d().username"
            (ngModelChange)="set('username', $event.trim().toLowerCase())"
          />
          <small class="muted block">Chữ thường không dấu, số, dấu chấm hoặc gạch.</small>
          @if (fe()['username']) {
            <small class="err">{{ fe()['username'] }}</small>
          }
        </div>
        <div>
          <label class="f" for="uc-mail">Thư điện tử (không bắt buộc)</label>
          <input
            id="uc-mail"
            pInputText
            type="email"
            class="full"
            maxlength="200"
            [ngModel]="d().email"
            (ngModelChange)="set('email', $event)"
          />
          @if (fe()['email']) {
            <small class="err">{{ fe()['email'] }}</small>
          }
        </div>
        <div>
          <label class="f" for="uc-title">Chức danh</label>
          <p-select
            inputId="uc-title"
            styleClass="full"
            [options]="titles()"
            optionLabel="label"
            optionValue="value"
            [filter]="true"
            filterBy="label"
            placeholder="Chọn chức danh"
            [ngModel]="d().jobTitleId"
            (ngModelChange)="set('jobTitleId', $event)"
          />
          @if (fe()['jobTitleId']) {
            <small class="err">{{ fe()['jobTitleId'] }}</small>
          }
        </div>
        <div>
          <label class="f" for="uc-gender">Giới tính</label>
          <p-select
            inputId="uc-gender"
            styleClass="full"
            [options]="genders"
            optionLabel="label"
            optionValue="value"
            placeholder="Chọn giới tính"
            [ngModel]="d().gender"
            (ngModelChange)="set('gender', $event)"
          />
        </div>
        <div>
          <label class="f" for="uc-from">Bắt đầu công tác từ ngày</label>
          <input
            id="uc-from"
            pInputText
            type="date"
            class="full"
            [ngModel]="d().validFrom"
            (ngModelChange)="set('validFrom', $event)"
          />
        </div>
      </div>
      <p class="inline party">
        <p-checkbox
          inputId="uc-party"
          [binary]="true"
          [ngModel]="d().partyMember"
          (ngModelChange)="set('partyMember', $event)"
        />
        <label for="uc-party">Là đảng viên</label>
      </p>
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
      }
      <ng-template #footer>
        <p-button label="Huỷ" severity="secondary" [text]="true" (onClick)="visible.set(false)" />
        <p-button
          label="Tạo tài khoản"
          icon="pi pi-user-plus"
          [disabled]="!ready()"
          [loading]="saving()"
          (onClick)="save()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: [
    PAGE_STYLES,
    `
      .lead {
        margin: 0 0 12px;
      }
      .muted {
        color: #4b5563;
      }
      .err {
        color: #9b1c1c;
        display: block;
      }
      .party {
        margin: 12px 0 0;
      }
      :host ::ng-deep .msg {
        margin-top: 12px;
        width: 100%;
      }
    `,
  ],
})
export class UserCreateDialog {
  private readonly api = inject(UserAdminApi);
  private readonly stepUp = inject(ACCESS_STEP_UP);

  readonly visible = model(false);
  readonly unitId = input.required<string>();
  readonly unitName = input('');
  readonly created = output<UserAccountCreated>();

  protected readonly genders = [
    { value: 'NAM', label: 'Nam' },
    { value: 'NU', label: 'Nữ' },
    { value: 'KHAC', label: 'Khác' },
  ];

  private readonly jobTitles = rxResource({
    params: () => (this.visible() ? true : undefined),
    stream: () =>
      this.api.jobTitles().pipe(map((items) => items.map((j) => ({ value: j.id, label: j.name })))),
  });
  protected readonly titles = () => (this.jobTitles.hasValue() ? this.jobTitles.value() : []);

  protected readonly d = signal<Draft>(draft());
  protected readonly fe = signal<Record<string, string>>({});
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);
  /** Người quản trị đã tự sửa tên đăng nhập thì thôi gợi ý theo họ tên. */
  private readonly usernameTouched = signal(false);

  protected readonly ready = () => {
    const d = this.d();
    return (
      d.fullName.trim().length >= 2 && d.username.length >= 3 && !!d.jobTitleId && !!d.validFrom
    );
  };

  constructor() {
    effect(() => {
      if (this.visible())
        untracked(() => {
          this.d.set(draft());
          this.fe.set({});
          this.error.set(null);
          this.usernameTouched.set(false);
        });
    });
  }

  protected setName(value: string) {
    this.d.update((d) => ({
      ...d,
      fullName: value,
      username: this.usernameTouched() ? d.username : suggestUsername(value),
    }));
  }

  protected set<K extends keyof Draft>(key: K, value: Draft[K]) {
    if (key === 'username') this.usernameTouched.set(true);
    this.d.update((d) => ({ ...d, [key]: value }));
  }

  protected async save() {
    const d = this.d();
    if (!(await this.stepUp('Tạo tài khoản cần xác thực nhiều lớp.'))) return;
    this.saving.set(true);
    this.error.set(null);
    this.api
      .create({
        fullName: d.fullName.trim(),
        username: d.username,
        email: d.email.trim() || null,
        orgUnitId: this.unitId(),
        jobTitleId: d.jobTitleId!,
        gender: d.gender,
        partyMember: d.partyMember,
        validFrom: d.validFrom,
      })
      .subscribe({
        next: (u) => {
          this.saving.set(false);
          this.visible.set(false);
          this.created.emit(u);
        },
        error: (e: unknown) => {
          this.saving.set(false);
          const r = adminError(e, 'Không tạo được tài khoản.');
          this.fe.set(r.fields);
          this.error.set(r.text);
        },
      });
  }
}
