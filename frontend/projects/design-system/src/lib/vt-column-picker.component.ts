import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';

/** Một cột trong danh mục cột của màn hình danh sách. */
export interface VtColumnOption {
  key: string;
  label: string;
  /** Cột bắt buộc: luôn hiện, không bỏ chọn được. */
  required?: boolean;
}

let seq = 0;

/**
 * Nút "Chọn cột" của danh sách (CNH-04): mở bảng ô chọn để ẩn hoặc hiện cột không bắt buộc.
 * Dùng được bằng bàn phím (Tab, Space, Enter; Esc đóng và trả tiêu điểm về nút). Thành phần chỉ
 * trình bày; màn hình lưu lựa chọn qua `ListPersonalization` của `@vt/api-client`.
 */
@Component({
  selector: 'vt-column-picker',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'vt-column-picker', '(keydown.escape)': 'close()' },
  template: `
    <button
      #trigger
      type="button"
      class="vt-lt__btn"
      [attr.aria-expanded]="open()"
      [attr.aria-controls]="panelId"
      [disabled]="disabled()"
      (click)="open.set(!open())"
    >
      <i class="pi pi-table" aria-hidden="true"></i> Chọn cột
    </button>
    @if (open()) {
      <fieldset [id]="panelId" class="vt-lt__panel">
        <legend>Cột hiển thị</legend>
        @for (c of columns(); track c.key) {
          <label class="vt-lt__opt">
            <input
              type="checkbox"
              [checked]="c.required || shown().has(c.key)"
              [disabled]="c.required || disabled()"
              (change)="toggle(c.key, $any($event.target).checked)"
            />
            <span>{{ c.label }}</span>
            @if (c.required) {
              <small>(luôn hiện)</small>
            }
          </label>
        }
        <button type="button" class="vt-lt__link" (click)="close()">Đóng</button>
      </fieldset>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: inline-block;
    }
    .vt-lt__btn {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      min-height: 2.5rem;
      padding: 0 12px;
      border: 1px solid #94a3b8;
      border-radius: 8px;
      background: #ffffff;
      color: #1f2937;
      font: inherit;
      cursor: pointer;
    }
    .vt-lt__btn:focus-visible,
    .vt-lt__link:focus-visible,
    .vt-lt__opt input:focus-visible {
      outline: 3px solid #1d4ed8;
      outline-offset: 2px;
    }
    .vt-lt__panel {
      position: absolute;
      z-index: 20;
      right: 0;
      top: calc(100% + 4px);
      min-width: 240px;
      margin: 0;
      padding: 12px;
      border: 1px solid #cbd5e1;
      border-radius: 10px;
      background: #ffffff;
      box-shadow: 0 10px 24px -8px rgba(15, 23, 42, 0.25);
      display: grid;
      gap: 6px;
    }
    .vt-lt__panel legend {
      font-weight: 700;
      padding: 0 4px;
    }
    .vt-lt__opt {
      display: flex;
      align-items: center;
      gap: 8px;
      min-height: 2rem;
    }
    .vt-lt__opt small {
      color: #4b5563;
    }
    .vt-lt__link {
      justify-self: end;
      border: 0;
      background: none;
      color: #1d4ed8;
      font: inherit;
      text-decoration: underline;
      cursor: pointer;
    }
  `,
})
export class VtColumnPickerComponent {
  readonly columns = input.required<VtColumnOption[]>();
  /** Mã các cột đang hiện. */
  readonly visible = input.required<string[]>();
  readonly disabled = input(false);
  readonly visibleChange = output<string[]>();

  protected readonly open = signal(false);
  protected readonly panelId = `vt-column-picker-${++seq}`;
  protected readonly shown = computed(() => new Set(this.visible()));
  private readonly trigger = viewChild<ElementRef<HTMLButtonElement>>('trigger');

  protected toggle(key: string, checked: boolean): void {
    const next = new Set(this.shown());
    if (checked) next.add(key);
    else next.delete(key);
    this.visibleChange.emit(
      this.columns()
        .filter((c) => c.required || next.has(c.key))
        .map((c) => c.key),
    );
  }

  protected close(): void {
    if (!this.open()) return;
    this.open.set(false);
    this.trigger()?.nativeElement.focus();
  }
}
