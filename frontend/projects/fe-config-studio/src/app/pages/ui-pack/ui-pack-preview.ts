import { Component, computed, input } from '@angular/core';
import { UiSduiNode, uiWidgetLabel } from '@vt/api-client';
import { TokenTree, homeWidgets, pxOf, tokenText } from './ui-pack-tokens';

/**
 * Xem trước giao diện mẫu từ token và bố cục (SCR-1801 vùng 4): đầu trang theo màu chủ đạo, thẻ "Việc
 * của tôi hôm nay", thanh tiến độ, nút chính và danh sách khối trang chủ. Chỉ dựng bằng thuộc tính
 * CSS đã biết, không chèn HTML từ cấu hình (TL-01 mục CSP).
 */
@Component({
  selector: 'vt-ui-pack-preview',
  template: `
    <figure
      class="pv"
      [class.eld]="elderly()"
      [style.--pv-primary]="c('color.primary')"
      [style.--pv-on-primary]="c('color.onPrimary')"
      [style.--pv-text]="elderly() ? '#000000' : c('color.text')"
      [style.--pv-bg]="c('color.background')"
      [style.--pv-danger]="c('color.danger')"
      [style.--pv-radius]="px('radius.card') + 'px'"
      [style.--pv-unit]="px('space.unit') + 'px'"
      [style.--pv-size]="(elderly() ? 18 : px('font.size.body')) + 'px'"
      [style.--pv-font]="font()"
      [attr.aria-label]="'Xem trước ' + caption()"
    >
      <div class="top">
        <strong class="brand">{{ brand() }}</strong>
        <span class="dot" aria-hidden="true"></span>
      </div>
      <div class="body">
        <p class="h">Việc của tôi hôm nay</p>
        <div class="tiles">
          <div class="tile first">
            <span>Việc đến hạn</span>
            <b>4</b>
          </div>
          <div class="tile">
            <span>Kết quả cần cập nhật</span>
            <b class="hot">2</b>
          </div>
        </div>
        <div class="tile wide">
          <span>Báo cáo chuyên đề quý</span>
          <span class="track" aria-hidden="true"><span class="fill"></span></span>
        </div>
        <div class="btns">
          <span class="b1">Cập nhật tiến độ</span>
          <span class="b2">Nộp minh chứng</span>
        </div>
        <ul class="ws" aria-label="Các khối trên trang chủ">
          @for (w of widgets(); track $index) {
            <li>{{ label(w.component) }}</li>
          }
        </ul>
      </div>
      <figcaption>{{ caption() }}</figcaption>
    </figure>
  `,
  styles: `
    .pv {
      margin: 0;
      border: 1px solid #e5e7eb;
      border-radius: 12px;
      overflow: hidden;
      background: var(--pv-bg);
      color: var(--pv-text);
      font-family: var(--pv-font);
      font-size: var(--pv-size);
    }
    .top {
      display: flex;
      justify-content: space-between;
      align-items: center;
      padding: 10px 14px;
      background: #fff;
      border-bottom: 1px solid #e5e7eb;
    }
    .brand {
      color: var(--pv-primary);
    }
    .dot {
      width: 18px;
      height: 18px;
      border-radius: 50%;
      background: var(--pv-primary);
    }
    .body {
      padding: calc(var(--pv-unit) * 3);
      display: grid;
      gap: calc(var(--pv-unit) * 2);
    }
    .h {
      margin: 0;
      font-weight: 600;
    }
    .tiles {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: calc(var(--pv-unit) * 2);
    }
    .tile {
      background: #fff;
      border-radius: var(--pv-radius);
      padding: calc(var(--pv-unit) * 3);
      display: grid;
      gap: 4px;
    }
    .tile.first {
      border-top: 3px solid var(--pv-primary);
    }
    .tile b {
      font-size: 1.4em;
    }
    .tile .hot {
      color: var(--pv-primary);
    }
    .track {
      display: block;
      height: 6px;
      border-radius: 3px;
      background: #e5e7eb;
    }
    .fill {
      display: block;
      width: 60%;
      height: 100%;
      border-radius: 3px;
      background: var(--pv-primary);
    }
    .btns {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .b1,
    .b2 {
      padding: 8px 14px;
      border-radius: var(--pv-radius);
      min-height: 36px;
      display: inline-flex;
      align-items: center;
    }
    .eld .b1,
    .eld .b2 {
      min-height: 48px;
    }
    .b1 {
      background: var(--pv-primary);
      color: var(--pv-on-primary);
      font-weight: 600;
    }
    .b2 {
      background: #fff;
      border: 1px solid #cbd5e1;
    }
    .ws {
      margin: 0;
      padding-left: 18px;
      font-size: 0.9em;
    }
    figcaption {
      padding: 8px 14px;
      border-top: 1px solid #e5e7eb;
      background: #fff;
      color: #4b5563;
      font-size: 0.85rem;
    }
  `,
})
export class UiPackPreview {
  readonly tokens = input.required<TokenTree>();
  readonly layouts = input.required<Record<string, UiSduiNode>>();
  readonly brand = input('');
  readonly caption = input('');
  readonly elderly = input(false);

  protected readonly widgets = computed(() => homeWidgets(this.layouts()));
  protected readonly font = computed(() => {
    const v = this.tokens()['font'] as { family?: { $value?: unknown } } | undefined;
    const f = v?.family?.$value;
    return Array.isArray(f) ? f.map((x) => (/\s/.test(String(x)) ? `"${x}"` : x)).join(', ') : '';
  });
  protected readonly label = uiWidgetLabel;

  protected c(path: string) {
    return tokenText(this.tokens(), path);
  }

  protected px(path: string) {
    return pxOf(this.tokens(), path);
  }
}
