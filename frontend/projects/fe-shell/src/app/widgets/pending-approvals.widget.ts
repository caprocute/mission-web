import { Component } from '@angular/core';
import { CardModule } from 'primeng/card';
import { BadgeModule } from 'primeng/badge';

/** `vt.widget.pendingApprovals`: hộp việc chờ duyệt (số liệu lấy từ workflow-service ở giai đoạn sau). */
@Component({
  selector: 'vt-pending-approvals-widget',
  imports: [CardModule, BadgeModule],
  template: `
    <p-card header="Chờ tôi duyệt">
      <ul>
        @for (i of items; track i.label) {
          <li>
            <span>{{ i.label }}</span
            ><p-badge [value]="i.count" />
          </li>
        }
      </ul>
    </p-card>
  `,
  styles: `
    ul {
      list-style: none;
      padding: 0;
      margin: 0;
    }
    li {
      display: flex;
      justify-content: space-between;
      padding: 10px 0;
      border-bottom: 1px solid var(--p-content-border-color, #eee);
    }
    li:last-child {
      border-bottom: 0;
    }
  `,
})
export class PendingApprovalsWidget {
  protected readonly items = [
    { label: 'Kế hoạch công tác quý', count: 3 },
    { label: 'Minh chứng chờ nghiệm thu', count: 5 },
    { label: 'Đề nghị gia hạn', count: 1 },
  ];
}
