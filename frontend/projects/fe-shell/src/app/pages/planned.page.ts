import { Component, inject } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { MessageModule } from 'primeng/message';

/** Trang giữ chỗ cho remote chưa phát triển, ghi rõ giai đoạn và màn hình theo kế hoạch. */
@Component({
  selector: 'vt-planned-page',
  imports: [MessageModule],
  template: `
    <h1>{{ data['title'] }}</h1>
    <p-message severity="info">
      Phân hệ này thuộc {{ data['wave'] }} của kế hoạch phát triển phân hệ web. Màn hình theo TL-02: {{ data['screens'] }}.
    </p-message>
  `,
  styles: `h1 { font-size: 1.5rem; margin: 0 0 16px; }`,
})
export class PlannedPage {
  protected readonly data = inject(ActivatedRoute).snapshot.data;
}
