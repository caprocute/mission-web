import { Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';
import { ButtonModule } from 'primeng/button';

/** Trang lỗi thân thiện khi không mở được trang (lỗi điều hướng chưa xử lý, WEB-012). */
@Component({
  selector: 'vt-error-page',
  imports: [RouterLink, ButtonModule],
  template: `
    <section class="error-page">
      <h1>Chưa mở được trang này</h1>
      <p>
        Hệ thống gặp sự cố khi mở trang đồng chí yêu cầu. Đồng chí có thể thử lại hoặc quay về trang
        chủ; nếu vẫn gặp lỗi, vui lòng báo cán bộ hỗ trợ.
      </p>
      <div class="actions">
        <button
          pButton
          type="button"
          icon="pi pi-refresh"
          label="Thử lại"
          (click)="retry()"
        ></button>
        <a
          pButton
          routerLink="/"
          class="p-button-outlined"
          icon="pi pi-home"
          label="Về trang chủ"
        ></a>
      </div>
    </section>
  `,
  styles: `
    .error-page {
      max-width: 40rem;
      margin: 3rem auto;
      padding: 0 1rem;
      text-align: center;
    }
    h1 {
      font-size: 1.5rem;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      justify-content: center;
      gap: 0.75rem;
      margin-top: 1.5rem;
    }
  `,
})
export class ErrorPage {
  private readonly router = inject(Router);
  /** Đường dẫn vừa mở không được (trình xử lý lỗi điều hướng gửi kèm `state.from`). */
  private readonly from = (this.router.currentNavigation()?.extras.state?.['from'] ??
    globalThis.history?.state?.from) as string | undefined;

  protected retry(): void {
    void this.router.navigateByUrl(this.from && this.from.startsWith('/') ? this.from : '/');
  }
}
