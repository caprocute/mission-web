import { Component } from '@angular/core';
import { MessageModule } from 'primeng/message';

/**
 * Trang khung của remote fe-appraisal (WEB-015); màn hình thật do các task QT-09, QT-10 dựng. Đã có
 * SCR-0901, SCR-0902; còn SCR-0903, SCR-0904, SCR-1001, SCR-1002 (wiki/16: trang giữ chỗ chỉ ghi tên
 * chức năng).
 */
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
