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
import { FormsModule } from '@angular/forms';
import {
  CustomRole,
  CustomRoleApi,
  PERMISSION_SCOPE_LABEL,
  PermissionCatalog,
  PermissionScope,
  scopesUpTo,
} from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { PAGE_STYLES, adminError } from '../users/users-ui';
import { ACCESS_STEP_UP } from './access-ui';

/** Một chức năng mà vai trò gốc có, kèm phạm vi tối đa được chọn. */
interface Option {
  key: string;
  name: string;
  limit: PermissionScope;
}
interface ScreenOptions {
  code: string;
  name: string;
  functions: Option[];
}
interface GroupOptions {
  code: string;
  name: string;
  screens: ScreenOptions[];
}

/**
 * Hộp tạo, sửa vai trò tuỳ chỉnh của SCR-0110 (POST, PUT `/config/custom-roles`, BS-04): tên, mô tả,
 * vai trò gốc (chỉ khi tạo) và bảng chọn chức năng theo nhóm màn hình. Chỉ liệt kê chức năng vai trò gốc
 * có; phạm vi chọn được không rộng hơn phạm vi của vai trò gốc (BR-01-23, máy chủ kiểm lại).
 */
@Component({
  selector: 'vt-custom-role-dialog',
  imports: [
    FormsModule,
    ButtonModule,
    CheckboxModule,
    DialogModule,
    InputTextModule,
    MessageModule,
    SelectModule,
    TextareaModule,
  ],
  template: `
    <p-dialog
      [header]="role() ? 'Sửa vai trò' : 'Tạo vai trò tuỳ chỉnh'"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(880px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <div class="grid">
        <div>
          <label class="f" for="cr-name">Tên vai trò</label>
          <input
            id="cr-name"
            pInputText
            class="full"
            maxlength="120"
            [ngModel]="name()"
            (ngModelChange)="name.set($event)"
          />
          @if (fe()['name']) {
            <small class="err">{{ fe()['name'] }}</small>
          }
        </div>
        <div>
          <label class="f" for="cr-base">Dựa trên vai trò</label>
          <p-select
            inputId="cr-base"
            styleClass="full"
            [options]="bases()"
            optionLabel="label"
            optionValue="value"
            placeholder="Chọn vai trò gốc"
            [disabled]="!!role()"
            [ngModel]="base()"
            (ngModelChange)="setBase($event)"
          />
          <small class="muted block">Vai trò mới chỉ có thể hẹp hơn hoặc bằng vai trò gốc.</small>
        </div>
      </div>
      <div class="desc">
        <label class="f" for="cr-desc">Mô tả (không bắt buộc)</label>
        <textarea
          id="cr-desc"
          pTextarea
          rows="2"
          maxlength="500"
          class="full"
          [ngModel]="description()"
          (ngModelChange)="description.set($event)"
        ></textarea>
      </div>

      @if (base()) {
        <div class="perm-head">
          <h3 class="h3">Quyền của vai trò</h3>
          <span class="muted small"
            >Đã chọn {{ chosenCount() }} / {{ totalCount() }} chức năng</span
          >
        </div>
        @if (fe()['permissions']) {
          <p class="err">{{ fe()['permissions'] }}</p>
        }
        <div class="groups">
          @for (g of groups(); track g.code) {
            <fieldset class="group">
              <legend>{{ g.name }}</legend>
              @for (s of g.screens; track s.code) {
                <div class="screen">
                  <p class="sname">{{ s.name }}</p>
                  @for (f of s.functions; track f.key) {
                    <div class="fn">
                      <span class="inline">
                        <p-checkbox
                          [inputId]="'cr-' + f.key"
                          [binary]="true"
                          [ngModel]="!!chosen()[f.key]"
                          (ngModelChange)="toggle(f, $event)"
                        />
                        <label [for]="'cr-' + f.key">{{ f.name }}</label>
                      </span>
                      @if (chosen()[f.key]; as sc) {
                        @if (scopeOptions(f).length > 1) {
                          <p-select
                            [inputId]="'cr-sc-' + f.key"
                            [ariaLabel]="'Phạm vi của ' + f.name"
                            size="small"
                            [options]="scopeOptions(f)"
                            optionLabel="label"
                            optionValue="value"
                            [ngModel]="sc"
                            (ngModelChange)="setScope(f.key, $event)"
                          />
                        } @else {
                          <span class="muted small">{{ scopeLabel[sc] }}</span>
                        }
                      }
                      @if (fe()['permissions.' + f.key]) {
                        <small class="err block">{{ fe()['permissions.' + f.key] }}</small>
                      }
                    </div>
                  }
                </div>
              }
            </fieldset>
          }
        </div>
      }
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
      }
      <ng-template #footer>
        <p-button label="Huỷ" severity="secondary" [text]="true" (onClick)="visible.set(false)" />
        <p-button
          [label]="role() ? 'Lưu thay đổi' : 'Tạo vai trò'"
          icon="pi pi-check"
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
      .desc {
        margin-top: 12px;
      }
      .muted {
        color: #4b5563;
      }
      .small {
        font-size: 0.875em;
      }
      .err {
        color: #9b1c1c;
        display: block;
      }
      .perm-head {
        display: flex;
        justify-content: space-between;
        align-items: baseline;
        margin-top: 16px;
      }
      .h3 {
        font-size: 1rem;
        margin: 0;
      }
      .groups {
        display: flex;
        flex-direction: column;
        gap: 12px;
        margin-top: 8px;
      }
      .group {
        border: 1px solid #e5e7eb;
        border-radius: 8px;
        padding: 8px 12px 12px;
      }
      legend {
        font-weight: 600;
        padding: 0 4px;
      }
      .screen + .screen {
        border-top: 1px dashed #e5e7eb;
        margin-top: 8px;
        padding-top: 8px;
      }
      .sname {
        margin: 4px 0;
        font-weight: 500;
      }
      .fn {
        display: flex;
        gap: 12px;
        align-items: center;
        flex-wrap: wrap;
        padding: 3px 0 3px 8px;
      }
      :host ::ng-deep .msg {
        margin-top: 12px;
        width: 100%;
      }
    `,
  ],
})
export class CustomRoleDialog {
  private readonly api = inject(CustomRoleApi);
  private readonly stepUp = inject(ACCESS_STEP_UP);

  readonly visible = model(false);
  readonly catalog = input.required<PermissionCatalog>();
  /** Vai trò đang sửa; không có là tạo mới. */
  readonly role = input<CustomRole | null>(null);
  readonly saved = output<CustomRole>();

  protected readonly scopeLabel = PERMISSION_SCOPE_LABEL;
  protected readonly name = signal('');
  protected readonly description = signal('');
  protected readonly base = signal<string | null>(null);
  protected readonly chosen = signal<Record<string, PermissionScope>>({});
  protected readonly fe = signal<Record<string, string>>({});
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  protected readonly bases = computed(() =>
    this.catalog()
      .roles.filter((r) => r.customizable)
      .map((r) => ({ value: r.code, label: r.name })),
  );

  /** Danh mục rút gọn theo vai trò gốc: chỉ chức năng vai trò gốc có, bỏ nhóm và màn hình trống. */
  protected readonly groups = computed<GroupOptions[]>(() => {
    const b = this.base();
    if (!b) return [];
    return this.catalog()
      .groups.map((g) => ({
        code: g.code,
        name: g.name,
        screens: g.screens
          .map((s) => ({
            code: s.code,
            name: s.name,
            functions: s.functions
              .filter((f) => !!f.grants[b])
              .map((f) => ({ key: f.key, name: f.name, limit: f.grants[b].scope })),
          }))
          .filter((s) => s.functions.length > 0),
      }))
      .filter((g) => g.screens.length > 0);
  });
  protected readonly totalCount = computed(() =>
    this.groups().reduce((n, g) => n + g.screens.reduce((m, s) => m + s.functions.length, 0), 0),
  );
  protected readonly chosenCount = computed(() => Object.keys(this.chosen()).length);
  protected readonly ready = () =>
    this.name().trim().length >= 2 && !!this.base() && this.chosenCount() > 0;

  constructor() {
    effect(() => {
      if (!this.visible()) return;
      untracked(() => {
        const r = this.role();
        this.name.set(r?.name ?? '');
        this.description.set(r?.description ?? '');
        this.base.set(r?.baseRole ?? null);
        this.chosen.set(Object.fromEntries((r?.permissions ?? []).map((p) => [p.key, p.scope])));
        this.fe.set({});
        this.error.set(null);
      });
    });
  }

  protected scopeOptions(f: Option) {
    return scopesUpTo(f.limit).map((value) => ({ value, label: PERMISSION_SCOPE_LABEL[value] }));
  }

  protected setBase(code: string) {
    this.base.set(code);
    this.chosen.set({});
  }

  protected toggle(f: Option, on: boolean) {
    this.chosen.update((c) => {
      const next = { ...c };
      if (on) next[f.key] = f.limit;
      else delete next[f.key];
      return next;
    });
  }

  protected setScope(key: string, scope: PermissionScope) {
    this.chosen.update((c) => ({ ...c, [key]: scope }));
  }

  protected async save() {
    const r = this.role();
    if (
      !(await this.stepUp(
        r ? 'Sửa vai trò cần xác thực nhiều lớp.' : 'Tạo vai trò cần xác thực nhiều lớp.',
      ))
    )
      return;
    const permissions = Object.entries(this.chosen()).map(([key, scope]) => ({ key, scope }));
    const body = {
      name: this.name().trim(),
      description: this.description().trim() || null,
      permissions,
    };
    this.saving.set(true);
    this.error.set(null);
    (r
      ? this.api.update(r, body)
      : this.api.create({ ...body, baseRole: this.base() as never })
    ).subscribe({
      next: (saved) => {
        this.saving.set(false);
        this.visible.set(false);
        this.saved.emit(saved);
      },
      error: (e: unknown) => {
        this.saving.set(false);
        const x = adminError(e, 'Không lưu được vai trò.');
        this.fe.set(x.fields);
        this.error.set(x.text);
      },
    });
  }
}
