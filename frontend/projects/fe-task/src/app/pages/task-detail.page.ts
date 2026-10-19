import { Component, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TASK_STATUS_LABEL, TaskApi, TaskDetail, toProblem } from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { CardModule } from 'primeng/card';
import { SliderModule } from 'primeng/slider';
import { TextareaModule } from 'primeng/textarea';
import { TagModule } from 'primeng/tag';
import { MessageModule } from 'primeng/message';
import { statusSeverity } from '../task-ui';

/** SCR-0601 Chi tiết nhiệm vụ: cập nhật tiến độ và báo điểm nghẽn (QT-06). */
@Component({
  selector: 'vt-task-detail-page',
  imports: [FormsModule, RouterLink, ButtonModule, CardModule, SliderModule, TextareaModule, TagModule, MessageModule],
  template: `
    <a routerLink=".." class="back">← Việc của tôi</a>
    @if (task.error()) {
      <p-message severity="error" text="Không tải được nhiệm vụ" />
    } @else if (task.value(); as t) {
      <header class="head">
        <h1>{{ t.title }}</h1>
        <p-tag [value]="statusLabel(t)" [severity]="sev(t)" />
      </header>
      <p class="muted">{{ t.code }} · {{ t.unitName }} · hạn {{ t.dueDate }} · {{ t.role === 'CHU_TRI' ? 'Chủ trì' : 'Phối hợp' }}</p>

      <div class="cols">
        <p-card header="Nội dung">
          <p>{{ t.description }}</p>
          @if (t.sourceDoc) {
            <p class="muted">Căn cứ: {{ t.sourceDoc.number }} · {{ t.sourceDoc.title }}</p>
          }
          @if (t.blockers.length) {
            <h3>Điểm nghẽn</h3>
            <ul>@for (b of t.blockers; track b.id) { <li [class.done]="b.resolved">{{ b.content }}</li> }</ul>
          }
          <h3>Lịch sử tiến độ</h3>
          <ul class="log">
            @for (l of t.progressLog; track l.at) { <li><strong>{{ l.percent }}%</strong> · {{ l.note }} <span class="muted">({{ l.at.slice(0, 10) }})</span></li> }
            @empty { <li class="muted">Chưa có cập nhật.</li> }
          </ul>
        </p-card>

        @if (canUpdate(t)) {
          <p-card header="Cập nhật tiến độ">
            <label for="pct">Tiến độ: <strong>{{ percent() }}%</strong></label>
            <p-slider id="pct" [ngModel]="percent()" (ngModelChange)="percent.set($event)" [step]="5" styleClass="slider" />
            <label for="note">Kết quả đã làm</label>
            <textarea id="note" pTextarea rows="3" [ngModel]="note()" (ngModelChange)="note.set($event)" class="full"></textarea>
            @if (!theme.reducedSteps() || theme.mode() !== 'elderly') {
              <label for="blk">Điểm nghẽn (nếu có)</label>
              <textarea id="blk" pTextarea rows="2" [ngModel]="blocker()" (ngModelChange)="blocker.set($event)" class="full"></textarea>
            }
            @if (error()) { <p-message severity="error" [text]="error()!" /> }
            @if (saved()) { <p-message severity="success" text="Đã lưu tiến độ" /> }
            <p-button label="Lưu tiến độ" icon="pi pi-check" [loading]="saving()" [disabled]="!note().trim()" (onClick)="save(t)" styleClass="mt" />
          </p-card>
        }
      </div>
    }
  `,
  styles: `
    .back { text-decoration: none; color: var(--vt-color-primary); }
    .head { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; } h1 { font-size: 1.4rem; margin: 12px 0 4px; }
    .muted { color: var(--p-text-muted-color, #6b7280); }
    .cols { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 1fr); gap: 16px; margin-top: 16px; }
    @media (max-width: 900px) { .cols { grid-template-columns: 1fr; } }
    label { display: block; margin: 12px 0 6px; } .full { width: 100%; } .done { text-decoration: line-through; }
    :host ::ng-deep .slider { margin: 12px 4px; } :host ::ng-deep .mt { margin-top: 12px; }
    .log { padding-left: 18px; }
  `,
})
export class TaskDetailPage {
  readonly id = input.required<string>();
  private readonly api = inject(TaskApi);
  protected readonly theme = inject(ThemeService);

  protected readonly task = rxResource({ params: () => this.id(), stream: ({ params }) => this.api.detail(params) });
  /** Thanh trượt bắt đầu từ tiến độ hiện tại của nhiệm vụ. */
  protected readonly percent = linkedSignal(() => (this.task.hasValue() ? this.task.value().progress : 0));
  protected readonly note = signal('');
  protected readonly blocker = signal('');
  protected readonly saving = signal(false);
  protected readonly saved = signal(false);
  protected readonly error = signal<string | null>(null);

  protected statusLabel(t: TaskDetail) { return TASK_STATUS_LABEL[t.status] + (t.overdue ? ' · quá hạn' : ''); }
  protected sev(t: TaskDetail) { return statusSeverity(t.status, t.overdue); }
  protected canUpdate(t: TaskDetail) { return t.status === 'DA_GIAO' || t.status === 'DANG_THUC_HIEN'; }

  protected save(t: TaskDetail): void {
    this.saving.set(true);
    this.error.set(null);
    this.saved.set(false);
    this.api.updateProgress(t, { percent: this.percent(), note: this.note(), blocker: this.blocker() || undefined }).subscribe({
      next: (updated) => {
        this.task.set(updated);
        this.note.set('');
        this.blocker.set('');
        this.saving.set(false);
        this.saved.set(true);
      },
      error: (e) => {
        this.error.set(toProblem(e).title);
        this.saving.set(false);
      },
    });
  }
}
