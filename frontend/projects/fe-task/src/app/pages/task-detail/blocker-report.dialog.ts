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
  BLOCKER_CAUSE_LABEL,
  BlockerCauseCategory,
  CAUSE_KIND_LABEL,
  CauseKind,
  IMPACT_LABEL,
  ImpactLevel,
  TaskApi,
  TaskDetail,
  toProblem,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TextareaModule } from 'primeng/textarea';

interface Draft {
  content: string;
  causeCategory: BlockerCauseCategory | null;
  causeClaimed: CauseKind | null;
  impactLevel: ImpactLevel | null;
  resolveBy: string;
}

const EMPTY: Draft = {
  content: '',
  causeCategory: null,
  causeClaimed: null,
  impactLevel: null,
  resolveBy: '',
};
const options = <K extends string>(m: Record<K, string>) =>
  (Object.keys(m) as K[]).map((value) => ({ value, label: m[value] }));

/**
 * SCR-0601 "Báo điểm nghẽn" (QT-06 bước 3) → `/api/v1/tasks/{id}/blockers` (POST), EVT-21 cho người giao.
 * Trường theo `task.task_blocker`: nội dung (≤ 500 ký tự), nhóm nguyên nhân, khách quan/chủ quan theo người báo,
 * mức ảnh hưởng, hạn mong muốn xử lý. Thành công hiện MSG-06-04 trên trang.
 */
@Component({
  selector: 'vt-blocker-report-dialog',
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
      header="Báo điểm nghẽn"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(600px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <label for="bl-content">Nội dung điểm nghẽn</label>
      <textarea
        id="bl-content"
        pTextarea
        rows="3"
        maxlength="500"
        class="full"
        [ngModel]="draft().content"
        (ngModelChange)="set('content', $event)"
      ></textarea>
      <small class="muted">{{ draft().content.length }}/500 ký tự</small>
      <div class="grid">
        <div>
          <label for="bl-cat">Nhóm nguyên nhân</label>
          <p-select
            inputId="bl-cat"
            [options]="causeOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="Chọn"
            [ngModel]="draft().causeCategory"
            (ngModelChange)="set('causeCategory', $event)"
            styleClass="full"
            appendTo="body"
          />
        </div>
        <div>
          <label for="bl-kind">Tính chất nguyên nhân (theo đồng chí)</label>
          <p-select
            inputId="bl-kind"
            [options]="kindOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="Chọn"
            [ngModel]="draft().causeClaimed"
            (ngModelChange)="set('causeClaimed', $event)"
            styleClass="full"
            appendTo="body"
          />
        </div>
        <div>
          <label for="bl-impact">Mức ảnh hưởng</label>
          <p-select
            inputId="bl-impact"
            [options]="impactOptions"
            optionLabel="label"
            optionValue="value"
            placeholder="Chọn"
            [ngModel]="draft().impactLevel"
            (ngModelChange)="set('impactLevel', $event)"
            styleClass="full"
            appendTo="body"
          />
        </div>
        <div>
          <label for="bl-due">Cần xử lý trước (không bắt buộc)</label>
          <input
            id="bl-due"
            pInputText
            type="date"
            class="full"
            [min]="today"
            [ngModel]="draft().resolveBy"
            (ngModelChange)="set('resolveBy', $event)"
          />
        </div>
      </div>
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
          label="Gửi điểm nghẽn"
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
export class BlockerReportDialog {
  readonly task = input.required<TaskDetail>();
  readonly visible = model(false);
  readonly saved = output<TaskDetail>();

  private readonly api = inject(TaskApi);
  protected readonly causeOptions = options(BLOCKER_CAUSE_LABEL);
  protected readonly kindOptions = options(CAUSE_KIND_LABEL);
  protected readonly impactOptions = options(IMPACT_LABEL);
  protected readonly today = new Date().toISOString().slice(0, 10);

  protected readonly draft = linkedSignal<boolean, Draft>({
    source: this.visible,
    computation: () => ({ ...EMPTY }),
  });
  protected readonly error = linkedSignal<boolean, string | null>({
    source: this.visible,
    computation: () => null,
  });
  protected readonly busy = signal(false);
  protected readonly valid = computed(() => {
    const d = this.draft();
    return !!d.content.trim() && !!d.causeCategory && !!d.causeClaimed && !!d.impactLevel;
  });

  protected set<K extends keyof Draft>(k: K, v: Draft[K]) {
    this.draft.update((d) => ({ ...d, [k]: v }));
  }

  protected save(): void {
    const d = this.draft();
    this.busy.set(true);
    this.error.set(null);
    this.api
      .reportBlocker(this.task(), {
        content: d.content.trim(),
        causeCategory: d.causeCategory!,
        causeClaimed: d.causeClaimed!,
        impactLevel: d.impactLevel!,
        resolveBy: d.resolveBy || undefined,
      })
      .subscribe({
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
