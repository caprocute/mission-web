import { Component } from '@angular/core';
import { MessageModule } from 'primeng/message';

/** Trang khung của remote fe-evidence (WEB-015); màn hình thật do các task QT-07 dựng. */
@Component({
  selector: 'vt-evidence-home-page',
  imports: [MessageModule],
  template: `
    <h1>Sản phẩm và minh chứng</h1>
    <p-message severity="info">
      Phân hệ Sản phẩm và minh chứng (remote fe-evidence) đã có khung, các màn hình đang được phát
      triển: SCR-0701, SCR-0703.
    </p-message>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
  `,
})
export class EvidenceHomePage {}
