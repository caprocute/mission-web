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
import { TaskApi, TaskDetail, toProblem } from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { MessageModule } from 'primeng/message';
import { SliderModule } from 'primeng/slider';
import { TextareaModule } from 'primeng/textarea';

/**
 * SCR-0601 "Cập nhật tiến độ" → `/api/v1/tasks/{id}/progress` (POST, Idempotency-Key, If-Match), EVT-05.
 * Người chủ trì đổi phần trăm; người phối hợp chỉ ghi nhật ký (BR-06-03). Giảm tiến độ bắt buộc ghi chú
 * (BR-06-02, MSG-06-01). 409 khi người khác vừa cập nhật: hiện thông báo, cho tải lại rồi nhập lại.
 */
@Component({
  selector: 'vt-progress-update-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, MessageModule, SliderModule, TextareaModule],
  template: `
    @let t = task();
    <p-dialog
      [header]="lead() ? 'Cập nhật tiến độ' : 'Ghi nhật ký thực hiện'"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p class="muted">{{ t.code }} · {{ t.title }}</p>
      @if (lead()) {
        <label for="pu-pct"
          >Tiến độ: <strong>{{ percent() }}%</strong>
          <span class="muted">(lần trước {{ t.progress }}%)</span></label
        >
        <p-slider
          inputId="pu-pct"
          [ngModel]="percent()"
          (ngModelChange)="percent.set($event)"
          [step]="5"
          styleClass="slider"
          ariaLabel="Tiến độ"
        />
      } @else {
        <p class="muted">
          Đồng chí là người phối hợp: nội dung được ghi vào nhật ký, phần trăm tiến độ do người chủ
          trì cập nhật.
        </p>
      }
      <label for="pu-note">{{ noteLabel() }}</label>
      <textarea
        id="pu-note"
        pTextarea
        rows="3"
        maxlength="1000"
        class="full"
        [ngModel]="note()"
        (ngModelChange)="note.set($event)"
      ></textarea>
      <small class="muted">{{ note().length }}/1.000 ký tự</small>
      @if (decreased() && !note().trim()) {
        <p class="err" role="alert">Tiến độ mới thấp hơn lần trước. Vui lòng ghi chú lý do.</p>
      }
      @if (error(); as e) {
        <p-message severity="error" styleClass="msg">
          <span>{{ e.title }}</span>
          @if (e.status === 409) {
            <button type="button" class="link" (click)="reloadRequested.emit()">Tải lại</button>
          }
        </p-message>
      }
      <ng-template #footer>
        <p-button
          label="Huỷ"
          severity="secondary"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          [label]="lead() ? 'Lưu ' + percent() + '%' : 'Ghi nhật ký'"
          [loading]="busy()"
          [disabled]="!canSave()"
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
    .full {
      width: 100%;
    }
    .muted {
      color: #4b5563;
    }
    .err {
      color: #b91c1c;
      margin: 8px 0 0;
    }
    .link {
      background: none;
      border: 0;
      color: inherit;
      text-decoration: underline;
      cursor: pointer;
      margin-left: 8px;
      font: inherit;
    }
    :host ::ng-deep .slider {
      margin: 12px 6px;
    }
    :host ::ng-deep .msg {
      display: block;
      margin-top: 12px;
    }
  `,
})
export class ProgressUpdateDialog {
  readonly task = input.required<TaskDetail>();
  readonly visible = model(false);
  readonly saved = output<TaskDetail>();
  readonly reloadRequested = output<void>();

  private readonly api = inject(TaskApi);
  protected readonly lead = computed(() => this.task().role === 'CHU_TRI');
  /** Mỗi lần mở lại hộp thoại bắt đầu từ tiến độ hiện tại và ghi chú trống. */
  protected readonly percent = linkedSignal({
    source: () => [this.task(), this.visible()] as const,
    computation: ([t]) => t.progress,
  });
  protected readonly note = linkedSignal({ source: this.visible, computation: () => '' });
  protected readonly error = linkedSignal<boolean, { title: string; status: number } | null>({
    source: this.visible,
    computation: () => null,
  });
  protected readonly busy = signal(false);

  protected readonly decreased = computed(() => this.percent() < this.task().progress);
  protected readonly noteLabel = computed(() =>
    !this.lead()
      ? 'Nội dung nhật ký'
      : this.decreased()
        ? 'Kết quả đã làm, lý do giảm tiến độ (bắt buộc)'
        : 'Kết quả đã làm',
  );
  protected readonly canSave = computed(() =>
    this.lead()
      ? (this.percent() !== this.task().progress || !!this.note().trim()) &&
        (!this.decreased() || !!this.note().trim())
      : !!this.note().trim(),
  );

  protected save(): void {
    this.busy.set(true);
    this.error.set(null);
    this.api
      .updateProgress(this.task(), {
        percent: this.lead() ? this.percent() : this.task().progress,
        note: this.note().trim(),
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
          this.error.set({ title: p.title, status: p.status });
        },
      });
  }
}
