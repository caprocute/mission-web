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
import { ProgressBatchResult, TaskApi, TaskSummary, toProblem } from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';

interface Row {
  task: TaskSummary;
  percent: number;
  note: string;
}

/**
 * SCR-0503 "Cập nhật tiến độ hàng loạt" (đã chọn nhiều dòng) → `/api/v1/tasks/progress-batch` (POST), EVT-05 mỗi nhiệm vụ.
 * Chỉ nhận dòng người dùng chủ trì và nhiệm vụ đang mở (BR-06-03); giảm tiến độ phải ghi chú (BR-06-02, MSG-06-01).
 * Máy chủ xử lý độc lập từng dòng: dòng lỗi (409, 422, 403) hiện lý do ngay tại dòng, dòng khác vẫn lưu.
 */
@Component({
  selector: 'vt-bulk-progress-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, InputTextModule, MessageModule],
  template: `
    <p-dialog
      header="Cập nhật tiến độ hàng loạt"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(760px, 96vw)' }"
      [closeOnEscape]="true"
      [dismissableMask]="false"
      closeAriaLabel="Đóng"
    >
      @if (skipped().length) {
        <p class="muted">
          {{ skipped().length }} nhiệm vụ không cập nhật được ở đây (đồng chí là người phối hợp hoặc
          nhiệm vụ không còn mở) nên được bỏ qua.
        </p>
      }
      <table class="grid">
        <thead>
          <tr>
            <th scope="col">Nhiệm vụ</th>
            <th scope="col">Hiện tại</th>
            <th scope="col">Tiến độ mới</th>
            <th scope="col">Ghi chú</th>
          </tr>
        </thead>
        <tbody>
          @for (r of rows(); track r.task.id; let i = $index) {
            <tr>
              <td>
                <strong>{{ r.task.title }}</strong>
                <div class="muted small">{{ r.task.code }}</div>
                @if (resultOf(r.task.id); as res) {
                  @if (res.status < 300) {
                    <span class="ok">Đã lưu</span>
                  } @else {
                    <span class="err">{{ res.problem?.title }}</span>
                  }
                }
              </td>
              <td>{{ r.task.progress }}%</td>
              <td>
                <input
                  pInputText
                  type="number"
                  class="pct"
                  [id]="'bp-' + i"
                  min="0"
                  max="100"
                  step="5"
                  inputmode="numeric"
                  [attr.aria-label]="'Tiến độ mới ' + r.task.code"
                  [ngModel]="r.percent"
                  (ngModelChange)="setPercent(i, $event)"
                />
                %
              </td>
              <td>
                <input
                  pInputText
                  [attr.aria-label]="'Ghi chú ' + r.task.code"
                  [ngModel]="r.note"
                  (ngModelChange)="setNote(i, $event)"
                  [class.need]="r.percent < r.task.progress && !r.note.trim()"
                  maxlength="1000"
                  [placeholder]="r.percent < r.task.progress ? 'Bắt buộc khi giảm tiến độ' : ''"
                />
              </td>
            </tr>
          }
        </tbody>
      </table>
      @if (invalid()) {
        <p class="err msg" role="alert">Tiến độ mới thấp hơn lần trước. Vui lòng ghi chú lý do.</p>
      }
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" />
      }
      <ng-template #footer>
        <p-button
          label="Đóng"
          severity="secondary"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          [label]="'Lưu ' + changed().length + ' nhiệm vụ'"
          [loading]="busy()"
          [disabled]="!changed().length || invalid()"
          (onClick)="save()"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    .grid {
      width: 100%;
      border-collapse: collapse;
    }
    th,
    td {
      text-align: left;
      padding: 8px;
      border-bottom: 1px solid var(--p-content-border-color, #eee);
      vertical-align: top;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.85em;
    }
    .ok {
      color: var(--p-green-700, #15803d);
      font-size: 0.9em;
    }
    .err {
      color: var(--p-red-700, #b91c1c);
      font-size: 0.9em;
    }
    input.need {
      border-color: var(--p-red-600, #dc2626);
    }
    .pct {
      width: 5.5rem;
    }
    :host ::ng-deep .msg,
    .msg {
      display: block;
      margin-top: 12px;
    }
  `,
})
export class BulkProgressDialog {
  readonly tasks = input.required<TaskSummary[]>();
  readonly visible = model(false);
  readonly saved = output<TaskSummary[]>();

  private readonly api = inject(TaskApi);
  private readonly editable = (t: TaskSummary) =>
    t.role === 'CHU_TRI' && (t.status === 'DA_GIAO' || t.status === 'DANG_THUC_HIEN');

  protected readonly rows = linkedSignal<TaskSummary[], Row[]>({
    source: this.tasks,
    computation: (ts) =>
      ts.filter(this.editable).map((task) => ({ task, percent: task.progress, note: '' })),
  });
  protected readonly skipped = computed(() => this.tasks().filter((t) => !this.editable(t)));
  protected readonly changed = computed(() =>
    this.rows().filter((r) => r.percent !== r.task.progress || r.note.trim()),
  );
  protected readonly invalid = computed(() =>
    this.changed().some((r) => r.percent < r.task.progress && !r.note.trim()),
  );
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly results = linkedSignal<TaskSummary[], ProgressBatchResult[]>({
    source: this.tasks,
    computation: () => [],
  });

  protected resultOf(id: string) {
    return this.results().find((r) => r.taskId === id);
  }
  protected setPercent(i: number, v: number | string | null) {
    const n = Math.round(Number(v));
    const percent = Number.isFinite(n) ? Math.min(100, Math.max(0, n)) : 0;
    this.rows.update((rs) => rs.map((r, j) => (j === i ? { ...r, percent } : r)));
  }
  protected setNote(i: number, v: string) {
    this.rows.update((rs) => rs.map((r, j) => (j === i ? { ...r, note: v } : r)));
  }

  protected save(): void {
    this.busy.set(true);
    this.error.set(null);
    const items = this.changed().map((r) => ({
      taskId: r.task.id,
      rowVersion: r.task.rowVersion,
      percent: r.percent,
      note: r.note.trim(),
    }));
    this.api.updateProgressBatch(items).subscribe({
      next: (res) => {
        this.busy.set(false);
        this.results.set(res);
        const ok = res.filter((r) => r.status < 300 && r.task).map((r) => r.task!);
        // dòng đã lưu nhận bản mới (rowVersion mới) để lần lưu sau không bị 409
        this.rows.update((rs) =>
          rs.map((r) => {
            const u = ok.find((x) => x.id === r.task.id);
            return u ? { task: u, percent: u.progress, note: '' } : r;
          }),
        );
        this.saved.emit(ok);
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(toProblem(e).title);
      },
    });
  }
}
