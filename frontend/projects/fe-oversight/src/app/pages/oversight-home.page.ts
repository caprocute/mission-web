import { Component } from '@angular/core';
import { MessageModule } from 'primeng/message';

/** Trang khung của remote fe-oversight (WEB-015); màn hình thật do các task QT-12, QT-13, QT-14 dựng. */
@Component({
  selector: 'vt-oversight-home-page',
  imports: [MessageModule],
  template: `
    <h1>Giám sát, hậu kiểm</h1>
    <p-message severity="info">
      Phân hệ Giám sát, hậu kiểm (remote fe-oversight) đã có khung, các màn hình đang được phát
      triển: SCR-1201, SCR-1202, SCR-1301, SCR-1302, SCR-1401.
    </p-message>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
  `,
})
export class OversightHomePage {}
