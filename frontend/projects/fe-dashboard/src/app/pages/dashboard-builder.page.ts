/*
 * SCR-1602 Trình dựng dashboard (QT-16 bước 6, 7; TL-02 SCR-1602; WEB-048).
 * Trường: 1 tên (bắt buộc, ≤ 100 ký tự, mặc định "Dashboard mới", không trùng trong không gian của người dùng,
 * 409 `ANALYTICS-409-DUPLICATE-TITLE`); 2 danh mục chỉ số, chiều (`GET /analytics/semantic-model`, chỉ đọc);
 * 3 widget (ít nhất 1, tối đa 20; vị trí, kích thước trong lưới 12 cột); 4 chỉ số của widget; 5 chiều (tối đa 2);
 * 6 kiểu biểu đồ (thẻ số, cột, đường, thanh, bảng); 7 bộ lọc "chiều = giá trị" trên chiều công bố; 8 cho phép
 * đi xuống (chỉ chỉ số có liên kết truy vết); 9 chia sẻ (`RIENG`, `DON_VI`, `TENANT`; `config.dashboard_def`).
 * Chức năng: kéo (hoặc bấm "Thêm") chỉ số vào lưới, truy vấn xem trước (`POST /analytics/query`); xem trước ở
 * chế độ người xem (phía client); lưu (`POST /analytics/dashboards`, `PUT /analytics/dashboards/{id}` kèm
 * `If-Match`); chia sẻ (`POST /analytics/dashboards/{id}/shares`, EVT-21, MSG-16-05). BR-16-02 chỉ chỉ số và
 * chiều của lớp ngữ nghĩa, không SQL tự do; kết hợp sai hiện MSG-16-04. Bố cục lưu dạng định nghĩa (không có
 * số liệu); biểu đồ dựng từ `CHART_REGISTRY` theo `chartType`. Quyền: SCR-1602.dung, .chia-se.
 */
import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { OrgApi, toProblem } from '@vt/api-client';
import { AnalyticsApi, AnalyticsDashboard, CHART_TYPES, CHART_TYPE_LABEL, DASHBOARD_LIMITS, DashboardShareScope, DashboardWidget, SHARE_SCOPE_LABEL, SemanticDimension, SemanticMeasure } from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { MultiSelectModule } from 'primeng/multiselect';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { forkJoin, map } from 'rxjs';
import { WidgetCard } from '../charts/widget-card';
import { DASHBOARD_CAN } from '../dashboard-permissions';
import { eq, errorText, fmtDateTime } from '../dashboard-ui';
import { defaultHeight, gridArea, pack, suggestChart } from './dashboard-layout';

const WIDTHS = [
  { label: 'Một phần tư', value: 3 },
  { label: 'Một nửa', value: 6 },
  { label: 'Ba phần tư', value: 9 },
  { label: 'Toàn bộ chiều ngang', value: 12 },
];

@Component({
  selector: 'vt-dashboard-builder-page',
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    CheckboxModule,
    DialogModule,
    InputTextModule,
    MessageModule,
    MultiSelectModule,
    SelectModule,
    SelectButtonModule,
    WidgetCard,
  ],
  template: `
    <p class="crumb"><a [routerLink]="id() ? '../..' : '..'">Dashboard của tôi</a></p>
    <h1>{{ id() ? 'Sửa dashboard' : 'Tạo dashboard' }}</h1>
    <div class="head">
      <div class="title-field">
        <label for="db-title">Tên dashboard</label>
        <input
          pInputText
          id="db-title"
          [maxlength]="limits.titleMax"
          [ngModel]="title()"
          (ngModelChange)="title.set($event)"
        />
        @if (errs()['title']; as m) {
          <p class="err">{{ m }}</p>
        }
        <p class="hint">
          @if (current(); as c) {
            Bản nháp, lưu lần gần nhất lúc {{ dt(c.updatedAt) }}.
          } @else {
            Chưa lưu.
          }
        </p>
      </div>
      <div class="actions">
        <p-button
          [label]="previewMode() ? 'Quay lại chỉnh sửa' : 'Xem trước'"
          [outlined]="true"
          [attr.aria-pressed]="previewMode()"
          (onClick)="previewMode.set(!previewMode())"
        />
        <p-button
          label="Lưu dashboard"
          icon="pi pi-save"
          [disabled]="!widgets().length || busy()"
          [loading]="busy()"
          (onClick)="save()"
        />
        @if (can('SCR-1602.chia-se') && current()) {
          <p-button
            label="Chia sẻ"
            icon="pi pi-share-alt"
            severity="secondary"
            (onClick)="openShare()"
          />
        }
        @if (current(); as c) {
          <a
            pButton
            [routerLink]="['/dashboard/dashboards', c.id]"
            label="Mở dashboard"
            [text]="true"
          ></a>
        }
      </div>
    </div>

    @if (notice(); as n) {
      <p-message severity="success" role="status">{{ n }}</p-message>
    }
    @if (error(); as e) {
      <p-message severity="error" role="alert">{{ e }}</p-message>
    }
    @if (modelRes.error() || loaded.error()) {
      <p-message severity="error" role="alert">{{
        err(modelRes.error() ?? loaded.error())
      }}</p-message>
    }

    <div class="builder" [class.preview]="previewMode()">
      @if (!previewMode()) {
        <aside class="box catalog" aria-labelledby="db-cat-h">
          <h2 class="h" id="db-cat-h">Chỉ số</h2>
          <ul class="plain-list">
            @for (m of measures(); track m.name) {
              <li
                draggable="true"
                (dragstart)="drag($event, 'm', m.name)"
                [attr.data-measure]="m.name"
                class="drag"
              >
                <span>{{ m.title }}</span>
                <p-button
                  icon="pi pi-plus"
                  [text]="true"
                  size="small"
                  [ariaLabel]="'Thêm biểu đồ ' + m.title"
                  (onClick)="addWidget(m.name)"
                />
              </li>
            }
          </ul>
          <h2 class="h h-gap">Chiều</h2>
          <ul class="plain-list">
            @for (d of dimensions(); track d.name) {
              <li
                draggable="true"
                (dragstart)="drag($event, 'd', d.name)"
                [attr.data-dimension]="d.name"
                class="drag"
              >
                <span>{{ d.title }}</span>
                <p-button
                  icon="pi pi-plus"
                  [text]="true"
                  size="small"
                  [disabled]="!selected()"
                  [ariaLabel]="'Thêm chiều ' + d.title + ' vào biểu đồ đang chọn'"
                  (onClick)="addDimension(d.name)"
                />
              </li>
            }
          </ul>
          @if (model(); as m) {
            <p class="hint">
              Danh mục chỉ số chuẩn, phiên bản {{ m.version }}. Chỉ dùng được chỉ số và chiều trong
              danh mục.
            </p>
          }
        </aside>
      }

      <section class="canvas" aria-label="Lưới dashboard">
        <div class="board">
          @for (w of widgets(); track w.id; let i = $index) {
            <section
              class="box widget"
              [class.sel]="!previewMode() && selectedId() === w.id"
              [style.grid-area]="area(i)"
              [attr.data-widget-id]="w.id"
              (dragover)="$event.preventDefault()"
              (drop)="dropOnWidget($event, w)"
            >
              <vt-widget-card [widget]="w" [model]="model()" [interactive]="previewMode()" />
              @if (!previewMode()) {
                <p-button
                  [label]="selectedId() === w.id ? 'Đang chọn' : 'Chọn để sửa'"
                  [text]="true"
                  size="small"
                  [attr.aria-pressed]="selectedId() === w.id"
                  (onClick)="selectedId.set(w.id)"
                />
              }
            </section>
          }
          @if (!previewMode()) {
            <div
              class="drop"
              (dragover)="$event.preventDefault()"
              (drop)="dropNew($event)"
              [style.grid-column]="'1 / -1'"
            >
              Thả chỉ số vào đây, hoặc bấm nút thêm cạnh tên chỉ số.
            </div>
          }
        </div>
      </section>

      @if (!previewMode()) {
        <aside class="box props" aria-labelledby="db-props-h">
          <h2 class="h" id="db-props-h">Thuộc tính biểu đồ</h2>
          @if (selected(); as w) {
            <div class="field">
              <label for="wp-measure">Chỉ số</label>
              <p-select
                inputId="wp-measure"
                [options]="measures()"
                optionLabel="title"
                optionValue="name"
                [ngModel]="w.measure"
                (ngModelChange)="setMeasure($event)"
                appendTo="body"
              />
            </div>
            <div class="field">
              <label for="wp-dims">Chiều (tối đa 2)</label>
              <p-multiselect
                inputId="wp-dims"
                [options]="dimensions()"
                optionLabel="title"
                optionValue="name"
                [selectionLimit]="limits.maxDimensions"
                [showToggleAll]="false"
                [ngModel]="w.dimensions ?? []"
                (ngModelChange)="setDimensions($event)"
                placeholder="Không theo chiều nào"
                appendTo="body"
              />
              @for (m of combinationErrors(w); track m) {
                <p class="err" role="alert">{{ m }}</p>
              }
            </div>
            <div class="field">
              <label for="wp-type">Kiểu biểu đồ</label>
              <p-select
                inputId="wp-type"
                [options]="chartOptions"
                optionLabel="label"
                optionValue="value"
                [ngModel]="w.chartType"
                (ngModelChange)="patch({ chartType: $event, h: height($event) })"
                appendTo="body"
              />
            </div>
            <div class="field">
              <label for="wp-width">Độ rộng</label>
              <p-select
                inputId="wp-width"
                [options]="widths"
                optionLabel="label"
                optionValue="value"
                [ngModel]="w.w"
                (ngModelChange)="patch({ w: $event })"
                appendTo="body"
              />
            </div>
            <fieldset class="field">
              <legend class="label">Bộ lọc</legend>
              <ul class="plain-list">
                @for (f of w.filters ?? []; track $index; let fi = $index) {
                  <li class="filter">
                    <span>{{ dimTitle(f.member) }} là {{ f.values.join(', ') }}</span>
                    <p-button
                      icon="pi pi-times"
                      [text]="true"
                      size="small"
                      [ariaLabel]="'Bỏ bộ lọc ' + dimTitle(f.member)"
                      (onClick)="removeFilter(fi)"
                    />
                  </li>
                }
              </ul>
              <div class="filter-new">
                <label for="wp-fdim" class="sr-only">Chiều lọc</label>
                <p-select
                  inputId="wp-fdim"
                  [options]="filterDims()"
                  optionLabel="title"
                  optionValue="name"
                  [ngModel]="filterDim()"
                  (ngModelChange)="filterDim.set($event)"
                  placeholder="Chọn chiều"
                  appendTo="body"
                />
                <label for="wp-fval" class="sr-only">Giá trị lọc</label>
                <p-select
                  inputId="wp-fval"
                  [options]="filterValues()"
                  [ngModel]="filterValue()"
                  (ngModelChange)="filterValue.set($event)"
                  placeholder="Chọn giá trị"
                  [disabled]="!filterDim()"
                  appendTo="body"
                />
                <p-button
                  label="Thêm bộ lọc"
                  size="small"
                  [outlined]="true"
                  [disabled]="!filterDim() || !filterValue()"
                  (onClick)="addFilter()"
                />
              </div>
            </fieldset>
            <div class="field check">
              <p-checkbox
                inputId="wp-drill"
                [binary]="true"
                [disabled]="!measureOf(w.measure)?.drillable"
                [ngModel]="w.drillDown && !!measureOf(w.measure)?.drillable"
                (ngModelChange)="patch({ drillDown: $event })"
              />
              <label for="wp-drill" class="plain">Cho phép đi xuống tới minh chứng</label>
            </div>
            @if (!measureOf(w.measure)?.drillable) {
              <p class="hint">Chỉ số này không có liên kết truy vết nên không đi xuống được.</p>
            }
            <div class="actions">
              <p-button label="Đưa lên trước" size="small" [text]="true" (onClick)="move(-1)" />
              <p-button label="Đưa xuống sau" size="small" [text]="true" (onClick)="move(1)" />
              <p-button
                label="Xoá biểu đồ"
                size="small"
                severity="danger"
                [outlined]="true"
                (onClick)="removeSelected()"
              />
            </div>
          } @else {
            <p class="muted">Chọn một biểu đồ trên lưới để sửa thuộc tính.</p>
          }
        </aside>
      }
    </div>

    <p-dialog
      closeAriaLabel="Đóng"
      header="Chia sẻ dashboard"
      [modal]="true"
      [visible]="sharing()"
      (visibleChange)="sharing.set($event)"
      [style]="{ width: 'min(560px, 95vw)' }"
    >
      <p-message severity="info">
        Người được chia sẻ chỉ thấy số liệu trong phạm vi quản lý của chính họ.
      </p-message>
      <div class="field">
        <span class="label" id="cs-scope-l">Phạm vi chia sẻ</span>
        <p-selectbutton
          [options]="scopeOptions"
          optionLabel="label"
          optionValue="value"
          [ngModel]="shareScope()"
          (ngModelChange)="shareScope.set($event)"
          ariaLabelledBy="cs-scope-l"
        />
      </div>
      @if (shareScope() === 'DON_VI') {
        <div class="field">
          <label for="cs-units">Đơn vị được xem</label>
          <p-multiselect
            inputId="cs-units"
            [options]="unitOptions()"
            optionLabel="name"
            optionValue="id"
            [ngModel]="shareUnits()"
            (ngModelChange)="shareUnits.set($event)"
            placeholder="Chọn đơn vị"
            appendTo="body"
          />
        </div>
      }
      @if (shareError(); as e) {
        <p-message severity="error" role="alert">{{ e }}</p-message>
      }
      <div class="actions">
        <p-button label="Lưu chia sẻ" [loading]="busy()" (onClick)="share()" />
        <p-button label="Huỷ" [text]="true" severity="secondary" (onClick)="sharing.set(false)" />
      </div>
    </p-dialog>
  `,
  styleUrls: ['../dashboard.css', './dashboard-layout.css'],
  styles: `
    :host {
      --p-togglebutton-color: #374151;
    }
    .title-field {
      min-width: min(420px, 100%);
    }
    .title-field input {
      width: 100%;
    }
    .builder {
      display: grid;
      grid-template-columns: 240px minmax(0, 1fr) 300px;
      gap: 12px;
      align-items: start;
    }
    .builder.preview {
      grid-template-columns: minmax(0, 1fr);
    }
    @media (max-width: 1100px) {
      .builder {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    .drag {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 6px;
      cursor: grab;
      padding: 4px 0;
    }
    .h-gap {
      margin-top: 16px;
    }
    .widget.sel {
      outline: 3px solid #1e3a8a;
    }
    .drop {
      border: 2px dashed #6b7280;
      border-radius: 8px;
      padding: 24px;
      text-align: center;
      color: #374151;
    }
    .filter {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    .filter-new {
      display: grid;
      gap: 6px;
    }
    fieldset {
      border: 0;
      padding: 0;
    }
    .check {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    label.plain {
      font-weight: 400;
      display: inline;
      margin: 0;
    }
    :host ::ng-deep .props .p-select,
    :host ::ng-deep .props .p-multiselect,
    :host ::ng-deep .field .p-multiselect {
      width: 100%;
    }
  `,
})
export class DashboardBuilderPage {
  private readonly api = inject(AnalyticsApi);
  private readonly orgApi = inject(OrgApi);
  private readonly router = inject(Router);
  protected readonly can = inject(DASHBOARD_CAN);
  /** Có khi sửa (`dashboards/:id/edit`), trống khi tạo mới. */
  readonly id = input<string | null>(null);

  protected readonly limits = DASHBOARD_LIMITS;
  protected readonly widths = WIDTHS;
  protected readonly chartOptions = CHART_TYPES.map((t) => ({
    value: t,
    label: CHART_TYPE_LABEL[t],
  }));
  protected readonly scopeOptions = (['RIENG', 'DON_VI', 'TENANT'] as DashboardShareScope[]).map(
    (v) => ({
      value: v,
      label: SHARE_SCOPE_LABEL[v],
    }),
  );

  protected readonly modelRes = rxResource({ stream: () => this.api.semanticModel() });
  protected readonly model = computed(() =>
    this.modelRes.hasValue() ? this.modelRes.value() : null,
  );
  protected readonly measures = computed<SemanticMeasure[]>(() => this.model()?.measures ?? []);
  /** Chiều kỹ thuật (`*.id`) chỉ dùng để đi xuống, không đưa vào danh mục kéo thả. */
  protected readonly dimensions = computed<SemanticDimension[]>(() =>
    (this.model()?.dimensions ?? []).filter((d) => !d.name.endsWith('.id')),
  );

  protected readonly loaded = rxResource({
    params: () => this.id() ?? undefined,
    stream: ({ params }) => this.api.dashboard(params, { includeData: false }),
  });
  protected readonly current = linkedSignal<AnalyticsDashboard | null>(() =>
    this.loaded.hasValue() ? this.loaded.value() : null,
  );
  protected readonly title = linkedSignal(() => this.current()?.title ?? 'Dashboard mới');
  protected readonly widgets = linkedSignal<DashboardWidget[]>(() =>
    structuredClone(this.current()?.layout ?? []),
  );
  protected readonly selectedId = linkedSignal<AnalyticsDashboard | null, string | null>({
    source: () => this.current(),
    computation: (c) => c?.layout[0]?.id ?? null,
  });
  protected readonly selected = computed(
    () => this.widgets().find((w) => w.id === this.selectedId()) ?? null,
  );
  protected readonly previewMode = signal(false);

  protected readonly filterDim = signal<string | null>(null);
  protected readonly filterValue = signal<string | null>(null);
  /** Chiều lọc được: chiều công bố hỗ trợ chỉ số của biểu đồ đang chọn. */
  protected readonly filterDims = computed(() => {
    const m = this.selected()?.measure;
    return this.dimensions().filter(
      (d) => d.type !== 'time' && (!m || d.supportedMeasures?.includes(m)),
    );
  });
  /** Giá trị lọc lấy từ lớp ngữ nghĩa (truy vấn theo đúng chiều), không nhập tự do. */
  private readonly filterValuesRes = rxResource({
    params: () => {
      const d = this.filterDim();
      const m = this.selected()?.measure;
      return d && m ? { d, m } : undefined;
    },
    stream: ({ params }) =>
      this.api
        .query({ measures: [params.m], dimensions: [params.d], limit: 200 })
        .pipe(map((r) => r.rows.map((x) => String(x[0])).filter((v) => v && v !== 'null'))),
  });
  protected readonly filterValues = computed(() =>
    this.filterValuesRes.hasValue() ? this.filterValuesRes.value() : [],
  );

  protected readonly sharing = signal(false);
  protected readonly shareScope = signal<DashboardShareScope>('RIENG');
  protected readonly shareUnits = signal<string[]>([]);
  protected readonly shareError = signal<string | null>(null);
  private readonly unitsRes = rxResource({
    params: () => (this.sharing() ? true : undefined),
    stream: () =>
      forkJoin([this.orgApi.units('unit'), this.orgApi.units('children')]).pipe(
        map(([a, b]) => [...a.items, ...b.items]),
      ),
  });
  protected readonly unitOptions = computed(() =>
    this.unitsRes.hasValue() ? this.unitsRes.value() : [],
  );

  protected readonly errs = signal<Record<string, string>>({});
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal(false);
  private seq = 0;

  protected measureOf(name: string) {
    return this.measures().find((m) => m.name === name);
  }
  protected dimTitle(name: string) {
    return this.model()?.dimensions.find((d) => d.name === name)?.title ?? 'Chiều';
  }
  protected height(t: DashboardWidget['chartType']) {
    return defaultHeight(t);
  }
  protected area(i: number) {
    return gridArea(pack(this.widgets())[i]);
  }
  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected err(e: unknown) {
    return errorText(e, 'Không tải được dữ liệu của trình dựng.');
  }

  /** MSG-16-04: "Chỉ số {chi_so} không hỗ trợ chiều {chieu}." (chặn sớm; máy chủ vẫn kiểm). */
  protected combinationErrors(w: DashboardWidget): string[] {
    const m = this.measureOf(w.measure);
    return [...(w.dimensions ?? []), ...(w.filters ?? []).map((f) => f.member)]
      .map((n) => this.model()?.dimensions.find((d) => d.name === n))
      .filter((d): d is SemanticDimension => !!d && !d.supportedMeasures?.includes(w.measure))
      .map((d) => `Chỉ số ${m?.title ?? 'đã chọn'} không hỗ trợ chiều ${d.title}.`);
  }

  private nextId() {
    const used = new Set(this.widgets().map((w) => w.id));
    let id = '';
    do id = `w${this.widgets().length + ++this.seq}`;
    while (used.has(id));
    return id;
  }

  addWidget(measure: string) {
    this.notice.set(null);
    if (this.widgets().length >= DASHBOARD_LIMITS.maxWidgets) {
      this.error.set(`Mỗi dashboard tối đa ${DASHBOARD_LIMITS.maxWidgets} biểu đồ.`);
      return;
    }
    this.error.set(null);
    const w: DashboardWidget = {
      id: this.nextId(),
      x: 0,
      y: 0,
      w: 3,
      h: defaultHeight('THE_SO'),
      measure,
      dimensions: [],
      chartType: 'THE_SO',
      filters: [],
      drillDown: !!this.measureOf(measure)?.drillable,
    };
    this.widgets.set([...this.widgets(), w]);
    this.selectedId.set(w.id);
  }

  protected patch(p: Partial<DashboardWidget>) {
    const id = this.selectedId();
    this.widgets.set(this.widgets().map((w) => (w.id === id ? { ...w, ...p } : w)));
  }

  protected setMeasure(measure: string) {
    this.patch({ measure, drillDown: !!this.measureOf(measure)?.drillable });
  }

  protected setDimensions(dims: string[]) {
    const dimensions = dims.slice(0, DASHBOARD_LIMITS.maxDimensions);
    const w = this.selected();
    const type = this.model()?.dimensions.find((d) => d.name === dimensions[0])?.type;
    // đổi từ thẻ số sang biểu đồ khi thêm chiều đầu tiên
    const chartType =
      w && (w.chartType === 'THE_SO') !== !dimensions.length
        ? suggestChart(dimensions, type)
        : w?.chartType;
    this.patch({
      dimensions,
      ...(chartType
        ? { chartType, h: defaultHeight(chartType), w: dimensions.length && w!.w < 6 ? 6 : w!.w }
        : {}),
    });
  }

  addDimension(name: string) {
    const w = this.selected();
    if (!w) return;
    if ((w.dimensions ?? []).includes(name)) return;
    if ((w.dimensions ?? []).length >= DASHBOARD_LIMITS.maxDimensions) {
      this.error.set(`Mỗi biểu đồ tối đa ${DASHBOARD_LIMITS.maxDimensions} chiều.`);
      return;
    }
    this.error.set(null);
    this.setDimensions([...(w.dimensions ?? []), name]);
  }

  protected addFilter() {
    const d = this.filterDim();
    const v = this.filterValue();
    const w = this.selected();
    if (!d || !v || !w) return;
    this.patch({ filters: [...(w.filters ?? []).filter((f) => f.member !== d), eq(d, v)] });
    this.filterDim.set(null);
    this.filterValue.set(null);
  }
  protected removeFilter(i: number) {
    const w = this.selected();
    if (w) this.patch({ filters: (w.filters ?? []).filter((_, j) => j !== i) });
  }

  protected move(dir: -1 | 1) {
    const ws = [...this.widgets()];
    const i = ws.findIndex((w) => w.id === this.selectedId());
    const j = i + dir;
    if (i < 0 || j < 0 || j >= ws.length) return;
    [ws[i], ws[j]] = [ws[j], ws[i]];
    this.widgets.set(ws);
  }

  protected removeSelected() {
    const id = this.selectedId();
    const ws = this.widgets().filter((w) => w.id !== id);
    this.widgets.set(ws);
    this.selectedId.set(ws[0]?.id ?? null);
  }

  // ── kéo thả (HTML5); mọi thao tác kéo đều có nút tương đương cho bàn phím ──
  protected drag(e: DragEvent, kind: 'm' | 'd', name: string) {
    e.dataTransfer?.setData('text/plain', `${kind}:${name}`);
    if (e.dataTransfer) e.dataTransfer.effectAllowed = 'copy';
  }
  private dropped(e: DragEvent): { kind: string; name: string } | null {
    e.preventDefault();
    const raw = e.dataTransfer?.getData('text/plain') ?? '';
    const i = raw.indexOf(':');
    return i > 0 ? { kind: raw.slice(0, i), name: raw.slice(i + 1) } : null;
  }
  protected dropNew(e: DragEvent) {
    const d = this.dropped(e);
    if (d?.kind === 'm' && this.measureOf(d.name)) this.addWidget(d.name);
  }
  protected dropOnWidget(e: DragEvent, w: DashboardWidget) {
    const d = this.dropped(e);
    if (!d) return;
    e.stopPropagation();
    if (d.kind === 'm' && this.measureOf(d.name)) this.addWidget(d.name);
    if (d.kind === 'd') {
      this.selectedId.set(w.id);
      this.addDimension(d.name);
    }
  }

  save() {
    this.notice.set(null);
    this.error.set(null);
    const title = this.title().trim();
    const e: Record<string, string> = {};
    if (!title) e['title'] = 'Nhập tên dashboard.';
    else if (title.length > DASHBOARD_LIMITS.titleMax)
      e['title'] = `Tên dashboard tối đa ${DASHBOARD_LIMITS.titleMax} ký tự.`;
    this.errs.set(e);
    if (Object.keys(e).length) return;
    if (!this.widgets().length) {
      this.error.set('Dashboard cần ít nhất một biểu đồ.');
      return;
    }
    const bad = this.widgets().flatMap((w) => this.combinationErrors(w));
    if (bad.length) {
      this.error.set(bad[0]);
      return;
    }
    const body = { title, layout: pack(this.widgets()) };
    const cur = this.current();
    this.busy.set(true);
    (cur ? this.api.saveDashboard(cur, body) : this.api.createDashboard(body)).subscribe({
      next: (d) => {
        this.busy.set(false);
        this.current.set(d);
        this.notice.set('Đã lưu dashboard.');
        if (!cur)
          this.router.navigate(['/dashboard/dashboards', d.id, 'edit'], { replaceUrl: true });
      },
      error: (x) => {
        this.busy.set(false);
        const p = toProblem(x);
        if (p.errors?.some((m) => m.field === 'title')) {
          this.errs.set({ title: p.errors.map((m) => m.message).join(' ') });
          return;
        }
        this.error.set(errorText(x, 'Không lưu được dashboard.'));
        if (p.status === 412) this.loaded.reload();
      },
    });
  }

  protected openShare() {
    const c = this.current();
    if (!c) return;
    this.shareScope.set(c.shareScope);
    this.shareUnits.set([...c.sharedUnitIds]);
    this.shareError.set(null);
    this.sharing.set(true);
  }

  protected share() {
    const c = this.current();
    if (!c) return;
    if (this.shareScope() === 'DON_VI' && !this.shareUnits().length) {
      this.shareError.set('Chọn ít nhất một đơn vị được xem.');
      return;
    }
    this.busy.set(true);
    this.api
      .share(c, {
        shareScope: this.shareScope(),
        ...(this.shareScope() === 'DON_VI' ? { sharedUnitIds: this.shareUnits() } : {}),
      })
      .subscribe({
        next: (d) => {
          this.busy.set(false);
          this.current.set(d);
          this.sharing.set(false);
          this.notice.set(
            d.shareScope === 'RIENG'
              ? 'Dashboard chỉ còn mình đồng chí xem được.'
              : 'Đã chia sẻ dashboard. Người được chia sẻ chỉ thấy số liệu trong phạm vi quản lý của chính họ.',
          );
        },
        error: (x) => {
          this.busy.set(false);
          this.shareError.set(errorText(x, 'Không chia sẻ được dashboard.'));
        },
      });
  }
}
