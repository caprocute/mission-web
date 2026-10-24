import {
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  model,
  output,
  signal,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import {
  DISCREPANCY_FIELD_LABEL,
  DiscrepancyResolution,
  DiscrepancySeverity,
  FIELD_PERSON_MATCH,
  OrgDiscrepancy,
  OrgSyncApi,
  SEVERITY_LABEL,
  SUGGESTED_ACTION_LABEL,
  SYNC_SOURCE_LABEL,
} from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { ADMIN_CAN } from '../../admin-permissions';
import { errorText, fmtInt } from '../../admin-ui';
import { OrgSyncAssignDialog, OrgSyncMapDialog } from './org-sync-dialogs';

/** MSG-01-13; khi máy chủ chưa trả số phân công (chờ hợp đồng) chỉ báo số quyết định. */
export const MSG_01_13 = (n: number, m?: number) =>
  m === undefined
    ? `Đã áp dụng ${n} quyết định.`
    : `Đã áp dụng ${n} quyết định. ${m} phân công được cập nhật với ngày hiệu lực theo quyết định gốc.`;

const PAGE = 20;
const TABS = Object.keys(DISCREPANCY_FIELD_LABEL);

/**
 * Vùng 2 "Danh sách lệch dữ liệu" và vùng 3 thao tác của SCR-0104: lọc theo trường lệch và mức,
 * phân trang con trỏ, chọn dòng rồi lấy theo nguồn hoặc giữ nội bộ (`/org/discrepancies/resolve`,
 * kết quả từng mục; phiên AAL2, máy chủ trả 401 step-up khi thiếu), ánh xạ thủ công, giao xử lý.
 * Kết quả báo lên trang qua `done`.
 */
@Component({
  selector: 'vt-org-sync-list',
  imports: [FormsModule, ButtonModule, OrgSyncMapDialog, OrgSyncAssignDialog],
  template: `
    <div class="bar">
      <div class="tabs" role="group" aria-label="Lọc theo trường lệch">
        <button type="button" [attr.aria-pressed]="!field()" (click)="field.set(null)">
          Tất cả {{ int(total()) }}
        </button>
        @for (t of tabs(); track t) {
          <button type="button" [attr.aria-pressed]="field() === t" (click)="field.set(t)">
            {{ fieldText(t) }} {{ byField(t) }}
          </button>
        }
      </div>
      <label class="sr-only" for="sev">Mức</label>
      <select
        id="sev"
        class="native"
        [ngModel]="severity() ?? ''"
        (ngModelChange)="severity.set($event || null)"
      >
        <option value="">Mức: Tất cả</option>
        @for (s of sevs; track s) {
          <option [value]="s">Mức: {{ sevLabel[s] }}</option>
        }
      </select>
    </div>

    <div class="scroll">
      <table class="tbl" [attr.aria-busy]="first.isLoading()">
        <caption class="sr-only">
          Danh sách lệch dữ liệu, chọn tên cán bộ để xem chi tiết
        </caption>
        <thead>
          <tr>
            @if (canAct()) {
              <th scope="col" class="c">
                <input
                  type="checkbox"
                  aria-label="Chọn tất cả dòng đang hiện"
                  [checked]="allChecked()"
                  (change)="checkAll($any($event.target).checked)"
                />
              </th>
            }
            <th scope="col">Cán bộ</th>
            <th scope="col">Trường</th>
            @if (!simple()) {
              <th scope="col">Nguồn</th>
            }
            <th scope="col">Giá trị nguồn</th>
            <th scope="col">Dữ liệu hiện có</th>
            <th scope="col">Mức</th>
            <th scope="col">Đề xuất</th>
          </tr>
        </thead>
        <tbody>
          @for (d of rows(); track d.id) {
            <tr [class.on]="focused()?.id === d.id">
              @if (canAct()) {
                <td class="c">
                  <input
                    type="checkbox"
                    [attr.aria-label]="'Chọn ' + d.personName"
                    [checked]="selected().has(d.id)"
                    (change)="toggle(d, $any($event.target).checked)"
                  />
                </td>
              }
              <th scope="row">
                <button
                  type="button"
                  class="who"
                  [attr.aria-current]="focused()?.id === d.id"
                  (click)="focused.set(d)"
                >
                  {{ d.personName }}
                </button>
                @if (d.maskedNationalId) {
                  <span class="muted">CCCD {{ d.maskedNationalId }}</span>
                }
                @if (d.assigneeName) {
                  <span class="muted">Đã giao: {{ d.assigneeName }}</span>
                }
              </th>
              <td>{{ fieldText(d.field) }}</td>
              @if (!simple()) {
                <td>{{ srcLabel[d.sourceConnector] }}</td>
              }
              <td>{{ d.sourceValueMasked }}</td>
              <td>{{ d.currentValueMasked }}</td>
              <td>
                <span class="sev" [class]="'sev s-' + d.severity">{{ sevLabel[d.severity] }}</span>
              </td>
              <td>{{ actText(d.suggestedAction) }}</td>
            </tr>
          } @empty {
            <tr>
              <td colspan="8" class="muted">
                {{ first.isLoading() ? 'Đang tải…' : 'Không còn mục lệch nào cần đối soát.' }}
              </td>
            </tr>
          }
        </tbody>
      </table>
    </div>
    @if (first.error(); as e) {
      <p class="err" role="alert">{{ err(e, 'Không tải được danh sách lệch.') }}</p>
    }
    @if (cursor()) {
      <button type="button" class="link" [disabled]="moreBusy()" (click)="more()">
        Xem thêm (đang hiện {{ rows().length }})
      </button>
    }

    @if (canAct()) {
      <div class="acts" role="group" aria-label="Xử lý dòng đã chọn">
        @if (can('SCR-0104.xu-ly-lech')) {
          <p-button
            [label]="'Lấy theo nguồn (' + selected().size + ')'"
            [disabled]="!selected().size || busy()"
            (onClick)="resolve('NHAN_NGUON')"
          />
          <p-button
            label="Giữ dữ liệu nội bộ"
            severity="contrast"
            [outlined]="true"
            [disabled]="!selected().size || busy()"
            (onClick)="resolve('GIU_HIEN_TAI')"
          />
          <p-button
            label="Ánh xạ thủ công"
            severity="contrast"
            [outlined]="true"
            [disabled]="!mapRow()"
            (onClick)="mapOpen.set(true)"
          />
        }
        @if (can('SCR-0104.giao-xu-ly')) {
          <p-button
            label="Ghi chú, giao xử lý"
            severity="contrast"
            [outlined]="true"
            [disabled]="!one()"
            (onClick)="assignOpen.set(true)"
          />
        }
      </div>
    }
    @if (error(); as e) {
      <p class="err" role="alert">{{ e }}</p>
    }

    @if (mapRow(); as r) {
      <vt-org-sync-map-dialog [row]="r" [(visible)]="mapOpen" (saved)="afterMap(r)" />
    }
    @if (one(); as r) {
      <vt-org-sync-assign-dialog [row]="r" [(visible)]="assignOpen" (saved)="afterAssign($event)" />
    }
  `,
  styles: `
    .bar {
      display: flex;
      justify-content: space-between;
      gap: 8px;
      flex-wrap: wrap;
      margin-bottom: 8px;
    }
    .tabs {
      display: inline-flex;
      flex-wrap: wrap;
      border: 1px solid #cbd5e1;
      border-radius: 6px;
      overflow: hidden;
    }
    .tabs button {
      border: 0;
      border-right: 1px solid #e5e7eb;
      background: #fff;
      padding: 8px 12px;
      font: inherit;
      cursor: pointer;
    }
    .tabs button[aria-pressed='true'] {
      background: #b8002a;
      color: #fff;
    }
    .native {
      padding: 8px 10px;
      border: 1px solid #cbd5e1;
      border-radius: 6px;
      font: inherit;
      min-height: 40px;
      background: #fff;
    }
    .scroll {
      overflow-x: auto;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
    }
    .tbl th,
    .tbl td {
      text-align: left;
      padding: 8px 6px;
      border-bottom: 1px solid #e5e7eb;
      vertical-align: top;
    }
    thead th {
      color: #4b5563;
      font-weight: 500;
    }
    tr.on {
      background: #fff1f3;
    }
    .c {
      width: 32px;
    }
    .who {
      background: none;
      border: 0;
      padding: 0;
      font: inherit;
      font-weight: 600;
      text-align: left;
      cursor: pointer;
      text-decoration: underline;
    }
    .muted {
      display: block;
      color: #4b5563;
      font-weight: 400;
      font-size: 0.875em;
    }
    .sev {
      padding: 1px 8px;
      border-radius: 999px;
      font-size: 0.85em;
      background: #eef0f3;
    }
    .s-CAO {
      background: #fde8eb;
      color: #a3001f;
    }
    .s-VUA {
      background: #fff4d6;
      color: #6b4400;
    }
    .acts {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      margin-top: 12px;
      padding: 8px 0;
      position: sticky;
      bottom: 0;
      background: #fff;
      border-top: 1px solid #e5e7eb;
    }
    .err {
      color: #b4001f;
    }
    .link {
      background: none;
      border: 0;
      padding: 8px 0;
      color: #b8002a;
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
export class OrgSyncList {
  readonly simple = input(false);
  /** Tăng khi trang cần tải lại danh sách (sau đồng bộ). */
  readonly version = input(0);
  readonly focused = model<OrgDiscrepancy | null>(null);
  /** Thông báo kết quả xử lý (MSG-01-13…) để trang hiện và tải lại số đếm. */
  readonly done = output<string>();

  private readonly api = inject(OrgSyncApi);
  protected readonly can = inject(ADMIN_CAN);
  protected readonly sevLabel = SEVERITY_LABEL;
  protected readonly srcLabel = SYNC_SOURCE_LABEL;
  protected readonly sevs = Object.keys(SEVERITY_LABEL) as DiscrepancySeverity[];
  protected readonly int = fmtInt;
  protected readonly err = errorText;
  protected readonly fieldText = (f: string) => DISCREPANCY_FIELD_LABEL[f] ?? 'Thông tin khác';
  protected readonly actText = (a: string) =>
    SUGGESTED_ACTION_LABEL[a] ?? 'Xem xét, xử lý thủ công';

  readonly field = signal<string | null>(null);
  readonly severity = signal<DiscrepancySeverity | null>(null);
  private readonly reloads = signal(0);

  protected readonly first = rxResource({
    params: () => ({
      field: this.field() ?? undefined,
      severity: this.severity() ?? undefined,
      v: this.version() + this.reloads(),
    }),
    stream: ({ params }) =>
      this.api.discrepancies({ field: params.field, severity: params.severity, limit: PAGE }),
  });
  protected readonly page = computed(() => (this.first.hasValue() ? this.first.value() : null));
  private readonly extra = linkedSignal<OrgDiscrepancy[]>(() => (this.page(), []));
  protected readonly cursor = linkedSignal(() => this.page()?.nextCursor ?? null);
  protected readonly rows = computed(() => [...(this.page()?.items ?? []), ...this.extra()]);
  /** Dòng đầu, để vùng 4 có nội dung khi chưa chọn dòng nào. */
  readonly firstRow = computed(() => this.rows()[0] ?? null);
  protected readonly selected = linkedSignal<Set<string>>(() => (this.page(), new Set()));
  protected readonly moreBusy = signal(false);
  protected readonly busy = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly mapOpen = signal(false);
  protected readonly assignOpen = signal(false);

  /** Tổng theo bộ lọc đang chọn: đếm theo trường nếu có, nếu không cộng số đếm theo mức. */
  protected readonly total = computed(() => {
    const p = this.page();
    if (!p) return 0;
    if (p.countsByField && !this.field()) {
      return Object.values(p.countsByField).reduce((a, b) => a + b, 0);
    }
    const sev = this.severity();
    return sev ? p.counts[sev] : p.counts.CAO + p.counts.VUA + p.counts.THAP;
  });
  /** Tab chỉ hiện trường còn mục lệch (khi máy chủ trả số đếm theo trường), luôn giữ tab đang chọn. */
  protected readonly tabs = computed(() => {
    const c = this.page()?.countsByField;
    return TABS.filter((t) => this.field() === t || !c || (c[t] ?? 0) > 0);
  });
  protected byField(t: string): string {
    const n = this.page()?.countsByField?.[t];
    return n === undefined ? '' : fmtInt(n);
  }
  protected readonly canAct = computed(
    () => this.can('SCR-0104.xu-ly-lech') || this.can('SCR-0104.giao-xu-ly'),
  );
  protected readonly allChecked = computed(
    () => this.rows().length > 0 && this.rows().every((d) => this.selected().has(d.id)),
  );
  private readonly picked = computed(() => this.rows().filter((d) => this.selected().has(d.id)));
  protected readonly one = computed(() => (this.picked().length === 1 ? this.picked()[0] : null));
  protected readonly mapRow = computed(() =>
    this.one()?.field === FIELD_PERSON_MATCH ? this.one() : null,
  );

  protected toggle(d: OrgDiscrepancy, on: boolean) {
    const s = new Set(this.selected());
    if (on) s.add(d.id);
    else s.delete(d.id);
    this.selected.set(s);
    if (on) this.focused.set(d);
  }

  protected checkAll(on: boolean) {
    this.selected.set(on ? new Set(this.rows().map((d) => d.id)) : new Set());
  }

  protected more() {
    const c = this.cursor();
    if (!c) return;
    this.moreBusy.set(true);
    this.api
      .discrepancies({
        field: this.field() ?? undefined,
        severity: this.severity() ?? undefined,
        cursor: c,
        limit: PAGE,
      })
      .subscribe({
        next: (p) => {
          this.moreBusy.set(false);
          this.extra.update((x) => [...x, ...p.items]);
          this.cursor.set(p.nextCursor);
        },
        error: (e) => {
          this.moreBusy.set(false);
          this.error.set(errorText(e, 'Không tải thêm được.'));
        },
      });
  }

  /** Mục lỗi (ví dụ MSG-01-15 cho dòng không khớp người) báo kèm tên cán bộ; mục khác vẫn áp dụng. */
  protected resolve(resolution: DiscrepancyResolution) {
    const ids = [...this.selected()];
    this.busy.set(true);
    this.error.set(null);
    this.api.resolve(ids, resolution).subscribe({
      next: (r) => {
        this.busy.set(false);
        const okIds = r.results.filter((x) => x.status < 300).map((x) => x.id);
        const failed = r.results.filter((x) => x.status >= 300);
        if (failed.length) {
          const names = failed
            .map((x) => this.rows().find((d) => d.id === x.id)?.personName ?? x.id)
            .join(', ');
          this.error.set(`${names}: ${failed[0].problem?.title ?? 'Không xử lý được.'}`);
        }
        if (!okIds.length) return;
        this.afterChange(okIds);
        this.done.emit(MSG_01_13(okIds.length, r.assignmentsUpdated));
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(errorText(e, 'Không áp dụng được quyết định.'));
      },
    });
  }

  protected afterMap(r: OrgDiscrepancy) {
    this.afterChange([r.id]);
    this.done.emit(`Đã ánh xạ ${r.personName} vào hồ sơ nội bộ đã chọn.`);
  }

  protected afterAssign(d: OrgDiscrepancy) {
    if (this.focused()?.id === d.id) this.focused.set(d);
    this.reloads.update((n) => n + 1);
    this.done.emit(`Đã giao ${d.assigneeName ?? ''} kiểm tra dòng lệch của ${d.personName}.`);
  }

  private afterChange(ids: string[]) {
    if (this.focused() && ids.includes(this.focused()!.id)) this.focused.set(null);
    this.reloads.update((n) => n + 1);
  }
}
