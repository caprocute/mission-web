import { Component, effect, input, model, output, signal, untracked } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { MessageModule } from 'primeng/message';
import { TextareaModule } from 'primeng/textarea';

/**
 * Hộp nhập lý do bắt buộc khi từ chối đề nghị cấp vai trò, thu hồi vai trò hoặc thu hồi uỷ quyền
 * (WEB-058, lý do ghi vào bản ghi và nhật ký kiểm toán). Trang cha gọi API và báo lỗi qua `error`.
 */
@Component({
  selector: 'vt-reason-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, MessageModule, TextareaModule],
  template: `
    <p-dialog
      [header]="header()"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(520px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p class="lead">{{ lead() }}</p>
      <label for="rs-text">Lý do (bắt buộc)</label>
      <textarea
        id="rs-text"
        pTextarea
        rows="3"
        maxlength="500"
        class="full"
        [ngModel]="text()"
        (ngModelChange)="text.set($event)"
      ></textarea>
      @if (error(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
      }
      <ng-template #footer>
        <p-button label="Huỷ" severity="secondary" [text]="true" (onClick)="visible.set(false)" />
        <p-button
          [label]="confirmLabel()"
          severity="danger"
          [disabled]="text().trim().length < 5"
          [loading]="busy()"
          (onClick)="confirmed.emit(text().trim())"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    label {
      display: block;
      margin-bottom: 4px;
      font-weight: 500;
    }
    .full {
      width: 100%;
    }
    .lead {
      margin: 0 0 12px;
    }
    :host ::ng-deep .msg {
      margin-top: 12px;
      width: 100%;
    }
  `,
})
export class ReasonDialog {
  readonly visible = model(false);
  readonly header = input.required<string>();
  readonly lead = input('');
  readonly confirmLabel = input('Xác nhận');
  readonly busy = input(false);
  readonly error = input<string | null>(null);
  readonly confirmed = output<string>();

  protected readonly text = signal('');

  constructor() {
    effect(() => {
      if (this.visible()) untracked(() => this.text.set(''));
    });
  }
}
