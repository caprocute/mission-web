import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  InjectionToken,
  OnInit,
  inject,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ReactiveFormsModule } from '@angular/forms';
import { FieldType, FieldTypeConfig, FormlyAttributes } from '@ngx-formly/core';
import { Observable, isObservable, of } from 'rxjs';
import { descId, errorId } from './field-wrapper';

export interface CatalogOption {
  value: string | number;
  label: string;
  disabled?: boolean;
}

/**
 * Nguồn danh mục cho `vt.input.catalog` khi schema khai báo `x-catalog` (mã danh mục). Ứng dụng
 * cung cấp hàm gọi API danh mục; không cung cấp thì dùng `enum`/`oneOf` của schema.
 */
export const VT_FORM_CATALOGS = new InjectionToken<
  (code: string) => Observable<CatalogOption[]> | CatalogOption[]
>('vt.formCatalogs');

/** Thuộc tính ARIA chung: trỏ tới mô tả và lỗi, đánh dấu sai và bắt buộc. */
abstract class VtFieldBase extends FieldType<FieldTypeConfig> {
  get describedBy(): string | null {
    const ids = [];
    if (this.props.description) ids.push(descId(this.id));
    if (this.showError) ids.push(errorId(this.id));
    return ids.length ? ids.join(' ') : null;
  }
}

/** `vt.input.text`: ô chữ một dòng. */
@Component({
  selector: 'vt-field-text',
  imports: [ReactiveFormsModule, FormlyAttributes],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<input
    class="vt-input"
    type="text"
    [formControl]="formControl"
    [formlyAttributes]="field"
    [attr.maxlength]="props.maxLength ?? null"
    [attr.aria-describedby]="describedBy"
    [attr.aria-invalid]="showError"
    [attr.aria-required]="props.required || null"
  />`,
})
export class VtTextField extends VtFieldBase {}

/** `vt.input.score`: điểm số trong khoảng `minimum`..`maximum`, bước `multipleOf`. */
@Component({
  selector: 'vt-field-score',
  imports: [ReactiveFormsModule, FormlyAttributes],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="vt-score">
      <input
        class="vt-input"
        type="number"
        inputmode="decimal"
        [formControl]="formControl"
        [formlyAttributes]="field"
        [attr.min]="props.min ?? null"
        [attr.max]="props.max ?? null"
        [attr.step]="props['step'] ?? 'any'"
        [attr.aria-describedby]="describedBy"
        [attr.aria-invalid]="showError"
        [attr.aria-required]="props.required || null"
      />
      @if (props.max !== undefined) {
        <span class="vt-score__max" aria-hidden="true">/ {{ props.max }}</span>
      }
    </span>
  `,
})
export class VtScoreField extends VtFieldBase {}

/** `vt.input.textarea`: văn bản dài, đếm ký tự khi có `maxLength`. */
@Component({
  selector: 'vt-field-textarea',
  imports: [ReactiveFormsModule, FormlyAttributes],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <textarea
      class="vt-input"
      [rows]="props.rows ?? 4"
      [formControl]="formControl"
      [formlyAttributes]="field"
      [attr.maxlength]="props.maxLength ?? null"
      [attr.aria-describedby]="describedBy"
      [attr.aria-invalid]="showError"
      [attr.aria-required]="props.required || null"
    ></textarea>
    @if (props.maxLength) {
      <span class="vt-count">{{ length() }}/{{ props.maxLength }} ký tự</span>
    }
  `,
})
export class VtTextareaField extends VtFieldBase {
  protected length(): number {
    const v = this.formControl.value;
    return typeof v === 'string' ? v.length : 0;
  }
}

/** `vt.input.date`: ngày dạng `YYYY-MM-DD` (JSON Schema `format: date`), dùng ô ngày gốc của trình duyệt. */
@Component({
  selector: 'vt-field-date',
  imports: [ReactiveFormsModule, FormlyAttributes],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<input
    class="vt-input vt-input--date"
    type="date"
    [formControl]="formControl"
    [formlyAttributes]="field"
    [attr.min]="props['minDate'] ?? null"
    [attr.max]="props['maxDate'] ?? null"
    [attr.aria-describedby]="describedBy"
    [attr.aria-invalid]="showError"
    [attr.aria-required]="props.required || null"
  />`,
})
export class VtDateField extends VtFieldBase {}

/** `vt.input.catalog`: chọn một giá trị trong danh mục (`oneOf` có `title`, `enum` hoặc `x-catalog`). */
@Component({
  selector: 'vt-field-catalog',
  imports: [ReactiveFormsModule, FormlyAttributes],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <select
      class="vt-input"
      [formControl]="formControl"
      [formlyAttributes]="field"
      [attr.aria-describedby]="describedBy"
      [attr.aria-invalid]="showError"
      [attr.aria-required]="props.required || null"
      [attr.aria-busy]="loading() || null"
    >
      <option [ngValue]="null" [disabled]="props.required">
        {{ props.placeholder || '-- Chọn --' }}
      </option>
      @for (o of items(); track o.value) {
        <option [ngValue]="o.value" [disabled]="o.disabled">{{ o.label }}</option>
      }
    </select>
  `,
})
export class VtCatalogField extends VtFieldBase implements OnInit {
  private readonly source = inject(VT_FORM_CATALOGS, { optional: true });
  private readonly destroyRef = inject(DestroyRef);
  protected readonly items = signal<CatalogOption[]>([]);
  protected readonly loading = signal(false);

  ngOnInit(): void {
    const code = this.props['catalog'] as string | undefined;
    const fallback = (this.props.options as CatalogOption[] | undefined) ?? [];
    if (!code || !this.source) {
      this.items.set(fallback);
      return;
    }
    const res = this.source(code);
    const obs = isObservable(res) ? res : of(res);
    this.loading.set(true);
    obs.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (list) => this.items.set(list.length ? list : fallback),
      error: () => {
        this.items.set(fallback);
        this.loading.set(false);
      },
      complete: () => this.loading.set(false),
    });
  }
}

/** Hộp đánh dấu có nhãn bấm được (dùng cho tiêu chí chung dạng boolean). */
@Component({
  selector: 'vt-field-checkbox',
  imports: [ReactiveFormsModule, FormlyAttributes],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <label class="vt-check">
      <input
        type="checkbox"
        [formControl]="formControl"
        [formlyAttributes]="field"
        [attr.aria-describedby]="describedBy"
        [attr.aria-invalid]="showError"
      />
      <span>{{ props.label }}</span>
    </label>
  `,
})
export class VtCheckboxField extends VtFieldBase {}
