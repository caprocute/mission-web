import { Component } from '@angular/core';
import { MessageModule } from 'primeng/message';

/** Trang khung của remote fe-dossier (WEB-015); màn hình thật do các task QT-11 dựng. */
@Component({
  selector: 'vt-dossier-home-page',
  imports: [MessageModule],
  template: `
    <h1>Kiểm điểm, hồ sơ số</h1>
    <p-message severity="info">
      Phân hệ Kiểm điểm, hồ sơ số (remote fe-dossier) đã có khung, các màn hình đang được phát
      triển: SCR-1101, SCR-1102.
    </p-message>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
  `,
})
export class DossierHomePage {}
