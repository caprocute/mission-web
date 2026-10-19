import { Component, input } from '@angular/core';
import { OkrReviewCheck } from '@vt/api-client';

/** Nhãn kết quả kiểm tra tự động (Đạt, cảnh báo, lỗi) của SCR-0203, màu đạt tương phản WCAG AA. */
@Component({
  selector: 'vt-check-tag',
  template: `<span class="tag" [class]="'t-' + check().tone">{{ check().label }}</span>`,
  styles: `
    .tag {
      display: inline-block;
      border-radius: 10px;
      padding: 2px 10px;
      font-size: 0.8125rem;
      font-weight: 700;
      white-space: nowrap;
    }
    .t-DAT {
      background: #e7f6ec;
      color: #166534;
    }
    .t-CANH_BAO {
      background: #fef6e4;
      color: #7c4a03;
    }
    .t-LOI {
      background: #fdecea;
      color: #991b1b;
    }
  `,
})
export class CheckTag {
  readonly check = input.required<OkrReviewCheck>();
}
