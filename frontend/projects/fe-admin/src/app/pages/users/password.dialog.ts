import { Component, input, model, signal } from '@angular/core';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';

/**
 * Hộp hiện mật khẩu tạm thời một lần sau khi tạo tài khoản hoặc đặt lại mật khẩu (SCR-0108, SCR-0109;
 * BS-04). Mật khẩu không lưu ở đâu khác; người dùng phải đổi mật khẩu ở lần đăng nhập đầu.
 */
@Component({
  selector: 'vt-password-dialog',
  imports: [ButtonModule, DialogModule],
  template: `
    <p-dialog
      header="Mật khẩu tạm thời"
      [modal]="true"
      [(visible)]="visible"
      [closable]="true"
      [style]="{ width: 'min(480px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p>
        Mật khẩu tạm thời của tài khoản <strong>{{ username() }}</strong
        >:
      </p>
      <p class="pw" aria-live="polite">
        <code>{{ password() }}</code>
        <p-button
          [label]="copied() ? 'Đã sao chép' : 'Sao chép'"
          icon="pi pi-copy"
          severity="secondary"
          [outlined]="true"
          size="small"
          (onClick)="copy()"
        />
      </p>
      <p class="note">
        Mật khẩu chỉ hiện một lần. Hãy chuyển cho người dùng theo kênh an toàn; người dùng phải đổi
        mật khẩu khi đăng nhập lần đầu.
      </p>
      <ng-template #footer>
        <p-button label="Đã ghi nhận" (onClick)="visible.set(false)" />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    .pw {
      display: flex;
      gap: 12px;
      align-items: center;
      flex-wrap: wrap;
    }
    code {
      font-size: 1.15rem;
      padding: 6px 10px;
      background: #f1f5f9;
      border-radius: 6px;
      letter-spacing: 0.04em;
    }
    .note {
      color: #4b5563;
      font-size: 0.9em;
    }
  `,
})
export class PasswordDialog {
  readonly visible = model(false);
  readonly username = input('');
  readonly password = input('');
  protected readonly copied = signal(false);

  protected copy() {
    void navigator.clipboard?.writeText(this.password()).then(
      () => this.copied.set(true),
      () => this.copied.set(false),
    );
  }
}
