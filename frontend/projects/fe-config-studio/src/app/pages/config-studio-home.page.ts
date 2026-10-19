import { Component } from '@angular/core';
import { MessageModule } from 'primeng/message';

/** Trang khung của remote fe-config-studio (WEB-015); màn hình thật do các task QT-15, QT-18 dựng. */
@Component({
  selector: 'vt-config-studio-home-page',
  imports: [MessageModule],
  template: `
    <h1>Cấu hình nghiệp vụ</h1>
    <p-message severity="info">Chức năng này đang được chuẩn bị đưa vào sử dụng.</p-message>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
  `,
})
export class ConfigStudioHomePage {}
