import { ChangeDetectionStrategy, Component, InjectionToken, inject } from '@angular/core';
import { FieldType, FormlyField } from '@ngx-formly/core';

/** Biểu mẫu chứa nhóm trường cho biết nhóm thứ `index` đã được dựng chưa (dựng dần, WEB-010). */
export interface SectionReveal {
  sectionVisible(index: number): boolean;
}
export const VT_SECTION_REVEAL = new InjectionToken<SectionReveal>('vt.sectionReveal');

/**
 * Nhóm trường theo `ui.sections`: fieldset có legend để trình đọc màn hình đọc tên nhóm. Biểu mẫu lớn
 * dựng dần từng nhóm (mỗi nhóm một tác vụ) để không có tác vụ dài quá ngân sách 50 ms; nhóm chưa dựng
 * giữ chỗ bằng chiều cao ước lượng. Điều khiển của mọi trường đã có trong `FormGroup` từ đầu (Formly
 * tạo khi dựng cấu hình), nên giá trị, kiểm tra hợp lệ và quy tắc CEL không phụ thuộc nhóm đã vẽ chưa.
 */
@Component({
  selector: 'vt-form-section',
  imports: [FormlyField],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <fieldset class="vt-section" [attr.aria-busy]="visible ? null : 'true'">
      @if (props.label) {
        <legend>{{ props.label }}</legend>
      }
      @if (visible) {
        @for (f of field.fieldGroup; track $index) {
          <formly-field [field]="f" />
        }
      } @else {
        <div
          class="vt-section__cho"
          [style.min-height.px]="(field.fieldGroup?.length ?? 0) * 76"
        ></div>
      }
    </fieldset>
  `,
})
export class VtFormSection extends FieldType {
  private readonly reveal = inject(VT_SECTION_REVEAL, { optional: true });

  get visible(): boolean {
    const index = this.props['sectionIndex'];
    return !this.reveal || typeof index !== 'number' || this.reveal.sectionVisible(index);
  }
}
