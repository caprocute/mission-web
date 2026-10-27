import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

/**
 * Bố cục khung xương:
 * - `page`: tiêu đề, hàng thẻ số liệu và một bảng (khi chưa biết trang có gì);
 * - `table`: đầu bảng và các dòng theo số cột; `header=false` khi đặt trong thân `p-table`;
 * - `list`: các dòng có biểu tượng tròn, hai dòng chữ (danh sách, hàng chờ, thông báo);
 * - `cards`: lưới thẻ (thẻ mục lục, thẻ nhiệm vụ, thẻ gói hồ sơ);
 * - `stats`: hàng thẻ số liệu (KPI) của bảng điều hành;
 * - `dashboard`: hàng thẻ số liệu và lưới khối biểu đồ;
 * - `chart`: một khối biểu đồ, không viền (thường nằm trong thẻ có sẵn tiêu đề);
 * - `form`: các cặp nhãn, ô nhập và nút gửi;
 * - `detail`: tiêu đề bản ghi, lưới thuộc tính và đoạn nội dung;
 * - `tree`: cây thụt lề (cây mục tiêu, cây tổ chức);
 * - `text`: vài dòng chữ (đoạn giải thích, vết tính, gợi ý).
 */
export type VtSkeletonVariant =
  | 'page'
  | 'table'
  | 'list'
  | 'cards'
  | 'stats'
  | 'dashboard'
  | 'chart'
  | 'form'
  | 'detail'
  | 'tree'
  | 'text';

const DEFAULT_ROWS: Record<VtSkeletonVariant, number> = {
  page: 5,
  table: 6,
  list: 4,
  cards: 6,
  stats: 4,
  dashboard: 4,
  chart: 1,
  form: 4,
  detail: 6,
  tree: 6,
  text: 3,
};

/**
 * Khung xương khi đang tải dữ liệu (YC-PT-28): các khối mờ mô phỏng đúng bố cục nội dung
 * sắp hiện, thay cho vùng trống hay dòng chữ "Đang tải…". Trình đọc màn hình nghe một câu
 * nghiệp vụ ngắn (`label`), phần khối mờ ẩn khỏi cây trợ năng. Hiệu ứng ánh sáng tắt khi
 * người dùng bật giảm chuyển động; chế độ cao tuổi dùng khối đậm hơn, dòng cao hơn.
 * Nút đang gửi thao tác vẫn dùng trạng thái chờ trên nút, không dùng thành phần này.
 */
@Component({
  selector: 'vt-skeleton',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    class: 'vt-skeleton',
    role: 'status',
    'aria-live': 'polite',
    'aria-busy': 'true',
    '[attr.data-variant]': 'variant()',
  },
  template: `
    <span class="vt-sk-sr">{{ label() }}</span>
    <div class="vt-sk" aria-hidden="true">
      @switch (variant()) {
        @case ('page') {
          <div class="vt-sk-head">
            <span class="b h1"></span>
            <span class="b line w40"></span>
          </div>
          <div class="vt-sk-stats">
            @for (i of seq(4); track i) {
              <div class="card stat">
                <span class="b line w50"></span><span class="b num"></span>
              </div>
            }
          </div>
          <div class="card">
            <div class="vt-sk-row th" [style.--vt-sk-cols]="cols()">
              @for (c of seq(cols()); track c) {
                <span class="b line"></span>
              }
            </div>
            @for (r of seq(count()); track r) {
              <div class="vt-sk-row" [style.--vt-sk-cols]="cols()">
                @for (c of seq(cols()); track c) {
                  <span class="b line" [class.w60]="c % 2 === 1"></span>
                }
              </div>
            }
          </div>
        }
        @case ('table') {
          <div [class.card]="header()">
            @if (header()) {
              <div class="vt-sk-row th" [style.--vt-sk-cols]="cols()">
                @for (c of seq(cols()); track c) {
                  <span class="b line"></span>
                }
              </div>
            }
            @for (r of seq(count()); track r) {
              <div class="vt-sk-row" [style.--vt-sk-cols]="cols()">
                @for (c of seq(cols()); track c) {
                  <span class="b line" [class.w60]="(c + r) % 3 === 1"></span>
                }
              </div>
            }
          </div>
        }
        @case ('list') {
          @for (r of seq(count()); track r) {
            <div class="vt-sk-item">
              <span class="b dot"></span>
              <span class="vt-sk-lines">
                <span class="b line" [class.w70]="r % 2 === 0" [class.w50]="r % 2 === 1"></span>
                <span class="b line sm w40"></span>
              </span>
            </div>
          }
        }
        @case ('cards') {
          <div class="vt-sk-grid">
            @for (r of seq(count()); track r) {
              <div class="card">
                <span class="b icon"></span>
                <span class="b line w70"></span>
                <span class="b line sm"></span>
                <span class="b line sm w50"></span>
              </div>
            }
          </div>
        }
        @case ('stats') {
          <div class="vt-sk-stats">
            @for (i of seq(count()); track i) {
              <div class="card stat">
                <span class="b line w50"></span><span class="b num"></span>
              </div>
            }
          </div>
        }
        @case ('dashboard') {
          <div class="vt-sk-stats">
            @for (i of seq(count()); track i) {
              <div class="card stat">
                <span class="b line w50"></span><span class="b num"></span>
              </div>
            }
          </div>
          <div class="vt-sk-charts">
            @for (i of seq(2); track i) {
              <div class="card"><span class="b line w40"></span><span class="b chart"></span></div>
            }
          </div>
        }
        @case ('chart') {
          <span class="b line w40"></span>
          <span class="b chart"></span>
        }
        @case ('form') {
          <div class="card">
            @for (r of seq(count()); track r) {
              <div class="vt-sk-field">
                <span class="b line sm w30"></span>
                <span class="b input"></span>
              </div>
            }
            <span class="b btn"></span>
          </div>
        }
        @case ('detail') {
          <div class="card">
            <span class="b line lg w50"></span>
            <div class="vt-sk-props">
              @for (r of seq(count()); track r) {
                <span class="vt-sk-lines">
                  <span class="b line sm w40"></span>
                  <span class="b line w70"></span>
                </span>
              }
            </div>
            <span class="b line"></span>
            <span class="b line"></span>
            <span class="b line w60"></span>
          </div>
        }
        @case ('tree') {
          <div [class.card]="header()">
            @for (r of seq(count()); track r) {
              <div class="vt-sk-item" [style.padding-left.rem]="indent(r)">
                <span class="b dot sq"></span>
                <span class="b line" [class.w50]="r % 2 === 0" [class.w40]="r % 2 === 1"></span>
              </div>
            }
          </div>
        }
        @default {
          @for (r of seq(count()); track r) {
            <span class="b line" [class.w60]="r === count() - 1"></span>
          }
        }
      }
    </div>
  `,
  styles: `
    :host {
      display: block;
      /* đặt trong lưới (thẻ KPI, lưới thẻ) thì chiếm trọn hàng */
      grid-column: 1 / -1;
      --vt-sk-base: #e2e8f0;
      --vt-sk-shine: #f1f5f9;
    }
    :host-context([data-mode='elderly']) {
      --vt-sk-base: #cbd5e1;
      --vt-sk-shine: #e2e8f0;
    }
    .vt-sk-sr {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
    .vt-sk {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .card {
      display: flex;
      flex-direction: column;
      gap: 10px;
      padding: 16px;
      background: var(--vt-color-surface, #fff);
      border: 1px solid var(--vt-color-border, #e2e8f0);
      border-radius: var(--vt-radius-card, 12px);
    }
    .b {
      display: block;
      border-radius: 6px;
      background: linear-gradient(
        90deg,
        var(--vt-sk-base) 0%,
        var(--vt-sk-shine) 40%,
        var(--vt-sk-base) 80%
      );
      background-size: 300% 100%;
      animation: vt-sk-shimmer 1.4s ease-in-out infinite;
    }
    .line {
      height: 0.8em;
      width: 100%;
    }
    .line.sm {
      height: 0.65em;
    }
    .line.lg {
      height: 1.2em;
    }
    .h1 {
      height: 1.6em;
      width: 32%;
      min-width: 10rem;
    }
    .num {
      height: 1.8em;
      width: 45%;
    }
    .dot {
      flex: none;
      width: 2.25em;
      height: 2.25em;
      border-radius: 50%;
    }
    .dot.sq {
      width: 1.1em;
      height: 1.1em;
      border-radius: 4px;
    }
    .icon {
      width: 2.5em;
      height: 2.5em;
      border-radius: 10px;
    }
    .input {
      height: var(--vt-min-touch, 40px);
      width: 100%;
    }
    .btn {
      height: var(--vt-min-touch, 40px);
      width: 8rem;
      margin-top: 4px;
    }
    .chart {
      height: 12rem;
      width: 100%;
    }
    .w30 {
      width: 30%;
    }
    .w40 {
      width: 40%;
    }
    .w50 {
      width: 50%;
    }
    .w60 {
      width: 60%;
    }
    .w70 {
      width: 70%;
    }
    .vt-sk-head {
      display: flex;
      flex-direction: column;
      gap: 10px;
      margin-bottom: 8px;
    }
    .vt-sk-row {
      display: grid;
      grid-template-columns: repeat(var(--vt-sk-cols, 4), minmax(0, 1fr));
      gap: 16px;
      align-items: center;
      padding: 10px 0;
      border-bottom: 1px solid var(--vt-color-border, #e2e8f0);
    }
    .vt-sk-row:last-child {
      border-bottom: 0;
    }
    .vt-sk-row.th .line {
      height: 0.7em;
      width: 55%;
    }
    .vt-sk-item {
      display: flex;
      align-items: center;
      gap: 12px;
      padding: 8px 0;
    }
    .vt-sk-lines {
      display: flex;
      flex-direction: column;
      gap: 8px;
      flex: 1;
      min-width: 0;
    }
    .vt-sk-grid {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(15rem, 1fr));
      gap: 16px;
    }
    .vt-sk-stats {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(11rem, 1fr));
      gap: 16px;
    }
    .vt-sk-charts {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(20rem, 1fr));
      gap: 16px;
    }
    .vt-sk-field {
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .vt-sk-props {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(12rem, 1fr));
      gap: 16px;
      margin: 6px 0 8px;
    }
    @keyframes vt-sk-shimmer {
      0% {
        background-position: 100% 0;
      }
      100% {
        background-position: 0 0;
      }
    }
    @media (prefers-reduced-motion: reduce) {
      .b {
        animation: none;
        background: var(--vt-sk-base);
      }
    }
    @media (max-width: 640px) {
      .vt-sk-row {
        grid-template-columns: repeat(min(var(--vt-sk-cols, 4), 2), minmax(0, 1fr));
      }
      .vt-sk-row > .line:nth-child(n + 3) {
        display: none;
      }
    }
  `,
})
export class VtSkeletonComponent {
  /** Bố cục khung xương, chọn gần nhất với nội dung sắp hiện. */
  readonly variant = input<VtSkeletonVariant>('page');
  /** Số dòng, số thẻ hay số trường; bỏ trống thì dùng mặc định theo bố cục. */
  readonly rows = input<number | undefined>(undefined);
  /** Số cột của bảng (bố cục `table`, `page`). */
  readonly columns = input(4);
  /**
   * Có vẽ đầu bảng và khung thẻ không (bố cục `table`, `tree`); đặt `false` khi khung xương nằm
   * trong thân bảng hoặc trong một khung có sẵn.
   */
  readonly header = input(true);
  /** Câu đọc cho trình đọc màn hình, viết bằng lời nghiệp vụ. */
  readonly label = input('Đang tải dữ liệu');

  protected readonly count = computed(() =>
    Math.max(1, Math.min(this.rows() ?? DEFAULT_ROWS[this.variant()], 24)),
  );
  protected readonly cols = computed(() => Math.max(1, Math.min(this.columns(), 8)));

  protected seq(n: number): number[] {
    return Array.from({ length: n }, (_, i) => i);
  }

  /** Thụt lề giả lập cấp cây: 0, 1, 2, 2, 1, 2… */
  protected indent(r: number): number {
    return [0, 1.5, 3, 3, 1.5, 3][r % 6];
  }
}
