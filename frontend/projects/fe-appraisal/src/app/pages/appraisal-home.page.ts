import { Component } from '@angular/core';
import { MessageModule } from 'primeng/message';

/** Trang khung của remote fe-appraisal (WEB-015); màn hình thật do các task QT-09, QT-10 dựng. */
@Component({
  selector: 'vt-appraisal-home-page',
  imports: [MessageModule],
  template: `
    <h1>Đánh giá, xếp loại</h1>
    <p-message severity="info">
      Phân hệ Đánh giá, xếp loại (remote fe-appraisal) đã có Phiếu tự đánh giá (SCR-0901); các màn
      hình đang được phát triển: SCR-0902 … SCR-0904, SCR-1001, SCR-1002.
    </p-message>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
  `,
})
export class AppraisalHomePage {}
