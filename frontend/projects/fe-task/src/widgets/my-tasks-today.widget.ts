import { Component, computed, inject, input } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { TaskApi } from '@vt/api-client';
import { PermissionService } from '@vt/auth';
import { CardModule } from 'primeng/card';
import { TagModule } from 'primeng/tag';
import { daysLeft, statusSeverity } from '../app/task-ui';

/**
 * `vt.widget.myTasksToday`: khối "Việc của tôi hôm nay" trên SCR-0002. Chỉ hiện (và chỉ gọi API) khi
 * vai trò có `SCR-0503.xem`; liên kết sang chi tiết chỉ khi có `SCR-0601.xem` (WEB-055, wiki/15a).
 */
@Component({
  selector: 'vt-my-tasks-today-widget',
  imports: [RouterLink, CardModule, TagModule],
  template: `
    @if (canList) {
      <p-card>
        <ng-template #title>Việc của tôi hôm nay</ng-template>
        <!-- giữ chỗ đủ số dòng limit ngay từ đầu để liên kết "Xem tất cả" không nhảy khi dữ liệu về (CLS, WEB-059) -->
        <ul class="list" [style.min-height]="listMinHeight()">
          @for (t of top(); track t.id) {
            <li>
              @if (canDetail) {
                <a [routerLink]="['/tasks', t.id]">{{ t.title }}</a>
              } @else {
                <span>{{ t.title }}</span>
              }
              <p-tag [value]="due(t.dueDate)" [severity]="sev(t)" />
            </li>
          } @empty {
            <li class="muted">Không có việc cần xử lý hôm nay.</li>
          }
        </ul>
        <a routerLink="/tasks" class="more">Xem tất cả việc của tôi →</a>
      </p-card>
    }
  `,
  styles: `
    .list {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    li {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
      padding: 10px 0;
      border-bottom: 1px solid var(--p-content-border-color, #eee);
      min-height: var(--vt-min-touch, 40px);
    }
    li a {
      color: var(--vt-color-text);
      text-decoration: none;
    }
    li a:hover {
      color: var(--vt-color-primary-strong, #d1002d);
    }
    .more {
      display: inline-block;
      margin-top: 12px;
      color: var(--vt-color-primary-strong, #d1002d);
      text-decoration: none;
    }
    .muted {
      color: var(--p-text-muted-color, #566275);
    }
  `,
})
export class MyTasksTodayWidget {
  readonly limit = input(7);
  /** Mỗi dòng cao tối đa khoảng 4rem (tiêu đề hai dòng); đổi theo cỡ chữ của chế độ cao tuổi. */
  protected readonly listMinHeight = computed(() => `calc(${this.limit()} * 4rem)`);
  private readonly permissions = inject(PermissionService);
  protected readonly canList = this.permissions.can('SCR-0503.xem');
  protected readonly canDetail = this.permissions.can('SCR-0601.xem');
  // widget SDUI phải chịu được lỗi API: lỗi thì hiện như danh sách rỗng, không làm hỏng trang chủ
  private readonly page = toSignal(
    this.canList
      ? inject(TaskApi)
          .myTasks({ status: ['DA_GIAO', 'DANG_THUC_HIEN'] })
          .pipe(catchError(() => of(null)))
      : of(null),
  );
  protected readonly top = computed(() =>
    (this.page()?.items ?? [])
      .slice()
      .sort((a, b) => a.dueDate.localeCompare(b.dueDate))
      .slice(0, this.limit()),
  );
  protected due(d: string) {
    const n = daysLeft(d);
    return n < 0 ? `Quá hạn ${-n} ngày` : n === 0 ? 'Hôm nay' : `Còn ${n} ngày`;
  }
  protected sev(t: { status: Parameters<typeof statusSeverity>[0]; overdue: boolean }) {
    return statusSeverity(t.status, t.overdue);
  }
}
