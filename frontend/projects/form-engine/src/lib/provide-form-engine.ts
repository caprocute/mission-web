import { Provider, makeEnvironmentProviders } from '@angular/core';
import { FormlyFieldConfig, provideFormlyCore } from '@ngx-formly/core';
import {
  VtCatalogField,
  VtCheckboxField,
  VtDateField,
  VtScoreField,
  VtTextField,
  VtTextareaField,
} from './fields/field-types';
import { VtFormSection } from './fields/field-section';
import { VtFormFieldWrapper, VtFormSectionWrapper } from './fields/field-wrapper';

/** Ánh xạ tên widget của UI Pack sang kiểu trường Formly đã đăng ký. */
export const WIDGET_TYPES: Record<string, string> = {
  'vt.input.text': 'vt-text',
  'vt.input.score': 'vt-score',
  'vt.input.textarea': 'vt-textarea',
  'vt.input.date': 'vt-date',
  'vt.input.catalog': 'vt-catalog',
  'vt.input.checkbox': 'vt-checkbox',
};

type Msg = (err: unknown, field: FormlyFieldConfig) => string;
const p = (f: FormlyFieldConfig) => f.props ?? {};
const VALIDATION_MESSAGES: { name: string; message: string | Msg }[] = [
  { name: 'required', message: 'Trường này bắt buộc nhập.' },
  { name: 'minLength', message: (_e, f) => `Cần ít nhất ${p(f).minLength} ký tự.` },
  { name: 'maxLength', message: (_e, f) => `Không quá ${p(f).maxLength} ký tự.` },
  { name: 'min', message: (_e, f) => `Giá trị nhỏ nhất là ${p(f).min}.` },
  { name: 'max', message: (_e, f) => `Giá trị lớn nhất là ${p(f).max}.` },
  { name: 'minimum', message: (_e, f) => `Giá trị nhỏ nhất là ${p(f).min}.` },
  { name: 'maximum', message: (_e, f) => `Giá trị lớn nhất là ${p(f).max}.` },
  {
    name: 'exclusiveMinimum',
    message: (_e, f) => `Giá trị phải lớn hơn ${p(f)['exclusiveMinimum']}.`,
  },
  {
    name: 'exclusiveMaximum',
    message: (_e, f) => `Giá trị phải nhỏ hơn ${p(f)['exclusiveMaximum']}.`,
  },
  { name: 'multipleOf', message: (_e, f) => `Giá trị phải là bội số của ${p(f)['step']}.` },
  { name: 'pattern', message: 'Giá trị không đúng định dạng.' },
  { name: 'type', message: 'Giá trị không đúng kiểu dữ liệu.' },
  { name: 'enum', message: 'Giá trị không có trong danh mục.' },
  { name: 'const', message: 'Giá trị không hợp lệ.' },
];

/**
 * Provider của Formly với các widget `vt.input.*`, khung trường dễ truy cập và thông báo lỗi tiếng
 * Việt (ADR-05 lớp 3). Không nạp bộ kiểu PrimeNG của Formly để gói tải đầu nhỏ; widget dùng biến CSS
 * của PrimeNG và design system nên theo theme tenant và chế độ cao tuổi.
 *
 * `VtFormComponent` tự khai báo các provider này ở cấp component (WEB-052), nên Formly,
 * `@angular/forms` và `@vt/form-engine` chỉ được tải khi một màn hình nạp lười thật sự dùng
 * `<vt-form>`, không nằm trong lần tải đầu của shell.
 */
export function formEngineProviders(): Provider {
  return provideFormlyCore({
    wrappers: [
      { name: 'vt-field', component: VtFormFieldWrapper },
      { name: 'vt-section', component: VtFormSectionWrapper },
      // tên cũ trước WEB-010, giữ để không vỡ cấu hình đã có
      { name: 'panel', component: VtFormSectionWrapper },
    ],
    types: [
      { name: 'vt-text', component: VtTextField, wrappers: ['vt-field'] },
      { name: 'vt-score', component: VtScoreField, wrappers: ['vt-field'] },
      { name: 'vt-textarea', component: VtTextareaField, wrappers: ['vt-field'] },
      { name: 'vt-date', component: VtDateField, wrappers: ['vt-field'] },
      { name: 'vt-catalog', component: VtCatalogField, wrappers: ['vt-field'] },
      { name: 'vt-checkbox', component: VtCheckboxField },
      // nhóm trường của ui.sections, dựng dần khi biểu mẫu lớn
      { name: 'vt-section-group', component: VtFormSection },
    ],
    validationMessages: VALIDATION_MESSAGES,
  });
}

/**
 * Đăng ký Formly ở cấp môi trường (ứng dụng hoặc tuyến). Không bắt buộc vì `<vt-form>` đã tự có
 * provider; chỉ dùng khi màn hình dựng `formly-form` trực tiếp. Không gọi trong `app.config` của
 * shell để trang chủ không tải Formly (WEB-052).
 */
export function provideFormEngine() {
  return makeEnvironmentProviders([formEngineProviders()]);
}
