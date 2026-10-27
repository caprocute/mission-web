/*
 * "Nhiệm vụ / Chờ duyệt" — hàng chờ của người giao việc, lãnh đạo đơn vị:
 * - đăng ký nhiệm vụ của cán bộ (QT-05 bước 1c, bước 3; TL-02 SCR-0501 "Đường vào: menu Nhiệm vụ"):
 *   `GET /tasks?status=NHAP&source=DANG_KY`, quyền SCR-0501.giao;
 * - nhiệm vụ có đề nghị gia hạn, điều chỉnh chờ duyệt (SCR-0601 "Duyệt đề nghị", QT-08 bước 0 cần xử
 *   lý hết trước khi khoá kỳ): `GET /tasks?pendingChangeRequest=true`, quyền SCR-0601.duyet-de-nghi.
 * Mỗi dòng mở SCR-0601, nơi người duyệt ra quyết định; danh sách chỉ là lối vào, máy chủ lọc theo
 * phạm vi đơn vị (OPA) và kiểm quyền duyệt khi ghi.
 */
import { Component, computed, inject, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { TaskApi, TaskSummary, toProblem } from '@vt/api-client';
import { MessageModule } from 'primeng/message';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TASK_CAN } from '../task-permissions';
import { formatDate } from '../task-ui';
import { VtSkeletonComponent } from '@vt/design-system';

type Queue = 'DANG_KY' | 'DE_NGHI';

@Component({
  selector: 'vt-approvals-page',
  imports: [VtSkeletonComponent, FormsModule, RouterLink, MessageModule, SelectButtonModule],
  template: `
    <h1>Chờ duyệt</h1>
    <p class="muted">Đăng ký nhiệm vụ và đề nghị gia hạn, điều chỉnh đang chờ đồng chí duyệt.</p>
    @if (queues().length > 1) {
      <p-selectbutton
        [options]="queues()"
        optionLabel="label"
        optionValue="value"
        [ngModel]="queue()"
        (ngModelChange)="queue.set($event)"
        [allowEmpty]="false"
        ariaLabelledBy="q-lbl"
      />
      <span id="q-lbl" class="sr-only">Loại việc chờ duyệt</span>
    }

    @if (list.error()) {
      <p-message severity="error" styleClass="msg">
        <span>{{ error() }}</span>
        <button type="button" class="link" (click)="list.reload()">Thử lại</button>
      </p-message>
    } @else if (list.isLoading() && !rows().length) {
      <vt-skeleton variant="list" [rows]="5" label="Đang tải danh sách chờ duyệt" />
    } @else if (!rows().length) {
      <p class="muted empty">
        {{
          queue() === 'DANG_KY'
            ? 'Không có nhiệm vụ đăng ký nào chờ duyệt.'
            : 'Không có đề nghị gia hạn, điều chỉnh nào chờ duyệt.'
        }}
      </p>
    } @else {
      <table class="tbl">
        <caption class="sr-only">
          {{
            queue() === 'DANG_KY' ? 'Nhiệm vụ đăng ký chờ duyệt' : 'Nhiệm vụ có đề nghị chờ duyệt'
          }}
        </caption>
        <thead>
          <tr>
            <th scope="col">Mã</th>
            <th scope="col">Tên nhiệm vụ</th>
            <th scope="col">{{ queue() === 'DANG_KY' ? 'Hạn đề xuất' : 'Hạn hiện tại' }}</th>
            <th scope="col">Số lượng</th>
            <th scope="col"><span class="sr-only">Thao tác</span></th>
          </tr>
        </thead>
        <tbody>
          @for (t of rows(); track t.id) {
            <tr>
              <td>{{ t.code }}</td>
              <td>
                <a [routerLink]="['/tasks', t.id]">{{ t.title }}</a>
              </td>
              <td>{{ date(t.dueDate) }}</td>
              <td>{{ t.plannedQuantity }} {{ t.unit }}</td>
              <td>
                <a
                  [routerLink]="['/tasks', t.id]"
                  class="act"
                  [attr.aria-label]="'Xem xét ' + t.title"
                  >Xem xét</a
                >
              </td>
            </tr>
          }
        </tbody>
      </table>
    }
  `,
  styles: `
    :host {
      display: block;
      --p-togglebutton-color: #374151;
    }
    h1 {
      font-size: 1.5rem;
      margin: 0 0 4px;
    }
    .muted {
      color: #4b5563;
    }
    .empty {
      margin-top: 16px;
    }
    .link {
      background: none;
      border: 0;
      color: inherit;
      text-decoration: underline;
      cursor: pointer;
      margin-left: 8px;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
      margin-top: 16px;
      background: var(--p-content-background, #fff);
    }
    .tbl th {
      text-align: left;
      color: #4b5563;
      font-weight: 500;
    }
    .tbl th,
    .tbl td {
      padding: 10px 8px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
      vertical-align: top;
    }
    .act {
      font-weight: 600;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
    @media (max-width: 800px) {
      .tbl th:nth-child(4),
      .tbl td:nth-child(4) {
        display: none;
      }
    }
  `,
})
export class ApprovalsPage {
  private readonly api = inject(TaskApi);
  protected readonly can = inject(TASK_CAN);

  /** Chỉ hiện hàng chờ mà vai trò có quyền duyệt (YC-PT-21); tuyến đã chặn khi không có hàng nào. */
  protected readonly queues = computed(() => {
    const out: { value: Queue; label: string }[] = [];
    if (this.can('SCR-0501.giao')) out.push({ value: 'DANG_KY', label: 'Đăng ký nhiệm vụ' });
    if (this.can('SCR-0601.duyet-de-nghi'))
      out.push({ value: 'DE_NGHI', label: 'Đề nghị gia hạn, điều chỉnh' });
    return out;
  });
  protected readonly queue = linkedSignal<Queue | undefined>(() => this.queues()[0]?.value);

  protected readonly list = rxResource({
    params: () => (this.queue() ? { q: this.queue()! } : undefined),
    stream: ({ params }) =>
      params.q === 'DANG_KY'
        ? this.api.unitTasks({ status: ['NHAP'], source: 'DANG_KY', limit: 100 })
        : this.api.unitTasks({ pendingChangeRequest: true, includeSubunits: true, limit: 100 }),
  });
  protected readonly rows = computed<TaskSummary[]>(() => this.list.value()?.items ?? []);
  protected readonly error = computed(() => toProblem(this.list.error()).title);

  protected date(d: string) {
    return formatDate(d, true);
  }
}
