import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Thanh tiến độ có tên truy cập (WCAG 2.1 AA, WEB-059).
 * `p-progressbar` của PrimeNG 21 gắn `aria-level="72%"` lên phần tử role=progressbar
 * (axe: aria-allowed-attr, aria-valid-attr-value) và không nhận tên, nên nền tảng dùng
 * thành phần này thay thế. Phần trăm hiện bằng chữ bên cạnh thanh, không đặt chữ trắng lên nền đỏ.
 */
@Component({
  selector: 'vt-progress-bar',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'vt-progress',
    role: 'progressbar',
    'aria-valuemin': '0',
    'aria-valuemax': '100',
    '[attr.aria-valuenow]': 'percent()',
    '[attr.aria-valuetext]': 'percent() + "%"',
    '[attr.aria-label]': 'label()',
  },
  template: `
    <span class="vt-progress__track" aria-hidden="true">
      <span class="vt-progress__fill" [style.width.%]="percent()"></span>
    </span>
    @if (showValue()) {
      <span class="vt-progress__value" aria-hidden="true">{{ percent() }}%</span>
    }
  `,
  styles: `
    :host {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .vt-progress__track {
      flex: 1;
      height: 0.6rem;
      border-radius: 999px;
      background: #dde1e7;
      overflow: hidden;
    }
    .vt-progress__fill {
      display: block;
      height: 100%;
      background: var(--vt-color-primary);
      border-radius: inherit;
    }
    .vt-progress__value {
      min-width: 3ch;
      text-align: right;
      font-weight: 600;
      font-variant-numeric: tabular-nums;
    }
  `,
})
export class VtProgressBarComponent {
  /** Giá trị 0..100; ngoài khoảng thì kẹp lại. */
  readonly value = input(0);
  /** Tên đọc cho trình đọc màn hình, thường là tên chỉ tiêu hoặc nhiệm vụ. */
  readonly label = input.required<string>();
  readonly showValue = input(true);
  protected readonly percent = computed(() =>
    Math.round(Math.min(100, Math.max(0, Number(this.value()) || 0))),
  );
}
