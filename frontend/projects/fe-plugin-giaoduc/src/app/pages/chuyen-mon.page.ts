import { Component, inject } from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { TASK_STATUS_LABEL, TaskStatus } from '@vt/api-client';
import { PermissionService, screenKeys } from '@vt/auth';
import { MessageModule } from 'primeng/message';
import { CHI_SO, nhiemVuCuaToi } from '../chuyen-mon-data';

/**
 * Trang "Chuyên môn giáo dục" của plugin (ADR-05 lớp 4, WEB-051): tổng hợp nhiệm vụ chuyên môn của
 * người dùng trong kỳ từ API công bố `/api/v1/tasks/mine`; mở chi tiết ở màn hình lõi `/tasks/:id`
 * khi vai trò có SCR-0601.
 */
@Component({
  selector: 'vt-giaoduc-chuyen-mon-page',
  imports: [RouterLink, DatePipe, MessageModule],
  template: `
    <h1>Chuyên môn giáo dục</h1>
    <p class="lead">Theo dõi nhiệm vụ chuyên môn của đồng chí trong kỳ đánh giá hiện tại.</p>
    @if (data.error()) {
      <p-message severity="warn"
        >Chưa tải được danh sách nhiệm vụ, đồng chí vui lòng thử lại sau.</p-message
      >
    }
    <ul class="stats" aria-label="Số liệu nhiệm vụ chuyên môn">
      @for (c of chiSo; track c.key) {
        <li>
          <span class="label">{{ c.label }}</span>
          <span class="value">{{ data.value()?.counts?.[c.key] ?? '—' }}</span>
        </li>
      }
    </ul>
    <table class="list">
      <caption class="sr-only">
        Nhiệm vụ chuyên môn trong kỳ
      </caption>
      <thead>
        <tr>
          <th scope="col">Nhiệm vụ</th>
          <th scope="col">Trạng thái</th>
          <th scope="col">Hạn hoàn thành</th>
        </tr>
      </thead>
      <tbody>
        @for (t of data.value()?.items ?? []; track t.id) {
          <tr>
            <td>
              @if (canDetail) {
                <a [routerLink]="['/tasks', t.id]">{{ t.title }}</a>
              } @else {
                {{ t.title }}
              }
            </td>
            <td>{{ statusLabel(t.status) }}</td>
            <td>{{ t.dueDate | date: 'dd/MM/yyyy' }}</td>
          </tr>
        } @empty {
          @if (!data.isLoading()) {
            <tr>
              <td colspan="3" class="muted">Chưa có nhiệm vụ trong kỳ.</td>
            </tr>
          }
        }
      </tbody>
    </table>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 4px;
    }
    .lead,
    .muted {
      color: var(--vt-color-text-muted);
    }
    .stats {
      list-style: none;
      padding: 0;
      margin: 16px 0;
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
      gap: 12px;
    }
    .stats li {
      background: var(--vt-color-surface);
      border: 1px solid var(--vt-color-border);
      border-radius: var(--vt-radius-card);
      padding: 12px 16px;
      display: flex;
      flex-direction: column;
    }
    .value {
      font-size: 1.75rem;
      font-weight: 600;
    }
    .list {
      width: 100%;
      border-collapse: collapse;
      background: var(--vt-color-surface);
    }
    th,
    td {
      text-align: left;
      padding: 10px 12px;
      border-bottom: 1px solid var(--vt-color-border);
    }
    td a {
      display: inline-flex;
      align-items: center;
      min-height: var(--vt-min-touch, 40px);
      color: var(--vt-color-primary-strong);
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class ChuyenMonPage {
  protected readonly chiSo = CHI_SO;
  protected readonly data = nhiemVuCuaToi(50);
  protected readonly canDetail = inject(PermissionService).canAny(...screenKeys('SCR-0601'));

  protected statusLabel(s: TaskStatus): string {
    return TASK_STATUS_LABEL[s] ?? '';
  }
}
