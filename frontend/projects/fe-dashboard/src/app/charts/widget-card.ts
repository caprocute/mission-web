import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Router } from '@angular/router';
import {
  AnalyticsApi,
  AnalyticsRow,
  DIMENSION,
  DashboardWidget,
  DashboardWidgetData,
  SemanticModel,
} from '@vt/api-client/domains';
import { errorText, valueLabel } from '../dashboard-ui';
import { ChartHost } from './charts';
import { VtSkeletonComponent } from '@vt/design-system';

/**
 * Một widget của dashboard tự dựng (SCR-1602): tiêu đề dựng từ tên chỉ số, chiều của lớp ngữ nghĩa, biểu đồ
 * lấy từ `CHART_REGISTRY`. Số liệu dùng `data` khi trang xem đã có (GET dashboard), còn trình dựng gọi truy vấn
 * xem trước (`POST /analytics/query`); kết hợp sai hiện MSG-16-04 do máy chủ trả. Cho phép đi xuống thì bấm
 * giá trị theo đơn vị mở SCR-1603 bước đơn vị.
 */
@Component({
  selector: 'vt-widget-card',
  imports: [VtSkeletonComponent, ChartHost],
  template: `
    <h2 class="wt">{{ title() }}</h2>
    @if (filterText(); as f) {
      <p class="small muted">{{ f }}</p>
    }
    @if (result(); as r) {
      <vt-chart
        [type]="widget().chartType"
        [columns]="r.columns"
        [rows]="r.rows"
        [caption]="title()"
        [drill]="drillFn()"
      />
    } @else if (preview.error()) {
      <p class="err" role="alert">{{ err(preview.error()) }}</p>
    } @else {
      <vt-skeleton variant="chart" label="Đang tải biểu đồ" />
    }
  `,
  styles: `
    .wt {
      font-size: 1rem;
      margin: 0 0 4px;
    }
    .small {
      font-size: 0.875em;
      margin: 0 0 6px;
    }
    .muted {
      color: #4b5563;
    }
    .err {
      color: #b91c1c;
      font-size: 0.875em;
      margin: 0;
    }
  `,
})
export class WidgetCard {
  private readonly api = inject(AnalyticsApi);
  private readonly router = inject(Router);
  readonly widget = input.required<DashboardWidget>();
  readonly model = input<SemanticModel | null>(null);
  /** Số liệu sẵn có (trang xem); không có thì tự truy vấn xem trước. */
  readonly data = input<DashboardWidgetData | null>(null);
  readonly periodId = input<string | null>(null);
  /** Trình dựng ở chế độ soạn không đi xuống. */
  readonly interactive = input(true);

  protected readonly preview = rxResource({
    params: () => {
      if (this.data()) return undefined;
      const w = this.widget();
      return {
        measures: [w.measure],
        dimensions: w.dimensions ?? [],
        filters: w.filters ?? [],
        ...(this.periodId() ? { periodId: this.periodId()! } : {}),
      };
    },
    stream: ({ params }) => this.api.query(params),
  });
  protected readonly result = computed(() => {
    const d = this.data();
    if (d) return { columns: d.columns, rows: d.rows };
    return this.preview.hasValue() ? this.preview.value() : null;
  });

  private title_(name: string, kind: 'measures' | 'dimensions') {
    const list = this.model()?.[kind] as { name: string; title: string }[] | undefined;
    return list?.find((x) => x.name === name)?.title ?? null;
  }
  protected readonly title = computed(() => {
    const w = this.widget();
    const m =
      this.title_(w.measure, 'measures') ??
      this.result()?.columns.find((c) => c.name === w.measure)?.title ??
      'Chỉ số';
    const dims = (w.dimensions ?? [])
      .map((d) => this.title_(d, 'dimensions') ?? '')
      .filter(Boolean);
    return dims.length ? `${m} theo ${dims.map((d) => d.toLowerCase()).join(', ')}` : m;
  });
  protected readonly filterText = computed(() => {
    const fs = this.widget().filters ?? [];
    if (!fs.length) return null;
    return (
      'Lọc: ' +
      fs
        .map(
          (f) =>
            `${this.title_(f.member, 'dimensions') ?? 'Chiều'} là ${f.values.map((v) => valueLabel(f.member, v)).join(', ')}`,
        )
        .join('; ')
    );
  });
  protected readonly drillFn = computed(() => {
    const w = this.widget();
    if (!this.interactive() || !w.drillDown || !(w.dimensions ?? []).includes(DIMENSION.unitName))
      return null;
    return (row: AnalyticsRow) =>
      this.router.navigate(['/dashboard/units'], {
        queryParams: {
          name: String(row[DIMENSION.unitName]),
          ...(this.periodId() ? { period: this.periodId() } : {}),
        },
      });
  });
  protected err(e: unknown) {
    return errorText(e, 'Không tải được số liệu của biểu đồ.');
  }
}
