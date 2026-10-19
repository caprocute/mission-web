import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FieldWrapper, FormlyConfig, FormlyFieldConfig } from '@ngx-formly/core';

/** id của phần mô tả và phần lỗi, dùng cho `aria-describedby` của ô nhập. */
export const descId = (id: string | undefined) => `${id}-desc`;
export const errorId = (id: string | undefined) => `${id}-error`;

/**
 * Khung một trường: nhãn gắn `for`, dấu bắt buộc có chữ cho trình đọc màn hình, mô tả và lỗi
 * có id để ô nhập trỏ tới bằng `aria-describedby` (WCAG 1.3.1, 3.3.1).
 */
@Component({
  selector: 'vt-form-field',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="vt-field" [class.vt-field--invalid]="showError">
      @if (props.label && !props['hideLabel']) {
        <label class="vt-field__label" [attr.for]="id">
          {{ props.label }}
          @if (props.required) {
            <span class="vt-field__req" aria-hidden="true">*</span>
            <span class="vt-sr-only">(bắt buộc)</span>
          }
        </label>
      }
      <ng-container #fieldComponent />
      @if (props.description) {
        <p class="vt-field__desc" [id]="descId">{{ props.description }}</p>
      }
      @if (showError) {
        <p class="vt-field__error" [id]="errorId">
          <i class="pi pi-exclamation-circle" aria-hidden="true"></i>
          <span>{{ errorMessage }}</span>
        </p>
      }
    </div>
  `,
})
export class VtFormFieldWrapper extends FieldWrapper {
  private readonly config = inject(FormlyConfig);

  /**
   * Thông báo của lỗi đầu tiên, tính lại mỗi lần kiểm tra thay đổi (lỗi quy tắc CEL của trường
   * này có thể đổi khi người dùng sửa trường khác, không phát statusChanges).
   */
  get errorMessage(): string {
    const errors = this.formControl.errors ?? {};
    // lỗi máy chủ và lỗi quy tắc nghiệp vụ cụ thể hơn lỗi schema nên hiện trước
    const keys = Object.keys(errors).sort((a, b) => rank(a) - rank(b));
    const key = keys[0];
    if (!key) return '';
    const err = errors[key] as { message?: string } | undefined;
    if (err && typeof err === 'object' && err.message) return err.message;
    const msg = this.config.getValidatorMessage(key) as
      string | ((e: unknown, f: FormlyFieldConfig) => string) | undefined;
    return typeof msg === 'function' ? msg(err, this.field) : (msg ?? 'Giá trị chưa hợp lệ.');
  }
  get descId(): string {
    return descId(this.id);
  }
  get errorId(): string {
    return errorId(this.id);
  }
}

const rank = (k: string) => (k === 'server' ? 0 : k === 'rule' ? 2 : 1);

/** Nhóm trường theo `ui.sections`: fieldset có legend để trình đọc màn hình đọc tên nhóm. */
@Component({
  selector: 'vt-form-section',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <fieldset class="vt-section">
      @if (props.label) {
        <legend>{{ props.label }}</legend>
      }
      <ng-container #fieldComponent />
    </fieldset>
  `,
})
export class VtFormSectionWrapper extends FieldWrapper {}
