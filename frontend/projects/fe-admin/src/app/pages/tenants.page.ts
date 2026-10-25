/*
 * SCR-0101 "Danh sách tenant" (QT-01, TL-02a Hình 6.7) — đối chiếu đặc tả:
 * Tác nhân AC-01 `platform_admin`, phiên AAL2, máy tính. Đường vào: menu "Nền tảng / Danh sách tenant".
 * Trường: 1 tên tenant; 2 tên miền; 3 cell `CELL-xxx`; 4 mô hình silo/bridge/pool (BR-01-01); 5 tài khoản
 *   (đồng bộ mỗi giờ); 6 Policy Pack đang gắn; 7 UI Pack; 8 trạng thái (bảy trạng thái Hình 6.6, thêm
 *   `DANG_CHUYEN_CELL` của TL-03); 9 tải theo cell = tài khoản / dung lượng thiết kế.
 * Vùng: thẻ tổng quan (đang vận hành / 5.000 NFR-01, tài khoản, đang khởi tạo, cell); 1 lọc cell, mô hình,
 *   trạng thái, tìm theo tên, mã, tên miền; 2 bảng tenant, phân trang con trỏ; 3 tải theo cell và việc cần
 *   xử lý; 4 nút "Khởi tạo tenant" (mở SCR-0102), "Xuất danh sách" (202 + Location, tác vụ nền).
 * Chức năng: Khởi tạo tenant; Chi tiết (GET /config/tenants/{id}); Lọc, tìm (GET /config/tenants?cell=&model=
 *   &status=&q=&cursor=, kèm `summary` cho thẻ tổng quan và tải theo cell); Xuất danh sách (POST
 *   /config/tenants/export, bộ lọc trong thân yêu cầu).
 * Theo `config.yaml` (WEB-064): hợp đồng chưa có danh sách "việc cần xử lý", số trung tâm dữ liệu, tổng
 *   số tenant theo bộ lọc, danh sách tenant đang khởi tạo toàn vùng và từ khoá khi xuất; màn hình bỏ các
 *   phần đó, MSG-01-02 lấy từ các dòng đang hiện (wiki/14, Điểm lệch).
 * Quy tắc, thông báo: BR-01-02 và MSG-01-01 (cell vượt 85%), MSG-01-02 (tenant đang khởi tạo bước n/6).
 * Quyền (wiki/15a): SCR-0101.xem, SCR-0101.xuat, SCR-0102.buoc-1-3 cho nút khởi tạo.
 */
import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { JOB_STATUS_LABEL, Job, JobTracker } from '@vt/api-client';
import {
  CellLoad,
  TENANCY_MODELS,
  TENANCY_MODEL_LABEL,
  TENANT_ONBOARDING_STATUSES,
  TENANT_STATUS_LABEL,
  TenancyModel,
  TenantApi,
  TenantQuery,
  TenantRow,
  TenantStats,
  TenantStatus,
  FILTER_QUERY_PARAM,
  createListPersonalization,
  decodeCriteria,
  encodeCriteria,
} from '@vt/api-client/domains';
import {
  ThemeService,
  VtColumnPickerComponent,
  VtSavedFiltersComponent,
  VtSkeletonComponent,
} from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { Subject, debounceTime, finalize } from 'rxjs';
import { ADMIN_CAN } from '../admin-permissions';
import { errorText, fmtAccounts, fmtInt, fmtPercent } from '../admin-ui';
import { TENANTS_CODEC, TENANTS_COLUMNS, TENANTS_SCREEN, TenantsFilterState } from './tenants.list';

const PAGE_SIZE = 8;

interface Filters {
  q: string;
  cell: string | null;
  model: TenancyModel | null;
  status: TenantStatus | null;
}

/** MSG-01-01 */
export const MSG_01_01 = (cell: string, percent: number) =>
  `Cụm máy chủ ${cell} đã sử dụng ${percent}% dung lượng thiết kế. Đề nghị lập kế hoạch mở cụm máy chủ mới.`;
/** MSG-01-02 */
export const MSG_01_02 = (tenant: string, step: number) =>
  `Đơn vị ${tenant} đang khởi tạo, đến bước ${step}/6.`;

@Component({
  selector: 'vt-tenants-page',
  imports: [
    VtSkeletonComponent,
    FormsModule,
    RouterLink,
    ButtonModule,
    InputTextModule,
    MessageModule,
    SelectModule,
    VtColumnPickerComponent,
    VtSavedFiltersComponent,
  ],
  template: `
    <header class="head">
      <div>
        <h1>Danh sách đơn vị triển khai</h1>
        <p class="muted">Các đơn vị đang sử dụng hệ thống và tải của từng cụm máy chủ</p>
      </div>
      <div class="actions">
        @if (can('SCR-0101.xuat') && !simple()) {
          <p-button
            label="Xuất danh sách"
            icon="pi pi-download"
            severity="contrast"
            [outlined]="true"
            [loading]="exporting()"
            (onClick)="exportList()"
          />
        }
        @if (can('SCR-0102.buoc-1-3')) {
          <a
            pButton
            class="act"
            routerLink="new"
            icon="pi pi-plus"
            label="Khởi tạo đơn vị triển khai"
          ></a>
        }
      </div>
    </header>

    @if (exportJob(); as j) {
      <p class="note" role="status">
        Xuất danh sách: {{ jobLabel(j) }}
        @if (j.status === 'HOAN_TAT' && j.resultLocation) {
          <button type="button" class="link" (click)="download(j.resultLocation)">Tải tệp</button>
        }
      </p>
    }
    @if (actionError(); as e) {
      <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
    }

    @if (ov(); as o) {
      <section class="cards" aria-label="Tổng quan">
        <div class="card first">
          <p class="label">Đơn vị đang vận hành</p>
          <p class="big">{{ int(o.activeTenants) }}</p>
        </div>
        <div class="card">
          <p class="label">Tài khoản</p>
          <p class="big">{{ accounts(o.totalAccounts) }}</p>
          <p class="muted small">
            {{ percent(o.totalAccounts / o.accountLimit) }} của {{ accounts(o.accountLimit) }}
          </p>
        </div>
        <div class="card">
          <p class="label">Đang khởi tạo</p>
          <p class="big warn">{{ int(o.onboardingTenants) }}</p>
        </div>
        <div class="card">
          <p class="label">Cụm máy chủ</p>
          <p class="big">{{ int(o.cellCount) }}</p>
          <p class="muted small">{{ overloaded().length }} cụm vượt 85% dung lượng</p>
        </div>
      </section>
    }
    @if (onboardingRows().length) {
      <ul class="warnbox notices" aria-label="Đơn vị đang khởi tạo">
        @for (t of onboardingRows(); track t.id) {
          <li>
            <a [routerLink]="[t.id, 'onboarding']" [queryParams]="{ step: t.onboardingStep }">{{
              msg0102(t.name, t.onboardingStep ?? 1)
            }}</a>
          </li>
        }
      </ul>
    }

    <div class="cols">
      <section class="box" aria-labelledby="tbl-title">
        <h2 id="tbl-title" class="sr-only">Bảng đơn vị triển khai</h2>
        <div class="filters" role="search" aria-label="Lọc danh sách đơn vị triển khai">
          <input
            pInputText
            type="search"
            class="search"
            placeholder="Tìm theo tên, mã, tên miền…"
            aria-label="Tìm theo tên, mã, tên miền"
            [ngModel]="filters().q"
            (ngModelChange)="search$.next($event)"
          />
          <p-select
            [options]="cellOptions()"
            optionLabel="label"
            optionValue="value"
            [ngModel]="filters().cell"
            (ngModelChange)="setFilter('cell', $event)"
            placeholder="Cụm máy chủ: Tất cả"
            [showClear]="true"
            ariaLabel="Cụm máy chủ"
            appendTo="body"
          />
          <p-select
            [options]="modelOptions"
            optionLabel="label"
            optionValue="value"
            [ngModel]="filters().model"
            (ngModelChange)="setFilter('model', $event)"
            placeholder="Mức cô lập: Tất cả"
            [showClear]="true"
            ariaLabel="Mức cô lập"
            appendTo="body"
          />
          <p-select
            [options]="statusOptions"
            optionLabel="label"
            optionValue="value"
            [ngModel]="filters().status"
            (ngModelChange)="setFilter('status', $event)"
            placeholder="Trạng thái: Tất cả"
            [showClear]="true"
            ariaLabel="Trạng thái"
            appendTo="body"
          />
        </div>
        <div class="list-tools">
          <vt-saved-filters
            [filters]="list.filters()"
            [activeId]="list.activeId()"
            [busy]="list.busy()"
            (apply)="applySaved($event)"
            (save)="list.saveFilter($event.name, state(), $event.isDefault)"
            (rename)="list.rename($event.id, $event.name)"
            (toggleDefault)="list.toggleDefault($event)"
            (remove)="list.remove($event)"
          />
          @if (!simple()) {
            <vt-column-picker
              [columns]="list.columns"
              [visible]="list.visible()"
              (visibleChange)="list.setVisible($event)"
            />
          }
        </div>
        @if (list.message(); as m) {
          <p class="list-msg" [class.err]="!m.ok" role="status">{{ m.text }}</p>
        }

        @if (page.error()) {
          <p-message severity="error" styleClass="msg">
            <span>{{ err(page.error(), 'Không tải được danh sách đơn vị triển khai.') }}</span>
            <button type="button" class="link" (click)="page.reload()">Thử lại</button>
          </p-message>
        }
        <div class="scroll">
          <table class="tbl" [attr.aria-busy]="page.isLoading()">
            <caption class="sr-only">
              Danh sách đơn vị triển khai
            </caption>
            <thead>
              <tr>
                <th scope="col">Đơn vị triển khai</th>
                @if (list.shows('cellCode')) {
                  <th scope="col">Cụm máy chủ</th>
                }
                @if (list.shows('tenancyModel')) {
                  <th scope="col">Mức cô lập</th>
                }
                @if (list.shows('accountCount')) {
                  <th scope="col" class="num">Tài khoản</th>
                }
                @if (!simple() && list.shows('policyPacks')) {
                  <th scope="col">Bộ quy định đánh giá</th>
                }
                @if (!simple() && list.shows('uiPack')) {
                  <th scope="col">Bộ giao diện</th>
                }
                @if (list.shows('status')) {
                  <th scope="col">Trạng thái</th>
                }
                <th scope="col"><span class="sr-only">Thao tác</span></th>
              </tr>
            </thead>
            <tbody>
              @for (t of pg()?.items ?? []; track t.id) {
                <tr>
                  <td>
                    <span class="name">{{ t.name }}</span>
                    <span class="muted small block">{{ t.domain }}</span>
                  </td>
                  @if (list.shows('cellCode')) {
                    <td class="nowrap">{{ t.cellCode }}</td>
                  }
                  @if (list.shows('tenancyModel')) {
                    <td>
                      <span class="chip">{{ modelLabel[t.tenancyModel] }}</span>
                    </td>
                  }
                  @if (list.shows('accountCount')) {
                    <td class="num">{{ int(t.accountCount) }}</td>
                  }
                  @if (!simple() && list.shows('policyPacks')) {
                    <td>{{ (t.policyPacks ?? []).join(' + ') || '—' }}</td>
                  }
                  @if (!simple() && list.shows('uiPack')) {
                    <td>{{ t.uiPack ?? '—' }}</td>
                  }
                  @if (list.shows('status')) {
                    <td>
                      <span class="status" [class]="'status st-' + tone(t)">{{
                        statusText(t)
                      }}</span>
                    </td>
                  }
                  <td>
                    <a
                      pButton
                      class="act"
                      [routerLink]="t.id"
                      label="Chi tiết"
                      severity="secondary"
                      [outlined]="true"
                      size="small"
                      [attr.aria-label]="'Chi tiết ' + t.name"
                    ></a>
                  </td>
                </tr>
              } @empty {
                @if (page.isLoading()) {
                  <tr>
                    <td [attr.colspan]="colspan()">
                      <vt-skeleton
                        variant="table"
                        [header]="false"
                        [columns]="5"
                        label="Đang tải danh sách đơn vị"
                      />
                    </td>
                  </tr>
                } @else {
                  <tr>
                    <td [attr.colspan]="colspan()" class="empty muted">
                      Không có đơn vị nào phù hợp với điều kiện lọc.
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
        @if (pg(); as p) {
          <nav class="pager" aria-label="Phân trang">
            <span class="muted" aria-live="polite">
              @if (p.items.length) {
                Hiển thị {{ from() }}–{{ from() + p.items.length - 1 }}
              } @else {
                Không có kết quả
              }
            </span>
            <p-button
              icon="pi pi-angle-left"
              severity="secondary"
              [outlined]="true"
              size="small"
              ariaLabel="Trang trước"
              [disabled]="!cursors().length"
              (onClick)="prev()"
            />
            <p-button
              icon="pi pi-angle-right"
              severity="secondary"
              [outlined]="true"
              size="small"
              ariaLabel="Trang sau"
              [disabled]="!p.nextCursor"
              (onClick)="next(p.nextCursor)"
            />
          </nav>
        }
      </section>

      <aside>
        <section class="box" aria-labelledby="cell-title">
          <h2 id="cell-title" class="h">Tải theo cụm máy chủ</h2>
          @for (m of overloaded(); track m.cellCode) {
            <p class="warnbox" role="status">{{ msg0101(m) }}</p>
          }
          <table class="tbl cells">
            <caption class="sr-only">
              Tải theo cụm máy chủ
            </caption>
            <thead>
              <tr>
                <th scope="col">Cụm máy chủ</th>
                <th scope="col" class="num">Tài khoản</th>
                <th scope="col">Sử dụng</th>
              </tr>
            </thead>
            <tbody>
              @for (c of cellRows(); track c.cellCode) {
                <tr>
                  <td class="nowrap">{{ c.cellCode }}</td>
                  <td class="num">{{ accounts(c.accountCount) }}</td>
                  <td>
                    <span class="meter" role="img" [attr.aria-label]="'Sử dụng ' + percent(load(c))"
                      ><span
                        class="fill"
                        [class.hi]="over(c)"
                        [class.mid]="load(c) > 0.7 && !over(c)"
                        [style.width.%]="Math.min(100, load(c) * 100)"
                      ></span
                    ></span>
                    <span class="small">{{ percent(load(c)) }}</span>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </section>
      </aside>
    </div>
  `,
  styles: `
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    h1 {
      font-size: 1.4rem;
      margin: 4px 0;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.875em;
    }
    .block {
      display: block;
    }
    .nowrap {
      white-space: nowrap;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
      gap: 12px;
      margin: 12px 0;
    }
    .card {
      padding: 14px 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
    }
    .card.first {
      border-top: 3px solid var(--vt-color-primary, #b8002a);
    }
    .card p {
      margin: 0;
    }
    .label {
      color: #4b5563;
    }
    .big {
      font-size: 1.9rem;
      font-weight: 700;
      line-height: 1.3;
    }
    .big.warn {
      color: #8a5a00;
    }
    .warnbox {
      padding: 8px 12px;
      border: 1px solid #f3d27a;
      border-radius: 6px;
      background: #fff8e1;
      color: #6b4400;
      margin: 0 0 12px;
    }
    .notices {
      list-style: none;
      display: grid;
      gap: 4px;
    }
    .notices a {
      color: inherit;
    }
    a.act {
      white-space: nowrap;
      text-decoration: none;
    }
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      margin-bottom: 12px;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 2.6fr) minmax(260px, 1fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1100px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    .list-tools {
      display: flex;
      flex-wrap: wrap;
      justify-content: space-between;
      align-items: flex-start;
      gap: 8px;
      margin: 8px 0;
    }
    .list-msg {
      margin: 0 0 8px;
      color: #047857;
    }
    .list-msg.err {
      color: #b91c1c;
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-bottom: 8px;
    }
    .search {
      flex: 1 1 240px;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 8px;
    }
    .scroll {
      overflow-x: auto;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
    }
    .tbl th {
      text-align: left;
      color: #4b5563;
      font-weight: 500;
      vertical-align: bottom;
    }
    .tbl th,
    .tbl td {
      padding: 10px 8px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
      vertical-align: middle;
    }
    .tbl.cells td,
    .tbl.cells th {
      padding: 8px 4px;
    }
    .num {
      text-align: right;
    }
    .name {
      font-weight: 600;
    }
    .chip {
      display: inline-block;
      padding: 2px 8px;
      border-radius: 4px;
      background: #f3f4f6;
      font-size: 0.875em;
    }
    .status {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 999px;
      font-size: 0.875em;
      white-space: nowrap;
    }
    .st-ok {
      background: #e7f6ec;
      color: #146c2e;
    }
    .st-warn {
      background: #fff4d6;
      color: #7a4f00;
    }
    .st-wait {
      background: #f3f4f6;
      color: #374151;
    }
    .st-bad {
      background: #fde8e8;
      color: #9b1c1c;
    }
    .empty {
      text-align: center;
      padding: 24px;
    }
    .pager {
      display: flex;
      align-items: center;
      justify-content: flex-end;
      gap: 8px;
      margin-top: 12px;
    }
    .meter {
      display: inline-block;
      width: 64px;
      height: 8px;
      border-radius: 4px;
      background: #e5e7eb;
      overflow: hidden;
      vertical-align: middle;
      margin-right: 6px;
    }
    .meter .fill {
      display: block;
      height: 100%;
      background: #1f9d55;
    }
    .meter .fill.mid {
      background: #d69e2e;
    }
    .meter .fill.hi {
      background: #c53030;
    }
    .note {
      padding: 8px 12px;
      border-radius: 6px;
      background: #f3f4f6;
    }
    .link {
      background: none;
      border: 0;
      padding: 0 0 0 8px;
      color: var(--vt-color-primary, #b8002a);
      text-decoration: underline;
      cursor: pointer;
      font: inherit;
    }
    :host ::ng-deep .msg {
      margin: 8px 0;
      width: 100%;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class TenantsPage {
  private readonly api = inject(TenantApi);
  private readonly jobs = inject(JobTracker);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(ADMIN_CAN);

  protected readonly Math = Math;
  protected readonly int = fmtInt;
  protected readonly accounts = fmtAccounts;
  protected readonly percent = fmtPercent;
  protected readonly err = errorText;
  protected readonly msg0102 = MSG_01_02;

  /** Chế độ người cao tuổi rút gọn: bỏ cột gói, nút xuất (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected readonly modelLabel = TENANCY_MODEL_LABEL;
  protected readonly modelOptions = TENANCY_MODELS.map((m) => ({
    value: m,
    label: TENANCY_MODEL_LABEL[m],
  }));
  protected readonly statusOptions = (Object.keys(TENANT_STATUS_LABEL) as TenantStatus[]).map(
    (s) => ({ value: s, label: TENANT_STATUS_LABEL[s] }),
  );

  protected readonly filters = signal<Filters>({ q: '', cell: null, model: null, status: null });
  /** Con trỏ của các trang đã đi qua, để quay lại trang trước. */
  protected readonly cursors = signal<string[]>([]);
  private readonly cursor = signal<string | undefined>(undefined);
  protected readonly search$ = new Subject<string>();
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  /** CNH-04: cột tuỳ chọn và bộ lọc đã lưu, lưu theo người dùng trên máy chủ. */
  protected readonly list = createListPersonalization(
    TENANTS_SCREEN,
    TENANTS_COLUMNS,
    TENANTS_CODEC,
  );
  protected readonly state = computed<TenantsFilterState>(() => {
    const { cell, model, status } = this.filters();
    return { cell, model, status };
  });
  protected readonly colspan = computed(
    () =>
      2 +
      ['cellCode', 'tenancyModel', 'accountCount', 'status'].filter((k) => this.list.shows(k))
        .length +
      (this.simple() ? 0 : ['policyPacks', 'uiPack'].filter((k) => this.list.shows(k)).length),
  );

  protected readonly page = rxResource({
    params: () => ({ ...this.query(), cursor: this.cursor(), limit: PAGE_SIZE }),
    stream: ({ params }) => this.api.list(params),
  });

  /** Giá trị an toàn khi tài nguyên lỗi (đọc `value()` của resource lỗi sẽ ném lỗi). */
  protected readonly pg = computed(() => (this.page.hasValue() ? this.page.value() : undefined));
  /** `summary` không theo bộ lọc; giữ bản trước trong lúc tải trang khác. */
  protected readonly ov = linkedSignal<TenantStats | undefined, TenantStats | undefined>({
    source: () => this.pg()?.summary,
    computation: (s, prev) => s ?? prev?.value,
  });
  protected readonly cellRows = computed(() => this.ov()?.cellLoads ?? []);
  protected readonly cellOptions = computed(() =>
    this.cellRows().map((c) => ({ value: c.cellCode, label: c.cellCode })),
  );
  /** BR-01-02, MSG-01-01: cell vượt ngưỡng (máy chủ tính `overThreshold`). */
  protected readonly overloaded = computed(() => this.cellRows().filter((c) => this.over(c)));
  /** MSG-01-02 cho các tenant đang khởi tạo trên trang đang xem. */
  protected readonly onboardingRows = computed(() =>
    (this.pg()?.items ?? []).filter((t) => this.onboarding(t)),
  );
  protected readonly from = computed(() => this.cursors().length * PAGE_SIZE + 1);

  protected readonly exporting = signal(false);
  protected readonly exportJob = signal<Job | null>(null);
  protected readonly actionError = signal<string | null>(null);

  constructor() {
    this.search$.pipe(debounceTime(300)).subscribe((q) => this.setFilter('q', q));
    const shared = decodeCriteria(this.route.snapshot.queryParamMap.get(FILTER_QUERY_PARAM));
    if (shared) this.filters.update((f) => ({ ...f, ...TENANTS_CODEC.fromCriteria(shared).state }));
    // đường dẫn chia sẻ thắng bộ lọc mặc định đã lưu
    this.list.load(shared ? undefined : (s) => this.setState(s, true));
  }

  /** CNH-04: áp dụng một bộ lọc đã lưu bằng một chạm; `null` bỏ mọi điều kiện đã lưu. */
  protected applySaved(id: string | null) {
    this.setState(this.list.apply(id) ?? { cell: null, model: null, status: null }, true);
  }

  private setState(s: TenantsFilterState, keepActive = false) {
    this.filters.update((f) => ({ ...f, ...s }));
    this.cursors.set([]);
    this.cursor.set(undefined);
    this.syncUrl(keepActive);
  }

  /** Trạng thái lọc lên URL để chia sẻ trong nội bộ; quyền xem dữ liệu do máy chủ quyết định. */
  private syncUrl(keepActive: boolean) {
    if (!keepActive) this.list.activeId.set(null);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { [FILTER_QUERY_PARAM]: encodeCriteria(this.list.criteriaOf(this.state())) },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  private query(): Omit<TenantQuery, 'cursor' | 'limit'> {
    const f = this.filters();
    return {
      q: f.q.trim() || undefined,
      cell: f.cell ?? undefined,
      model: f.model ?? undefined,
      status: f.status ?? undefined,
    };
  }

  protected setFilter<K extends keyof Filters>(key: K, value: Filters[K]) {
    this.filters.update((f) => ({ ...f, [key]: value ?? (key === 'q' ? '' : null) }));
    this.cursors.set([]);
    this.cursor.set(undefined);
    if (key !== 'q') this.syncUrl(false);
  }

  protected next(nextCursor: string | null) {
    if (!nextCursor) return;
    this.cursors.update((c) => [...c, this.cursor() ?? '']);
    this.cursor.set(nextCursor);
  }

  protected prev() {
    const stack = this.cursors();
    const back = stack[stack.length - 1];
    this.cursors.set(stack.slice(0, -1));
    this.cursor.set(back || undefined);
  }

  protected load(c: CellLoad): number {
    return c.loadPercent / 100;
  }

  protected over(c: CellLoad): boolean {
    return c.overThreshold ?? c.loadPercent > 85;
  }

  protected msg0101(c: CellLoad): string {
    return MSG_01_01(c.cellCode, Math.round(c.loadPercent));
  }

  private onboarding(t: TenantRow): boolean {
    return TENANT_ONBOARDING_STATUSES.includes(t.status) && !!t.onboardingStep;
  }

  protected statusText(t: TenantRow): string {
    if (this.onboarding(t)) {
      return `Đang khởi tạo ${t.onboardingStep}/6`;
    }
    return TENANT_STATUS_LABEL[t.status];
  }

  protected tone(t: TenantRow): 'ok' | 'warn' | 'wait' | 'bad' {
    if (t.status === 'DANG_VAN_HANH') return 'ok';
    if (t.status === 'TAM_NGUNG' || t.status === 'DA_CHAM_DUT') return 'bad';
    if (t.status === 'SAN_SANG') return 'wait';
    return 'warn';
  }

  protected jobLabel(j: Job): string {
    return j.message ? `${JOB_STATUS_LABEL[j.status]} · ${j.message}` : JOB_STATUS_LABEL[j.status];
  }

  protected exportList() {
    this.actionError.set(null);
    this.exporting.set(true);
    const { cell, model, status } = this.query();
    this.api.export({ cell, model, status, format: 'CSV' }).subscribe({
      next: (location) => {
        if (!location) {
          this.exporting.set(false);
          return;
        }
        this.jobs
          .track(location)
          .pipe(finalize(() => this.exporting.set(false)))
          .subscribe({
            next: (j) => this.exportJob.set(j),
            error: (e) =>
              this.actionError.set(errorText(e, 'Không theo dõi được tiến độ xuất danh sách.')),
          });
      },
      error: (e) => {
        this.exporting.set(false);
        this.actionError.set(errorText(e, 'Không xuất được danh sách.'));
      },
    });
  }

  protected download(resultLocation: string) {
    this.api.download(resultLocation).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = 'danh-sach-don-vi-trien-khai.csv';
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      },
      error: (e) => this.actionError.set(errorText(e, 'Không tải được tệp xuất.')),
    });
  }
}
