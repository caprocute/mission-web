import { Component, computed, inject, signal } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime } from 'rxjs';
import { TASK_STATUS_LABEL, TaskApi, TaskStatus, TaskSummary } from '@vt/api-client';
import { TableModule } from 'primeng/table';
import { TagModule } from 'primeng/tag';
import { ProgressBarModule } from 'primeng/progressbar';
import { InputTextModule } from 'primeng/inputtext';
import { SelectButtonModule } from 'primeng/selectbutton';
import { daysLeft, statusSeverity } from '../task-ui';

type Filter = 'ALL' | 'OPEN' | 'OVERDUE' | 'WAITING';

/** SCR-0503 Việc của tôi (desktop) thuộc QT-05. */
@Component({
  selector: 'vt-my-tasks-page',
  imports: [FormsModule, RouterLink, TableModule, TagModule, ProgressBarModule, InputTextModule, SelectButtonModule],
  template: `
    <header class="page-head">
      <div>
        <h1>Việc của tôi</h1>
        <p class="muted">Kỳ {{ period }} · {{ openCount() }} việc đang mở, {{ overdueCount() }} việc quá hạn</p>
      </div>
      <input pInputText type="search" placeholder="Tìm theo tên hoặc mã nhiệm vụ" [ngModel]="q()" (ngModelChange)="q.set($event)" aria-label="Tìm nhiệm vụ" />
    </header>

    <p-selectbutton [options]="filters" [ngModel]="filter()" (ngModelChange)="filter.set($event)" optionLabel="label" optionValue="value" aria-label="Lọc nhiệm vụ" />

    <p-table [value]="rows()" [loading]="loading()" dataKey="id" styleClass="mt" [tableStyle]="{ 'min-width': '48rem' }">
      <ng-template #header>
        <tr>
          <th>Nhiệm vụ</th><th>Vai trò</th><th>Hạn</th><th style="width: 14rem">Tiến độ</th><th>Trạng thái</th>
        </tr>
      </ng-template>
      <ng-template #body let-t>
        <tr>
          <td>
            <a [routerLink]="[t.id]" class="task-link">{{ t.title }}</a>
            <div class="muted small">{{ t.code }} · {{ t.unitName }} · giao bởi {{ t.assignerName }}</div>
          </td>
          <td>{{ t.role === 'CHU_TRI' ? 'Chủ trì' : 'Phối hợp' }}</td>
          <td [class.danger]="t.overdue">{{ t.dueDate }}<div class="muted small">{{ dueText(t) }}</div></td>
          <td><p-progressbar [value]="t.progress" [showValue]="true" /></td>
          <td><p-tag [value]="label(t.status, t.overdue)" [severity]="sev(t)" /></td>
        </tr>
      </ng-template>
      <ng-template #emptymessage>
        <tr><td colspan="5" class="muted">Không có nhiệm vụ nào phù hợp bộ lọc.</td></tr>
      </ng-template>
    </p-table>
  `,
  styles: `
    .page-head { display: flex; justify-content: space-between; align-items: flex-end; gap: 16px; flex-wrap: wrap; margin-bottom: 16px; }
    h1 { margin: 0; font-size: 1.5rem; }
    .muted { color: var(--p-text-muted-color, #6b7280); margin: 4px 0 0; }
    .small { font-size: 0.85em; }
    .danger { color: var(--p-red-600, #b91c1c); font-weight: 600; }
    .task-link { color: var(--vt-color-text); font-weight: 600; text-decoration: none; }
    .task-link:hover { color: var(--vt-color-primary); text-decoration: underline; }
    :host ::ng-deep .mt { margin-top: 16px; display: block; }
  `,
})
export class MyTasksPage {
  private readonly api = inject(TaskApi);
  protected readonly period = '2026-Q4';
  protected readonly filters: { label: string; value: Filter }[] = [
    { label: 'Đang mở', value: 'OPEN' }, { label: 'Quá hạn', value: 'OVERDUE' },
    { label: 'Chờ nghiệm thu', value: 'WAITING' }, { label: 'Tất cả', value: 'ALL' },
  ];
  protected readonly filter = signal<Filter>('OPEN');
  protected readonly q = signal('');
  /** Từ khoá tìm kiếm được trễ 250 ms để không gọi API theo từng phím gõ. */
  private readonly debouncedQ = toSignal(toObservable(this.q).pipe(debounceTime(250)), { initialValue: '' });

  private readonly page = rxResource({
    params: () => ({ f: this.filter(), q: this.debouncedQ() }),
    stream: ({ params: { f, q } }) => {
      const status: TaskStatus[] | undefined =
        f === 'OPEN' ? ['DA_GIAO', 'DANG_THUC_HIEN', 'CHO_NGHIEM_THU'] : f === 'WAITING' ? ['CHO_NGHIEM_THU'] : undefined;
      return this.api.myTasks({ status, overdueOnly: f === 'OVERDUE', q });
    },
  });
  protected readonly loading = this.page.isLoading;
  protected readonly rows = computed(() => (this.page.hasValue() ? this.page.value().items : []));
  protected readonly openCount = computed(() => this.rows().filter((t) => t.status !== 'HOAN_THANH').length);
  protected readonly overdueCount = computed(() => this.rows().filter((t) => t.overdue).length);

  protected label(s: TaskStatus, overdue: boolean) {
    return overdue && s !== 'HOAN_THANH' ? `${TASK_STATUS_LABEL[s]} · quá hạn` : TASK_STATUS_LABEL[s];
  }
  protected sev(t: TaskSummary) { return statusSeverity(t.status, t.overdue); }
  protected dueText(t: TaskSummary) {
    const n = daysLeft(t.dueDate);
    return n < 0 ? `Quá hạn ${-n} ngày` : n === 0 ? 'Hạn hôm nay' : `Còn ${n} ngày`;
  }
}
