import { Component, computed, inject, input, linkedSignal, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TASK_GROUP_LABEL, TaskApi, TaskDetail, TaskSummary, toProblem } from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { SliderModule } from 'primeng/slider';
import { TextareaModule } from 'primeng/textarea';
import { TASK_CAN } from '../../task-permissions';
import { formatDate } from '../../task-ui';

/**
 * SCR-0503 vùng 3: xem nhanh nhiệm vụ đang chọn và cập nhật nhanh tiến độ ngay trên danh sách.
 * - Lưu tiến độ: nhiệm vụ đang thực hiện, người chủ trì đổi phần trăm (BR-06-03); người phối hợp chỉ ghi nhật ký.
 * - Giảm tiến độ bắt buộc ghi chú (BR-06-02, MSG-06-01); máy chủ vẫn kiểm lại.
 * - Nhận việc: nhiệm vụ `DA_GIAO`, người chủ trì → `/api/v1/tasks/{id}/accept` (EVT-06).
 * - Lưu thành công hiện MSG-05-13; 409 hiện thông báo xung đột và nút tải lại.
 */
@Component({
  selector: 'vt-task-preview-panel',
  imports: [FormsModule, RouterLink, ButtonModule, MessageModule, SliderModule, TextareaModule],
  template: `
    @let t = task();
    <section class="card" aria-labelledby="preview-title">
      <h2 id="preview-title">
        <span class="code">{{ t.code }}</span>
      </h2>
      <p class="title">{{ t.title }}</p>
      <dl>
        <dt>Sản phẩm</dt>
        <dd>{{ t.plannedQuantity }} {{ t.unit }} · {{ t.doneQuantity }} đã xác nhận</dd>
        <dt>Người giao</dt>
        <dd>{{ t.assignerName }}</dd>
        <dt>Nhóm</dt>
        <dd>{{ groupLabel[t.taskGroup] }}</dd>
        @if (t.keyResultTitle) {
          <dt>Đóng góp</dt>
          <dd>{{ t.keyResultTitle }}</dd>
        }
        <dt>Hạn</dt>
        <dd>{{ date(t.dueDate) }}</dd>
        <dt>Điểm nghẽn</dt>
        <dd [class.warn]="t.openBlockerCount > 0">
          {{ t.openBlockerCount ? t.openBlockerCount + ' đang mở' : 'Không có' }}
        </dd>
      </dl>

      @if (canUpdate()) {
        @if (t.role === 'CHU_TRI') {
          <label for="pv-pct"
            >Tiến độ: <strong>{{ percent() }}%</strong></label
          >
          <p-slider
            inputId="pv-pct"
            [ngModel]="percent()"
            (ngModelChange)="percent.set($event)"
            [step]="5"
            styleClass="slider"
            [ariaLabel]="'Tiến độ ' + t.code"
          />
        } @else {
          <p class="muted small">
            Đồng chí là người phối hợp: ghi nhật ký, không đổi phần trăm tiến độ.
          </p>
        }
        @if (needNote()) {
          <label for="pv-note">{{
            t.role === 'CHU_TRI' ? 'Ghi chú lý do giảm tiến độ' : 'Nội dung nhật ký'
          }}</label>
          <textarea
            id="pv-note"
            pTextarea
            rows="2"
            maxlength="1000"
            class="full"
            [ngModel]="note()"
            (ngModelChange)="note.set($event)"
          ></textarea>
        }
      }

      @if (error(); as e) {
        <p-message severity="error" styleClass="msg">
          <span>{{ e.title }}</span>
          @if (e.status === 409) {
            <button type="button" class="link" (click)="reloadRequested.emit()">Tải lại</button>
          }
        </p-message>
      }
      @if (info(); as m) {
        <p-message severity="success" [text]="m" styleClass="msg" role="status" />
      }

      <div class="actions">
        <a
          pButton
          [routerLink]="[t.id]"
          [outlined]="true"
          severity="secondary"
          label="Mở chi tiết"
        ></a>
        @if (canAccept()) {
          <p-button label="Nhận việc" icon="pi pi-check" [loading]="busy()" (onClick)="accept()" />
        }
        @if (canUpdate()) {
          <p-button
            [label]="t.role === 'CHU_TRI' ? 'Lưu ' + percent() + '%' : 'Ghi nhật ký'"
            [loading]="busy()"
            [disabled]="!dirty() || (needNote() && !note().trim())"
            (onClick)="save()"
          />
        }
      </div>
    </section>
  `,
  styles: `
    .card {
      background: var(--vt-color-surface, #fff);
      border-radius: var(--vt-radius-card, 8px);
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
    }
    h2 {
      margin: 0;
      font-size: 1.1rem;
    }
    .title {
      font-weight: 600;
      margin: 8px 0 12px;
    }
    dl {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 6px 16px;
      margin: 0 0 12px;
    }
    dt {
      color: #4b5563;
    }
    dd {
      margin: 0;
    }
    .warn {
      color: var(--p-orange-700, #c2410c);
      font-weight: 600;
    }
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
    .small {
      font-size: 0.9em;
    }
    .actions {
      display: flex;
      justify-content: space-between;
      gap: 8px;
      flex-wrap: wrap;
      margin-top: 12px;
    }
    .actions > * {
      min-height: var(--vt-min-touch, 40px);
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
      margin-top: 12px;
      display: block;
    }
  `,
})
export class TaskPreviewPanel {
  readonly task = input.required<TaskSummary>();
  readonly saved = output<TaskDetail>();
  readonly reloadRequested = output<void>();

  private readonly api = inject(TaskApi);
  private readonly can = inject(TASK_CAN);
  protected readonly groupLabel = TASK_GROUP_LABEL;

  /** Ghi chú, lỗi, thông báo chỉ đặt lại khi chọn nhiệm vụ khác, không đặt lại khi nhận bản mới của cùng nhiệm vụ. */
  private readonly taskId = computed(() => this.task().id);
  protected readonly percent = linkedSignal(() => this.task().progress);
  protected readonly note = linkedSignal({ source: this.taskId, computation: () => '' });
  protected readonly busy = signal(false);
  protected readonly error = linkedSignal<string, { title: string; status: number } | null>({
    source: this.taskId,
    computation: () => null,
  });
  protected readonly info = linkedSignal<string, string | null>({
    source: this.taskId,
    computation: () => null,
  });

  protected readonly canUpdate = computed(
    () =>
      this.can('SCR-0503.tien-do') && ['DA_GIAO', 'DANG_THUC_HIEN'].includes(this.task().status),
  );
  protected readonly canAccept = computed(
    () =>
      this.can('SCR-0503.nhan-viec') &&
      this.task().status === 'DA_GIAO' &&
      this.task().role === 'CHU_TRI',
  );
  /** Người phối hợp luôn ghi nội dung; người chủ trì chỉ phải ghi khi giảm tiến độ (BR-06-02). */
  protected readonly needNote = computed(
    () => this.task().role === 'PHOI_HOP' || this.percent() < this.task().progress,
  );
  protected readonly dirty = computed(
    () => this.percent() !== this.task().progress || !!this.note().trim(),
  );

  protected date(d: string) {
    return formatDate(d, true);
  }

  protected save(): void {
    const t = this.task();
    this.run(
      this.api.updateProgress(t, { percent: this.percent(), note: this.note().trim() }),
      (u) => `Đã lưu tiến độ ${u.progress}%.`,
    );
  }

  protected accept(): void {
    this.run(
      this.api.accept(this.task()),
      () => 'Đã nhận việc. Nhiệm vụ chuyển sang Đang thực hiện.',
    );
  }

  private run(req: ReturnType<TaskApi['accept']>, msg: (t: TaskDetail) => string): void {
    this.busy.set(true);
    this.error.set(null);
    this.info.set(null);
    req.subscribe({
      next: (u) => {
        this.busy.set(false);
        this.note.set('');
        this.info.set(msg(u));
        this.saved.emit(u);
      },
      error: (e) => {
        this.busy.set(false);
        const p = toProblem(e);
        this.error.set({ title: p.title, status: p.status });
      },
    });
  }
}
