import { Component, input } from '@angular/core';
import { CardModule } from 'primeng/card';
import { ProgressBarModule } from 'primeng/progressbar';

/** `vt.widget.okrProgress`: tiến độ OKR (dữ liệu mẫu cho tới khi có fe-okr và MOD-01). */
@Component({
  selector: 'vt-okr-progress-widget',
  imports: [CardModule, ProgressBarModule],
  template: `
    <p-card [header]="scope() === 'unit' ? 'Tiến độ OKR của đơn vị' : 'Tiến độ OKR của tôi'">
      @for (o of objectives; track o.title) {
        <div class="row"><span>{{ o.title }}</span><p-progressbar [value]="o.progress" /></div>
      }
    </p-card>
  `,
  styles: `.row { display: grid; gap: 6px; margin-bottom: 14px; }`,
})
export class OkrProgressWidget {
  readonly scope = input<'me' | 'unit'>('me');
  protected readonly objectives = [
    { title: 'O1. Nâng cao chất lượng phục vụ người dân', progress: 72 },
    { title: 'O2. Hoàn thành chuyển đổi số nội bộ', progress: 55 },
    { title: 'O3. Kiện toàn tổ chức bộ máy', progress: 38 },
  ];
}
