import {
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import {
  CAUSE_KIND_LABEL,
  CHANGE_REQUEST_TYPE_LABEL,
  CauseKind,
  ChangeRequest,
  TaskApi,
  TaskDetail,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputNumberModule } from 'primeng/inputnumber';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';
import { Observable } from 'rxjs';
import { formatDate } from '../../task-ui';

/** Nội dung đang nhập: nguyên nhân (đề nghị), ý kiến hoặc lý do, hạn và số lượng chốt (đăng ký). */
interface Draft {
  cause: CauseKind | null;
  note: string;
  dueDate: string;
  quantity: number | null;
}

/**
 * Quyết định của người quản lý trên SCR-0601; `request` có giá trị là đề nghị, rỗng là nhiệm vụ đăng ký,
 * `approve` chọn duyệt hay từ chối:
 * - đề nghị gia hạn, điều chỉnh (QT-06 bước 6, `SCR-0601.duyet-de-nghi`): xác định nguyên nhân khách quan
 *   hoặc chủ quan, duyệt hoặc từ chối có lý do → `decideTaskChangeRequest`; người duyệt khác người gửi
 *   (BR-06-08) do máy chủ kiểm;
 * - nhiệm vụ cán bộ đăng ký (QT-05 bước 1c, `SCR-0501.giao`): duyệt kèm hạn và số lượng chốt, hoặc từ chối
 *   có lý do → `decideTaskRegistration` (If-Match).
 */
@Component({
  selector: 'vt-manager-decision-dialog',
  imports: [
    FormsModule,
    ButtonModule,
    DialogModule,
    InputNumberModule,
    InputTextModule,
    MessageModule,
    SelectModule,
    TextareaModule,
  ],
  template: `
    <p-dialog
      [header]="header()"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      @let t = task();
      @if (request(); as r) {
        <p class="muted">{{ type(r) }} · {{ detail(r) }}</p>
        <p>{{ r.reason }}</p>
        <label for="md-cause">Nguyên nhân (người gửi nêu: {{ kind(r.causeClaimed) }})</label>
        <p-select
          inputId="md-cause"
          [options]="causeOptions"
          optionLabel="label"
          optionValue="value"
          placeholder="Chọn"
          [ngModel]="draft().cause"
          (ngModelChange)="set('cause', $event)"
          styleClass="full"
          appendTo="body"
        />
      } @else {
        <p class="muted">{{ t.code }} · {{ t.title }}</p>
        @if (approve()) {
          <div class="grid">
            <div>
              <label for="md-due">Hạn hoàn thành</label>
              <input
                id="md-due"
                pInputText
                type="date"
                class="full"
                [min]="today"
                [ngModel]="draft().dueDate"
                (ngModelChange)="set('dueDate', $event)"
              />
            </div>
            <div>
              <label for="md-qty">Số lượng ({{ t.unit }})</label>
              <p-inputnumber
                inputId="md-qty"
                [min]="1"
                [max]="99"
                [ngModel]="draft().quantity"
                (ngModelChange)="set('quantity', $event)"
                styleClass="full"
              />
            </div>
          </div>
        }
      }
      <label for="md-note">{{ noteLabel() }}</label>
      <textarea
        id="md-note"
        pTextarea
        rows="3"
        maxlength="1000"
        class="full"
        [ngModel]="draft().note"
        (ngModelChange)="set('note', $event)"
      ></textarea>
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" />
      }
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="secondary"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          [label]="confirmLabel()"
          [severity]="approve() ? undefined : 'danger'"
          [loading]="busy()"
          [disabled]="!valid()"
          (onClick)="save()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    label {
      display: block;
      margin: 12px 0 6px;
    }
    .full,
    :host ::ng-deep .full {
      width: 100%;
    }
    .grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0 12px;
    }
    @media (max-width: 560px) {
      .grid {
        grid-template-columns: 1fr;
      }
    }
    .muted {
      color: #4b5563;
    }
    :host ::ng-deep .msg {
      display: block;
      margin-top: 12px;
    }
  `,
})
export class ManagerDecisionDialog {
  readonly task = input.required<TaskDetail>();
  readonly request = input<ChangeRequest | null>(null);
  readonly approve = input(true);
  readonly visible = model(false);
  readonly saved = output<TaskDetail>();

  private readonly api = inject(TaskApi);
  protected readonly today = new Date().toISOString().slice(0, 10);
  protected readonly causeOptions = (Object.keys(CAUSE_KIND_LABEL) as CauseKind[]).map((value) => ({
    value,
    label: CAUSE_KIND_LABEL[value],
  }));

  protected readonly header = computed(() =>
    this.request()
      ? this.approve()
        ? 'Duyệt đề nghị'
        : 'Từ chối đề nghị'
      : this.approve()
        ? 'Duyệt đăng ký nhiệm vụ'
        : 'Từ chối đăng ký nhiệm vụ',
  );
  protected readonly confirmLabel = computed(() =>
    this.request()
      ? this.approve()
        ? 'Duyệt đề nghị'
        : 'Từ chối đề nghị'
      : this.approve()
        ? 'Duyệt và giao việc'
        : 'Từ chối đăng ký',
  );
  protected readonly noteLabel = computed(() =>
    this.approve() ? 'Ý kiến (không bắt buộc)' : 'Lý do từ chối *',
  );

  protected readonly draft = linkedSignal<boolean, Draft>({
    source: this.visible,
    computation: () => ({
      cause: this.request()?.causeClaimed ?? null,
      note: '',
      dueDate: this.task().dueDate,
      quantity: this.task().plannedQuantity,
    }),
  });
  protected readonly error = linkedSignal<boolean, string | null>({
    source: this.visible,
    computation: () => null,
  });
  protected readonly busy = signal(false);
  protected readonly valid = computed(() => {
    const d = this.draft();
    if (!this.approve() && !d.note.trim()) return false;
    if (this.request()) return !!d.cause;
    return !this.approve() || (!!d.dueDate && !!d.quantity);
  });

  protected type(r: ChangeRequest) {
    return CHANGE_REQUEST_TYPE_LABEL[r.type];
  }
  protected kind(k: CauseKind) {
    return CAUSE_KIND_LABEL[k].toLowerCase();
  }
  protected detail(r: ChangeRequest) {
    if (r.type === 'GIA_HAN' && r.newDueDate) return `hạn mới ${formatDate(r.newDueDate, true)}`;
    if (r.type === 'DOI_SO_LUONG') return `số lượng mới ${r.newQuantity ?? ''}`;
    if (r.type === 'DOI_SAN_PHAM') return `sản phẩm mới: ${r.newProduct ?? ''}`;
    return `gửi ${formatDate(r.submittedAt, true)}`;
  }

  protected set<K extends keyof Draft>(k: K, v: Draft[K]) {
    this.draft.update((d) => ({ ...d, [k]: v }));
  }

  protected save(): void {
    const d = this.draft();
    const t = this.task();
    const r = this.request();
    const note = d.note.trim() || undefined;
    const call: Observable<TaskDetail> = r
      ? this.api.decideChangeRequest(t, r.id, {
          approve: this.approve(),
          causeDecided: d.cause!,
          note,
        })
      : this.api.decideRegistration(
          t,
          this.approve()
            ? { approve: true, dueDate: d.dueDate, quantity: d.quantity! }
            : { approve: false, reason: note },
        );
    this.busy.set(true);
    this.error.set(null);
    call.subscribe({
      next: (u) => {
        this.busy.set(false);
        this.saved.emit(u);
        this.visible.set(false);
      },
      error: (e) => {
        this.busy.set(false);
        const p = toProblem(e);
        this.error.set(p.errors?.length ? p.errors.map((x) => x.message).join('; ') : p.title);
      },
    });
  }
}
