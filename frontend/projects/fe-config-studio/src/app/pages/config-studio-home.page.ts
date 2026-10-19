import { Component } from '@angular/core';
import { MessageModule } from 'primeng/message';

/** Trang khung của remote fe-config-studio (WEB-015); màn hình thật do các task QT-15, QT-18 dựng. */
@Component({
  selector: 'vt-config-studio-home-page',
  imports: [MessageModule],
  template: `
    <h1>Config Studio</h1>
    <p-message severity="info">
      Phân hệ Config Studio (remote fe-config-studio) đã có khung, các màn hình đang được phát
      triển: SCR-1501 … SCR-1504, SCR-1801, SCR-1802.
    </p-message>
  `,
  styles: `
    h1 {
      font-size: 1.5rem;
      margin: 0 0 16px;
    }
  `,
})
export class ConfigStudioHomePage {}
