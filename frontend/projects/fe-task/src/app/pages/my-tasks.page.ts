/*
 * SCR-0503 "Việc của tôi" (QT-05, TL-02 Hình 8.5) — đối chiếu đặc tả:
 * Trường: 1 bộ lọc nhanh có số đếm (Tất cả, Chậm, Sắp hạn, Chờ nghiệm thu, Hoàn thành) + lọc vai trò, nhóm, KR;
 *   2 nhiệm vụ, mã; 3 vai trò; 4 hạn dd/mm; 5 tiến độ; 6 tình trạng (ngày làm việc theo lịch nghỉ); 7 trạng thái;
 *   8, 9 đăng ký: mục danh mục, số lượng, hạn đề xuất (vùng 5, `TaskRegistrationPanel`).
 * Chức năng: lọc, sắp xếp (theo hạn) → GET /api/v1/tasks/mine?period=&filter=&cursor=&limit=20 (đường dẫn theo wiki/14);
 *   mở chi tiết → SCR-0601; nhận việc → POST /tasks/{id}/accept (EVT-06); lưu tiến độ → POST /tasks/{id}/progress (EVT-05);
 *   cập nhật hàng loạt → POST /tasks/progress-batch; gửi đăng ký → POST /tasks/registrations (EVT-21);
 *   xuất Excel → POST /tasks/export (202 + Location).
 * Quy tắc: BR-06-02 (giảm tiến độ phải ghi chú), BR-06-03 (chỉ chủ trì đổi phần trăm). Thông báo: MSG-05-11, MSG-05-13, MSG-06-01.
 * Phân quyền (wiki/15a): SCR-0503.xem, .nhan-viec, .tien-do, .dang-ky — đều `member` phạm vi Bản thân.
 */
import {
  Component,
  ElementRef,
  computed,
  inject,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { rxResource, toObservable, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { debounceTime } from 'rxjs';
import {
  AssigneeRole,
  MY_TASKS_QUICK_FILTER_LABEL,
  MyTasksQuery,
  MyTasksQuickFilter,
  TASK_GROUP_LABEL,
  TaskApi,
  TaskDetail,
  TaskGroup,
  TaskSummary,
  toProblem,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TagModule } from 'primeng/tag';
import { TASK_CAN } from '../task-permissions';
import { formatDate, progressTone, situation } from '../task-ui';
import { BulkProgressDialog } from './my-tasks/bulk-progress.dialog';
import { TaskPreviewPanel } from './my-tasks/task-preview.panel';
import { TaskRegistrationPanel } from './my-tasks/task-registration.panel';

interface SavedFilters {
  filter: MyTasksQuickFilter;
  role: AssigneeRole | null;
  group: TaskGroup | null;
  kr: string | null;
}

const FILTER_KEY = 'vt.fe-task.myTasks.filters';
const PAGE_SIZE = 20;
const DEFAULT_FILTERS: SavedFilters = { filter: 'TAT_CA', role: null, group: null, kr: null };

/** Đọc bộ lọc đã lưu (chỉ là lựa chọn giao diện, không phải dữ liệu C2 nên được lưu cục bộ). */
export function readSavedFilters(
  storage: Pick<Storage, 'getItem'> | undefined = globalThis.localStorage,
): SavedFilters {
  try {
    const v = JSON.parse(storage?.getItem(FILTER_KEY) ?? 'null') as Partial<SavedFilters> | null;
    if (!v || typeof v !== 'object') return DEFAULT_FILTERS;
    return {
      filter: v.filter && v.filter in MY_TASKS_QUICK_FILTER_LABEL ? v.filter : 'TAT_CA',
      role: v.role === 'CHU_TRI' || v.role === 'PHOI_HOP' ? v.role : null,
      group: v.group === 'THEN_CHOT' || v.group === 'THUONG_XUYEN' ? v.group : null,
      kr: typeof v.kr === 'string' ? v.kr : null,
    };
  } catch {
    return DEFAULT_FILTERS;
  }
}

@Component({
  selector: 'vt-my-tasks-page',
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    CheckboxModule,
    InputTextModule,
    MessageModule,
    SelectModule,
    TagModule,
    TaskPreviewPanel,
    TaskRegistrationPanel,
    BulkProgressDialog,
  ],
  template: `
    <header class="page-head">
      <div>
        <h1>Việc của tôi</h1>
        <p class="muted">{{ periodLabel() }} · {{ counts().TAT_CA }} nhiệm vụ · sắp theo hạn</p>
      </div>
      <div class="head-actions">
        <input
          pInputText
          type="search"
          placeholder="Tìm theo tên hoặc mã nhiệm vụ"
          [ngModel]="q()"
          (ngModelChange)="q.set($event)"
          aria-label="Tìm nhiệm vụ"
        />
        @if (!reduced()) {
          <p-button
            label="Xuất Excel"
            severity="contrast"
            [outlined]="true"
            [loading]="exporting()"
            (onClick)="exportExcel()"
          />
        }
        @if (can('SCR-0503.dang-ky')) {
          <p-button label="Đăng ký nhiệm vụ" icon="pi pi-plus" (onClick)="focusRegistration()" />
        }
      </div>
    </header>

    <section class="card filters" aria-label="Bộ lọc">
      <div class="quick" role="group" aria-label="Lọc nhanh">
        @for (f of quickFilters; track f) {
          <button
            type="button"
            class="chip"
            [class.active]="filter() === f"
            [attr.aria-pressed]="filter() === f"
            (click)="setFilter(f)"
          >
            {{ quickLabel[f] }} <span class="count">{{ counts()[f] }}</span>
          </button>
        }
      </div>
      @if (!reduced()) {
        <div class="selects">
          <p-select
            [options]="roleOptions"
            optionLabel="label"
            optionValue="value"
            [ngModel]="role()"
            (ngModelChange)="setRole($event)"
            ariaLabel="Lọc theo vai trò"
            placeholder="Vai trò: Tất cả"
            [showClear]="true"
          />
          <p-select
            [options]="groupOptions"
            optionLabel="label"
            optionValue="value"
            [ngModel]="group()"
            (ngModelChange)="setGroup($event)"
            ariaLabel="Lọc theo nhóm"
            placeholder="Nhóm: Tất cả"
            [showClear]="true"
          />
          <p-select
            [options]="krOptions()"
            optionLabel="title"
            optionValue="id"
            [ngModel]="kr()"
            (ngModelChange)="setKr($event)"
            ariaLabel="Lọc theo KR"
            placeholder="KR: Tất cả"
            [showClear]="true"
          />
        </div>
      }
    </section>

    @if (notice(); as n) {
      <p-message [severity]="n.severity" [text]="n.text" styleClass="notice" />
    }

    <div class="layout">
      <section class="card list" aria-labelledby="list-title">
        <h2 id="list-title">Danh sách nhiệm vụ</h2>
        @if (page.error()) {
          <p-message severity="error" styleClass="notice">
            <span>{{ loadError() }}</span>
            <button type="button" class="link" (click)="page.reload()">Thử lại</button>
          </p-message>
        }
        <div class="table-wrap">
          <table class="tasks" [attr.aria-busy]="page.isLoading()">
            <thead>
              <tr>
                @if (!reduced()) {
                  <th scope="col" class="sel">
                    <p-checkbox
                      [binary]="true"
                      [ngModel]="allSelected()"
                      (ngModelChange)="toggleAll($event)"
                      ariaLabel="Chọn tất cả"
                    />
                  </th>
                }
                <th scope="col">Nhiệm vụ</th>
                @if (!reduced()) {
                  <th scope="col">Nhóm</th>
                }
                <th scope="col">Vai trò</th>
                <th scope="col">Hạn</th>
                <th scope="col" class="pct">Tiến độ</th>
                <th scope="col">Tình trạng</th>
                @if (!reduced()) {
                  <th scope="col">Trạng thái</th>
                }
              </tr>
            </thead>
            <tbody>
              @for (t of rows(); track t.id) {
                @let s = sit(t);
                <tr [class.current]="t.id === previewId()" (click)="previewId.set(t.id)">
                  @if (!reduced()) {
                    <td class="sel" (click)="$event.stopPropagation()">
                      <p-checkbox
                        [binary]="true"
                        [ngModel]="selected().has(t.id)"
                        (ngModelChange)="toggle(t.id, $event)"
                        [ariaLabel]="'Chọn ' + t.code"
                      />
                    </td>
                  }
                  <td>
                    <a [routerLink]="[t.id]" class="task-link" (click)="$event.stopPropagation()">{{
                      t.title
                    }}</a>
                    <div class="muted small">{{ t.code }}</div>
                    <button
                      type="button"
                      class="peek"
                      (click)="previewId.set(t.id); $event.stopPropagation()"
                      [attr.aria-label]="'Xem nhanh ' + t.code"
                      [attr.aria-pressed]="t.id === previewId()"
                    >
                      Xem nhanh
                    </button>
                  </td>
                  @if (!reduced()) {
                    <td>
                      <span class="group">{{ groupLabel[t.taskGroup] }}</span>
                    </td>
                  }
                  <td>{{ t.role === 'CHU_TRI' ? 'Chủ trì' : 'Phối hợp' }}</td>
                  <td>{{ date(t.dueDate) }}</td>
                  <td class="pct">
                    <div
                      class="bar"
                      role="progressbar"
                      [attr.aria-valuenow]="t.progress"
                      aria-valuemin="0"
                      aria-valuemax="100"
                      [attr.aria-label]="'Tiến độ ' + t.code"
                    >
                      <span [class]="'fill ' + tone(t)" [style.width.%]="t.progress"></span>
                    </div>
                    <span class="small">{{ t.progress }}%</span>
                  </td>
                  <td>
                    <p-tag
                      [value]="s.label"
                      [severity]="s.severity"
                      [rounded]="true"
                      styleClass="nowrap"
                    />
                  </td>
                  @if (!reduced()) {
                    <td class="status">{{ t.status }}</td>
                  }
                </tr>
              } @empty {
                @if (!page.isLoading()) {
                  <tr>
                    <td [attr.colspan]="reduced() ? 5 : 8" class="muted">
                      Không có nhiệm vụ nào phù hợp bộ lọc.
                    </td>
                  </tr>
                }
              }
            </tbody>
          </table>
        </div>
        <footer class="list-foot">
          @if (!reduced() && can('SCR-0503.tien-do')) {
            <p-button
              label="Cập nhật tiến độ hàng loạt"
              severity="secondary"
              [outlined]="true"
              [disabled]="!selected().size"
              (onClick)="openBulk()"
            />
          }
          <span class="muted small" aria-live="polite">
            @if (page.isLoading()) {
              Đang tải…
            } @else {
              1–{{ rows().length }} / {{ total() }} · {{ pageSize }} dòng/trang
            }
          </span>
          @if (nextCursor()) {
            <p-button
              label="Xem thêm"
              [text]="true"
              [loading]="loadingMore()"
              (onClick)="loadMore()"
            />
          }
        </footer>
      </section>

      <aside class="side">
        @if (preview(); as p) {
          <vt-task-preview-panel
            [task]="p"
            (saved)="onSaved($event)"
            (reloadRequested)="page.reload()"
          />
        }
        @if (can('SCR-0503.dang-ky')) {
          <vt-task-registration-panel #reg [period]="period()" (registered)="onRegistered()" />
        }
      </aside>
    </div>

    <!-- WEB-052: hộp cập nhật hàng loạt (p-dialog) chỉ tải khi người dùng mở lần đầu -->
    @defer (when bulkOpen()) {
      <vt-bulk-progress-dialog
        [tasks]="bulkTasks()"
        [(visible)]="bulkOpen"
        (saved)="onBulkSaved($event)"
      />
    }
  `,
  styles: `
    :host {
      --mt-muted: #4b5563;
      --mt-link: color-mix(in srgb, var(--vt-color-primary) 82%, black);
    }
    .page-head {
      display: flex;
      justify-content: space-between;
      align-items: flex-end;
      gap: 16px;
      flex-wrap: wrap;
      margin-bottom: 16px;
    }
    h1 {
      margin: 0;
      font-size: 1.5rem;
    }
    h2 {
      margin: 0 0 12px;
      font-size: 1.05rem;
    }
    .head-actions {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      align-items: center;
    }
    .muted {
      color: var(--mt-muted);
      margin: 4px 0 0;
    }
    .small {
      font-size: 0.85em;
    }
    .card {
      background: var(--vt-color-surface, #fff);
      border-radius: var(--vt-radius-card, 8px);
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
    }
    .filters {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      margin-bottom: 16px;
    }
    .quick {
      display: flex;
      flex-wrap: wrap;
    }
    .chip {
      border: 1px solid var(--p-content-border-color, #d1d5db);
      background: transparent;
      padding: 0 14px;
      min-height: var(--vt-min-touch, 40px);
      font: inherit;
      cursor: pointer;
      color: var(--vt-color-text);
      margin-left: -1px;
    }
    .chip:first-child {
      border-radius: 6px 0 0 6px;
    }
    .chip:last-child {
      border-radius: 0 6px 6px 0;
    }
    .chip.active {
      background: var(--mt-link);
      border-color: var(--mt-link);
      color: var(--vt-color-on-primary, #fff);
      font-weight: 600;
    }
    .selects {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 340px;
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1100px) {
      .layout {
        grid-template-columns: 1fr;
      }
    }
    .side {
      display: grid;
      gap: 16px;
    }
    .table-wrap {
      overflow-x: auto;
    }
    .tasks {
      width: 100%;
      border-collapse: collapse;
      min-width: 44rem;
    }
    .tasks th {
      text-align: left;
      font-weight: 600;
      color: var(--mt-muted);
      padding: 10px 8px;
      background: var(--p-content-hover-background, #f9fafb);
    }
    .tasks td {
      padding: 10px 8px;
      border-top: 1px solid var(--p-content-border-color, #eee);
      vertical-align: middle;
    }
    .tasks tbody tr {
      cursor: pointer;
    }
    .tasks tr.current {
      background: color-mix(in srgb, var(--vt-color-primary) 9%, white);
    }
    .sel {
      width: 2.5rem;
    }
    .pct {
      width: 9rem;
    }
    .status {
      font-size: 0.85em;
      color: var(--mt-muted);
    }
    .task-link {
      color: var(--vt-color-text);
      font-weight: 600;
      text-decoration: none;
    }
    .task-link:hover {
      color: var(--mt-link);
      text-decoration: underline;
    }
    .peek {
      background: none;
      border: 0;
      padding: 0;
      margin-top: 2px;
      color: var(--mt-link);
      font: inherit;
      font-size: 0.85em;
      cursor: pointer;
      text-decoration: underline;
    }
    .group {
      background: var(--p-content-hover-background, #f3f4f6);
      border-radius: 4px;
      padding: 2px 8px;
      font-size: 0.9em;
      white-space: nowrap;
    }
    .bar {
      height: 8px;
      background: #e5e7eb;
      border-radius: 4px;
      overflow: hidden;
    }
    .fill {
      display: block;
      height: 100%;
    }
    .fill.danger {
      background: #c81e1e;
    }
    .fill.warn {
      background: #d97706;
    }
    .fill.ok {
      background: #15803d;
    }
    .list-foot {
      display: flex;
      justify-content: space-between;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
      margin-top: 12px;
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
    :host ::ng-deep .notice {
      display: block;
      margin-bottom: 16px;
    }
    :host ::ng-deep .nowrap {
      white-space: nowrap;
    }
  `,
})
export class MyTasksPage {
  private readonly api = inject(TaskApi);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(TASK_CAN);
  private readonly reg = viewChild('reg', { read: ElementRef });

  protected readonly quickFilters: MyTasksQuickFilter[] = [
    'TAT_CA',
    'CHAM',
    'SAP_HAN',
    'CHO_NGHIEM_THU',
    'HOAN_THANH',
  ];
  protected readonly quickLabel = MY_TASKS_QUICK_FILTER_LABEL;
  protected readonly groupLabel = TASK_GROUP_LABEL;
  protected readonly pageSize = PAGE_SIZE;
  protected readonly roleOptions = [
    { label: 'Chủ trì', value: 'CHU_TRI' },
    { label: 'Phối hợp', value: 'PHOI_HOP' },
  ];
  protected readonly groupOptions = [
    { label: 'Then chốt', value: 'THEN_CHOT' },
    { label: 'Thường xuyên', value: 'THUONG_XUYEN' },
  ];

  /** Chế độ Đảng viên cao tuổi có `reducedSteps`: chỉ giữ lọc nhanh, danh sách gọn và cập nhật từng việc. */
  protected readonly reduced = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  private readonly saved = readSavedFilters();
  protected readonly filter = signal<MyTasksQuickFilter>(this.saved.filter);
  protected readonly role = signal<AssigneeRole | null>(this.saved.role);
  protected readonly group = signal<TaskGroup | null>(this.saved.group);
  protected readonly kr = signal<string | null>(this.saved.kr);
  protected readonly q = signal('');
  /** Từ khoá tìm kiếm được trễ 250 ms để không gọi API theo từng phím gõ. */
  private readonly debouncedQ = toSignal(toObservable(this.q).pipe(debounceTime(250)), {
    initialValue: '',
  });

  private readonly query = computed<MyTasksQuery>(() => ({
    filter: this.filter(),
    role: this.role() ?? undefined,
    group: this.group() ?? undefined,
    keyResultId: this.kr() ?? undefined,
    q: this.debouncedQ() || undefined,
    limit: PAGE_SIZE,
  }));
  protected readonly page = rxResource({
    params: () => this.query(),
    stream: ({ params }) => this.api.myTasks(params),
  });
  /** Trang đầu hoặc `null`; không đọc thẳng giá trị tài nguyên khi đang lỗi (sẽ ném ResourceValueError). */
  private readonly firstPage = computed(() => (this.page.hasValue() ? this.page.value() : null));

  /** Trang tiếp theo nạp thêm và bản cập nhật tại chỗ; cả hai đặt lại khi trang đầu đổi. */
  private readonly more = linkedSignal<unknown, TaskSummary[]>({
    source: this.firstPage,
    computation: () => [],
  });
  private readonly patched = linkedSignal<unknown, Map<string, TaskSummary>>({
    source: this.firstPage,
    computation: () => new Map(),
  });
  protected readonly nextCursor = linkedSignal(() => this.firstPage()?.nextCursor ?? null);
  protected readonly loadingMore = signal(false);

  protected readonly rows = computed(() => {
    const first = this.firstPage()?.items ?? [];
    const p = this.patched();
    return [...first, ...this.more()].map((t) => p.get(t.id) ?? t);
  });
  protected readonly counts = computed(
    () =>
      this.firstPage()?.counts ?? {
        TAT_CA: 0,
        CHAM: 0,
        SAP_HAN: 0,
        CHO_NGHIEM_THU: 0,
        HOAN_THANH: 0,
      },
  );
  protected readonly total = computed(() => this.counts()[this.filter()]);
  protected readonly period = computed(() => this.firstPage()?.period ?? null);
  protected readonly periodLabel = computed(() => this.period()?.label ?? 'Kỳ hiện tại');
  protected readonly krOptions = computed(() => this.firstPage()?.keyResults ?? []);
  protected readonly loadError = computed(() =>
    this.page.error() ? toProblem(this.page.error()).title : '',
  );

  /** Dòng đang xem nhanh (vùng 3): mặc định dòng đầu tiên còn mở. */
  protected readonly previewId = linkedSignal<TaskSummary[], string | null>({
    source: this.rows,
    computation: (rows, prev) =>
      prev?.value && rows.some((r) => r.id === prev.value)
        ? prev.value
        : ((rows.find((r) => r.status === 'DANG_THUC_HIEN' || r.status === 'DA_GIAO') ?? rows[0])
            ?.id ?? null),
  });
  protected readonly preview = computed(
    () => this.rows().find((r) => r.id === this.previewId()) ?? null,
  );

  protected readonly selected = linkedSignal<unknown, Set<string>>({
    source: this.firstPage,
    computation: () => new Set(),
  });
  protected readonly allSelected = computed(
    () => this.rows().length > 0 && this.rows().every((r) => this.selected().has(r.id)),
  );
  protected readonly bulkOpen = signal(false);
  protected readonly bulkTasks = signal<TaskSummary[]>([]);

  protected readonly exporting = signal(false);
  protected readonly notice = signal<{
    severity: 'success' | 'error' | 'info';
    text: string;
  } | null>(null);

  protected sit(t: TaskSummary) {
    return situation(t);
  }
  protected tone(t: TaskSummary) {
    return progressTone(t);
  }
  protected date(d: string) {
    return formatDate(d);
  }

  protected setFilter(f: MyTasksQuickFilter) {
    this.filter.set(f);
    this.persist();
  }
  protected setRole(v: AssigneeRole | null) {
    this.role.set(v ?? null);
    this.persist();
  }
  protected setGroup(v: TaskGroup | null) {
    this.group.set(v ?? null);
    this.persist();
  }
  protected setKr(v: string | null) {
    this.kr.set(v ?? null);
    this.persist();
  }

  private persist(): void {
    const v: SavedFilters = {
      filter: this.filter(),
      role: this.role(),
      group: this.group(),
      kr: this.kr(),
    };
    try {
      localStorage.setItem(FILTER_KEY, JSON.stringify(v));
    } catch {
      /* trình duyệt chặn bộ nhớ cục bộ: bộ lọc chỉ giữ trong phiên */
    }
  }

  protected toggle(id: string, on: boolean) {
    this.selected.update((s) => {
      const n = new Set(s);
      if (on) n.add(id);
      else n.delete(id);
      return n;
    });
  }
  protected toggleAll(on: boolean) {
    this.selected.set(on ? new Set(this.rows().map((r) => r.id)) : new Set());
  }

  protected loadMore(): void {
    const cursor = this.nextCursor();
    if (!cursor) return;
    this.loadingMore.set(true);
    this.api.myTasks({ ...this.query(), cursor }).subscribe({
      next: (p) => {
        this.more.update((m) => [...m, ...p.items]);
        this.nextCursor.set(p.nextCursor);
        this.loadingMore.set(false);
      },
      error: (e) => {
        this.loadingMore.set(false);
        this.notice.set({ severity: 'error', text: toProblem(e).title });
      },
    });
  }

  protected onSaved(t: TaskDetail): void {
    this.patch([t]);
  }

  protected openBulk(): void {
    this.bulkTasks.set(this.rows().filter((r) => this.selected().has(r.id)));
    this.bulkOpen.set(true);
  }

  protected onBulkSaved(ts: TaskSummary[]): void {
    this.patch(ts);
    if (ts.length)
      this.notice.set({ severity: 'success', text: `Đã lưu tiến độ ${ts.length} nhiệm vụ.` });
  }

  private patch(ts: TaskSummary[]): void {
    this.patched.update((m) => {
      const n = new Map(m);
      for (const t of ts) n.set(t.id, t);
      return n;
    });
  }

  protected onRegistered(): void {
    // nhiệm vụ đăng ký (NHAP) xuất hiện trong danh sách và số đếm của trang đầu
    this.page.reload();
  }

  protected focusRegistration(): void {
    const el = this.reg()?.nativeElement as HTMLElement | undefined;
    el?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    el?.querySelector<HTMLElement>('h2')?.focus();
  }

  protected exportExcel(): void {
    this.exporting.set(true);
    const { limit: _limit, ...query } = this.query();
    this.api.exportMyTasks(query).subscribe({
      next: () => {
        this.exporting.set(false);
        this.notice.set({
          severity: 'info',
          text: 'Đã tạo yêu cầu xuất Excel. Tệp sẽ sẵn sàng trong mục Thông báo khi xử lý xong.',
        });
      },
      error: (e) => {
        this.exporting.set(false);
        this.notice.set({ severity: 'error', text: toProblem(e).title });
      },
    });
  }
}
