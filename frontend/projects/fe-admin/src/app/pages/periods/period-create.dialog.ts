import { Component, effect, inject, model, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  PERIOD_FLOW_LABEL,
  PERIOD_KIND_LABEL,
  Period,
  PeriodApi,
  PeriodCreate,
  PeriodFlow,
  PeriodKind,
  fieldErrors,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';

interface Draft {
  code: string;
  name: string;
  kind: PeriodKind;
  flow: PeriodFlow;
  startDate: string;
  endDate: string;
  planDeadline: string;
  submissionDeadline: string;
  planApprovalDeadline: string;
  evidenceDeadline: string;
  acceptanceBacklogDeadline: string;
  plannedLockDate: string;
}

const EMPTY: Draft = {
  code: '',
  name: '',
  kind: 'QUY',
  flow: 'CA_HAI',
  startDate: '',
  endDate: '',
  planDeadline: '',
  submissionDeadline: '',
  planApprovalDeadline: '',
  evidenceDeadline: '',
  acceptanceBacklogDeadline: '',
  plannedLockDate: '',
};

/** Lịch kỳ (SCR-0801 trường 2, `config.period.milestones`); bỏ trống thì máy chủ lấy theo Policy Pack. */
const MILESTONES: { key: keyof Draft; label: string }[] = [
  { key: 'planApprovalDeadline', label: 'Hạn duyệt kế hoạch' },
  { key: 'evidenceDeadline', label: 'Hạn nộp minh chứng' },
  { key: 'acceptanceBacklogDeadline', label: 'Hạn nghiệm thu tồn đọng' },
  { key: 'plannedLockDate', label: 'Ngày khoá dự kiến' },
];

/**
 * Hộp "Tạo kỳ" của SCR-0801 (POST `/periods`): mã, tên, loại kỳ, luồng, từ ngày, đến ngày, lịch mốc.
 * Máy chủ ghim Policy Pack hiệu lực (BR-08-07), chặn chồng lấn cùng loại (409 `PERIOD-409-OVERLAP`) và
 * trả cảnh báo khi lịch khoá muộn hơn tham số (BR-08-12).
 */
@Component({
  selector: 'vt-period-create-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, InputTextModule, MessageModule, SelectModule],
  template: `
    <p-dialog
      header="Tạo kỳ đánh giá"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(680px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <div class="grid">
        <div>
          <label for="pc-code">Mã kỳ</label>
          <input
            id="pc-code"
            pInputText
            class="full"
            maxlength="16"
            placeholder="2027-Q2"
            [ngModel]="d().code"
            (ngModelChange)="set('code', $event)"
          />
          @if (fe()['code']) {
            <small class="err">{{ fe()['code'] }}</small>
          }
        </div>
        <div>
          <label for="pc-name">Tên kỳ</label>
          <input
            id="pc-name"
            pInputText
            class="full"
            maxlength="64"
            placeholder="Quý II/2027"
            [ngModel]="d().name"
            (ngModelChange)="set('name', $event)"
          />
          @if (fe()['name']) {
            <small class="err">{{ fe()['name'] }}</small>
          }
        </div>
        <div>
          <label for="pc-kind">Loại kỳ</label>
          <p-select
            inputId="pc-kind"
            styleClass="full"
            [options]="kinds"
            optionLabel="label"
            optionValue="value"
            [ngModel]="d().kind"
            (ngModelChange)="set('kind', $event)"
          />
        </div>
        <div>
          <label for="pc-flow">Luồng áp dụng</label>
          <p-select
            inputId="pc-flow"
            styleClass="full"
            [options]="flows"
            optionLabel="label"
            optionValue="value"
            [ngModel]="d().flow"
            (ngModelChange)="set('flow', $event)"
          />
        </div>
        <div>
          <label for="pc-start">Từ ngày</label>
          <input
            id="pc-start"
            pInputText
            type="date"
            class="full"
            [ngModel]="d().startDate"
            (ngModelChange)="set('startDate', $event)"
          />
          @if (fe()['startDate']) {
            <small class="err">{{ fe()['startDate'] }}</small>
          }
        </div>
        <div>
          <label for="pc-end">Đến ngày</label>
          <input
            id="pc-end"
            pInputText
            type="date"
            class="full"
            [ngModel]="d().endDate"
            (ngModelChange)="set('endDate', $event)"
          />
          @if (fe()['endDate']) {
            <small class="err">{{ fe()['endDate'] }}</small>
          }
        </div>
      </div>
      <fieldset>
        <legend>Lịch kỳ</legend>
        <p class="muted small">Bỏ trống để lấy theo bộ quy định đánh giá đang hiệu lực.</p>
        <div class="grid">
          @for (m of milestones; track m.key) {
            <div>
              <label [for]="'pc-' + m.key">{{ m.label }}</label>
              <input
                [id]="'pc-' + m.key"
                pInputText
                type="date"
                class="full"
                [ngModel]="d()[m.key]"
                (ngModelChange)="set(m.key, $event)"
              />
            </div>
          }
        </div>
      </fieldset>
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
      }
      <ng-template #footer>
        <p-button label="Huỷ" severity="secondary" [text]="true" (onClick)="visible.set(false)" />
        <p-button label="Tạo kỳ" icon="pi pi-check" [loading]="saving()" (onClick)="save()" />
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
    fieldset {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 6px;
      padding: 8px 12px 0;
      margin: 0;
    }
    legend {
      font-weight: 600;
      padding: 0 4px;
    }
    .muted {
      color: #4b5563;
      margin: 0 0 8px;
    }
    .small {
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
export class PeriodCreateDialog {
  private readonly api = inject(PeriodApi);

  readonly visible = model(false);
  readonly created = output<Period>();

  protected readonly milestones = MILESTONES;
  protected readonly kinds = (Object.keys(PERIOD_KIND_LABEL) as PeriodKind[]).map((value) => ({
    value,
    label: PERIOD_KIND_LABEL[value],
  }));
  protected readonly flows = (Object.keys(PERIOD_FLOW_LABEL) as PeriodFlow[]).map((value) => ({
    value,
    label: PERIOD_FLOW_LABEL[value],
  }));

  protected readonly d = signal<Draft>({ ...EMPTY });
  protected readonly fe = signal<Record<string, string>>({});
  protected readonly error = signal<string | null>(null);
  protected readonly saving = signal(false);

  constructor() {
    // mỗi lần mở là một bản nháp mới
    effect(() => {
      if (this.visible()) untracked(() => this.reset());
    });
  }

  private reset() {
    this.d.set({ ...EMPTY });
    this.fe.set({});
    this.error.set(null);
  }

  protected set<K extends keyof Draft>(key: K, value: Draft[K]) {
    this.d.update((d) => ({ ...d, [key]: value }));
  }

  protected save() {
    const d = this.d();
    const opt = (v: string) => v || undefined;
    const milestones = Object.fromEntries(
      MILESTONES.filter((m) => d[m.key]).map((m) => [m.key, d[m.key]]),
    );
    const body: PeriodCreate = {
      code: d.code.trim(),
      name: d.name.trim(),
      kind: d.kind,
      flow: d.flow,
      startDate: d.startDate,
      endDate: d.endDate,
      planDeadline: opt(d.planDeadline),
      submissionDeadline: opt(d.submissionDeadline),
      milestones: Object.keys(milestones).length ? milestones : undefined,
    };
    this.saving.set(true);
    this.error.set(null);
    this.api.create(body).subscribe({
      next: (p) => {
        this.saving.set(false);
        this.visible.set(false);
        this.created.emit(p);
      },
      error: (e: unknown) => {
        this.saving.set(false);
        const p = toProblem(e);
        this.fe.set(fieldErrors(p));
        this.error.set(p.detail ? `${p.title}. ${p.detail}` : p.title);
      },
    });
  }
}
