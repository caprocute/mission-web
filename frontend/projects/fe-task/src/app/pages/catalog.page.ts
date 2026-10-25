/*
 * SCR-0401 "Thư viện danh mục công việc" (QT-04, TL-02 Hình 7.12) — đối chiếu đặc tả:
 * Trường: 1 danh mục, phiên bản; 2 kế thừa từ; 3 vị trí việc làm; 4 mã `TC-xx-nn` (tự sinh); 5 tên ≤ 255, không từ mơ hồ;
 *   6 sản phẩm; 7 đơn vị tính; 8 mức khó 1–5; 9 điểm chuẩn 0–100 (BR-04-14, gợi ý theo mức khó); 10 tiêu chuẩn chất lượng
 *   ≤ 1.000; 11 minh chứng yêu cầu ≤ 500; 12 nhóm mặc định; 13 thời hạn chuẩn; 14 trạng thái (Đang áp dụng, Ngừng).
 * Vùng: 1 bộ lọc (mã, tên, sản phẩm; vị trí; mức khó; trạng thái) và tổng số; 2 bảng theo Bảng 3; 3 thẻ chi tiết (hệ số
 *   mức khó, thời hạn chuẩn, số nhiệm vụ đã dùng, phiên bản); 4 Nhập từ Excel, Nhân bản phiên bản, Thêm công việc;
 *   5 kết quả nhập Excel gần nhất, tải tệp lỗi.
 * API: GET /catalog, /catalog/{id}/items, /catalog/{id}/positions, /catalog/{id}/imports/latest; POST /catalog/{id}/items;
 *   PUT /catalog/{id}/items/{itemId} (If-Match); POST /catalog/{id}/versions; POST /catalog/{id}/imports (202);
 *   GET /catalog/imports/{jobId}, /catalog/imports/{jobId}/errors.
 * Quy tắc, thông báo: BR-04-13/MSG-04-01 (sửa mục đã dùng ở kỳ khoá), BR-04-14, MSG-04-02 (kết quả nhập).
 * "Đưa vào kế hoạch" (phạm vi WEB-025) chuyển mục đã chọn sang SCR-0402 qua tham số `catalogItems` (WEB-026 nhận).
 */
import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TASK_GROUP_LABEL, toProblem } from '@vt/api-client';
import {
  CATALOG_ITEM_STATUS_LABEL,
  CatalogApi,
  CatalogImportJob,
  CatalogItem,
  CatalogItemSaveResult,
  CatalogItemStatus,
  WORK_CATALOG_STATUS_LABEL,
  WorkCatalog,
} from '@vt/api-client/domains';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TagModule } from 'primeng/tag';
import { debounceTime, filter, map, switchMap, take, takeWhile, timer } from 'rxjs';
import { RouterLink } from '@angular/router';
import { TASK_CAN } from '../task-permissions';
import { formatDate } from '../task-ui';
import { CatalogItemDialog } from './catalog/catalog-item.dialog';

const PAGE_SIZE = 10;
const fmt = (n: number) => n.toLocaleString('vi-VN');

interface Filters {
  q: string;
  positionId: string | null;
  difficulty: number | null;
  status: CatalogItemStatus | null;
}

@Component({
  selector: 'vt-catalog-page',
  imports: [
    VtSkeletonComponent,
    FormsModule,
    ButtonModule,
    CheckboxModule,
    InputTextModule,
    MessageModule,
    SelectModule,
    TagModule,
    CatalogItemDialog,
    RouterLink,
  ],
  template: `
    @if (can('SCR-0501.giao') || can('SCR-0601.duyet-de-nghi')) {
      <a routerLink="/tasks/approvals" class="approvals">Đăng ký và đề nghị chờ duyệt</a>
    }
    @if (catalogs.error()) {
      <p-message severity="error" styleClass="msg">
        <span>{{ loadError() }}</span>
        <button type="button" class="link" (click)="catalogs.reload()">Thử lại</button>
      </p-message>
    } @else if (catalog(); as c) {
      <header class="head">
        <div>
          <h1>Thư viện danh mục công việc</h1>
          <p class="muted">
            Theo Bảng 3, Phụ lục 1 · Danh mục "{{ c.name }}" · phiên bản {{ c.versionNo }} ({{
              versionText(c)
            }})
            @if (c.parentCatalogName) {
              · kế thừa từ {{ c.parentCatalogName }}
            }
          </p>
          <div class="ver">
            <label for="cat-ver" class="inline">Phiên bản</label>
            <p-select
              inputId="cat-ver"
              [options]="versionOptions()"
              optionLabel="label"
              optionValue="value"
              [ngModel]="c.id"
              (ngModelChange)="selectCatalog($event)"
              appendTo="body"
              styleClass="ver-sel"
            />
          </div>
        </div>
        @if (canEdit() && c.status !== 'HET_HIEU_LUC') {
          <div class="actions">
            @if (!simple()) {
              <input
                #file
                type="file"
                accept=".xlsx,.xls,.csv"
                class="sr-only"
                tabindex="-1"
                aria-hidden="true"
                (change)="onFile($event)"
              />
              <p-button
                label="Nhập từ Excel"
                icon="pi pi-upload"
                severity="contrast"
                [outlined]="true"
                [loading]="importing()"
                (onClick)="file.click()"
              />
              <p-button
                label="Nhân bản phiên bản"
                icon="pi pi-copy"
                severity="contrast"
                [outlined]="true"
                [loading]="cloning()"
                (onClick)="clone(c)"
              />
            }
            <p-button label="Thêm công việc" icon="pi pi-plus" (onClick)="openAdd()" />
          </div>
        }
      </header>

      @if (notice(); as n) {
        <p-message severity="success" [text]="n" styleClass="msg" role="status" />
      }
      @if (info(); as n) {
        <p class="note" role="status">{{ n }}</p>
      }
      @if (actionError(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
      }

      <section class="box filters" aria-label="Bộ lọc">
        <span class="search">
          <input
            pInputText
            type="search"
            class="full"
            placeholder="Tìm theo mã, tên công việc, sản phẩm…"
            aria-label="Tìm theo mã, tên công việc, sản phẩm"
            [ngModel]="filters().q"
            (ngModelChange)="setFilter('q', $event)"
          />
        </span>
        <p-select
          [options]="positionOptions()"
          optionLabel="label"
          optionValue="value"
          [ngModel]="filters().positionId"
          placeholder="Vị trí việc làm: Tất cả"
          (ngModelChange)="setFilter('positionId', $event)"
          [showClear]="true"
          ariaLabel="Vị trí việc làm"
          appendTo="body"
          styleClass="f-pos"
        />
        @if (!simple()) {
          <p-select
            [options]="difficultyOptions"
            optionLabel="label"
            optionValue="value"
            [ngModel]="filters().difficulty"
            placeholder="Mức khó: Tất cả"
            (ngModelChange)="setFilter('difficulty', $event)"
            [showClear]="true"
            ariaLabel="Mức khó"
            appendTo="body"
          />
          <p-select
            [options]="statusOptions"
            optionLabel="label"
            optionValue="value"
            [ngModel]="filters().status"
            placeholder="Trạng thái: Tất cả"
            (ngModelChange)="setFilter('status', $event)"
            [showClear]="true"
            ariaLabel="Trạng thái"
            appendTo="body"
          />
        }
        <span class="muted total" aria-live="polite">
          @if (page.value(); as p) {
            {{ fmt(p.total) }} công việc · {{ p.positionCount }} vị trí việc làm
          }
        </span>
      </section>

      <div class="cols">
        <section class="box" aria-labelledby="tbl-title">
          <div class="tbl-head">
            <h2 id="tbl-title" class="h">Danh mục</h2>
            @if (canPlan() && !simple()) {
              <p-button
                [label]="'Đưa vào kế hoạch (' + picked().length + ')'"
                icon="pi pi-calendar-plus"
                size="small"
                [disabled]="!picked().length"
                (onClick)="toPlan()"
              />
            }
          </div>
          @if (page.error()) {
            <p-message severity="error" styleClass="msg"
              ><span>Không tải được danh mục.</span>
              <button type="button" class="link" (click)="page.reload()">Thử lại</button></p-message
            >
          }
          <div class="scroll">
            <table class="tbl">
              <caption class="sr-only">
                Danh mục công việc phiên bản
                {{
                  c.versionNo
                }}
              </caption>
              <thead>
                <tr>
                  @if (canPlan() && !simple()) {
                    <th scope="col" class="chk"><span class="sr-only">Chọn</span></th>
                  }
                  <th scope="col">Mã</th>
                  <th scope="col">Công việc</th>
                  <th scope="col">Sản phẩm</th>
                  <th scope="col">Đơn vị tính</th>
                  <th scope="col" class="num">Mức khó</th>
                  <th scope="col" class="num">Điểm</th>
                  @if (!simple()) {
                    <th scope="col">Tiêu chuẩn chất lượng</th>
                    <th scope="col">Minh chứng yêu cầu</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (i of items(); track i.id) {
                  <tr
                    [class.sel]="selected()?.id === i.id"
                    [class.off]="i.status === 'NGUNG'"
                    (click)="selected.set(i)"
                  >
                    @if (canPlan() && !simple()) {
                      <td class="chk" (click)="$event.stopPropagation()">
                        <p-checkbox
                          [binary]="true"
                          [ngModel]="picked().includes(i.id)"
                          (ngModelChange)="togglePick(i, $event)"
                          [disabled]="i.status === 'NGUNG'"
                          [ariaLabel]="'Chọn ' + i.code"
                        />
                      </td>
                    }
                    <td>
                      <button
                        type="button"
                        class="code"
                        [attr.aria-pressed]="selected()?.id === i.id"
                        (click)="selected.set(i)"
                      >
                        {{ i.code }}
                      </button>
                    </td>
                    <td>
                      {{ i.name }}
                      @if (i.status === 'NGUNG') {
                        <p-tag value="Ngừng" severity="secondary" />
                      }
                    </td>
                    <td>{{ i.productName }}</td>
                    <td>{{ i.unit }}</td>
                    <td class="num">
                      <span class="diff">{{ i.difficulty }}</span>
                    </td>
                    <td class="num">{{ i.baseScore }}</td>
                    @if (!simple()) {
                      <td class="small">{{ i.qualityCriteria }}</td>
                      <td class="small">{{ i.evidenceRequired }}</td>
                    }
                  </tr>
                } @empty {
                  @if (page.isLoading()) {
                    <tr>
                      <td [attr.colspan]="colCount()">
                        <vt-skeleton
                          variant="table"
                          [header]="false"
                          [columns]="5"
                          label="Đang tải danh mục"
                        />
                      </td>
                    </tr>
                  } @else {
                    <tr>
                      <td [attr.colspan]="colCount()" class="muted empty">
                        Không có công việc phù hợp bộ lọc.
                      </td>
                    </tr>
                  }
                }
              </tbody>
            </table>
          </div>
          <nav class="pager" aria-label="Phân trang">
            @if (page.value(); as p) {
              <span class="muted">{{ range() }} / {{ fmt(p.total) }}</span>
            }
            <p-button
              icon="pi pi-angle-left"
              severity="contrast"
              [outlined]="true"
              size="small"
              ariaLabel="Trang trước"
              [disabled]="!cursors().length"
              (onClick)="prev()"
            />
            <p-button
              icon="pi pi-angle-right"
              severity="contrast"
              [outlined]="true"
              size="small"
              ariaLabel="Trang sau"
              [disabled]="!page.value()?.nextCursor"
              (onClick)="next()"
            />
          </nav>
        </section>

        <aside>
          @if (selected(); as s) {
            <section class="box" aria-labelledby="dt-title">
              <h2 id="dt-title" class="h">{{ s.code }} · {{ s.name }}</h2>
              <dl>
                <dt>Vị trí việc làm</dt>
                <dd>{{ s.positionName ?? '—' }}</dd>
                <dt>Nhóm mặc định</dt>
                <dd>{{ group(s) }}</dd>
                <dt>Mức khó</dt>
                <dd>{{ s.difficulty }}/5 · hệ số {{ factor(c, s.difficulty) }}</dd>
                <dt>Điểm chuẩn</dt>
                <dd>{{ s.baseScore }} / 100</dd>
                <dt>Đơn vị tính</dt>
                <dd>{{ s.unit }}</dd>
                <dt>Thời hạn chuẩn</dt>
                <dd>{{ s.standardDays ? s.standardDays + ' ngày làm việc' : '—' }}</dd>
                <dt>Dùng ở</dt>
                <dd>{{ s.usageCount ?? 0 }} nhiệm vụ · {{ s.usagePeriods ?? 0 }} kỳ</dd>
                <dt>Trạng thái</dt>
                <dd>{{ itemStatus(s) }}</dd>
                <dt>Phiên bản</dt>
                <dd>
                  {{ c.versionNo }}
                  @if (c.effectiveFromDate) {
                    · thay phiên bản {{ c.versionNo - 1 }} từ {{ date(c.effectiveFromDate) }}
                  }
                </dd>
              </dl>
              @if (canEdit() && c.status !== 'HET_HIEU_LUC') {
                <p-button
                  label="Sửa"
                  icon="pi pi-pencil"
                  severity="contrast"
                  [outlined]="true"
                  size="small"
                  (onClick)="openEdit(s)"
                />
              }
            </section>
          }
          @if (!simple() && canEdit()) {
            <section class="box" aria-labelledby="imp-title" aria-live="polite">
              <h2 id="imp-title" class="h">Kết quả nhập Excel gần nhất</h2>
              @if (latestImport(); as j) {
                <p>{{ j.fileName }} · {{ fmt(j.totalRows) }} dòng</p>
                @if (j.status === 'DANG_XU_LY') {
                  <p class="muted">Đang kiểm tra tệp…</p>
                } @else {
                  <ul class="imp">
                    <li>
                      <span class="badge ok">{{ fmt(j.validRows) }}</span> dòng hợp lệ
                    </li>
                    @for (g of j.errorGroups; track g.reason) {
                      <li>
                        <span
                          class="badge"
                          [class.bad]="g.kind !== 'MO_HO'"
                          [class.warn]="g.kind === 'MO_HO'"
                          >{{ fmt(g.count) }}</span
                        >
                        {{ g.reason }}
                      </li>
                    }
                  </ul>
                  @if (errorCount(j)) {
                    <p-button
                      label="Tải tệp lỗi"
                      icon="pi pi-download"
                      severity="contrast"
                      [outlined]="true"
                      size="small"
                      (onClick)="downloadErrors(j)"
                    />
                  }
                }
              } @else {
                <p class="muted">Chưa nhập tệp nào cho phiên bản này.</p>
              }
            </section>
          }
        </aside>
      </div>

      <vt-catalog-item-dialog
        [catalog]="c"
        [positions]="positions.value() ?? []"
        [item]="editing()"
        [(visible)]="dialogOpen"
        (saved)="onSaved($event)"
      />
    } @else {
      <vt-skeleton variant="page" [columns]="5" label="Đang tải danh mục" />
    }
  `,
  styles: `
    .approvals {
      display: inline-block;
      margin-bottom: 8px;
      font-weight: 600;
    }
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
      font-size: 0.9em;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .ver {
      display: flex;
      align-items: center;
      gap: 8px;
      margin-top: 4px;
    }
    label.inline {
      font-weight: 500;
    }
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      margin-bottom: 12px;
    }
    .filters {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
      margin-top: 12px;
    }
    .search {
      flex: 1 1 260px;
    }
    .full {
      width: 100%;
    }
    .total {
      margin-left: auto;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 2.2fr) minmax(260px, 1fr);
      gap: 16px;
    }
    @media (max-width: 1100px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 8px;
    }
    .tbl-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
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
      vertical-align: top;
    }
    .tbl tbody tr {
      cursor: pointer;
    }
    .tbl tr.sel {
      background: #fdf2f4;
    }
    .tbl tr.off td {
      color: #6b7280;
    }
    .num {
      text-align: right;
    }
    .chk {
      width: 36px;
    }
    .empty {
      text-align: center;
      padding: 24px;
    }
    .code {
      background: none;
      border: 0;
      padding: 0;
      font: inherit;
      font-weight: 600;
      color: inherit;
      cursor: pointer;
      text-align: left;
      white-space: nowrap;
    }
    .code:focus-visible {
      outline: 2px solid var(--vt-color-primary);
      outline-offset: 2px;
    }
    .diff {
      display: inline-block;
      min-width: 24px;
      padding: 2px 6px;
      border-radius: 4px;
      background: #f3f4f6;
      text-align: center;
    }
    .pager {
      display: flex;
      justify-content: flex-end;
      align-items: center;
      gap: 8px;
      margin-top: 12px;
    }
    dl {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 6px 16px;
      margin: 0 0 8px;
    }
    dt {
      color: #4b5563;
    }
    dd {
      margin: 0;
    }
    .imp {
      list-style: none;
      padding: 0;
      margin: 0 0 12px;
    }
    .imp li {
      padding: 4px 0;
    }
    .badge {
      display: inline-block;
      min-width: 36px;
      padding: 2px 8px;
      border-radius: 4px;
      text-align: center;
      margin-right: 6px;
      font-weight: 600;
    }
    .badge.ok {
      background: #dcfce7;
      color: #14532d;
    }
    .badge.bad {
      background: #fee2e2;
      color: #7f1d1d;
    }
    .badge.warn {
      background: #fef3c7;
      color: #78350f;
    }
    .note {
      margin: 8px 0;
      padding: 10px 14px;
      border-radius: 6px;
      background: #eff6ff;
      border: 1px solid #93c5fd;
      color: #1e3a8a;
    }
    :host ::ng-deep .msg {
      display: block;
      margin: 8px 0;
    }
    .link {
      background: none;
      border: 0;
      color: inherit;
      text-decoration: underline;
      cursor: pointer;
      margin-left: 8px;
      font: inherit;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      padding: 0;
      margin: -1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      border: 0;
    }
  `,
})
export class CatalogPage {
  private readonly api = inject(CatalogApi);
  private readonly theme = inject(ThemeService);
  private readonly router = inject(Router);
  protected readonly can = inject(TASK_CAN);
  protected readonly fmt = fmt;

  protected readonly canEdit = computed(() => this.can('SCR-0401.sua'));
  protected readonly canPlan = computed(() => this.can('SCR-0402.lap'));
  /** Chế độ người cao tuổi: bỏ cột tiêu chuẩn, minh chứng, bộ lọc phụ, nhập Excel, đưa vào kế hoạch (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );
  protected readonly difficultyOptions = [1, 2, 3, 4, 5].map((v) => ({
    value: v,
    label: `Mức khó ${v}`,
  }));
  protected readonly statusOptions = (
    Object.keys(CATALOG_ITEM_STATUS_LABEL) as CatalogItemStatus[]
  ).map((value) => ({
    value,
    label: CATALOG_ITEM_STATUS_LABEL[value],
  }));

  protected readonly catalogs = rxResource({
    stream: () => this.api.catalogs().pipe(map((p) => p.items)),
  });
  protected readonly loadError = computed(() =>
    toProblem(this.catalogs.error()).status === 403
      ? 'Đồng chí không có quyền xem danh mục công việc.'
      : 'Không tải được danh mục công việc.',
  );
  /** Phiên bản đang xem: mặc định phiên bản đang áp dụng (máy chủ xếp trước). */
  private readonly catalogId = linkedSignal<WorkCatalog[] | undefined, string | null>({
    source: () => this.catalogs.value(),
    computation: (list, prev) =>
      prev?.value && list?.some((c) => c.id === prev.value)
        ? prev.value
        : ((list?.find((c) => c.status === 'AP_DUNG') ?? list?.[0])?.id ?? null),
  });
  protected readonly catalog = computed(
    () => this.catalogs.value()?.find((c) => c.id === this.catalogId()) ?? null,
  );
  protected readonly versionOptions = computed(() =>
    (this.catalogs.value() ?? []).map((c) => ({
      value: c.id,
      label: `Phiên bản ${c.versionNo} · ${WORK_CATALOG_STATUS_LABEL[c.status]}`,
    })),
  );
  protected readonly positions = rxResource({
    params: () => this.catalogId() ?? undefined,
    stream: ({ params }) => this.api.positions(params),
  });
  protected readonly positionOptions = computed(() =>
    (this.positions.value() ?? []).map((p) => ({ value: p.id, label: p.name })),
  );

  protected readonly filters = signal<Filters>({
    q: '',
    positionId: null,
    difficulty: null,
    status: 'AP_DUNG',
  });
  private readonly debounced = toSignal(toObservable(this.filters).pipe(debounceTime(250)), {
    initialValue: this.filters(),
  });
  /** Con trỏ của các trang trước để quay lại; đặt lại khi đổi phiên bản hoặc bộ lọc. */
  protected readonly cursors = linkedSignal<unknown, (string | null)[]>({
    source: () => [this.catalogId(), this.debounced()],
    computation: () => [],
  });
  private readonly cursor = linkedSignal<unknown, string | null>({
    source: () => [this.catalogId(), this.debounced()],
    computation: () => null,
  });
  private readonly reloadKey = signal(0);
  protected readonly page = rxResource({
    params: () => {
      const id = this.catalogId();
      return id
        ? { id, f: this.debounced(), cursor: this.cursor(), k: this.reloadKey() }
        : undefined;
    },
    stream: ({ params }) =>
      this.api.versionItems(params.id, {
        q: params.f.q.trim() || undefined,
        positionId: params.f.positionId ?? undefined,
        difficulty: params.f.difficulty ?? undefined,
        status: params.f.status ?? undefined,
        cursor: params.cursor ?? undefined,
        limit: PAGE_SIZE,
      }),
  });
  protected readonly items = computed(() => (this.page.hasValue() ? this.page.value().items : []));
  protected readonly range = computed(() => {
    const start = this.cursors().length * PAGE_SIZE;
    const n = this.items().length;
    return n ? `${start + 1}–${start + n}` : '0';
  });
  protected readonly colCount = computed(
    () => 6 + (this.simple() ? 0 : 2) + (this.canPlan() && !this.simple() ? 1 : 0),
  );
  /** Mục đang xem chi tiết: giữ khi còn trong trang, nếu không chọn dòng đầu. */
  protected readonly selected = linkedSignal<CatalogItem[], CatalogItem | null>({
    source: this.items,
    computation: (list, prev) => list.find((i) => i.id === prev?.value?.id) ?? list[0] ?? null,
  });
  protected readonly picked = linkedSignal<string | null, string[]>({
    source: this.catalogId,
    computation: () => [],
  });

  private readonly latest = rxResource({
    params: () => this.catalogId() ?? undefined,
    stream: ({ params }) => this.api.latestImport(params),
  });
  private readonly runningJob = signal<CatalogImportJob | null>(null);
  protected readonly latestImport = computed(
    () => this.runningJob() ?? this.latest.value() ?? null,
  );

  protected readonly dialogOpen = signal(false);
  protected readonly editing = signal<CatalogItem | null>(null);
  protected readonly importing = signal(false);
  protected readonly cloning = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly info = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);

  protected versionText(c: WorkCatalog) {
    return c.status === 'AP_DUNG' && c.effectiveFromLabel
      ? `áp dụng từ ${c.effectiveFromLabel}`
      : WORK_CATALOG_STATUS_LABEL[c.status];
  }
  protected group(i: CatalogItem) {
    return TASK_GROUP_LABEL[i.defaultGroup];
  }
  protected itemStatus(i: CatalogItem) {
    return CATALOG_ITEM_STATUS_LABEL[i.status ?? 'AP_DUNG'];
  }
  protected factor(c: WorkCatalog, d: number) {
    return String(c.difficultyFactors[String(d)] ?? 1).replace('.', ',');
  }
  protected date(d: string) {
    return formatDate(d, true);
  }
  protected errorCount(j: CatalogImportJob) {
    return j.errorGroups.reduce((s, g) => s + g.count, 0);
  }

  protected setFilter<K extends keyof Filters>(k: K, v: Filters[K]) {
    this.filters.update((f) => ({ ...f, [k]: v }));
  }
  protected selectCatalog(id: string) {
    this.clearMessages();
    this.runningJob.set(null);
    this.catalogId.set(id);
  }
  protected next() {
    const n = this.page.value()?.nextCursor;
    if (!n) return;
    this.cursors.update((c) => [...c, this.cursor()]);
    this.cursor.set(n);
  }
  protected prev() {
    const c = this.cursors();
    if (!c.length) return;
    this.cursor.set(c[c.length - 1]);
    this.cursors.set(c.slice(0, -1));
  }
  protected togglePick(i: CatalogItem, on: boolean) {
    this.picked.update((p) => (on ? [...p, i.id] : p.filter((x) => x !== i.id)));
  }
  protected toPlan() {
    void this.router.navigate(['/plans'], {
      queryParams: { catalogItems: this.picked().join(',') },
    });
  }

  private clearMessages() {
    this.notice.set(null);
    this.info.set(null);
    this.actionError.set(null);
  }
  protected openAdd() {
    this.clearMessages();
    this.editing.set(null);
    this.dialogOpen.set(true);
  }
  protected openEdit(i: CatalogItem) {
    this.clearMessages();
    this.editing.set(i);
    this.dialogOpen.set(true);
  }

  /** Lưu xong: nếu máy chủ ghi sang phiên bản khác thì chuyển sang phiên bản đó để người dùng thấy thay đổi. */
  protected onSaved(r: CatalogItemSaveResult) {
    const before = this.catalog();
    const msg = this.editing()
      ? `Đã lưu công việc ${r.item.code}.`
      : `Đã thêm công việc ${r.item.code}.`;
    if (r.movedToNewVersion) {
      this.info.set(
        this.editing()
          ? `Công việc ${r.item.code} đã được dùng ở kỳ đã khoá. Thay đổi đã lưu ở phiên bản danh mục mới (phiên bản ${r.catalog.versionNo}, nháp); kỳ cũ giữ nguyên phiên bản ${before?.versionNo}.`
          : `Đã thêm công việc ${r.item.code} vào phiên bản nháp ${r.catalog.versionNo}; phiên bản ${before?.versionNo} đang áp dụng giữ nguyên.`,
      );
      this.catalogs.reload();
      this.catalogId.set(r.catalog.id);
      this.filters.set({ q: r.item.code, positionId: null, difficulty: null, status: null });
    } else {
      this.notice.set(msg);
      this.catalogs.reload();
      this.reloadKey.update((k) => k + 1);
    }
  }

  protected clone(c: WorkCatalog) {
    this.clearMessages();
    this.cloning.set(true);
    this.api.cloneVersion(c.id).subscribe({
      next: (n) => {
        this.cloning.set(false);
        this.notice.set(
          `Đã tạo phiên bản ${n.versionNo} ở trạng thái nháp từ phiên bản ${c.versionNo}.`,
        );
        this.catalogs.reload();
        this.catalogId.set(n.id);
      },
      error: (e) => {
        this.cloning.set(false);
        this.actionError.set(toProblem(e).title);
      },
    });
  }

  /** Nhập Excel: 202 rồi hỏi tác vụ mỗi giây tới khi xong; xong hiện MSG-04-02. */
  protected onFile(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const f = input.files?.[0];
    input.value = '';
    const c = this.catalog();
    if (!f || !c) return;
    this.clearMessages();
    this.importing.set(true);
    this.api
      .importExcel(c.id, f)
      .pipe(
        switchMap(({ location }) =>
          timer(0, 1000).pipe(switchMap(() => this.api.importJob(location))),
        ),
        takeWhile((j) => j.status === 'DANG_XU_LY', true),
        filter((j) => {
          this.runningJob.set(j);
          return j.status !== 'DANG_XU_LY';
        }),
        take(1),
      )
      .subscribe({
        next: (j) => {
          this.importing.set(false);
          const bad = this.errorCount(j);
          this.notice.set(
            `Đã nhập ${fmt(j.validRows)} dòng hợp lệ; ${fmt(bad)} dòng lỗi.${bad ? ' Tải tệp lỗi để xem lý do.' : ''}`,
          );
          this.reloadKey.update((k) => k + 1);
        },
        error: (e) => {
          this.importing.set(false);
          this.runningJob.set(null);
          const p = toProblem(e);
          this.actionError.set(
            p.errors?.length ? p.errors.map((x) => x.message).join('; ') : p.title,
          );
        },
      });
  }

  protected downloadErrors(j: CatalogImportJob) {
    this.api.importErrors(j.id).subscribe({
      next: (blob) => {
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `loi-${j.fileName.replace(/\.[^.]+$/, '')}.csv`;
        a.click();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      },
      error: (e) => this.actionError.set(toProblem(e).title),
    });
  }
}
