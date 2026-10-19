import { Component, computed, input } from '@angular/core';
import { PresentedProblem } from '../problem-presenter';

/**
 * Lỗi theo trường từ `errors[]` của Problem Details, đặt ngay dưới ô nhập. Gắn ô nhập với lỗi
 * bằng `aria-describedby` trỏ tới `id` truyền vào: `<vt-field-error [problem]="p" field="percent" id="pct-err" />`.
 */
@Component({
  selector: 'vt-field-error',
  template: `
    @if (text(); as t) {
      <small class="vt-field-error" role="alert">{{ t }}</small>
    }
  `,
  styles: `
    .vt-field-error {
      display: block;
      margin-top: 4px;
      color: var(--p-red-700, #b91c1c);
    }
  `,
})
export class FieldErrorComponent {
  readonly problem = input<PresentedProblem | null>(null);
  readonly field = input.required<string>();

  protected readonly text = computed(() => this.problem()?.fields[this.field()] ?? null);
}
