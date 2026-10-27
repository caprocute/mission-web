import {
  Component,
  computed,
  effect,
  inject,
  input,
  model,
  output,
  signal,
  untracked,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { OrgApi } from '@vt/api-client';
import {
  ROLE_CODE_LABEL,
  ROLE_SCOPE_LABEL,
  RoleAdminApi,
  RoleBinding,
  RoleBindingScope,
  RoleCode,
  SENSITIVE_ROLES,
} from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { map } from 'rxjs';
import { ACCESS_STEP_UP, accessError, dayEnd, dayStart, isoDay } from './access-ui';
import { RoleNames } from './role-names';

interface Draft {
  personId: string | null;
  roleCode: string | null;
  scope: RoleBindingScope;
  flow: 'DANG' | 'NHA_NUOC' | '';
  from: string;
  to: string;
  reason: string;
}

const draft = (): Draft => ({
  personId: null,
  roleCode: null,
  scope: 'DON_VI',
  flow: '',
  from: isoDay(),
  to: isoDay(365),
  reason: '',
});

/**
 * Hộp "Cấp vai trò" của SCR-0105 (F3, POST `/config/role-bindings`): người, vai trò, đơn vị phạm vi
 * (đơn vị đang chọn trên cây), phạm vi, luồng, thời hạn bắt buộc (BR-01-16), căn cứ. Vai trò nhạy cảm
 * được báo trước là cần người duyệt thứ hai (BR-01-14). Xác thực bước lên trước khi gửi (BR-01-15).
 */
@Component({
  selector: 'vt-grant-dialog',
  imports: [
    FormsModule,
    ButtonModule,
    DialogModule,
    InputTextModule,
    MessageModule,
    SelectModule,
    TextareaModule,
  ],
  template: `
    <p-dialog
      header="Cấp vai trò"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(640px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p class="lead">
        Đơn vị phạm vi: <strong>{{ unitName() }}</strong>
      </p>
      <div class="grid">
        <div>
          <label for="gr-person">Cán bộ được cấp</label>
          <p-select
            inputId="gr-person"
            styleClass="full"
            [options]="people()"
            [loading]="persons.isLoading()"
            optionLabel="label"
            optionValue="value"
            [filter]="true"
            filterBy="label"
            placeholder="Chọn cán bộ"
            [ngModel]="d().personId"
            (ngModelChange)="set('personId', $event)"
          />
          @if (fe()['personId']) {
            <small class="err">{{ fe()['personId'] }}</small>
          }
        </div>
        <div>
          <label for="gr-role">Vai trò</label>
          <p-select
            inputId="gr-role"
            styleClass="full"
            [options]="roles()"
            optionLabel="label"
            optionValue="value"
            placeholder="Chọn vai trò"
            [ngModel]="d().roleCode"
            (ngModelChange)="set('roleCode', $event)"
          />
        </div>
        <div>
          <label for="gr-scope">Phạm vi</label>
          <p-select
            inputId="gr-scope"
            styleClass="full"
            [options]="scopes"
            optionLabel="label"
            optionValue="value"
            [ngModel]="d().scope"
            (ngModelChange)="set('scope', $event)"
          />
        </div>
        <div>
          <label for="gr-flow">Luồng đánh giá</label>
          <p-select
            inputId="gr-flow"
            styleClass="full"
            [options]="flows"
            optionLabel="label"
            optionValue="value"
            [ngModel]="d().flow"
            (ngModelChange)="set('flow', $event)"
          />
        </div>
        <div>
          <label for="gr-from">Hiệu lực từ ngày</label>
          <input
            id="gr-from"
            pInputText
            type="date"
            class="full"
            [ngModel]="d().from"
            (ngModelChange)="set('from', $event)"
          />
        </div>
        <div>
          <label for="gr-to">Đến hết ngày</label>
          <input
            id="gr-to"
            pInputText
            type="date"
            class="full"
            [min]="d().from"
            [ngModel]="d().to"
            (ngModelChange)="set('to', $event)"
          />
          @if (fe()['validTo']) {
            <small class="err">{{ fe()['validTo'] }}</small>
          }
        </div>
      </div>
      <label for="gr-reason">Căn cứ (quyết định, văn bản)</label>
      <textarea
        id="gr-reason"
        pTextarea
        rows="2"
        maxlength="500"
        class="full"
        [ngModel]="d().reason"
        (ngModelChange)="set('reason', $event)"
      ></textarea>
      @if (fe()['reason']) {
        <small class="err">{{ fe()['reason'] }}</small>
      }
      @if (sensitive()) {
        <p-message severity="warn" styleClass="msg">
          Vai trò {{ names.label(d().roleCode) }} là vai trò nhạy cảm: đề nghị chỉ có hiệu lực sau
          khi một người duyệt thứ hai khác đồng chí phê duyệt.
        </p-message>
      }
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
      }
      <ng-template #footer>
        <p-button label="Huỷ" severity="secondary" [text]="true" (onClick)="visible.set(false)" />
        <p-button
          [label]="sensitive() ? 'Gửi duyệt' : 'Cấp vai trò'"
          icon="pi pi-check"
          [disabled]="!ready()"
          [loading]="saving()"
          (onClick)="save()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    .grid {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 12px;
      margin-bottom: 12px;
    }
    label {
      display: block;
      margin-bottom: 4px;
      font-weight: 500;
    }
    :host ::ng-deep .full,
    .full {
      width: 100%;
    }
    .lead {
      margin: 0 0 12px;
    }
    .err {
      color: #9b1c1c;
    }
    :host ::ng-deep .msg {
      margin-top: 12px;
      width: 100%;
    }
  `,
})
export class GrantDialog {
  private readonly api = inject(RoleAdminApi);
  private readonly org = inject(OrgApi);
  private readonly stepUp = inject(ACCESS_STEP_UP);

  readonly visible = model(false);
  readonly unitId = input.required<string>();
  readonly unitName = input('');
  readonly granted = output<RoleBinding>();

  protected readonly names = inject(RoleNames);
  /** Vai trò hệ thống rồi đến vai trò tuỳ chỉnh đang dùng của đơn vị triển khai (BS-04). */
  protected readonly roles = computed(() => [
    ...(Object.keys(ROLE_CODE_LABEL) as RoleCode[]).map((value) => ({
      value: value as string,
      label: ROLE_CODE_LABEL[value],
    })),
    ...this.names.activeCustom().map((r) => ({ value: r.code, label: r.name })),
  ]);
  protected readonly scopes = (Object.keys(ROLE_SCOPE_LABEL) as RoleBindingScope[]).map(
    (value) => ({ value, label: ROLE_SCOPE_LABEL[value] }),
  );
  protected readonly flows = [
    { value: '', label: 'Cả hai luồng' },
    { value: 'DANG', label: 'Khối Đảng' },
    { value: 'NHA_NUOC', label: 'Khối Nhà nước' },
  ];

  protected readonly persons = rxResource({
    params: () => (this.visible() ? true : undefined),
    stream: () =>
      this.org
        .persons()
        .pipe(
          map((p) =>
            p.items.map((x) => ({ value: x.id, label: `${x.name} · ${x.title}, ${x.unitName}` })),
          ),
        ),
  });
  protected readonly people = () => (this.persons.hasValue() ? this.persons.value() : []);

  protected readonly d = signal<Draft>(draft());
  protected readonly fe = signal<Record<string, string>>({});
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly sensitive = () => {
    const r = this.d().roleCode;
    return !!r && SENSITIVE_ROLES.includes(r as RoleCode);
  };
  protected readonly ready = () => {
    const d = this.d();
    return !!d.personId && !!d.roleCode && !!d.from && !!d.to && d.reason.trim().length >= 5;
  };

  constructor() {
    effect(() => {
      if (this.visible())
        untracked(() => {
          this.d.set(draft());
          this.fe.set({});
          this.error.set(null);
        });
    });
  }

  protected set<K extends keyof Draft>(key: K, value: Draft[K]) {
    this.d.update((d) => ({ ...d, [key]: value }));
  }

  protected async save() {
    const d = this.d();
    if (!(await this.stepUp('Cấp vai trò cần xác thực nhiều lớp.'))) return;
    this.saving.set(true);
    this.error.set(null);
    this.api
      .grant({
        personId: d.personId!,
        roleCode: d.roleCode!,
        orgUnitId: this.unitId(),
        scope: d.scope,
        flow: d.flow || null,
        validFrom: dayStart(d.from),
        validTo: dayEnd(d.to),
        reason: d.reason.trim(),
      })
      .subscribe({
        next: (b) => {
          this.saving.set(false);
          this.visible.set(false);
          this.granted.emit(b);
        },
        error: (e: unknown) => {
          this.saving.set(false);
          const r = accessError(e, 'Không cấp được vai trò.');
          this.fe.set(r.fields);
          this.error.set(r.text);
        },
      });
  }
}
