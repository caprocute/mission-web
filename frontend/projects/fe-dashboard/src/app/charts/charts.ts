import { NgComponentOutlet } from '@angular/common';
import { Component, Type, computed, input } from '@angular/core';
import {
  AnalyticsCell,
  AnalyticsColumn,
  AnalyticsRow,
  DashboardChartType,
  PREVIOUS_SUFFIX,
  cellNumber,
  formatCell,
  resultRows,
} from '@vt/api-client/domains';

/**
 * Biểu đồ của bảng điều hành và dashboard tự dựng (SCR-1601, SCR-1602), vẽ bằng SVG và CSS qua liên kết
 * template (không `innerHTML`, không thư viện biểu đồ ngoài). `CHART_REGISTRY` là danh mục component theo
 * kiểu biểu đồ: dashboard chỉ lưu `chartType`, giao diện chỉ dựng component có trong danh mục (giống
 * registry SDUI, ADR-05). Mỗi biểu đồ kèm bảng số liệu ẩn cho trình đọc màn hình (WCAG 1.1.1).
 */

export interface ChartPoint {
  label: string;
  value: number | null;
  previous: number | null;
  row: AnalyticsRow;
}

/** Tách cột chiều, cột chỉ số chính và cột kỳ trước của một kết quả truy vấn. */
export function toSeries(columns: AnalyticsColumn[], rows: AnalyticsCell[][]) {
  const dims = columns.filter((c) => c.type === 'string' || c.type === 'time');
  const measure = columns.find(
    (c) => (c.type === 'number' || c.type === 'count') && !c.name.endsWith(PREVIOUS_SUFFIX),
  );
  const prev = measure && columns.find((c) => c.name === measure.name + PREVIOUS_SUFFIX);
  // mã kỹ thuật để đi xuống (`*.id`) không hiện làm nhãn
  const labelDims = dims.filter((d) => !d.name.endsWith('.id'));
  const points: ChartPoint[] = resultRows({ columns, rows }).map((r) => ({
    label: labelDims.map((d) => String(r[d.name] ?? '')).join(' · ') || (measure?.title ?? ''),
    value: measure ? cellNumber(r[measure.name]) : null,
    previous: prev ? cellNumber(r[prev.name]) : null,
    row: r,
  }));
  return { dims: labelDims, measure, prev, points };
}

@Component({
  selector: 'vt-chart-base',
  template: '',
})
export abstract class ChartBase {
  readonly columns = input<AnalyticsColumn[]>([]);
  readonly rows = input<AnalyticsCell[][]>([]);
  /** Tên biểu đồ cho trình đọc màn hình. */
  readonly caption = input('');
  /** Đi xuống khi bấm một giá trị; không có thì chỉ hiển thị. */
  readonly drill = input<((row: AnalyticsRow) => void) | null>(null);
  /** Tên hai chuỗi của biểu đồ đường (kỳ này, kỳ trước). */
  readonly seriesNames = input<[string, string] | null>(null);

  protected readonly series = computed(() => toSeries(this.columns(), this.rows()));
  protected readonly max = computed(() => {
    const s = this.series();
    if (s.measure?.unit === '%') return 100;
    const xs = s.points.flatMap((p) => [p.value ?? 0, p.previous ?? 0]);
    return Math.max(1, ...xs);
  });
  protected readonly srHeads = computed(() => {
    const s = this.series();
    return ['Mục', s.measure?.title ?? 'Giá trị', ...(s.prev ? [s.prev.title] : [])];
  });
  protected readonly srBody = computed(() => {
    const s = this.series();
    return s.points.map((p) => [
      p.label,
      this.fmt(p.value),
      ...(s.prev ? [this.fmt(p.previous)] : []),
    ]);
  });
  protected fmt(v: number | null) {
    return formatCell(v, this.series().measure);
  }
  protected pct(v: number | null) {
    return `${Math.max(0, Math.min(100, ((v ?? 0) / this.max()) * 100))}%`;
  }
}

/** Bảng số liệu ẩn đi kèm biểu đồ cho trình đọc màn hình. */
@Component({
  selector: 'vt-chart-sr-table',
  template: `
    <table class="sr-only">
      <caption>
        {{
          caption()
        }}
      </caption>
      <thead>
        <tr>
          @for (h of heads(); track $index) {
            <th scope="col">{{ h }}</th>
          }
        </tr>
      </thead>
      <tbody>
        @for (r of body(); track $index) {
          <tr>
            @for (c of r; track $index) {
              <td>{{ c }}</td>
            }
          </tr>
        }
      </tbody>
    </table>
  `,
  styles: `
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class ChartSrTable {
  readonly caption = input('');
  readonly heads = input<string[]>([]);
  readonly body = input<string[][]>([]);
}

/** `THE_SO`: một con số lớn, kèm chênh lệch so với kỳ trước khi có. */
@Component({
  selector: 'vt-stat-chart',
  template: `
    @if (series().points[0]; as p) {
      <p class="stat-v">{{ fmt(p.value) }}</p>
      @if (p.previous !== null && p.value !== null) {
        <p
          class="small"
          [class.delta-up]="p.value >= p.previous"
          [class.delta-down]="p.value < p.previous"
        >
          {{ p.value >= p.previous ? 'Tăng' : 'Giảm' }} {{ diff(p.value, p.previous) }} so với kỳ
          trước
        </p>
      }
    } @else {
      <p class="muted">Chưa có số liệu</p>
    }
  `,
  styles: `
    .stat-v {
      font-size: 2rem;
      font-weight: 700;
      color: #1e3a8a;
      margin: 0;
    }
    .small {
      font-size: 0.875em;
      margin: 0;
    }
    .delta-up {
      color: #166534;
    }
    .delta-down {
      color: #b91c1c;
    }
    .muted {
      color: #4b5563;
      margin: 0;
    }
  `,
})
export class StatChart extends ChartBase {
  protected diff(a: number, b: number) {
    const d = Math.abs(Math.round((a - b) * 10) / 10);
    return this.series().measure?.unit === '%'
      ? `${d.toLocaleString('vi-VN')} điểm phần trăm`
      : this.fmt(d);
  }
}

/** `THANH` (thanh ngang) và `COT` (cột đứng) dựng bằng CSS; nhãn bấm được khi cho đi xuống. */
@Component({
  selector: 'vt-bar-chart',
  imports: [ChartSrTable],
  template: `
    <ul class="bars" [class.vertical]="vertical()" aria-hidden="true">
      @for (p of series().points; track $index) {
        <li>
          <span class="lbl">{{ p.label }}</span>
          <span class="track"
            ><span
              class="fill"
              [style.width]="vertical() ? null : pct(p.value)"
              [style.height]="vertical() ? pct(p.value) : null"
            ></span
          ></span>
          <span class="val">{{ fmt(p.value) }}</span>
        </li>
      }
    </ul>
    <vt-chart-sr-table [caption]="caption()" [heads]="srHeads()" [body]="srBody()" />
    @if (drill(); as go) {
      <p class="drill small">
        Xem chi tiết:
        @for (p of series().points; track $index) {
          <button type="button" class="link-btn" (click)="go(p.row)">{{ p.label }}</button>
        }
      </p>
    }
  `,
  styles: `
    .bars {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: 6px;
    }
    .bars li {
      display: grid;
      grid-template-columns: minmax(90px, 38%) 1fr auto;
      gap: 8px;
      align-items: center;
    }
    .track {
      height: 12px;
      background: #e5e7eb;
      border-radius: 6px;
      overflow: hidden;
      display: block;
    }
    .fill {
      display: block;
      height: 100%;
      background: #1e3a8a;
    }
    .bars.vertical {
      display: flex;
      align-items: flex-end;
      gap: 10px;
      min-height: 160px;
    }
    .bars.vertical li {
      display: flex;
      flex-direction: column-reverse;
      align-items: center;
      flex: 1;
      gap: 4px;
    }
    .bars.vertical .track {
      width: 28px;
      height: 120px;
      display: flex;
      align-items: flex-end;
    }
    .bars.vertical .fill {
      width: 100%;
    }
    .bars.vertical .lbl {
      font-size: 0.8rem;
      text-align: center;
    }
    .lbl {
      overflow-wrap: anywhere;
    }
    .val {
      font-variant-numeric: tabular-nums;
    }
    .drill {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 10px;
      margin: 8px 0 0;
    }
    .small {
      font-size: 0.875em;
    }
    .link-btn {
      background: none;
      border: 0;
      padding: 0;
      color: #1d4ed8;
      text-decoration: underline;
      cursor: pointer;
      font: inherit;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class BarChart extends ChartBase {
  readonly vertical = input(false);
}

/** Cột đứng (`COT`). */
@Component({
  selector: 'vt-column-chart',
  imports: [BarChart],
  template: `<vt-bar-chart
    [columns]="columns()"
    [rows]="rows()"
    [caption]="caption()"
    [drill]="drill()"
    [vertical]="true"
  />`,
})
export class ColumnChart extends ChartBase {}

/** `DUONG`: đường xu hướng bằng SVG, tối đa hai chuỗi (kỳ này nét liền, kỳ trước nét đứt). */
@Component({
  selector: 'vt-line-chart',
  imports: [ChartSrTable],
  template: `
    <svg
      [attr.viewBox]="'0 0 ' + W + ' ' + H"
      class="line"
      role="img"
      [attr.aria-label]="caption()"
      focusable="false"
    >
      @for (g of grid; track g) {
        <line [attr.x1]="P" [attr.x2]="W - P" [attr.y1]="gy(g)" [attr.y2]="gy(g)" class="g" />
      }
      @if (prevPath()) {
        <polyline [attr.points]="prevPath()" class="prev" />
      }
      <polyline [attr.points]="curPath()" class="cur" />
      @for (p of series().points; track $index; let i = $index) {
        @if (p.value !== null) {
          <circle [attr.cx]="x(i)" [attr.cy]="y(p.value)" r="3.5" class="dot" />
        }
      }
    </svg>
    <div class="axis" aria-hidden="true">
      @for (p of series().points; track $index; let i = $index) {
        <span [style.left.%]="(x(i) / W) * 100">{{ p.label }}</span>
      }
    </div>
    @if (seriesNames(); as n) {
      <p class="legend small">
        <span class="k cur"></span>{{ n[0] }}
        @if (series().prev) {
          <span class="k prev"></span>{{ n[1] }}
        }
      </p>
    }
    <vt-chart-sr-table [caption]="caption()" [heads]="srHeads()" [body]="srBody()" />
  `,
  styles: `
    .line {
      width: 100%;
      height: auto;
      display: block;
    }
    .g {
      stroke: #e5e7eb;
    }
    .cur {
      fill: none;
      stroke: #1e3a8a;
      stroke-width: 2.5;
    }
    .prev {
      fill: none;
      stroke: #6b7280;
      stroke-width: 2;
      stroke-dasharray: 5 4;
    }
    .dot {
      fill: #1e3a8a;
    }
    .axis {
      position: relative;
      height: 1.4em;
      font-size: 0.8rem;
      color: #4b5563;
    }
    .axis span {
      position: absolute;
      top: 0;
      transform: translateX(-50%);
      white-space: nowrap;
    }
    .legend {
      display: flex;
      gap: 6px 14px;
      align-items: center;
      flex-wrap: wrap;
      margin: 4px 0 0;
    }
    .k {
      display: inline-block;
      width: 18px;
      height: 0;
      border-top: 3px solid #1e3a8a;
      margin-right: 4px;
      vertical-align: middle;
    }
    .k.prev {
      border-top: 3px dashed #6b7280;
    }
    .small {
      font-size: 0.875em;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class LineChart extends ChartBase {
  protected readonly W = 480;
  protected readonly H = 160;
  protected readonly P = 20;
  protected readonly grid = [0, 0.5, 1];
  protected x(i: number) {
    const n = Math.max(1, this.series().points.length - 1);
    return this.P + (i * (this.W - 2 * this.P)) / n;
  }
  protected gy(frac: number) {
    return this.H - 10 - frac * (this.H - 20);
  }
  protected y(v: number) {
    return this.gy(v / this.max());
  }
  private path(pick: (p: ChartPoint) => number | null) {
    return this.series()
      .points.map((p, i) => (pick(p) === null ? null : `${this.x(i)},${this.y(pick(p)!)}`))
      .filter(Boolean)
      .join(' ');
  }
  protected readonly curPath = computed(() => this.path((p) => p.value));
  protected readonly prevPath = computed(() =>
    this.series().prev ? this.path((p) => p.previous) : '',
  );
}

/** `BANG`: bảng số liệu; cột đầu bấm được để đi xuống. */
@Component({
  selector: 'vt-table-chart',
  template: `
    <div class="table-wrap">
      <table class="tbl">
        <caption class="sr-only">
          {{
            caption()
          }}
        </caption>
        <thead>
          <tr>
            @for (c of visibleColumns(); track c.name) {
              <th scope="col">{{ c.title }}</th>
            }
          </tr>
        </thead>
        <tbody>
          @for (p of series().points; track $index) {
            <tr>
              @for (c of visibleColumns(); track c.name; let first = $first) {
                <td>
                  @if (first && drill(); as go) {
                    <button type="button" class="link-btn" (click)="go(p.row)">
                      {{ cell(p.row, c) }}
                    </button>
                  } @else {
                    {{ cell(p.row, c) }}
                  }
                </td>
              }
            </tr>
          } @empty {
            <tr>
              <td [attr.colspan]="visibleColumns().length">Chưa có số liệu</td>
            </tr>
          }
        </tbody>
      </table>
    </div>
  `,
  styles: `
    .table-wrap {
      overflow-x: auto;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
    }
    .tbl th,
    .tbl td {
      text-align: left;
      padding: 6px 8px;
      border-bottom: 1px solid #e5e7eb;
    }
    .link-btn {
      background: none;
      border: 0;
      padding: 0;
      color: #1d4ed8;
      text-decoration: underline;
      cursor: pointer;
      font: inherit;
      text-align: left;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class TableChart extends ChartBase {
  protected readonly visibleColumns = computed(() =>
    this.columns().filter((c) => !c.name.endsWith('.id')),
  );
  protected cell(r: AnalyticsRow, c: AnalyticsColumn) {
    return formatCell(r[c.name], c);
  }
}

/** Danh mục component theo kiểu biểu đồ (`DashboardChartType`). */
export const CHART_REGISTRY: Record<DashboardChartType, Type<ChartBase>> = {
  THE_SO: StatChart,
  COT: ColumnChart,
  DUONG: LineChart,
  THANH: BarChart,
  BANG: TableChart,
};

/** Dựng biểu đồ theo `type` từ danh mục; kiểu lạ thì dùng bảng. */
@Component({
  selector: 'vt-chart',
  imports: [NgComponentOutlet],
  template: `<ng-container *ngComponentOutlet="component(); inputs: inputs()" />`,
})
export class ChartHost {
  readonly type = input.required<DashboardChartType>();
  readonly columns = input<AnalyticsColumn[]>([]);
  readonly rows = input<AnalyticsCell[][]>([]);
  readonly caption = input('');
  readonly drill = input<((row: AnalyticsRow) => void) | null>(null);
  protected readonly component = computed(() => CHART_REGISTRY[this.type()] ?? TableChart);
  protected readonly inputs = computed(() => ({
    columns: this.columns(),
    rows: this.rows(),
    caption: this.caption(),
    drill: this.drill(),
  }));
}
