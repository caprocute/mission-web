import { Component } from '@angular/core';
import { MessageModule } from 'primeng/message';

/** Trang khung của remote __PROJECT__ (WEB-015); màn hình thật do các task __QT__ dựng. */
@Component({
  selector: 'vt-__SHORT__-home-page',
  imports: [MessageModule],
  template: `
    <h1>__TITLE__</h1>
    <p-message severity="info">
      Phân hệ __TITLE__ (remote __PROJECT__) đã có khung, các màn hình đang được phát triển:
      __SCREENS__.
    </p-message>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
  `,
})
export class __CLASS__HomePage {}
