import { Component } from '@angular/core';
import { MessageModule } from 'primeng/message';

/** Trang đầu của remote fe-appraisal cho vai trò chưa có màn hình đánh giá riêng (WEB-015). */
@Component({
  selector: 'vt-appraisal-home-page',
  imports: [MessageModule],
  template: `
    <h1>Đánh giá, xếp loại</h1>
    <p-message severity="info"> Chức năng này đang được chuẩn bị đưa vào sử dụng. </p-message>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
  `,
})
export class AppraisalHomePage {}
