/*
 * SCR-0103 "Cây tổ chức có lịch sử" (QT-01 bước 6, TL-02a Hình 6.9) — đối chiếu đặc tả:
 * Tác nhân AC-02 (sửa); AC-07, AC-10 chỉ đọc. Đường vào: menu quản trị "Tenant / Cây tổ chức" (`/admin/org`).
 * Trường: 1 chế độ xem Hiện tại / Xem tại ngày (`asOf`, không sau hôm nay); 2 ghi nhận đến (`recordedAsOf`);
 *   3 mã đơn vị; 4 mã CSDL CBCCVC; 5 tên; 6 cấp, loại; 7 khối đối tượng PL 2A (BR-01-05); 8 luồng đánh giá;
 *   9 hiệu lực từ, đến; 10 ghi nhận lúc, người ghi; 11 đơn vị cấp trên; 12 đơn vị tiền thân (BR-01-07);
 *   13 phân công (BR-01-08).
 * Vùng: 1 bộ chọn chế độ; 2 cây đơn vị (đơn vị kết thúc hiện mờ, nhãn hợp nhất); 3 thẻ chi tiết; 4 lịch sử thay
 *   đổi (ghi muộn, tương lai đã duyệt); 5 nhân sự và phân công (chính, kiêm nhiệm, điều động đi, biệt phái; PL 6).
 * Chức năng: Xem tại ngày (`/org/units/tree?asOf=&recordedAsOf=`, nhánh sâu `/org/units/{id}/subtree`,
 *   khoá sửa); Nhập từ Excel (POST /org/imports, 202 + `/api/v1/jobs/{id}`, lỗi từng dòng ở
 *   `problem.errors`, EVT-14); Thay đổi tổ chức (POST /org/units/{id}/changes → `OrgChangeOrder`, EVT-14,
 *   EVT-15); Sửa (PUT /org/units/{id} `OrgUnitInput`, If-Match theo `rowVersion`, phiên bản mới, BR-01-06).
 *   Dữ liệu đồng bộ từ nguồn ngoài: máy chủ từ chối sửa (409), giao diện hiện thông báo của máy chủ.
 * Thông báo: MSG-01-08 chế độ chỉ đọc, MSG-01-09 thiếu tiền thân. MSG-01-10 (kỳ đã khoá) và MSG-01-11
 *   (số phân công chuyển theo) chưa hiện: hợp đồng `OrgUnit`, `OrgChangeOrder` chưa có trường mang chúng.
 * Quyền (wiki/15a): SCR-0103.xem, SCR-0103.nhap-excel, SCR-0103.thay-doi.
 */
import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { Job, JobTracker } from '@vt/api-client';
import { ORG_CHANGE_TYPE_LABEL, ORG_UNIT_TYPE_LABEL, OrgChangeOrder, OrgTimeQuery, OrgTreeApi, OrgUnitAssignment, OrgUnitDetail, OrgUnitVersion, UNIT_ASSIGNMENT_KIND_LABEL, orgImportErrors, orgUnitLevelLabel } from '@vt/api-client/domains';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { Subscription, catchError, forkJoin, map, of } from 'rxjs';
import { ADMIN_CAN } from '../../admin-permissions';
import { errorText, fmtDate, fmtDateTime } from '../../admin-ui';
import { OrgChangeDialog } from './org-change.dialog';
import { OrgTreeView, prevDay } from './org-tree-view';
import { OrgUnitEdit } from './org-unit-edit';

/** MSG-01-08 */
export const MSG_01_08 = (day: string) => `Đang xem cơ cấu tại ngày ${day}. Chế độ này chỉ đọc.`;

const today = () => new Date().toISOString().slice(0, 10);
const FLOW_LABEL: Record<string, string> = {
  NHA_NUOC: 'Nhà nước (Nghị định 335/2025)',
  DANG: 'Đảng',
};

@Component({
  selector: 'vt-org-tree-page',
  imports: [
    FormsModule,
    ButtonModule,
    InputTextModule,
    MessageModule,
    OrgTreeView,
    OrgChangeDialog,
    OrgUnitEdit,
  ],
  template: `
    <header class="head">
      <div>
        <h1>Cây tổ chức</h1>
        <p class="muted">
          Cơ cấu tổ chức theo thời gian hiệu lực; có thể xem lại cơ cấu tại một ngày bất kỳ.
        </p>
      </div>
      <div class="tools" role="group" aria-label="Chế độ xem">
        <div class="seg">
          <button type="button" [attr.aria-pressed]="!asOf()" (click)="setMode(false)">
            Hiện tại
          </button>
          <button type="button" [attr.aria-pressed]="!!asOf()" (click)="setMode(true)">
            Xem tại ngày…
          </button>
        </div>
        @if (asOf()) {
          <label class="sr-only" for="as-of">Ngày xem</label>
          <input
            id="as-of"
            pInputText
            type="date"
            [max]="maxDay"
            [ngModel]="asOf()"
            (ngModelChange)="setAsOf($event)"
          />
        }
        <label class="inline" for="rec-as-of">Ghi nhận đến</label>
        <input
          id="rec-as-of"
          pInputText
          type="date"
          [max]="maxDay"
          [ngModel]="recordedAsOf()"
          (ngModelChange)="setRecorded($event)"
        />
        @if (!readOnly() && can('SCR-0103.nhap-excel')) {
          <input
            #file
            type="file"
            accept=".xlsx"
            class="sr-only"
            tabindex="-1"
            aria-hidden="true"
            (change)="importFile($any($event.target))"
          />
          <p-button
            label="Nhập từ Excel"
            severity="contrast"
            [outlined]="true"
            [loading]="!!importJob() && !importDone()"
            (onClick)="file.click()"
          />
        }
        @if (!readOnly() && can('SCR-0103.thay-doi')) {
          <p-button
            label="Thay đổi tổ chức"
            icon="pi pi-plus"
            [disabled]="!canChange()"
            (onClick)="changeOpen.set(true)"
          />
        }
      </div>
    </header>

    @if (asOf(); as d) {
      <p class="warnbox" role="status">{{ msg0108(date(d)) }}</p>
    } @else if (!simple()) {
      <p class="warnbox">
        Cây hiển thị các đơn vị đang có hiệu lực. Chọn <strong>Xem tại ngày</strong>, ví dụ
        30/06/2025 (trước sắp xếp đơn vị hành chính cấp xã 01/07/2025), để tái hiện đúng cơ cấu đã
        dùng khi đánh giá quý II/2025; chế độ đó chỉ đọc.
      </p>
    }
    @if (importJob(); as j) {
      <p-message
        [severity]="
          j.status === 'THAT_BAI' ? 'error' : j.status === 'HOAN_TAT' ? 'success' : 'info'
        "
        styleClass="msg"
      >
        <div class="imp">
          <span role="status">{{
            j.status === 'THAT_BAI'
              ? (j.message ?? j.problem?.title ?? 'Nhập tệp không thành công.')
              : (j.message ?? 'Đang nhập tệp…')
          }}</span>
          @if (importErrors().length) {
            <ul class="imp-errs" aria-label="Lỗi trong tệp nhập">
              @for (e of importErrors().slice(0, 10); track $index) {
                <li>{{ e.message }}</li>
              }
            </ul>
            @if (importErrors().length > 10) {
              <span class="small"
                >Còn {{ importErrors().length - 10 }} lỗi khác trong báo cáo.</span
              >
            }
            <button type="button" class="link" (click)="downloadReport()">Tải báo cáo lỗi</button>
          }
        </div>
      </p-message>
    }
    @for (n of notices(); track n.text) {
      <p-message [severity]="n.kind" [text]="n.text" styleClass="msg" />
    }

    <div class="cols">
      <section class="box" aria-labelledby="z2">
        <h2 id="z2" class="h">Cơ cấu</h2>
        <vt-org-tree-view [time]="time()" [simple]="simple()" [(selected)]="selected" />
      </section>

      <div class="right">
        <section class="box" aria-labelledby="z3" aria-live="polite">
          @if (unit.error(); as e) {
            <p class="err">{{ err(e, 'Không tải được đơn vị.') }}</p>
          } @else if (u(); as u) {
            <div class="title">
              <h2 id="z3" class="h">{{ u.shortName ?? u.name }}</h2>
              <span class="pill" [class.off]="ended()">{{ statusText() }}</span>
              <span class="spacer"></span>
              @if (canEdit() && !editing()) {
                <p-button label="Sửa" [text]="true" (onClick)="editing.set(true)" />
              }
            </div>
            @if (editing()) {
              <vt-org-unit-edit
                [unit]="u"
                (saved)="onEdited($event)"
                (closed)="editing.set(false)"
                (reload)="unit.reload()"
              />
            } @else {
              <dl class="two">
                <div>
                  <dt>Mã đơn vị</dt>
                  <dd>
                    {{ u.code }}
                    @if (u.nationalCode) {
                      · mã CSDL CBCCVC {{ u.nationalCode }}
                    }
                  </dd>
                  <dt>Cấp, loại</dt>
                  <dd>{{ levelLabel(u.level) }} · {{ typeLabel[u.unitType] }}</dd>
                  <dt>Khối đối tượng</dt>
                  <dd>{{ u.subjectGroupName ?? 'Chưa xác định' }} (Phụ lục 2A)</dd>
                  <dt>Luồng đánh giá</dt>
                  <dd>{{ flows(u) }}</dd>
                </div>
                <div>
                  <dt>Hiệu lực</dt>
                  <dd>{{ date(u.validFrom) }} → {{ u.validTo ? date(prev(u.validTo)) : 'nay' }}</dd>
                  <dt>Ghi nhận lúc</dt>
                  <dd>
                    {{ dateTime(u.recordedFrom) }}
                    @if (u.recordedByName) {
                      · {{ u.recordedByName }}
                    }
                  </dd>
                  <dt>Đơn vị cấp trên</dt>
                  <dd>
                    {{ u.parentId ? (u.parentName ?? '') : 'Không có (đơn vị cấp cao nhất)' }}
                  </dd>
                  <dt>Đơn vị tiền thân</dt>
                  <dd>{{ predecessorsText() }}</dd>
                </div>
              </dl>
            }
          } @else {
            <p class="muted" aria-busy="true">Chọn một đơn vị trên cây.</p>
          }
        </section>

        <div class="pair" [class.one]="simple()">
          @if (!simple()) {
            <section class="box" aria-labelledby="z4">
              <h2 id="z4" class="h">Lịch sử thay đổi</h2>
              <ol class="tl">
                @for (h of historyRows(); track h.versionId) {
                  <li
                    [class]="'k-' + (h.validFrom > todayStr ? 'future' : (h.changeType ?? 'SUA'))"
                  >
                    <strong>
                      {{ h.validFrom > todayStr ? 'Sắp tới · ' : '' }}{{ date(h.validFrom) }} ·
                      {{ versionTitle(h) }}
                    </strong>
                    <span class="block">
                      {{ h.name }}
                      @if (h.parentName) {
                        · thuộc {{ h.parentName }}
                      }
                      @if (h.validTo) {
                        · đến {{ date(prev(h.validTo)) }}
                      }
                      @if (h.changeOrder) {
                        · {{ h.changeOrder.orderNo }}
                      }
                      @if (late(h)) {
                        · ghi nhận {{ date(h.recordedFrom) }} (ghi muộn {{ late(h) }} ngày)
                      }
                      @if (h.recordedTo) {
                        · đã đính chính
                      }
                    </span>
                  </li>
                } @empty {
                  <li class="muted">Chưa có thay đổi.</li>
                }
              </ol>
            </section>
          }
          <section class="box" aria-labelledby="z5">
            <h2 id="z5" class="h">Nhân sự và phân công</h2>
            <div class="scroll">
              <table class="tbl">
                <caption class="sr-only">
                  Phân công tại đơn vị
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Cán bộ</th>
                    <th scope="col">Phân công</th>
                    <th scope="col">Loại</th>
                    <th scope="col">Hiệu lực</th>
                  </tr>
                </thead>
                <tbody>
                  @for (a of assignRows(); track a.id) {
                    <tr>
                      <td>{{ a.personName }}</td>
                      <td>{{ a.jobTitleName }}</td>
                      <td>
                        <span class="tag">{{ kindOf[a.kind] }}</span>
                      </td>
                      <td>{{ period(a) }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="4" class="muted">Không có phân công còn hiệu lực.</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (moreAssignments()) {
              <button type="button" class="link" (click)="showAll.set(true)">
                Xem tất cả {{ allAssign().length }} phân công
              </button>
            }
            <p class="muted small">
              Người được điều động giữa kỳ được đánh giá theo Phụ lục 6: công tác tại đơn vị mới
              dưới 2 tháng thì đơn vị cũ đánh giá.
            </p>
          </section>
        </div>
      </div>
    </div>

    @if (u(); as u) {
      <vt-org-change-dialog [unit]="u" [(visible)]="changeOpen" (saved)="onChanged($event)" />
    }
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      align-items: flex-start;
    }
    h1 {
      font-size: 1.4rem;
      margin: 0 0 4px;
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
    .tools {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      align-items: center;
    }
    .seg {
      display: inline-flex;
      border: 1px solid #cbd5e1;
      border-radius: 6px;
      overflow: hidden;
    }
    .seg button {
      border: 0;
      background: #fff;
      padding: 8px 14px;
      font: inherit;
      cursor: pointer;
    }
    .seg button[aria-pressed='true'] {
      background: #b8002a;
      color: #fff;
    }
    .warnbox {
      border: 1px solid #f3d27a;
      background: #fff8e1;
      color: #6b4400;
      padding: 8px 12px;
      border-radius: 6px;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(280px, 1fr) minmax(0, 2.4fr);
      gap: 16px;
      align-items: start;
    }
    .pair {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
    }
    .pair.one {
      grid-template-columns: 1fr;
    }
    @media (max-width: 1100px) {
      .cols,
      .pair {
        grid-template-columns: 1fr;
      }
    }
    .box {
      padding: 16px;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      background: #fff;
      margin-bottom: 16px;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 12px;
    }
    .title {
      display: flex;
      gap: 8px;
      align-items: center;
      flex-wrap: wrap;
    }
    .title .h {
      margin: 0;
    }
    .spacer {
      flex: 1;
    }
    .pill {
      padding: 1px 10px;
      border-radius: 999px;
      background: #e7f6ec;
      color: #146c2e;
      font-size: 0.85em;
    }
    .pill.off {
      background: #eef0f3;
      color: #374151;
    }
    .two {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0 24px;
      margin: 12px 0 0;
    }
    dt {
      color: #4b5563;
      margin-top: 8px;
    }
    dd {
      margin: 2px 0 0;
    }
    .tl {
      list-style: none;
      margin: 0;
      padding: 0 0 0 18px;
      border-left: 2px solid #e5e7eb;
    }
    .tl li {
      margin: 0 0 14px;
      position: relative;
    }
    .tl li::before {
      content: '';
      position: absolute;
      left: -26px;
      top: 4px;
      width: 12px;
      height: 12px;
      border-radius: 50%;
      background: #1f9d55;
    }
    .tl .k-HOP_NHAT::before,
    .tl .k-SAP_NHAP::before {
      background: #c8102e;
    }
    .tl .k-DOI_TEN::before {
      background: #d69e2e;
    }
    .tl .k-future::before {
      background: #fff;
      border: 2px solid #4b5563;
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
    }
    .tag {
      background: #eef0f3;
      padding: 1px 8px;
      border-radius: 4px;
      font-size: 0.85em;
    }
    .err {
      color: #b4001f;
    }
    .imp-errs {
      margin: 6px 0 2px;
      padding-left: 20px;
    }
    .link {
      background: none;
      border: 0;
      padding: 4px 8px;
      color: #b8002a;
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
    }
  `,
})
export class OrgTreePage {
  private readonly api = inject(OrgTreeApi);
  private readonly jobs = inject(JobTracker);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(ADMIN_CAN);
  private readonly tree = viewChild(OrgTreeView);

  protected readonly maxDay = today();
  protected readonly todayStr = today();
  protected readonly levelLabel = orgUnitLevelLabel;
  protected readonly typeLabel = ORG_UNIT_TYPE_LABEL;
  protected readonly kindLabel = ORG_CHANGE_TYPE_LABEL;
  protected readonly date = fmtDate;
  protected readonly dateTime = fmtDateTime;
  protected readonly prev = prevDay;
  protected readonly err = errorText;
  protected readonly msg0108 = MSG_01_08;
  protected readonly kindOf = UNIT_ASSIGNMENT_KIND_LABEL;

  /** Chế độ Đảng viên cao tuổi rút gọn: bỏ lịch sử và số đếm phụ (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected readonly asOf = signal<string | null>(null);
  protected readonly recordedAsOf = signal(today());
  protected readonly time = computed<OrgTimeQuery>(() => ({
    asOf: this.asOf() ?? undefined,
    recordedAsOf: this.recordedAsOf() === today() ? undefined : this.recordedAsOf(),
  }));
  /** Xem tại ngày hoặc xem theo trục ghi nhận quá khứ: chỉ đọc (MSG-01-08). */
  protected readonly readOnly = computed(() => !!this.time().asOf || !!this.time().recordedAsOf);

  protected readonly selected = signal<string | null>(null);
  protected readonly editing = signal(false);
  protected readonly changeOpen = signal(false);
  protected readonly showAll = signal(false);
  protected readonly notices = signal<{ kind: 'success' | 'warn'; text: string }[]>([]);
  protected readonly importJob = signal<Job | null>(null);
  protected readonly importDone = computed(() =>
    ['HOAN_TAT', 'THAT_BAI', 'DA_HUY'].includes(this.importJob()?.status ?? ''),
  );
  /** Lỗi từng dòng của tệp nhập (`Job.problem.errors`). */
  protected readonly importErrors = computed(() => orgImportErrors(this.importJob()));
  private importSub: Subscription | null = null;

  private readonly params = computed(() => {
    const id = this.selected();
    return id ? { id, t: this.time() } : undefined;
  });
  protected readonly unit = rxResource({
    params: this.params,
    stream: ({ params }) => this.api.unit(params.id, params.t),
  });
  protected readonly u = computed<OrgUnitDetail | null>(() =>
    this.unit.hasValue() ? this.unit.value() : null,
  );
  /** Tên đơn vị tiền thân: đọc từng đơn vị tại ngày trước hiệu lực của phiên bản đang xem. */
  private readonly preds = rxResource({
    params: () => {
      const u = this.u();
      if (!u?.predecessorIds?.length) return undefined;
      return {
        ids: u.predecessorIds,
        t: { asOf: prevDay(u.validFrom), recordedAsOf: this.time().recordedAsOf },
      };
    },
    stream: ({ params }) =>
      forkJoin(
        params.ids.map((id) =>
          this.api.unit(id, params.t).pipe(
            map((p) => p.name),
            catchError(() => of(null)),
          ),
        ),
      ),
  });
  protected readonly predecessorsText = computed(() => {
    const ids = this.u()?.predecessorIds ?? [];
    if (!ids.length) return 'Không có';
    if (!this.preds.hasValue()) return 'Đang tải…';
    const names = this.preds.value().filter((n): n is string => !!n);
    return names.length ? names.join('; ') : `${ids.length} đơn vị`;
  });
  /** Vùng 4 chỉ theo trục ghi nhận: lịch sử là toàn bộ trục hiệu lực (hợp đồng không nhận `asOf`). */
  private readonly history = rxResource({
    params: this.params,
    stream: ({ params }) =>
      this.api.history(params.id, { recordedAsOf: params.t.recordedAsOf }, { limit: 50 }),
  });
  protected readonly historyRows = computed(() =>
    this.history.hasValue() ? this.history.value().items : [],
  );
  private readonly assign = rxResource({
    params: this.params,
    stream: ({ params }) => this.api.assignments(params.id, params.t, { limit: 200 }),
  });
  protected readonly allAssign = computed<OrgUnitAssignment[]>(() =>
    this.assign.hasValue() ? this.assign.value().items : [],
  );
  protected readonly assignRows = computed(() =>
    this.showAll() ? this.allAssign() : this.allAssign().slice(0, 8),
  );
  protected readonly moreAssignments = computed(
    () => !this.showAll() && this.allAssign().length > 8,
  );

  protected readonly ended = computed(() => {
    const u = this.u();
    return !!u?.validTo && u.validTo <= (this.asOf() ?? today());
  });
  protected readonly statusText = computed(() => {
    const u = this.u();
    if (!u) return '';
    if (this.ended()) return `Kết thúc ${fmtDate(prevDay(u.validTo!))}`;
    if (u.validFrom > (this.asOf() ?? today())) return `Hiệu lực từ ${fmtDate(u.validFrom)}`;
    return 'Đang hiệu lực';
  });
  protected readonly canEdit = computed(
    () => !this.readOnly() && this.can('SCR-0103.thay-doi') && !!this.u() && !this.ended(),
  );
  protected readonly canChange = computed(() => !!this.u() && !this.ended());

  protected flows(u: OrgUnitDetail) {
    return u.flows.map((f) => FLOW_LABEL[f] ?? 'luồng khác').join(' và ');
  }
  protected versionTitle(h: OrgUnitVersion) {
    if (h.changeType) return this.kindLabel[h.changeType];
    return h.changedFields?.length ? 'Cập nhật thông tin' : 'Ghi nhận đơn vị';
  }
  protected period(a: OrgUnitAssignment) {
    const from = a.validFrom ? fmtDate(a.validFrom) : '';
    return a.validTo ? `${from} → ${fmtDate(prevDay(a.validTo))}` : `${from} →`;
  }
  protected late(h: OrgUnitVersion): number {
    const days = Math.round(
      (Date.parse(h.recordedFrom.slice(0, 10)) - Date.parse(h.validFrom)) / 86_400_000,
    );
    return days > 0 && days < 365 ? days : 0;
  }

  protected setMode(past: boolean) {
    this.editing.set(false);
    this.asOf.set(past ? (this.asOf() ?? '2025-06-30') : null);
  }
  protected setAsOf(d: string) {
    if (d && d <= today()) this.asOf.set(d);
  }
  protected setRecorded(d: string) {
    if (d && d <= today()) this.recordedAsOf.set(d);
  }

  protected onEdited(r: OrgUnitDetail) {
    this.editing.set(false);
    this.notices.set([
      {
        kind: 'success',
        text: `Đã cập nhật thông tin ${r.name}, có hiệu lực từ ngày ${fmtDate(r.validFrom)}.`,
      },
    ]);
    this.refresh();
  }

  protected onChanged(r: OrgChangeOrder) {
    const kind = this.kindLabel[r.changeType].toLowerCase();
    const day = fmtDate(r.effectiveOn);
    this.notices.set([
      {
        kind: 'success',
        text:
          r.status === 'NHAP'
            ? `Đã ghi quyết định ${r.orderNo} (${kind}); thay đổi sẽ áp dụng từ ngày ${day}.`
            : `Đã ghi quyết định ${r.orderNo} (${kind}), có hiệu lực từ ngày ${day}.`,
      },
    ]);
    // đơn vị đang chọn kết thúc hiệu lực: chuyển sang đơn vị cấp trên để thấy cơ cấu mới
    const ends = ['HOP_NHAT', 'CHIA_TACH', 'GIAI_THE'].includes(r.changeType);
    const parent = this.u()?.parentId;
    if (ends && r.status !== 'NHAP' && r.effectiveOn <= today() && parent) {
      this.selected.set(parent);
    }
    this.refresh();
  }

  private refresh() {
    this.tree()?.reload();
    this.unit.reload();
    this.history.reload();
    this.assign.reload();
  }

  protected importFile(input: HTMLInputElement) {
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.importSub?.unsubscribe();
    this.importJob.set({
      id: '',
      type: 'IMPORT',
      status: 'CHO',
      progress: 0,
      message: `Đang tải ${file.name}…`,
    });
    this.importSub = this.api.import(file).subscribe({
      next: (accepted) => {
        this.importJob.set({ ...accepted.job, message: accepted.job.message ?? 'Đang nhập tệp…' });
        this.importSub = this.jobs.track(accepted.location).subscribe({
          next: (j) => this.importJob.set(j),
          complete: () => {
            if (this.importJob()?.status === 'HOAN_TAT') this.refresh();
          },
          error: (e) => this.importFail(e),
        });
      },
      error: (e) => this.importFail(e),
    });
  }

  private importFail(e: unknown) {
    this.importJob.set({
      id: '',
      type: 'IMPORT',
      status: 'THAT_BAI',
      message: errorText(e, 'Không nhập được tệp.'),
    });
  }

  /** Báo cáo lỗi dựng tại trình duyệt từ `problem.errors` của job (CSV, mở được bằng Excel). */
  protected downloadReport() {
    const cell = (v: string) => `"${v.replace(/"/g, '""')}"`;
    const lines = [
      ['Dòng', 'Nội dung lỗi'],
      ...this.importErrors().map((e) => [e.row ? String(e.row) : '', e.message]),
    ].map((l) => l.map(cell).join(','));
    const blob = new Blob(['\ufeff' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = 'bao-cao-loi-nhap-cay-to-chuc.csv';
    a.click();
    URL.revokeObjectURL(url);
  }
}
