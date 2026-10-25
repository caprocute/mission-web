/*
 * SCR-1601 Bảng điều hành lãnh đạo (QT-16 bước 1 – 4, 8; TL-02 SCR-1601; WEB-047).
 * Trường: 1 kỳ (`config.period`, mặc định kỳ đang mở); 2 tiến độ mục tiêu O `okr.tien_do_o`; 3 nhiệm vụ đúng
 * hạn `task.ty_le_dung_han`; 4 việc chậm `task.so_viec_cham`; 5 hồ sơ chờ thẩm định `appraisal.cho_tham_dinh`;
 * 6 tiến độ theo đơn vị (chiều `org_unit`, ngưỡng màu suy luận 80/60, wiki/14); 7 xu hướng 6 tuần kỳ này và
 * kỳ trước; 8 tóm tắt tuần `ai.suggestion_log` kind TOM_TAT_TUAN (nhãn "Nội dung do AI tạo", nguồn, thời
 * điểm, BR-16-06); 9 việc cần chỉ đạo (`GET /tasks?overdue&includeSubunits`, sắp theo mức trễ).
 * Chức năng: tải bảng (các truy vấn `POST /analytics/query` song song), so với quý trước, lọc khối và luồng,
 * bấm đơn vị mở SCR-1603, Nhắc (`POST /tasks/{id}/reminders`, EVT-21, MSG-16-02), Giao lại (`POST
 * /tasks/{id}/reassign`, EVT-04, MSG-16-03), Xem dữ liệu đã dùng (`GET /ai/suggestions/{id}/sources`), Xuất
 * báo cáo (`POST /analytics/reports`, 202). BR-16-01 phạm vi cây tổ chức do máy chủ lọc; BR-16-07 nhắc,
 * giao lại dùng quyền của người bấm; BR-16-08 hiện thời điểm cập nhật, trễ quá 5 phút hiện MSG-16-01.
 * Quyền (wiki/15a): SCR-1601.xem, .nhac, .giao-lai, .nguon-ai; giao việc mới SCR-0501.giao; mở đơn vị
 * SCR-1603.xem; hồ sơ chờ thẩm định SCR-0902.xem.
 */
import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { OrgApi, TaskApi, TaskSummary } from '@vt/api-client';
import {
  AiSourceList,
  AnalyticsApi,
  AnalyticsQueryResult,
  DIMENSION,
  MEASURE,
  PREVIOUS_SUFFIX,
  PeriodApi,
  cellNumber,
  resultRows,
} from '@vt/api-client/domains';
import { permittedOn } from '@vt/auth';
import { MenuItem } from 'primeng/api';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { MenuModule } from 'primeng/menu';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TagModule } from 'primeng/tag';
import { TextareaModule } from 'primeng/textarea';
import { forkJoin } from 'rxjs';
import { LineChart } from '../charts/charts';
import { DASHBOARD_CAN } from '../dashboard-permissions';
import {
  eq,
  errorText,
  fmtDate,
  fmtDateTime,
  fmtNum,
  progressStatus,
  weekOfPeriod,
} from '../dashboard-ui';
import { VtSkeletonComponent } from '@vt/design-system';

interface Kpi {
  key: string;
  title: string;
  value: number | null;
  previous: number | null;
  unit: string;
}

@Component({
  selector: 'vt-leader-dashboard-page',
  imports: [
    VtSkeletonComponent,
    FormsModule,
    RouterLink,
    ButtonModule,
    DialogModule,
    MenuModule,
    MessageModule,
    SelectModule,
    TagModule,
    TextareaModule,
    LineChart,
  ],
  template: `
    <div class="head">
      <div>
        <h1>Bảng điều hành</h1>
        <p class="muted">
          {{ periodName() }}{{ week() ? ', ' + week() : '' }}.
          @if (asOf(); as t) {
            Số liệu cập nhật lúc {{ dt(t) }}, theo phạm vi đơn vị đồng chí phụ trách.
          }
        </p>
      </div>
      <div class="actions">
        <p-button
          [label]="compare() ? 'Bỏ so sánh với quý trước' : 'So với quý trước'"
          [outlined]="!compare()"
          [attr.aria-pressed]="compare()"
          (onClick)="compare.set(!compare())"
        />
        <p-button
          label="Xuất báo cáo"
          icon="pi pi-download"
          severity="secondary"
          [loading]="exporting()"
          (onClick)="exportMenu.toggle($event)"
          aria-haspopup="true"
        />
        <p-menu #exportMenu [model]="exportItems" [popup]="true" appendTo="body" />
        @if (can('SCR-0501.giao')) {
          <a pButton routerLink="/tasks/assign" icon="pi pi-plus" label="Giao việc"></a>
        }
      </div>
    </div>

    <div class="filters">
      <div>
        <label for="bd-period">Kỳ</label>
        <p-select
          inputId="bd-period"
          [options]="periodOptions()"
          optionLabel="name"
          optionValue="id"
          [ngModel]="periodId()"
          (ngModelChange)="periodId.set($event)"
        />
      </div>
      <div>
        <label for="bd-group">Khối</label>
        <p-select
          inputId="bd-group"
          [options]="groupOptions()"
          [loading]="optionsRes.isLoading()"
          [ngModel]="group()"
          (ngModelChange)="group.set($event)"
          [showClear]="true"
          placeholder="Tất cả các khối"
        />
      </div>
      <div>
        <label for="bd-flow">Luồng</label>
        <p-select
          inputId="bd-flow"
          [options]="flowOptions()"
          [loading]="optionsRes.isLoading()"
          [ngModel]="flow()"
          (ngModelChange)="flow.set($event)"
          [showClear]="true"
          placeholder="Đảng và Nhà nước"
        />
      </div>
    </div>

    @if (stale(); as t) {
      <p-message severity="warn" role="status">
        Số liệu cập nhật lúc {{ dt(t) }}, chậm hơn thường lệ.
      </p-message>
    }
    @if (notice(); as n) {
      <p-message severity="success" role="status">{{ n }}</p-message>
    }
    @if (error(); as e) {
      <p-message severity="error" role="alert">{{ e }}</p-message>
    }

    @if (kpiRes.error()) {
      <p-message severity="error">{{ err(kpiRes.error()) }}</p-message>
    }
    <section class="grid kpis" aria-label="Bốn chỉ số chính">
      @for (k of kpis(); track k.key) {
        <div class="kpi" [attr.data-measure]="k.key">
          <p class="t">{{ k.title }}</p>
          <p class="v">{{ num(k.value) }}{{ k.unit }}</p>
          @if (compare() && k.value !== null && k.previous !== null) {
            <p class="small" [class.delta-up]="better(k)" [class.delta-down]="!better(k)">
              {{ k.value >= k.previous ? 'Tăng' : 'Giảm' }} {{ num(abs(k.value - k.previous))
              }}{{ k.unit ? ' điểm phần trăm' : '' }} so với quý trước
            </p>
          }
          @if (k.key === M.choThamDinh && can('SCR-0902.xem')) {
            <a routerLink="/appraisal/tham-dinh" class="small">Mở danh sách thẩm định</a>
          }
        </div>
      } @empty {
        <vt-skeleton variant="stats" label="Đang tải chỉ số" />
      }
    </section>

    <div class="grid cols">
      <div>
        <section class="box" aria-labelledby="bd-units-h">
          <h2 class="h" id="bd-units-h">Tiến độ mục tiêu O theo đơn vị</h2>
          @if (unitRes.error()) {
            <p-message severity="error">{{ err(unitRes.error()) }}</p-message>
          } @else {
            <div class="table-wrap">
              <table class="tbl units">
                <caption class="sr-only">
                  Tiến độ mục tiêu O của các đơn vị trong phạm vi
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Đơn vị</th>
                    <th scope="col">Tiến độ</th>
                    @if (compare()) {
                      <th scope="col">Quý trước</th>
                    }
                    <th scope="col">Tình trạng</th>
                  </tr>
                </thead>
                <tbody>
                  @for (u of units(); track u.id) {
                    <tr [attr.data-unit-id]="u.id">
                      <td>
                        @if (can('SCR-1603.xem')) {
                          <a
                            [routerLink]="['units']"
                            [queryParams]="{ id: u.id, name: u.name, period: periodId() }"
                            >{{ u.name }}</a
                          >
                        } @else {
                          {{ u.name }}
                        }
                      </td>
                      <td>
                        <div class="prog">
                          <span class="bar" aria-hidden="true"
                            ><span [style.width.%]="u.value ?? 0"></span
                          ></span>
                          <span>{{ num(u.value) }}%</span>
                        </div>
                      </td>
                      @if (compare()) {
                        <td>{{ num(u.previous) }}%</td>
                      }
                      <td>
                        @if (status(u.value); as s) {
                          <p-tag [value]="s.label" [severity]="s.severity" />
                        }
                      </td>
                    </tr>
                  } @empty {
                    <tr>
                      @if (periodsRes.isLoading() || unitRes.isLoading()) {
                        <td colspan="4">
                          <vt-skeleton
                            variant="table"
                            [header]="false"
                            [columns]="3"
                            [rows]="5"
                            label="Đang tải tiến độ theo đơn vị"
                          />
                        </td>
                      } @else {
                        <td colspan="4">Chưa có số liệu của kỳ này.</td>
                      }
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        </section>

        <section class="box" aria-labelledby="bd-trend-h">
          <h2 class="h" id="bd-trend-h">Xu hướng tiến độ 6 tuần</h2>
          @if (trendRes.hasValue()) {
            <vt-line-chart
              [columns]="trendRes.value().columns"
              [rows]="trendRes.value().rows"
              caption="Tiến độ mục tiêu O theo tuần, kỳ này và kỳ trước"
              [seriesNames]="[periodName(), previousName()]"
            />
          } @else if (trendRes.error()) {
            <p-message severity="error">{{ err(trendRes.error()) }}</p-message>
          } @else {
            <vt-skeleton variant="chart" label="Đang tải biểu đồ" />
          }
        </section>
      </div>

      <div>
        <section class="box summary" aria-labelledby="bd-sum-h">
          <h2 class="h" id="bd-sum-h">
            Tóm tắt tuần <span class="ai-tag">Nội dung do AI tạo</span>
          </h2>
          @if (summary(); as s) {
            <ul class="items">
              @for (i of s.items ?? []; track $index) {
                <li>{{ i }}</li>
              }
            </ul>
            <p class="small muted">
              Nguồn: {{ sourceTitles(s.sources) }}. Tạo lúc {{ dt(s.createdAt) }}.
            </p>
            @if (can('SCR-1601.nguon-ai')) {
              <p-button
                label="Xem dữ liệu đã dùng"
                [text]="true"
                size="small"
                [loading]="busy() === 'nguon'"
                (onClick)="openSources(s.id)"
              />
            }
          } @else if (periodsRes.isLoading() || summaryRes.isLoading()) {
            <vt-skeleton variant="list" [rows]="4" label="Đang tải tổng hợp" />
          } @else {
            <p class="muted">Chưa có tóm tắt tuần này.</p>
          }
        </section>

        <section class="box" id="can-chi-dao" aria-labelledby="bd-cd-h">
          <h2 class="h" id="bd-cd-h">Cần chỉ đạo ngay</h2>
          @if (taskRes.error()) {
            <p-message severity="error">{{ err(taskRes.error()) }}</p-message>
          }
          <ul class="plain-list directives">
            @for (t of directives(); track t.id) {
              <li [attr.data-task-id]="t.id">
                <p class="title">{{ t.title }}</p>
                <p class="small muted">{{ t.unitName }} · Hạn {{ d(t.dueDate) }}</p>
                <p-tag
                  [value]="lateLabel(t)"
                  [severity]="t.workingDaysLeft < 0 ? 'danger' : 'warn'"
                />
                <div class="actions row">
                  @if (can('SCR-1601.nhac')) {
                    <p-button
                      label="Nhắc"
                      size="small"
                      [outlined]="true"
                      [loading]="busy() === t.id + ':nhac'"
                      (onClick)="remind(t)"
                      [ariaLabel]="'Nhắc việc ' + t.title"
                    />
                  }
                  <!-- Chỉ người giao việc mới giao lại được (BR-16-07): theo thao tác máy chủ cho phép -->
                  @if (can('SCR-1601.giao-lai') && permittedOn(t, 'SCR-1601.giao-lai')) {
                    <p-button
                      label="Giao lại"
                      size="small"
                      [outlined]="true"
                      severity="secondary"
                      (onClick)="openReassign(t)"
                      [ariaLabel]="'Giao lại việc ' + t.title"
                    />
                  }
                </div>
              </li>
            } @empty {
              @if (periodsRes.isLoading() || taskRes.isLoading()) {
                <li><vt-skeleton variant="list" [rows]="4" label="Đang tải việc chậm" /></li>
              } @else {
                <li class="muted">Không có việc chậm trong phạm vi.</li>
              }
            }
          </ul>
        </section>
      </div>
    </div>

    <p-dialog
      closeAriaLabel="Đóng"
      header="Giao lại nhiệm vụ"
      [modal]="true"
      [visible]="!!reassigning()"
      (visibleChange)="$event || closeReassign()"
      [style]="{ width: 'min(520px, 95vw)' }"
    >
      @if (reassigning(); as t) {
        <p class="muted">{{ t.title }}</p>
        <div class="field">
          <label for="gl-person">Người chủ trì mới</label>
          <p-select
            inputId="gl-person"
            [options]="personOptions()"
            [loading]="personsRes.isLoading()"
            optionLabel="name"
            optionValue="id"
            [filter]="true"
            [ngModel]="leadId()"
            (ngModelChange)="leadId.set($event)"
            placeholder="Chọn cán bộ"
            appendTo="body"
          />
          @if (formErr()['lead']; as m) {
            <p class="err">{{ m }}</p>
          }
        </div>
        <div class="field">
          <label for="gl-reason">Lý do giao lại</label>
          <textarea
            pTextarea
            id="gl-reason"
            rows="3"
            maxlength="500"
            [ngModel]="reason()"
            (ngModelChange)="reason.set($event)"
          ></textarea>
          @if (formErr()['reason']; as m) {
            <p class="err">{{ m }}</p>
          }
        </div>
        @if (dialogError(); as e) {
          <p-message severity="error" role="alert">{{ e }}</p-message>
        }
        <div class="actions">
          <p-button
            label="Giao lại"
            [loading]="busy() === t.id + ':giao'"
            (onClick)="reassign(t)"
          />
          <p-button label="Huỷ" [text]="true" severity="secondary" (onClick)="closeReassign()" />
        </div>
      }
    </p-dialog>

    <p-dialog
      closeAriaLabel="Đóng"
      header="Dữ liệu đã dùng cho tóm tắt"
      [modal]="true"
      [visible]="!!sources()"
      (visibleChange)="$event || sources.set(null)"
      [style]="{ width: 'min(600px, 95vw)' }"
    >
      @if (sources(); as list) {
        <ul class="plain-list">
          @for (s of list.items; track $index) {
            <li>
              @if (s.masked) {
                <span class="muted">Nguồn ngoài phạm vi xem của đồng chí</span>
              } @else {
                <strong>{{ s.title }}</strong>
                @if (s.excerpt) {
                  <p class="small">{{ s.excerpt }}</p>
                }
                @if (s.asOf) {
                  <p class="small muted">Số liệu đến {{ dt(s.asOf) }}</p>
                }
              }
            </li>
          }
        </ul>
      }
    </p-dialog>
  `,
  styleUrls: ['../dashboard.css'],
  styles: `
    .filters {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
      margin-bottom: 12px;
    }
    .filters > div {
      min-width: 200px;
    }
    .prog {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    .prog .bar {
      flex: 1;
    }
    .items {
      margin: 0 0 8px;
      padding-left: 20px;
    }
    .items li {
      margin-bottom: 4px;
    }
    .directives .title {
      margin: 0 0 2px;
      font-weight: 600;
    }
    .directives p {
      margin: 0 0 4px;
    }
    .row {
      margin-top: 6px;
    }
    .field textarea {
      width: 100%;
    }
    :host ::ng-deep .field .p-select {
      width: 100%;
    }
  `,
})
export class LeaderDashboardPage {
  private readonly api = inject(AnalyticsApi);
  private readonly taskApi = inject(TaskApi);
  private readonly periodApi = inject(PeriodApi);
  private readonly orgApi = inject(OrgApi);
  protected readonly can = inject(DASHBOARD_CAN);
  protected readonly permittedOn = permittedOn;
  protected readonly M = MEASURE;

  protected readonly periodsRes = rxResource({
    stream: () => this.periodApi.list({ kind: 'QUY' }),
  });
  protected readonly periodOptions = computed(() =>
    this.periodsRes.hasValue() ? this.periodsRes.value() : [],
  );
  /** Kỳ hiện hành: kỳ quý đang mở gần nhất. */
  protected readonly periodId = linkedSignal<string | null>(() => {
    const ps = this.periodOptions();
    return ps.find((p) => p.status === 'MO' && !p.notStarted)?.id ?? ps[0]?.id ?? null;
  });
  private readonly period = computed(() =>
    this.periodOptions().find((p) => p.id === this.periodId()),
  );
  protected readonly periodName = computed(() => this.period()?.name ?? 'Kỳ hiện hành');
  protected readonly previousName = computed(() => {
    const ps = this.periodOptions();
    const cur = this.period();
    const prev =
      cur &&
      ps
        .filter((p) => p.endDate < cur.startDate)
        .sort((a, b) => b.endDate.localeCompare(a.endDate))[0];
    return prev?.name ?? 'Kỳ trước';
  });
  protected readonly week = computed(() => weekOfPeriod(this.period()));

  protected readonly compare = signal(false);
  protected readonly group = signal<string | null>(null);
  protected readonly flow = signal<string | null>(null);
  private readonly filters = computed(() => [
    ...(this.group() ? [eq(DIMENSION.subjectGroup, this.group()!)] : []),
    ...(this.flow() ? [eq(DIMENSION.flow, this.flow()!)] : []),
  ]);
  private readonly params = computed(() =>
    this.periodId()
      ? {
          periodId: this.periodId()!,
          compareToPreviousPeriod: this.compare(),
          filters: this.filters(),
        }
      : undefined,
  );

  protected readonly kpiRes = rxResource({
    params: () => this.params(),
    stream: ({ params }) =>
      this.api.query({
        ...params,
        measures: [MEASURE.tienDoO, MEASURE.dungHan, MEASURE.viecCham, MEASURE.choThamDinh],
      }),
  });
  protected readonly unitRes = rxResource({
    params: () => this.params(),
    stream: ({ params }) =>
      this.api.query({
        ...params,
        measures: [MEASURE.tienDoO],
        dimensions: [DIMENSION.unitId, DIMENSION.unitName],
      }),
  });
  protected readonly trendRes = rxResource({
    params: () => this.params(),
    stream: ({ params }) =>
      this.api.query({
        ...params,
        compareToPreviousPeriod: true,
        measures: [MEASURE.tienDoO],
        dimensions: [DIMENSION.week],
        timeGranularity: 'week',
      }),
  });
  /** Giá trị cho bộ lọc khối, luồng lấy từ chính lớp ngữ nghĩa (không đặt danh sách trong mã). */
  protected readonly optionsRes = rxResource({
    params: () => this.periodId() ?? undefined,
    stream: ({ params }) =>
      forkJoin(
        [DIMENSION.subjectGroup, DIMENSION.flow].map((d) =>
          this.api.query({ periodId: params, measures: [MEASURE.tienDoO], dimensions: [d] }),
        ),
      ),
  });
  protected readonly groupOptions = computed(() => this.values(0));
  protected readonly flowOptions = computed(() => this.values(1));
  protected readonly summaryRes = rxResource({
    params: () => this.periodId() ?? undefined,
    stream: ({ params }) => this.api.weeklySummary({ periodId: params }),
  });
  protected readonly summary = computed(() =>
    this.summaryRes.hasValue() ? this.summaryRes.value() : null,
  );
  protected readonly taskRes = rxResource({
    params: () => this.periodId() ?? undefined,
    stream: ({ params }) =>
      this.taskApi.unitTasks({ overdue: true, includeSubunits: true, period: params, limit: 3 }),
  });
  protected readonly directives = computed(() =>
    this.taskRes.hasValue() ? this.taskRes.value().items : [],
  );
  protected readonly personsRes = rxResource({
    params: () => (this.reassigning() ? true : undefined),
    stream: () => this.orgApi.persons(),
  });
  protected readonly personOptions = computed(() =>
    this.personsRes.hasValue() ? this.personsRes.value().items : [],
  );

  protected readonly kpis = computed<Kpi[]>(() => {
    if (!this.kpiRes.hasValue()) return [];
    const r = this.kpiRes.value();
    const row = resultRows(r)[0] ?? {};
    return r.columns
      .filter((c) => !c.name.endsWith(PREVIOUS_SUFFIX))
      .map((c) => ({
        key: c.name,
        title: c.title,
        value: cellNumber(row[c.name]),
        previous: cellNumber(row[c.name + PREVIOUS_SUFFIX]),
        unit: c.unit === '%' ? '%' : '',
      }));
  });
  protected readonly units = computed(() => {
    if (!this.unitRes.hasValue()) return [];
    return resultRows(this.unitRes.value()).map((r) => ({
      id: String(r[DIMENSION.unitId]),
      name: String(r[DIMENSION.unitName]),
      value: cellNumber(r[MEASURE.tienDoO]),
      previous: cellNumber(r[MEASURE.tienDoO + PREVIOUS_SUFFIX]),
    }));
  });
  private readonly results = computed(() =>
    [this.kpiRes, this.unitRes, this.trendRes]
      .filter((r) => r.hasValue())
      .map((r) => r.value() as AnalyticsQueryResult),
  );
  protected readonly asOf = computed(() => this.results()[0]?.dataAsOf ?? null);
  protected readonly stale = computed(() => this.results().find((r) => r.stale)?.dataAsOf ?? null);

  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly busy = signal<string | null>(null);
  protected readonly exporting = signal(false);
  protected readonly sources = signal<AiSourceList | null>(null);
  protected readonly reassigning = signal<TaskSummary | null>(null);
  protected readonly leadId = signal<string | null>(null);
  protected readonly reason = signal('');
  protected readonly formErr = signal<Record<string, string>>({});
  protected readonly dialogError = signal<string | null>(null);

  protected readonly exportItems: MenuItem[] = [
    { label: 'Tệp PDF', command: () => this.exportReport('PDF') },
    { label: 'Tệp Excel', command: () => this.exportReport('XLSX') },
  ];

  private values(i: number): string[] {
    if (!this.optionsRes.hasValue()) return [];
    const r = this.optionsRes.value()[i];
    return r ? r.rows.map((row) => String(row[0])).filter((v) => v && v !== 'null') : [];
  }

  protected num(v: number | null) {
    return fmtNum(v);
  }
  protected abs(v: number) {
    return Math.abs(Math.round(v * 10) / 10);
  }
  /** Việc chậm tăng là xấu; các chỉ số còn lại tăng là tốt. */
  protected better(k: Kpi) {
    const up = (k.value ?? 0) >= (k.previous ?? 0);
    return k.key === MEASURE.viecCham || k.key === MEASURE.choThamDinh ? !up : up;
  }
  protected status(v: number | null) {
    return progressStatus(v);
  }
  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected d(iso?: string | null) {
    return fmtDate(iso);
  }
  protected err(e: unknown) {
    return errorText(e, 'Chưa tải được số liệu.');
  }
  protected sourceTitles(s: { title: string }[]) {
    return s.map((x) => x.title).join('; ') || 'số liệu của bảng điều hành';
  }
  protected lateLabel(t: TaskSummary) {
    return t.workingDaysLeft < 0
      ? `Trễ ${-t.workingDaysLeft} ngày làm việc`
      : `Còn ${t.workingDaysLeft} ngày làm việc`;
  }

  protected openSources(id: string) {
    // Nút chờ trong lúc nạp nguồn; hộp thoại chỉ mở khi dữ liệu đã về
    this.busy.set('nguon');
    this.api.summarySources(id).subscribe({
      next: (s) => {
        this.busy.set(null);
        this.sources.set(s);
      },
      error: (e) => {
        this.busy.set(null);
        this.error.set(errorText(e));
      },
    });
  }

  protected remind(t: TaskSummary) {
    this.notice.set(null);
    this.error.set(null);
    this.busy.set(t.id + ':nhac');
    this.taskApi.remind(t.id).subscribe({
      next: () => {
        this.busy.set(null);
        this.notice.set(`Đã gửi nhắc tới người chủ trì về việc "${t.title}".`);
      },
      error: (e) => {
        this.busy.set(null);
        this.error.set(errorText(e));
      },
    });
  }

  protected openReassign(t: TaskSummary) {
    this.reassigning.set(t);
    this.leadId.set(null);
    this.reason.set('');
    this.formErr.set({});
    this.dialogError.set(null);
  }
  protected closeReassign() {
    this.reassigning.set(null);
  }

  protected reassign(t: TaskSummary) {
    const e: Record<string, string> = {};
    if (!this.leadId()) e['lead'] = 'Chọn người chủ trì mới.';
    if (!this.reason().trim()) e['reason'] = 'Nhập lý do giao lại.';
    this.formErr.set(e);
    if (Object.keys(e).length) return;
    const name =
      this.personOptions().find((p) => p.id === this.leadId())?.name ?? 'người được chọn';
    this.dialogError.set(null);
    this.busy.set(t.id + ':giao');
    this.taskApi
      .reassign(t, { leadPersonId: this.leadId()!, reason: this.reason().trim() })
      .subscribe({
        next: () => {
          this.busy.set(null);
          this.reassigning.set(null);
          this.error.set(null);
          this.notice.set(
            `Đã giao lại việc "${t.title}" cho ${name}. Người được giao cũ đã nhận thông báo.`,
          );
          this.taskRes.reload();
        },
        error: (x) => {
          this.busy.set(null);
          this.dialogError.set(errorText(x));
        },
      });
  }

  exportReport(format: 'PDF' | 'XLSX') {
    const periodId = this.periodId();
    if (!periodId) return;
    this.notice.set(null);
    this.error.set(null);
    this.exporting.set(true);
    this.api
      .exportReport({
        templateCode: 'BANG_DIEU_HANH',
        periodId,
        format,
        compareToPreviousPeriod: this.compare(),
      })
      .subscribe({
        next: () => {
          this.exporting.set(false);
          this.notice.set(
            `Đã nhận yêu cầu xuất báo cáo (${format === 'PDF' ? 'tệp PDF' : 'tệp Excel'}). Hệ thống sẽ thông báo khi tệp sẵn sàng để tải về.`,
          );
        },
        error: (e) => {
          this.exporting.set(false);
          this.error.set(errorText(e, 'Không tạo được báo cáo.'));
        },
      });
  }
}
