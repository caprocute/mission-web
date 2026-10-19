import { Component, input, linkedSignal, model, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { TextareaModule } from 'primeng/textarea';

/** Hộp ghi lý do dùng cho "Bỏ nhiệm vụ này" và "Bỏ qua văn bản" (SCR-0502). */
@Component({
  selector: 'vt-reason-dialog',
  imports: [FormsModule, ButtonModule, DialogModule, TextareaModule],
  template: `
    <p-dialog
      [header]="header()"
      [modal]="true"
      [(visible)]="visible"
      [style]="{ width: 'min(520px, 95vw)' }"
      [closeOnEscape]="!busy()"
      closeAriaLabel="Đóng"
    >
      @if (intro()) {
        <p class="intro">{{ intro() }}</p>
      }
      <label for="reason-text">Lý do <span class="req">*</span></label>
      <textarea
        id="reason-text"
        pTextarea
        rows="3"
        maxlength="1000"
        class="full"
        [ngModel]="text()"
        (ngModelChange)="text.set($event)"
        [attr.aria-invalid]="!!error()"
        [attr.aria-describedby]="error() ? 'reason-err' : null"
      ></textarea>
      @if (error()) {
        <small id="reason-err" class="err">{{ error() }}</small>
      }
      <ng-template #footer>
        <p-button
          label="Đóng"
          severity="contrast"
          [outlined]="true"
          (onClick)="visible.set(false)"
        />
        <p-button
          [label]="confirmLabel()"
          [disabled]="!text().trim()"
          [loading]="busy()"
          (onClick)="confirmed.emit(text().trim())"
        />
      </ng-template>
    </p-dialog>
  `,
  styles: `
    label {
      display: block;
      font-weight: 500;
      margin-bottom: 6px;
    }
    .full {
      width: 100%;
    }
    .req,
    .err {
      color: #b91c1c;
    }
    .intro {
      margin-top: 0;
    }
  `,
})
export class ReasonDialog {
  readonly visible = model(false);
  readonly header = input.required<string>();
  readonly intro = input('');
  readonly confirmLabel = input('Xác nhận');
  readonly busy = input(false);
  readonly error = input<string | null>(null);
  readonly confirmed = output<string>();
  /** Xoá lý do cũ mỗi lần mở lại. */
  protected readonly text = linkedSignal({ source: this.visible, computation: () => '' });
}
