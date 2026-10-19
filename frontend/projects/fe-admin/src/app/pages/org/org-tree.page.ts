/*
 * SCR-0103 "Cây tổ chức có lịch sử" (QT-01 bước 6, TL-02a Hình 6.9) — đối chiếu đặc tả:
 * Tác nhân AC-02 (sửa); AC-07, AC-10 chỉ đọc. Đường vào: menu quản trị "Tenant / Cây tổ chức" (`/admin/org`).
 * Trường: 1 chế độ xem Hiện tại / Xem tại ngày (`asOf`, không sau hôm nay); 2 ghi nhận đến (`recordedAsOf`);
 *   3 mã đơn vị; 4 mã CSDL CBCCVC; 5 tên; 6 cấp, loại; 7 khối đối tượng PL 2A (BR-01-05); 8 luồng đánh giá;
 *   9 hiệu lực từ, đến; 10 ghi nhận lúc, người ghi; 11 đơn vị cấp trên; 12 đơn vị tiền thân (BR-01-07);
 *   13 phân công (BR-01-08).
 * Vùng: 1 bộ chọn chế độ; 2 cây đơn vị (đơn vị kết thúc hiện mờ, nhãn hợp nhất); 3 thẻ chi tiết; 4 lịch sử thay
 *   đổi (ghi muộn, tương lai đã duyệt); 5 nhân sự và phân công (chính, kiêm nhiệm, điều động đi, biệt phái; PL 6).
 * Chức năng: Xem tại ngày (`/org/units/tree?asOf=&recordedAsOf=`, khoá sửa); Nhập từ Excel (POST /org/imports,
 *   202, EVT-14); Thay đổi tổ chức (POST /org/units/{id}/changes, EVT-14, EVT-15); Sửa (PUT /org/units/{id},
 *   If-Match, phiên bản mới, BR-01-06). Dữ liệu đồng bộ từ nguồn ngoài không sửa trên nền tảng.
 * Thông báo: MSG-01-08 chế độ chỉ đọc, MSG-01-09 thiếu tiền thân, MSG-01-10 ghi muộn vào kỳ đã khoá,
 *   MSG-01-11 chuyển phân công khi sáp nhập.
 * Quyền (wiki/15a): SCR-0103.xem, SCR-0103.nhap-excel, SCR-0103.thay-doi.
 */
import { Component, computed, inject, signal, viewChild } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import {
  Job,
  JobTracker,
  ORG_HISTORY_KIND_LABEL,
  ORG_UNIT_LEVEL_LABEL,
  ORG_UNIT_TYPE_LABEL,
  OrgChangeResult,
  OrgTimeQuery,
  OrgTreeApi,
  OrgUnitDetail,
  UNIT_ASSIGNMENT_KIND_LABEL,
  UnitAssignment,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { Subscription } from 'rxjs';
import { ADMIN_CAN } from '../../admin-permissions';
import { errorText, fmtDate, fmtDateTime } from '../../admin-ui';
import { OrgChangeDialog } from './org-change.dialog';
import { OrgTreeView, prevDay } from './org-tree-view';
import { OrgUnitEdit } from './org-unit-edit';

/** MSG-01-08 */
export const MSG_01_08 = (day: string) => `Đang xem cơ cấu tại ngày ${day}. Chế độ này chỉ đọc.`;

const today = () => new Date().toISOString().slice(0, 10);
const FLOW_LABEL: Record<string, string> = { NHA_NUOC: 'NHA_NUOC (NĐ 335)', DANG: 'DANG' };

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
        <p class="muted">Nguồn: org-service (SVC-04) · dữ liệu hai trục thời gian</p>
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
        Đang hiển thị cả đơn vị đã kết thúc hiệu lực. Chọn <strong>Xem tại ngày</strong>, ví dụ
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
        <span role="status">{{
          j.status === 'THAT_BAI'
            ? (j.problem?.title ?? 'Nhập lỗi.')
            : (j.message ?? 'Đang nhập tệp…')
        }}</span>
        @if (j.resultLocation) {
          <button type="button" class="link" (click)="downloadReport(j.resultLocation)">
            Tải báo cáo lỗi
          </button>
        }
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
              @if (u.syncedFrom) {
                <span class="pill sync">Đồng bộ từ CSDL CBCCVC</span>
              }
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
                    @if (u.externalCode) {
                      · mã CSDL CBCCVC {{ u.externalCode }}
                    }
                  </dd>
                  <dt>Cấp, loại</dt>
                  <dd>{{ levelLabel[u.level] }} · {{ typeLabel[u.unitType] }}</dd>
                  <dt>Khối đối tượng</dt>
                  <dd>{{ u.subjectGroup.name }} (PL 2A)</dd>
                  <dt>Luồng đánh giá</dt>
                  <dd>{{ flows(u) }}</dd>
                </div>
                <div>
                  <dt>Hiệu lực</dt>
                  <dd>{{ date(u.validFrom) }} → {{ u.validTo ? date(prev(u.validTo)) : 'nay' }}</dd>
                  <dt>Ghi nhận lúc</dt>
                  <dd>{{ dateTime(u.recordedFrom) }} · {{ u.recordedBy }}</dd>
                  <dt>Đơn vị cấp trên</dt>
                  <dd>{{ u.parent?.name ?? 'Gốc của cây' }}</dd>
                  <dt>Đơn vị tiền thân</dt>
                  <dd>{{ predecessors(u) }}</dd>
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
                @for (h of historyRows(); track h.id) {
                  <li [class]="'k-' + (h.effectiveOn > todayStr ? 'future' : h.kind)">
                    <strong>
                      {{ h.effectiveOn > todayStr ? 'Sắp tới · ' : '' }}{{ date(h.effectiveOn) }} ·
                      {{ h.title || kindLabel[h.kind] }}
                    </strong>
                    <span class="block">
                      {{ h.detail }}
                      @if (h.orderNo) {
                        · {{ h.orderNo }}
                      }
                      @if (late(h)) {
                        · ghi nhận {{ date(h.recordedAt) }} (ghi muộn {{ late(h) }} ngày)
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
                      <td>{{ a.jobTitle }}</td>
                      <td>
                        <span class="tag">{{ kindOf(a) }}</span>
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
              Người điều động giữa kỳ được đánh giá theo PL 6: dưới 2 tháng tại đơn vị mới thì đơn
              vị cũ đánh giá.
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
    .pill.off,
    .pill.sync {
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
  protected readonly levelLabel = ORG_UNIT_LEVEL_LABEL;
  protected readonly typeLabel = ORG_UNIT_TYPE_LABEL;
  protected readonly kindLabel = ORG_HISTORY_KIND_LABEL;
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
  private readonly history = rxResource({
    params: this.params,
    stream: ({ params }) => this.api.history(params.id, params.t),
  });
  protected readonly historyRows = computed(() =>
    this.history.hasValue() ? this.history.value() : [],
  );
  private readonly assign = rxResource({
    params: this.params,
    stream: ({ params }) => this.api.assignments(params.id, params.t),
  });
  protected readonly allAssign = computed(() =>
    this.assign.hasValue() ? this.assign.value() : [],
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
    () =>
      !this.readOnly() &&
      this.can('SCR-0103.thay-doi') &&
      !!this.u() &&
      !this.ended() &&
      !this.u()!.syncedFrom,
  );
  protected readonly canChange = computed(() => !!this.u() && !this.ended());

  protected flows(u: OrgUnitDetail) {
    return u.flows.map((f) => FLOW_LABEL[f] ?? f).join(' và ');
  }
  protected predecessors(u: OrgUnitDetail) {
    return u.predecessors.map((p) => p.name).join('; ') || 'Không có';
  }
  protected period(a: UnitAssignment) {
    const from = a.validFrom ? fmtDate(a.validFrom) : '';
    return a.validTo ? `${from} → ${fmtDate(prevDay(a.validTo))}` : `${from} →`;
  }
  protected late(h: { effectiveOn: string; recordedAt: string }): number {
    const days = Math.round(
      (Date.parse(h.recordedAt.slice(0, 10)) - Date.parse(h.effectiveOn)) / 86_400_000,
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
        text: `Đã ghi phiên bản mới của ${r.name} từ ngày ${fmtDate(r.validFrom)}.`,
      },
      ...(r.warning ? [{ kind: 'warn' as const, text: r.warning }] : []),
    ]);
    this.refresh();
  }

  protected onChanged(r: OrgChangeResult) {
    this.notices.set([
      { kind: 'success', text: r.message ?? 'Đã ghi thay đổi tổ chức.' },
      ...(r.warning ? [{ kind: 'warn' as const, text: r.warning }] : []),
    ]);
    this.selected.set(r.unitId);
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
      type: 'ORG_IMPORT',
      status: 'CHO',
      progress: 0,
      message: `Đang tải ${file.name}…`,
    });
    this.importSub = this.api.import(file).subscribe({
      next: (loc) => {
        this.importSub = this.jobs.track(loc).subscribe({
          next: (j) => this.importJob.set(j),
          complete: () => this.refresh(),
          error: (e) => this.importFail(e),
        });
      },
      error: (e) => this.importFail(e),
    });
  }

  private importFail(e: unknown) {
    this.importJob.set({
      id: '',
      type: 'ORG_IMPORT',
      status: 'THAT_BAI',
      problem: { type: 'about:blank', status: 0, title: errorText(e, 'Không nhập được tệp.') },
    });
  }

  protected downloadReport(loc: string) {
    this.api.report(loc).subscribe((blob) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'bao-cao-loi-nhap-cay-to-chuc.csv';
      a.click();
      URL.revokeObjectURL(url);
    });
  }
}
