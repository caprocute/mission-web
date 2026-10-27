import { Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import {
  NavigationCancel,
  NavigationEnd,
  NavigationError,
  Router,
  RouterOutlet,
} from '@angular/router';
import { filter, map, take } from 'rxjs';

/**
 * Gốc ứng dụng. Trong lúc tuyến đầu tiên còn chờ đăng nhập, phân công và tải remote, giữ nguyên
 * khung xương của khung trang như `index.html` (YC-PT-28) để màn hình không trắng trơn.
 */
@Component({
  selector: 'vt-root',
  imports: [RouterOutlet],
  template: `
    @if (!ready()) {
      <div class="vt-boot" role="status" aria-busy="true">
        <span class="vt-boot__sr">Đang mở ứng dụng</span>
        <div class="vt-boot__top" aria-hidden="true">
          <span class="vt-boot__b vt-boot__logo"></span>
          <span class="vt-boot__b vt-boot__search"></span>
          <span class="vt-boot__b vt-boot__avatar"></span>
        </div>
        <div class="vt-boot__body" aria-hidden="true">
          <div class="vt-boot__nav">
            @for (i of navRows; track i) {
              <span class="vt-boot__b"></span>
            }
          </div>
          <div class="vt-boot__main">
            <span class="vt-boot__b vt-boot__h1"></span>
            <span class="vt-boot__b vt-boot__p"></span>
            <div class="vt-boot__cards">
              @for (i of cards; track i) {
                <span class="vt-boot__b"></span>
              }
            </div>
            <span class="vt-boot__b vt-boot__panel"></span>
          </div>
        </div>
      </div>
    }
    <router-outlet />
  `,
})
export class App {
  protected readonly navRows = [0, 1, 2, 3, 4, 5];
  protected readonly cards = [0, 1, 2, 3];
  /** Tuyến đầu tiên đã xong (thành công, bị huỷ hay lỗi): bỏ khung xương khởi động. */
  protected readonly ready = toSignal(
    inject(Router).events.pipe(
      filter(
        (e) =>
          e instanceof NavigationEnd ||
          e instanceof NavigationCancel ||
          e instanceof NavigationError,
      ),
      take(1),
      map(() => true),
    ),
    { initialValue: false },
  );
}
