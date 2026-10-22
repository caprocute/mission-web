import { Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Gốc khi chạy fe-task độc lập (cổng 4201) để phát triển remote không cần shell. */
@Component({
  selector: 'vt-root',
  imports: [RouterOutlet],
  template: `<main style="padding: 24px; max-width: 1200px; margin: 0 auto">
    <router-outlet />
  </main>`,
})
export class App {}
