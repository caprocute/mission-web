import { Component, ElementRef, effect, inject, signal, viewChild } from '@angular/core';
import { RUNTIME_CONFIG } from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { SessionService } from '../session.service';
import { closeModal, openModal } from './modal';

/**
 * Hộp MFA bước lên (TL-02 QT-00: "giao diện mở hộp MFA bước lên mà không làm mất dữ liệu đang
 * nhập"; BR-00-02; RFC 9470). Nút "Xác thực" mở cửa sổ Keycloak với `acr_values`; màn hình bên
 * dưới giữ nguyên, thao tác được gửi lại sau khi đạt mức yêu cầu.
 */
@Component({
  selector: 'vt-step-up-dialog',
  imports: [ButtonModule],
  template: `
    <dialog
      #dlg
      class="vt-modal"
      aria-labelledby="vt-stepup-title"
      aria-describedby="vt-stepup-desc"
      (cancel)="cancel($event)"
    >
      <h2 id="vt-stepup-title">
        <i class="pi pi-shield" aria-hidden="true"></i> Xác thực nhiều lớp
      </h2>
      <p id="vt-stepup-desc">{{ session.stepUpRequest()?.reason }}</p>
      @if (!authEnabled) {
        <!-- Chỉ hiện khi tắt đăng nhập (môi trường phát triển, chưa có Keycloak): bấm Xác thực là đạt -->
        <p class="meta">Bấm "Xác thực" để tiếp tục.</p>
      }
      @if (error(); as e) {
        <p class="error" role="alert">{{ e }}</p>
      }
      <div class="actions">
        <p-button type="button" label="Huỷ" severity="secondary" (onClick)="cancel()" />
        <p-button
          type="button"
          label="Xác thực"
          icon="pi pi-lock-open"
          [loading]="busy()"
          (onClick)="confirm()"
        />
      </div>
    </dialog>
  `,
  styleUrl: './modal.scss',
})
export class StepUpDialogComponent {
  protected readonly session = inject(SessionService);
  protected readonly authEnabled = inject(RUNTIME_CONFIG).auth.enabled;
  private readonly dlg = viewChild.required<ElementRef<HTMLDialogElement>>('dlg');
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    effect(() => {
      const d = this.dlg().nativeElement;
      if (this.session.stepUpRequest()) openModal(d);
      else closeModal(d);
    });
  }

  protected async confirm(): Promise<void> {
    this.busy.set(true);
    this.error.set(null);
    try {
      await this.session.confirmStepUp();
    } catch {
      this.error.set(
        'Chưa hoàn tất xác thực (cửa sổ bị chặn hoặc đã đóng). Đồng chí bấm "Xác thực" để thử lại.',
      );
    } finally {
      this.busy.set(false);
    }
  }

  protected cancel(ev?: Event): void {
    ev?.preventDefault();
    this.error.set(null);
    this.session.cancelStepUp();
  }
}
