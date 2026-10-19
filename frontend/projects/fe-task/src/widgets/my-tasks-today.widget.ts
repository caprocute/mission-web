import { Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { TaskApi } from '@vt/api-client';
import { CardModule } from 'primeng/card';
import { TagModule } from 'primeng/tag';
import { daysLeft, statusSeverity } from '../app/task-ui';

/** `vt.widget.myTasksToday`: khối "Việc của tôi hôm nay" trên SCR-0002. */
@Component({
  selector: 'vt-my-tasks-today-widget',
  imports: [RouterLink, CardModule, TagModule],
  template: `
    <p-card>
      <ng-template #title>Việc của tôi hôm nay</ng-template>
      <ul class="list">
        @for (t of top(); track t.id) {
          <li>
            <a [routerLink]="['/tasks', t.id]">{{ t.title }}</a>
            <p-tag [value]="due(t.dueDate)" [severity]="sev(t)" />
          </li>
        } @empty {
          <li class="muted">Không có việc cần xử lý hôm nay.</li>
        }
      </ul>
      <a routerLink="/tasks" class="more">Xem tất cả việc của tôi →</a>
    </p-card>
  `,
  styles: `
    .list { list-style: none; margin: 0; padding: 0; }
    li { display: flex; justify-content: space-between; align-items: center; gap: 8px; padding: 10px 0; border-bottom: 1px solid var(--p-content-border-color, #eee); min-height: var(--vt-min-touch, 40px); }
    li a { color: var(--vt-color-text); text-decoration: none; } li a:hover { color: var(--vt-color-primary); }
    .more { display: inline-block; margin-top: 12px; color: var(--vt-color-primary); text-decoration: none; }
    .muted { color: var(--p-text-muted-color, #6b7280); }
  `,
})
export class MyTasksTodayWidget {
  readonly limit = input(7);
  private readonly page = toSignal(inject(TaskApi).myTasks({ status: ['DA_GIAO', 'DANG_THUC_HIEN'] }));
  protected readonly top = computed(() =>
    (this.page()?.items ?? []).slice().sort((a, b) => a.dueDate.localeCompare(b.dueDate)).slice(0, this.limit()),
  );
  protected due(d: string) {
    const n = daysLeft(d);
    return n < 0 ? `Quá hạn ${-n} ngày` : n === 0 ? 'Hôm nay' : `Còn ${n} ngày`;
  }
  protected sev(t: { status: Parameters<typeof statusSeverity>[0]; overdue: boolean }) { return statusSeverity(t.status, t.overdue); }
}
