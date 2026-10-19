import { Component } from '@angular/core';
import { MessageModule } from 'primeng/message';

/** Trang khung của remote fe-okr (WEB-015); màn hình thật do các task QT-02, QT-03 dựng. */
@Component({
  selector: 'vt-okr-home-page',
  imports: [MessageModule],
  template: `
    <h1>Mục tiêu và OKR</h1>
    <p-message severity="info">
      Phân hệ Mục tiêu và OKR (remote fe-okr) đã có khung, các màn hình đang được phát triển:
      SCR-0201, SCR-0202, SCR-0203, SCR-0301.
    </p-message>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
  `,
})
export class OkrHomePage {}
