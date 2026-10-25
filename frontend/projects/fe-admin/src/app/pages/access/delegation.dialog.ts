import { Component, effect, inject, input, model, output, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { OrgApi } from '@vt/api-client';
import {
  DELEGATION_KIND_LABEL,
  Delegation,
  DelegationKind,
  RoleAdminApi,
} from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { map } from 'rxjs';
import { ACCESS_STEP_UP, accessError, dayEnd, dayStart, isoDay } from './access-ui';

interface Draft {
  kind: DelegationKind;
  delegatorId: string | null;
  delegateId: string | null;
  from: string;
  to: string;
  reason: string;
}

const draft = (): Draft => ({
  kind: 'DUYET_THAY',
  delegatorId: null,
  delegateId: null,
  from: isoDay(),
  to: isoDay(14),
  reason: '',
});

/**
 * Hộp "Khai báo uỷ quyền" của SCR-0107 (F2, POST `/config/delegations`): loại uỷ quyền, người uỷ quyền
 * (người vắng mặt), người nhận, thời hạn bắt buộc (BR-01-16), lý do. Không uỷ quyền lại (BR-01-17,
 * MSG-01-18); người nhận vẫn chịu quy tắc phân tách nhiệm vụ của hồ sơ (BR-01-18), máy chủ kiểm khi thao
 * tác. Uỷ quyền check-in KR thay để trống KR là mọi KR của người uỷ quyền tại đơn vị; chọn KR cụ thể chờ
 * MOD-01 có API tra KR theo người (ghi ở wiki/14).
 */
@Component({
  selector: 'vt-delegation-dialog',
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
      header="Khai báo uỷ quyền khi vắng mặt"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(640px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p class="lead">
        Đơn vị: <strong>{{ unitName() }}</strong>
      </p>
      <div class="grid">
        <div>
          <label for="dl-kind">Loại uỷ quyền</label>
          <p-select
            inputId="dl-kind"
            styleClass="full"
            [options]="kinds"
            optionLabel="label"
            optionValue="value"
            [ngModel]="d().kind"
            (ngModelChange)="set('kind', $event)"
          />
        </div>
        <div>
          <label for="dl-from-p">Người vắng mặt (uỷ quyền)</label>
          <p-select
            inputId="dl-from-p"
            styleClass="full"
            [options]="people()"
            [loading]="persons.isLoading()"
            optionLabel="label"
            optionValue="value"
            [filter]="true"
            filterBy="label"
            placeholder="Chọn cán bộ"
            [ngModel]="d().delegatorId"
            (ngModelChange)="set('delegatorId', $event)"
          />
        </div>
        <div>
          <label for="dl-to-p">Người nhận uỷ quyền</label>
          <p-select
            inputId="dl-to-p"
            styleClass="full"
            [options]="people()"
            [loading]="persons.isLoading()"
            optionLabel="label"
            optionValue="value"
            [filter]="true"
            filterBy="label"
            placeholder="Chọn cán bộ"
            [ngModel]="d().delegateId"
            (ngModelChange)="set('delegateId', $event)"
          />
          @if (same()) {
            <small class="err">Người nhận phải khác người uỷ quyền.</small>
          }
        </div>
        <div>
          <label for="dl-from">Từ ngày</label>
          <input
            id="dl-from"
            pInputText
            type="date"
            class="full"
            [ngModel]="d().from"
            (ngModelChange)="set('from', $event)"
          />
        </div>
        <div>
          <label for="dl-to">Đến hết ngày</label>
          <input
            id="dl-to"
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
      <label for="dl-reason">Lý do vắng mặt, căn cứ</label>
      <textarea
        id="dl-reason"
        pTextarea
        rows="2"
        maxlength="500"
        class="full"
        [ngModel]="d().reason"
        (ngModelChange)="set('reason', $event)"
      ></textarea>
      <p class="muted">
        Người nhận không được uỷ quyền lại và vẫn không được duyệt phiếu của chính mình hay phiếu
        mình đã chấm ở bước trước.
      </p>
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
      }
      <ng-template #footer>
        <p-button label="Huỷ" severity="secondary" [text]="true" (onClick)="visible.set(false)" />
        <p-button
          label="Khai báo"
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
    .muted {
      color: #4b5563;
      font-size: 0.875em;
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
export class DelegationDialog {
  private readonly api = inject(RoleAdminApi);
  private readonly org = inject(OrgApi);
  private readonly stepUp = inject(ACCESS_STEP_UP);

  readonly visible = model(false);
  readonly unitId = input.required<string>();
  readonly unitName = input('');
  readonly created = output<Delegation>();

  protected readonly kinds = (Object.keys(DELEGATION_KIND_LABEL) as DelegationKind[]).map(
    (value) => ({ value, label: DELEGATION_KIND_LABEL[value] }),
  );

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

  protected readonly same = () => {
    const d = this.d();
    return !!d.delegatorId && d.delegatorId === d.delegateId;
  };
  protected readonly ready = () => {
    const d = this.d();
    return (
      !!d.delegatorId &&
      !!d.delegateId &&
      !this.same() &&
      !!d.from &&
      !!d.to &&
      d.reason.trim().length >= 5
    );
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
    if (!(await this.stepUp('Khai báo uỷ quyền cần xác thực nhiều lớp.'))) return;
    this.saving.set(true);
    this.error.set(null);
    this.api
      .delegate({
        kind: d.kind,
        delegatorId: d.delegatorId!,
        delegateId: d.delegateId!,
        orgUnitId: this.unitId(),
        subjectId: null,
        validFrom: dayStart(d.from),
        validTo: dayEnd(d.to),
        reason: d.reason.trim(),
      })
      .subscribe({
        next: (x) => {
          this.saving.set(false);
          this.visible.set(false);
          this.created.emit(x);
        },
        error: (e: unknown) => {
          this.saving.set(false);
          const r = accessError(e, 'Không khai báo được uỷ quyền.');
          this.fe.set(r.fields);
          this.error.set(r.text);
        },
      });
  }
}
