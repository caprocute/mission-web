import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Gốc khi chạy __PROJECT__ độc lập (cổng __PORT__) để phát triển remote không cần shell. */
@Component({
  selector: 'vt-root',
  imports: [RouterOutlet],
  template: `<main id="main" style="padding: 24px; max-width: 1200px; margin: 0 auto">
    <router-outlet />
  </main>`,
})
export class App {}
