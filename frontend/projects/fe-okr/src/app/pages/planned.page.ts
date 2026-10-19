import { Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { MessageModule } from 'primeng/message';

/**
 * Trang giữ chỗ cho màn hình fe-okr chưa làm (SCR-0202 ở WEB-022, SCR-0203 ở WEB-023, SCR-0301 ở
 * WEB-024), để nút trên SCR-0201 không dẫn tới trang lỗi. Mỗi task thay tuyến tương ứng bằng màn hình thật.
 */
@Component({
  selector: 'vt-okr-planned-page',
  imports: [MessageModule, RouterLink],
  template: `
    <h1>{{ data['heading'] }}</h1>
    <p-message severity="info">
      Màn hình {{ data['screen'] }} đang được phát triển ({{ data['task'] }}).
      <a routerLink="..">Quay lại cây mục tiêu</a>
    </p-message>
  `,
  styles: `
    h1 {
      font-size: 1.4rem;
      margin: 0 0 16px;
    }
  `,
})
export class OkrPlannedPage {
  protected readonly data = inject(ActivatedRoute).snapshot.data;
}
