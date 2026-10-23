/*
 * SCR-0201 "Cây mục tiêu OKR" (QT-02 bước 1, 9; TL-02 Hình 7.3) — đối chiếu đặc tả:
 * Trường: 1 kỳ (`config.period`, mặc định kỳ `MO`); 2 căn cứ (`okr.basis`, ít nhất một); 3 mục tiêu cấp trên
 *   (`okr.okr_link` PARENT, trừ cấp cao nhất); 4 mã, tên mục tiêu `O{n}` ≤ 200; 5 trạng thái NHAP/CHO_DUYET/DA_DUYET/DONG;
 *   6 tiến độ mục tiêu (trung bình có trọng số tiến độ KR, máy chủ tính); 7 nội dung KR ≤ 300; 8 trọng số; 9 giá trị
 *   hiện tại, chỉ tiêu; 10 mức tự tin (check-in mới nhất); 11 nguồn số liệu; 12 liên kết ngang (`okr_link` NGANG);
 *   13 nhiệm vụ liên kết (Tầng 2 chương trình, Tầng 3 nhiệm vụ).
 * Vùng: 1 dải căn cứ, mục tiêu cấp trên, số mục tiêu trên khuyến nghị; 2 mục tiêu với KR; 3 KR tự tin Thấp nổi bật;
 *   4 bảng phải: lịch sử check-in, vướng mắc, nhiệm vụ liên kết, nút Check-in; 5 liên kết ngang, quy tắc tự tin thấp.
 * Hành động: Cây/Bảng/Bản đồ liên kết (GET /api/v1/okr/objectives?unit=&period=&view=tree); Sửa mục tiêu NHAP của
 *   đơn vị (SCR-0202.soan-don-vi → SCR-0202, WEB-022); Thêm mục tiêu (AC-05 của
 *   đơn vị, kỳ chưa khoá → SCR-0202); Xuất mẫu (POST /api/v1/okr/objectives/export, 202); Check-in KR (chủ sở hữu KR,
 *   mục tiêu DA_DUYET → SCR-0301); Xem tất cả nhiệm vụ liên kết (/tasks?krId=).
 * Quy tắc, thông báo: BR-02-01 (khuyến nghị 1–3 mục tiêu, nhãn "Vượt 3 O"); MSG-02-01 (KR `RUI_RO`); MSG-02-02 (cây trống).
 * Quyền (wiki/15a): SCR-0201.xem, SCR-0201.them, SCR-0201.check-in.
 */
import { ScrollingModule } from '@angular/cdk/scrolling';
import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  CONFIDENCE_LABEL,
  Job,
  JobProgressComponent,
  JobTracker,
  KeyResult,
  OBJECTIVE_STATUS_LABEL,
  Objective,
  OkrApi,
  OkrBasis,
  OkrTreeView,
  ProblemPresenter,
  jobLocation,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { TagModule } from 'primeng/tag';
import { map } from 'rxjs';
import { OKR_CAN } from '../okr-permissions';
import {
  confidenceSeverity,
  defaultKr,
  descendantCount,
  formatNumber,
  indexTree,
  krTone,
  krsOf,
  statusSeverity,
  tableRows,
  valueOverTarget,
} from '../okr-ui';
import { KrSidePanel } from './tree/kr-side.panel';
import { ObjectiveNode } from './tree/objective-node';

const VIEWS: { value: OkrTreeView; label: string }[] = [
  { value: 'tree', label: 'Cây' },
  { value: 'table', label: 'Bảng' },
  { value: 'map', label: 'Bản đồ liên kết' },
];
/** Chiều cao cố định một dòng của chế độ Bảng, để ảo hoá bằng CDK (chỉ dựng dòng đang thấy). */
const ROW_HEIGHT = 52;

@Component({
  selector: 'vt-okr-tree-page',
  imports: [
    FormsModule,
    ScrollingModule,
    ButtonModule,
    MessageModule,
    SelectModule,
    TagModule,
    JobProgressComponent,
    KrSidePanel,
    ObjectiveNode,
  ],
  template: `
    @if (tree.error()) {
      <p-message severity="error" styleClass="msg">
        <span>{{ loadError() }}</span>
        <button type="button" class="linkbtn" (click)="tree.reload()">Thử lại</button>
      </p-message>
    } @else if (data(); as t) {
      <header class="head">
        <div>
          <h1>Cây mục tiêu OKR</h1>
          <p class="muted">
            {{ t.unit.name }} · {{ t.period.label }}
            @if (t.period.weekNo && t.period.weekCount) {
              · Tuần {{ t.period.weekNo }}/{{ t.period.weekCount }}
            }
            @if (locked()) {
              · <strong>kỳ đã khoá, chỉ xem</strong>
            }
          </p>
        </div>
        <div class="actions">
          @if (!simple()) {
            <div class="seg" role="group" aria-label="Chế độ hiển thị">
              @for (v of views; track v.value) {
                <button
                  type="button"
                  [class.on]="view() === v.value"
                  [attr.aria-pressed]="view() === v.value"
                  (click)="setView(v.value)"
                >
                  {{ v.label }}
                </button>
              }
            </div>
          }
          <label for="okr-period" class="inline">Kỳ</label>
          <p-select
            inputId="okr-period"
            [options]="t.periods"
            optionLabel="label"
            optionValue="id"
            [ngModel]="t.period.id"
            (ngModelChange)="setPeriod($event)"
            appendTo="body"
          />
          @if (!simple()) {
            <p-button
              label="Xuất mẫu"
              icon="pi pi-download"
              severity="contrast"
              [outlined]="true"
              [loading]="exporting()"
              [disabled]="t.objectives.length === 0"
              (onClick)="exportReport()"
            />
          }
          @if (can('SCR-0203.xem')) {
            <p-button
              label="Phê duyệt OKR"
              icon="pi pi-check-square"
              severity="contrast"
              [outlined]="true"
              (onClick)="openReview()"
            />
          }
          @if (canAssist()) {
            <p-button
              label="Trợ lý AI"
              icon="pi pi-sparkles"
              severity="secondary"
              data-act="tro-ly"
              (onClick)="openAssistant()"
            />
          }
          @if (canAdd()) {
            <p-button label="Thêm mục tiêu" icon="pi pi-plus" (onClick)="addObjective()" />
          }
        </div>
      </header>

      @if (exportJob() || exportError()) {
        <section class="box" aria-label="Xuất mẫu báo cáo OKR">
          @if (exportError(); as e) {
            <p class="err" role="alert">{{ e }}</p>
          } @else {
            <vt-job-progress
              [job]="exportJob()"
              label="Xuất mẫu báo cáo OKR"
              labelId="okr-export"
            />
            @if (exportJob()?.status === 'HOAN_TAT' && exportJob()?.resultLocation; as href) {
              <a class="linkbtn" [href]="href" download>Tải tệp báo cáo OKR</a>
            }
          }
        </section>
      }

      @for (kr of riskKrs(); track kr.id) {
        <p-message severity="warn" styleClass="msg">
          KR {{ kr.code }} có mức tự tin Thấp hai tuần liên tiếp và đã được đưa lên bảng điều hành
          của lãnh đạo.
        </p-message>
      }

      <section class="box strip" aria-label="Căn cứ và mục tiêu cấp trên">
        <p>
          <span class="lbl">Căn cứ</span>&nbsp;
          @for (b of t.bases; track b.id; let last = $last) {
            {{ basisText(b) }}{{ last ? '' : ';' }}
          } @empty {
            <span class="muted">chưa có căn cứ</span>
          }
          @if (t.parent; as p) {
            · <span class="lbl">Mục tiêu cấp trên</span>&nbsp;<strong>{{ p.code }}</strong>
            {{ p.title }}
            @if (p.status && p.status !== 'DA_DUYET') {
              <span class="muted">({{ statusLabel[p.status] }})</span>
            }
          }
        </p>
        <p-tag
          [severity]="overLimit() ? 'warn' : 'success'"
          [value]="rootCount() + '/' + t.recommendedMax + ' mục tiêu trong kỳ'"
        />
        @if (overLimit()) {
          <p-tag severity="warn" [value]="'Vượt ' + t.recommendedMax + ' O'" />
        }
      </section>

      @if (t.objectives.length === 0) {
        <p-message severity="info" styleClass="msg">
          Kỳ {{ t.period.label }} chưa có mục tiêu. Bấm "Thêm mục tiêu" hoặc sao chép từ kỳ trước.
        </p-message>
      } @else {
        <div class="cols">
          <div class="main">
            @switch (view()) {
              @case ('table') {
                <div
                  class="vtbl"
                  role="table"
                  aria-label="Bảng mục tiêu và KR"
                  [attr.aria-rowcount]="rows().length + 1"
                >
                  <div class="vrow vhead" role="row" aria-rowindex="1">
                    <span role="columnheader">Mục tiêu</span>
                    <span role="columnheader">KR</span>
                    <span role="columnheader">Trọng số</span>
                    <span role="columnheader">Hiện tại / chỉ tiêu</span>
                    <span role="columnheader">Tiến độ</span>
                    <span role="columnheader">Tự tin</span>
                  </div>
                  <cdk-virtual-scroll-viewport
                    [itemSize]="rowHeight"
                    class="vport"
                    [style.height.px]="viewportHeight()"
                    role="rowgroup"
                  >
                    <div
                      *cdkVirtualFor="let r of rows(); let i = index; trackBy: trackRow"
                      class="vrow"
                      role="row"
                      [attr.aria-rowindex]="i + 2"
                      [class.on]="r.kr?.id === selectedKrId()"
                    >
                      <span
                        role="cell"
                        class="ell"
                        [style.padding-left.px]="((r.objective.tier ?? 1) - 1) * 16"
                      >
                        <strong>{{ r.objective.code }}</strong> {{ r.objective.title }}
                      </span>
                      <span role="cell" class="ell">
                        @if (r.kr; as kr) {
                          <button type="button" class="code" (click)="selectKr(kr)">
                            {{ kr.code }}
                          </button>
                          {{ kr.title }}
                        } @else {
                          <span class="muted">Chưa có KR</span>
                        }
                      </span>
                      <span role="cell">{{ r.kr ? pct(r.kr.weight) : '' }}</span>
                      <span role="cell">{{ r.kr ? valueOverTarget(r.kr) : '' }}</span>
                      <span role="cell">
                        @if (r.kr; as kr) {
                          <span class="dot" [class]="krTone(kr)" aria-hidden="true"></span>
                          {{ pct(kr.progress) }}
                        }
                      </span>
                      <span role="cell">{{
                        r.kr?.confidence ? confLabel[r.kr!.confidence!] : ''
                      }}</span>
                    </div>
                  </cdk-virtual-scroll-viewport>
                </div>
                <p class="muted small">
                  {{ rows().length }} dòng · chọn mã KR để xem lịch sử check-in.
                </p>
              }
              @case ('map') {
                <div class="map">
                  <section class="mcol" aria-labelledby="map-up">
                    <h2 class="h" id="map-up">Căn cứ và cấp trên</h2>
                    @for (b of t.bases; track b.id) {
                      <div class="mnode basis">{{ basisText(b) }}</div>
                    }
                    @if (t.parent; as p) {
                      <div class="mnode">
                        <strong>{{ p.code }}</strong> {{ p.title }}
                        <span class="muted">· {{ p.unitName }}</span>
                      </div>
                    }
                  </section>
                  <section class="mcol" aria-labelledby="map-unit">
                    <h2 class="h" id="map-unit">{{ t.unit.name }}</h2>
                    @for (o of index().roots; track o.id) {
                      <div class="mnode main-o">
                        <strong>{{ o.code }}</strong> {{ o.title }}
                        <p-tag [severity]="statusSev(o)" [value]="statusLabel[o.status]" />
                        <ul>
                          @for (kr of krsOf(o); track kr.id) {
                            <li>
                              <button type="button" class="code" (click)="selectKr(kr)">
                                {{ kr.code }}
                              </button>
                              {{ pct(kr.progress) }}
                            </li>
                          }
                        </ul>
                      </div>
                    }
                  </section>
                  <section class="mcol" aria-labelledby="map-down">
                    <h2 class="h" id="map-down">Cấp dưới và liên kết ngang</h2>
                    @for (o of index().roots; track o.id) {
                      @if (descendants(o) > 0) {
                        <div class="mnode">
                          {{ o.code }} phân rã thành <strong>{{ descendants(o) }}</strong> mục tiêu
                          cấp dưới
                        </div>
                      }
                    }
                    @for (l of t.peerLinks; track l.id) {
                      <div class="mnode peer">
                        {{ l.fromLabel }} ↔ <strong>{{ l.peerUnitName }}</strong> ·
                        {{ l.peerCode }} "{{ l.peerTitle }}"
                      </div>
                    } @empty {
                      <p class="muted">Chưa có liên kết ngang.</p>
                    }
                  </section>
                </div>
              }
              @default {
                @for (o of index().roots; track o.id) {
                  <vt-objective-node
                    [objective]="o"
                    [children]="index().children"
                    [selectedKrId]="selectedKrId()"
                    [canEdit]="canEdit()"
                    (krSelected)="selectKr($event)"
                    (edit)="editObjective($event)"
                  />
                }
              }
            }
          </div>
          <aside aria-label="Chi tiết KR đang chọn">
            @if (selectedKr(); as kr) {
              <vt-kr-side-panel
                [kr]="kr"
                [canCheckIn]="canCheckIn(kr)"
                [peerLinks]="t.peerLinks"
                (checkIn)="checkIn($event)"
              />
            }
          </aside>
        </div>
      }
    } @else if (tree.isLoading()) {
      <p class="muted" aria-live="polite">Đang tải cây mục tiêu…</p>
    } @else {
      <!-- máy chủ trả thành công nhưng không có nội dung: báo lỗi thay vì treo ở trạng thái đang tải -->
      <p-message severity="error" styleClass="msg">
        <span>Không tải được cây mục tiêu. Đồng chí vui lòng thử lại.</span>
        <button type="button" class="linkbtn" (click)="tree.reload()">Thử lại</button>
      </p-message>
    }
  `,
  styles: `
    /* Đỏ thương hiệu đậm hơn token chính (#ee0033 chỉ đạt 4,48:1 với nền trắng, xem WEB-059) để chữ và nền màu đạt WCAG AA */
    :host {
      --okr-accent: #c4002a;
    }
    .head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
      margin-bottom: 16px;
      padding: 18px 22px;
      background: linear-gradient(135deg, #ffffff 0%, #fff8f9 100%);
      border: 1px solid #e2e8f0;
      border-left: 4px solid var(--okr-accent);
      border-radius: 14px;
      box-shadow: 0 4px 14px rgba(15, 23, 42, 0.04);
    }
    h1 {
      font-size: 1.45rem;
      font-weight: 800;
      letter-spacing: -0.02em;
      color: #0f172a;
      margin: 2px 0 4px;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.875rem;
    }
    .err {
      color: #b91c1c;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
    label.inline {
      font-weight: 500;
    }
    .seg {
      display: inline-flex;
      border: 1px solid var(--p-content-border-color, #d1d5db);
      border-radius: 6px;
      overflow: hidden;
    }
    .seg button {
      border: 0;
      border-right: 1px solid var(--p-content-border-color, #d1d5db);
      background: #fff;
      padding: 8px 14px;
      font: inherit;
      font-weight: 600;
      color: #374151;
      cursor: pointer;
    }
    .seg button:last-child {
      border-right: 0;
    }
    .seg button.on {
      background: var(--okr-accent);
      color: #fff;
    }
    .seg button:focus-visible,
    .code:focus-visible,
    .linkbtn:focus-visible {
      outline: 2px solid var(--okr-accent);
      outline-offset: 2px;
    }
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      margin-bottom: 12px;
    }
    .strip p {
      margin: 0 0 8px;
    }
    .lbl {
      font-size: 0.75rem;
      font-weight: 700;
      letter-spacing: 0.04em;
      text-transform: uppercase;
      color: #4b5563;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 2.2fr) minmax(280px, 1fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1100px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    :host ::ng-deep .msg {
      margin-bottom: 12px;
      display: block;
    }
    .linkbtn {
      background: none;
      border: 0;
      color: var(--okr-accent);
      font: inherit;
      font-weight: 600;
      cursor: pointer;
      margin-left: 8px;
    }
    .vtbl {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: #fff;
    }
    .vrow {
      display: grid;
      grid-template-columns: minmax(0, 1.4fr) minmax(0, 2fr) 80px 140px 90px 100px;
      gap: 8px;
      align-items: center;
      height: ${ROW_HEIGHT}px;
      padding: 0 10px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
      box-sizing: border-box;
    }
    .vrow.on {
      background: #fdf2f4;
    }
    .vhead {
      cursor: default;
      color: #4b5563;
      font-weight: 500;
      background: #f9fafb;
    }
    .ell {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .code {
      background: none;
      border: 0;
      padding: 0;
      font: inherit;
      font-weight: 700;
      color: inherit;
      cursor: pointer;
    }
    .dot {
      display: inline-block;
      width: 10px;
      height: 10px;
      border-radius: 50%;
      background: #9ca3af;
      margin-right: 4px;
    }
    .dot.ok {
      background: #15803d;
    }
    .dot.warn {
      background: #d97706;
    }
    .dot.danger {
      background: #c62828;
    }
    .map {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 12px;
    }
    @media (max-width: 900px) {
      .map {
        grid-template-columns: 1fr;
      }
    }
    .h {
      font-size: 1rem;
      margin: 0 0 8px;
    }
    .mnode {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      padding: 10px;
      background: #fff;
      margin-bottom: 8px;
    }
    .mnode.main-o {
      border-left: 4px solid var(--okr-accent);
    }
    .mnode.peer {
      border-style: dashed;
    }
    .mnode ul {
      margin: 6px 0 0;
      padding-left: 18px;
    }
  `,
})
export class OkrTreePage {
  private readonly api = inject(OkrApi);
  private readonly jobs = inject(JobTracker);
  private readonly problems = inject(ProblemPresenter);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(OKR_CAN);

  protected readonly views = VIEWS;
  protected readonly rowHeight = ROW_HEIGHT;
  protected readonly statusLabel = OBJECTIVE_STATUS_LABEL;
  protected readonly confLabel = CONFIDENCE_LABEL;
  protected readonly valueOverTarget = valueOverTarget;
  protected readonly krTone = krTone;
  protected readonly krsOf = krsOf;

  private readonly query = toSignal(
    this.route.queryParamMap.pipe(
      map((q) => ({
        period: q.get('period') ?? undefined,
        unit: q.get('unit') ?? undefined,
        view: (q.get('view') as OkrTreeView | null) ?? 'tree',
        kr: q.get('kr'),
      })),
    ),
    { initialValue: { period: undefined, unit: undefined, view: 'tree' as OkrTreeView, kr: null } },
  );

  /** Chế độ "Đảng viên cao tuổi" giảm bước: chỉ giữ chế độ Cây và thao tác chính (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );
  protected readonly view = computed<OkrTreeView>(() => {
    const v = this.query().view;
    return this.simple() || !VIEWS.some((x) => x.value === v) ? 'tree' : v;
  });

  private readonly treeQuery = computed(
    () => ({ period: this.query().period, unit: this.query().unit }),
    { equal: (a, b) => a.period === b.period && a.unit === b.unit },
  );
  protected readonly tree = rxResource({
    params: () => this.treeQuery(),
    stream: ({ params }) => this.api.tree(params),
  });
  protected readonly data = computed(() => (this.tree.hasValue() ? this.tree.value() : null));
  protected readonly loadError = computed(() =>
    this.tree.error() ? this.problems.describe(this.tree.error()).message : '',
  );

  protected readonly index = computed(() => indexTree(this.data()?.objectives ?? []));
  protected readonly rows = computed(() => tableRows(this.index()));
  protected readonly viewportHeight = computed(
    () => Math.min(Math.max(this.rows().length, 1), 12) * ROW_HEIGHT,
  );
  /** Số mục tiêu của chính đơn vị (tầng gốc) so với khuyến nghị (BR-02-01). */
  protected readonly rootCount = computed(() => this.index().roots.length);
  protected readonly overLimit = computed(
    () => this.rootCount() > (this.data()?.recommendedMax ?? Infinity),
  );
  protected readonly locked = computed(() => {
    const s = this.data()?.period.status;
    return s === 'DANG_KHOA' || s === 'DA_KHOA';
  });
  /** MSG-02-01 cho KR của đơn vị đang ở `RUI_RO` (chỉ tầng gốc để cây lớn không sinh hàng trăm thông báo). */
  protected readonly riskKrs = computed(() =>
    this.index()
      .roots.flatMap(krsOf)
      .filter((k) => k.riskFlag === 'RUI_RO'),
  );

  private readonly allKrs = computed(() => (this.data()?.objectives ?? []).flatMap(krsOf));
  /** KR đang chọn: theo `?kr=` nếu có, mặc định KR tự tin Thấp đầu tiên như mockup. */
  protected readonly selectedKrId = linkedSignal<string | null>(() => {
    const wanted = this.query().kr;
    if (wanted && this.allKrs().some((k) => k.id === wanted)) return wanted;
    return defaultKr(this.data()?.objectives ?? [])?.id ?? null;
  });
  protected readonly selectedKr = computed(
    () => this.allKrs().find((k) => k.id === this.selectedKrId()) ?? null,
  );
  private readonly objectiveById = computed(
    () => new Map((this.data()?.objectives ?? []).map((o) => [o.id, o])),
  );

  /** "Thêm mục tiêu": AC-05 của đơn vị (SCR-0201.them, đơn vị của ngữ cảnh), kỳ chưa khoá. */
  protected readonly canAdd = computed(
    () => !!this.data()?.viewerOwnUnit && !this.locked() && this.can('SCR-0201.them'),
  );

  /** "Sửa" mục tiêu `NHAP` của đơn vị (SCR-0202.soan-don-vi), kỳ chưa khoá. */
  protected readonly canEdit = computed(
    () => !!this.data()?.viewerOwnUnit && !this.locked() && this.can('SCR-0202.soan-don-vi'),
  );

  protected readonly exporting = signal(false);
  protected readonly exportJob = signal<Job | null>(null);
  protected readonly exportError = signal<string | null>(null);

  /** "Check-in KR": chủ sở hữu KR, mục tiêu `DA_DUYET`, kỳ đang mở (BR-03-01). */
  protected canCheckIn(kr: KeyResult): boolean {
    const o = this.objectiveById().get(kr.objectiveId);
    return (
      !!kr.viewerIsOwner &&
      o?.status === 'DA_DUYET' &&
      this.data()?.period.status === 'MO' &&
      this.can('SCR-0201.check-in')
    );
  }

  protected selectKr(kr: KeyResult) {
    this.selectedKrId.set(kr.id);
    this.navigate({ kr: kr.id }, true);
  }

  protected setView(v: OkrTreeView) {
    this.navigate({ view: v === 'tree' ? null : v });
  }

  protected setPeriod(id: string) {
    this.exportJob.set(null);
    this.exportError.set(null);
    this.navigate({ period: id, kr: null });
  }

  protected addObjective() {
    this.router.navigate(['objectives', 'new'], {
      relativeTo: this.route,
      queryParams: { period: this.data()?.period.id },
    });
  }

  /** "Trợ lý AI" (SCR-1701): người soạn OKR của đơn vị, kỳ chưa khoá. */
  protected readonly canAssist = computed(
    () => !!this.data()?.viewerOwnUnit && !this.locked() && this.can('SCR-1701.hoi'),
  );

  protected openAssistant() {
    this.router.navigate(['tro-ly'], {
      relativeTo: this.route,
      queryParams: { period: this.data()?.period.id },
    });
  }

  /** Hàng chờ SCR-0203 của người duyệt (`leader`). */
  protected openReview() {
    this.router.navigate(['phe-duyet'], { relativeTo: this.route });
  }

  protected editObjective(o: Objective) {
    this.router.navigate(['objectives', o.id, 'edit'], { relativeTo: this.route });
  }

  protected checkIn(kr: KeyResult) {
    this.router.navigate(['checkin'], { relativeTo: this.route, queryParams: { kr: kr.id } });
  }

  protected exportReport() {
    const t = this.data();
    if (!t) return;
    this.exporting.set(true);
    this.exportError.set(null);
    this.api
      .exportObjectives({ unitId: t.unit.id, periodId: t.period.id, view: this.view() })
      .subscribe({
        next: (res) => {
          const loc = jobLocation(res);
          if (!loc) {
            this.exporting.set(false);
            return;
          }
          this.jobs.track(loc).subscribe({
            next: (j) => this.exportJob.set(j),
            error: (e) => {
              this.exportError.set(this.problems.describe(e).message);
              this.exporting.set(false);
            },
            complete: () => this.exporting.set(false),
          });
        },
        error: (e) => {
          this.exportError.set(this.problems.present(e, { toast: false }).message);
          this.exporting.set(false);
        },
      });
  }

  protected basisText(b: OkrBasis) {
    return b.docNumber ? `${b.title} (số ${b.docNumber})` : b.title;
  }

  protected descendants(o: Objective) {
    return descendantCount(o.id, this.index().children);
  }

  protected statusSev(o: Objective) {
    return statusSeverity(o.status);
  }

  protected pct(x: number) {
    return `${formatNumber(Math.round(x * 100))}%`;
  }

  protected trackRow = (_: number, r: { objective: Objective; kr: KeyResult | null }) =>
    r.kr?.id ?? r.objective.id;

  protected readonly confSev = confidenceSeverity;

  private navigate(params: Record<string, string | null>, replaceUrl = false) {
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: params,
      queryParamsHandling: 'merge',
      replaceUrl,
    });
  }
}
