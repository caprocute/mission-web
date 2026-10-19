/*
 * SCR-0301 "Check-in KR" (QT-03 bước 2–7; TL-02 Hình 7.8) — đối chiếu đặc tả:
 * Trường: 1 KR (mặc định KR đầu tiên chưa check-in; BR-03-01); 2 công thức; 3 chỉ tiêu; 4 giá trị lần trước;
 *   5 giá trị hiện tại (bắt buộc, ≤ 4 chữ số lẻ; mặc định số liệu nguồn hoặc lần trước; BR-03-02, BR-03-05);
 *   6 giá trị nguồn kèm thời điểm lấy (SVC-13); 7 lý do ghi đè ≤ 500 (bắt buộc khi khác nguồn, BR-03-04);
 *   8 mức tự tin `CAO`/`TRUNG_BINH`/`THAP` (bắt buộc, mặc định lần trước); 9 kết quả nổi bật ≤ 1.000;
 *   10 vướng mắc, đề nghị hỗ trợ ≤ 1.000; 11 trạng thái `NHAP`/`DA_GUI` (ẩn).
 * Vùng: 1 KR của tôi cần check-in; 2 thông tin KR và giá trị; 3 mức tự tin, kết quả, vướng mắc; 4 "Gửi check-in";
 *   5 diễn biến KR và nhiệm vụ liên kết (BR-03-07).
 * Hành động: Lưu nháp (POST /api/v1/okr/key-results/{id}/checkins, `status=NHAP`); Gửi check-in (`DA_GUI`,
 *   Idempotency-Key; EVT-02, EVT-21 khi `RUI_RO`); Lấy lại số liệu nguồn (GET .../source-value);
 *   Lịch sử (GET .../checkins?cursor=).
 * Quy tắc: BR-03-01..BR-03-06 (máy chủ kiểm lại); BR-03-03 check-in đã gửi không sửa: không có nút sửa.
 * Thông báo: MSG-03-01 (đã gửi), MSG-03-02 (chênh lớn), MSG-03-03 (nguồn lỗi), MSG-03-04 (ghi đè thiếu lý do).
 * Quyền (wiki/15a): SCR-0301.check-in (AC-05 `manager`, AC-06 `member`, chủ sở hữu KR), SCR-0301.lich-su.
 * Chế độ cao tuổi (`reducedSteps`): luồng một bước, chỉ giữ ô cần thiết và nút "Gửi check-in".
 */
import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  effect,
  inject,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import {
  Confidence,
  OkrApi,
  OkrCheckinCreate,
  OkrCheckinStatus,
  ProblemPresenter,
  krProgress,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { MessageModule } from 'primeng/message';
import { forkJoin, map } from 'rxjs';
import { OKR_CAN } from '../../okr-permissions';
import { formatDateTime, formatNumber, formatValue } from '../../okr-ui';
import { CheckinHistory } from './checkin-history';
import { CheckinList, CheckinListRow } from './checkin-list';
import {
  isOverride,
  largeChange,
  largeChangeMessage,
  parseValue,
  valueText,
  weekdayDate,
} from './checkin-rules';
import { CheckinTrend } from './checkin-trend';

const LEVELS: { id: Confidence; label: string }[] = [
  { id: 'CAO', label: 'Cao' },
  { id: 'TRUNG_BINH', label: 'Trung bình' },
  { id: 'THAP', label: 'Thấp' },
];

@Component({
  selector: 'vt-okr-checkin-page',
  imports: [FormsModule, MessageModule, CheckinList, CheckinTrend, CheckinHistory],
  template: `
    @if (list.error()) {
      <p-message severity="error" styleClass="msg">{{ listError() }}</p-message>
    } @else if (!listData()) {
      <p class="muted" role="status">Đang tải KR cần check-in…</p>
    } @else {
      <header class="head">
        <div>
          <h1>Check-in kết quả then chốt</h1>
          <p class="muted">
            @if (period(); as p) {
              @if (p.weekNo) {
                Tuần {{ p.weekNo }}/{{ p.weekCount }} ·
              }
              {{ p.label }}
            }
            @if (due(); as d) {
              · hạn check-in {{ dueText(d) }}
            }
          </p>
        </div>
        @if (!simple() && can('SCR-0301.lich-su') && detail()) {
          <button
            id="btn-history"
            type="button"
            class="btn"
            [attr.aria-expanded]="historyOpen()"
            (click)="historyOpen() ? closeHistory() : historyOpen.set(true)"
          >
            Lịch sử
          </button>
        }
      </header>

      @if (notice(); as n) {
        <p-message severity="success" styleClass="msg">
          <span role="status" class="ok-text">{{ n }}</span>
        </p-message>
      }
      @if (error(); as e) {
        <p-message severity="error" styleClass="msg">
          <span role="alert">{{ e }}</span>
        </p-message>
      }
      @if (historyOpen() && detail(); as k) {
        <div #historyBox>
          <vt-checkin-history
            [code]="k.code"
            [unit]="k.unit"
            [items]="historyItems()"
            (closed)="closeHistory()"
          />
        </div>
      }

      <div class="cols" [class.simple]="simple()">
        <vt-checkin-list [rows]="rows()" [selectedId]="selectedId()" (selected)="select($event)" />

        @if (kr.error()) {
          <p-message severity="error" styleClass="msg">{{ krError() }}</p-message>
        } @else if (detail(); as k) {
          <form class="box main" (ngSubmit)="send('DA_GUI')" novalidate>
            <h2 class="sec">{{ k.code }} · {{ k.title }}</h2>
            <dl class="info">
              @if (k.objective; as o) {
                <dt>Mục tiêu</dt>
                <dd>{{ o.code }} · {{ o.title }}</dd>
              }
              @if (k.formulaText) {
                <dt>Công thức</dt>
                <dd>{{ k.formulaText }}</dd>
              }
              <dt>Chỉ tiêu</dt>
              <dd>{{ fmtValue(k.targetValue, k.unit) }}</dd>
            </dl>
            @if (!canCheckin()) {
              <p class="warn" role="note">
                Chỉ check-in được KR thuộc mục tiêu đã duyệt trong kỳ đang mở.
              </p>
            }

            <fieldset [disabled]="!canCheckin() || busy()">
              <legend class="sr-only">Giá trị và mức tự tin</legend>
              <div class="vals">
                <div>
                  <label for="c-prev">Giá trị lần trước</label>
                  <input
                    id="c-prev"
                    class="inp"
                    readonly
                    [value]="k.lastCheckin ? fmtValue(k.lastCheckin.value, k.unit) : 'Chưa có'"
                  />
                  @if (k.lastCheckin; as l) {
                    <small>Gửi lúc {{ when(l.checkinAt) }}</small>
                  }
                </div>
                <div>
                  <label for="c-value"
                    >Giá trị hiện tại ({{ k.unit }}) <span aria-hidden="true">*</span></label
                  >
                  <input
                    id="c-value"
                    class="inp"
                    inputmode="decimal"
                    autocomplete="off"
                    required
                    [attr.aria-invalid]="errors()['value'] ? true : null"
                    [attr.aria-describedby]="errors()['value'] ? 'c-value-err' : 'c-value-hint'"
                    [ngModel]="valueInput()"
                    (ngModelChange)="setValue($event)"
                    name="value"
                  />
                  @if (errors()['value']; as m) {
                    <small id="c-value-err" class="err">{{ m }}</small>
                  } @else {
                    <small id="c-value-hint">
                      @if (preview() !== null) {
                        Tiến độ KR: {{ preview() }}% chỉ tiêu
                      }
                    </small>
                  }
                </div>
                <div>
                  <span class="lbl" id="c-src-l">Nguồn số liệu</span>
                  @if (k.dataSource) {
                    <p class="src" aria-labelledby="c-src-l">
                      {{ k.dataSource }} ({{ srcError() ? 'nhập tay' : 'tự động' }})
                    </p>
                    @if (src(); as s) {
                      <small>
                        {{ fmtValue(s.value, k.unit) }}, cập nhật lúc {{ when(s.fetchedAt) }}
                      </small>
                    } @else if (source.isLoading()) {
                      <small role="status">Đang lấy số liệu nguồn…</small>
                    }
                    @if (!simple()) {
                      <button type="button" class="link" (click)="source.reload()">
                        Lấy lại số liệu nguồn
                      </button>
                    }
                  } @else {
                    <p class="src">Nhập tay</p>
                  }
                </div>
              </div>
              @if (srcError(); as m) {
                <p class="warn" role="alert">{{ m }}</p>
              }
              @if (override()) {
                <label for="c-overrideReason">
                  Lý do khác số liệu nguồn <span aria-hidden="true">*</span>
                </label>
                <textarea
                  id="c-overrideReason"
                  class="inp"
                  rows="2"
                  maxlength="500"
                  [attr.aria-invalid]="errors()['overrideReason'] ? true : null"
                  [attr.aria-describedby]="errors()['overrideReason'] ? 'c-reason-err' : null"
                  [ngModel]="reason()"
                  (ngModelChange)="reason.set($event)"
                  name="overrideReason"
                ></textarea>
                @if (errors()['overrideReason']; as m) {
                  <small id="c-reason-err" class="err">{{ m }}</small>
                }
              }

              <div
                id="c-confidence"
                class="conf"
                role="radiogroup"
                tabindex="-1"
                aria-labelledby="c-conf-l"
                [attr.aria-describedby]="errors()['confidence'] ? 'c-conf-err' : null"
              >
                <p id="c-conf-l" class="lbl">
                  Mức tự tin đạt chỉ tiêu cuối kỳ
                  <span aria-hidden="true">*</span>
                </p>
                <div class="levels">
                  @for (l of levels; track l.id) {
                    <label class="level" [class]="'lv-' + l.id" [class.on]="confidence() === l.id">
                      <input
                        type="radio"
                        name="confidence"
                        [value]="l.id"
                        [checked]="confidence() === l.id"
                        (change)="confidence.set(l.id)"
                      />
                      {{ l.label }}
                    </label>
                  }
                </div>
                @if (errors()['confidence']; as m) {
                  <small id="c-conf-err" class="err">{{ m }}</small>
                }
              </div>

              <label for="c-highlights">Kết quả nổi bật trong tuần</label>
              <textarea
                id="c-highlights"
                class="inp"
                rows="3"
                maxlength="1000"
                [ngModel]="highlights()"
                (ngModelChange)="highlights.set($event)"
                name="highlights"
              ></textarea>
              <label for="c-blockers">Vướng mắc, đề nghị hỗ trợ</label>
              <textarea
                id="c-blockers"
                class="inp"
                rows="3"
                maxlength="1000"
                placeholder="Không có"
                [attr.aria-describedby]="confidence() === 'THAP' ? 'c-blk-hint' : null"
                [ngModel]="blockers()"
                (ngModelChange)="blockers.set($event)"
                name="blockers"
              ></textarea>
              @if (confidence() === 'THAP') {
                <small id="c-blk-hint">
                  Mức tự tin Thấp hai lần liên tiếp sẽ đưa KR lên bảng điều hành của lãnh đạo; đồng
                  chí nên ghi rõ vướng mắc để được hỗ trợ.
                </small>
              }
            </fieldset>

            @if (pendingLarge(); as m) {
              <div #confirmBox class="confirm" role="alert" tabindex="-1">
                <p>{{ m }}</p>
                <div class="actions">
                  <button type="button" class="btn" (click)="pendingLarge.set(null)">
                    Sửa lại
                  </button>
                  <button type="button" class="btn primary" (click)="send('DA_GUI', true)">
                    Vẫn gửi
                  </button>
                </div>
              </div>
            }

            @if (canCheckin()) {
              <div class="foot">
                <p class="muted small">
                  Check-in đã gửi được lưu vết và không sửa được; khi cần điều chỉnh, đồng chí gửi
                  check-in mới.
                </p>
                <div class="actions">
                  @if (!simple()) {
                    <button type="button" class="btn" [disabled]="busy()" (click)="send('NHAP')">
                      Lưu nháp
                    </button>
                  }
                  <button type="submit" class="btn primary" [disabled]="busy()">
                    Gửi check-in
                  </button>
                </div>
              </div>
            }
          </form>
          @if (!simple()) {
            <vt-checkin-trend [kr]="k" [history]="historyItems()" [current]="parsed()" />
          }
        } @else if (selectedId()) {
          <p class="muted" role="status">Đang tải KR…</p>
        }
      </div>
    }
  `,
  styles: `
    .head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 16px;
      margin-bottom: 16px;
    }
    h1 {
      font-size: 1.5rem;
      margin: 0 0 4px;
    }
    .muted,
    small {
      color: #4b5563;
    }
    .small {
      font-size: 0.85rem;
      margin: 0;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(240px, 0.8fr) minmax(0, 1.4fr) minmax(240px, 1fr);
      gap: 16px;
      align-items: start;
    }
    .cols.simple {
      grid-template-columns: minmax(240px, 0.8fr) minmax(0, 2fr);
    }
    @media (max-width: 1200px) {
      .cols,
      .cols.simple {
        grid-template-columns: 1fr;
      }
    }
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
    }
    .sec {
      display: flex;
      gap: 8px;
      font-size: 1.1rem;
      margin: 0 0 12px;
    }
    .num {
      display: inline-grid;
      place-items: center;
      flex: none;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: #111827;
      color: #fff;
      font-size: 0.75rem;
    }
    .num.inv {
      background: #fff;
      color: #c4002a;
    }
    .info {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 6px 16px;
      margin: 0 0 12px;
    }
    dt {
      color: #4b5563;
    }
    dd {
      margin: 0;
    }
    fieldset {
      border: 0;
      padding: 0;
      margin: 0;
      display: grid;
      gap: 6px;
    }
    .vals {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 12px;
      margin-bottom: 6px;
    }
    .vals > div {
      display: grid;
      gap: 4px;
      align-content: start;
    }
    label,
    .lbl {
      font-weight: 600;
      margin: 6px 0 0;
    }
    .inp {
      font: inherit;
      width: 100%;
      box-sizing: border-box;
      padding: 8px 10px;
      border: 1px solid #9ca3af;
      border-radius: 6px;
    }
    .inp[readonly] {
      background: #f9fafb;
    }
    .inp:focus-visible,
    .btn:focus-visible,
    .link:focus-visible,
    .level:focus-within {
      outline: 2px solid #c4002a;
      outline-offset: 2px;
    }
    .src {
      margin: 0;
    }
    .err {
      color: #b91c1c;
    }
    .warn {
      color: #7c4a03;
      background: #fef6e4;
      border-radius: 4px;
      padding: 6px 10px;
    }
    .levels {
      display: grid;
      grid-template-columns: repeat(3, 1fr);
      gap: 8px;
      margin-top: 6px;
    }
    .level {
      position: relative;
      margin: 0;
      padding: 10px;
      text-align: center;
      border: 1px solid #9ca3af;
      border-radius: 6px;
      cursor: pointer;
    }
    .level input {
      position: absolute;
      opacity: 0;
    }
    .lv-CAO.on {
      background: #15803d;
      border-color: #15803d;
      color: #fff;
    }
    .lv-TRUNG_BINH.on {
      background: #fde68a;
      border-color: #92400e;
    }
    .lv-THAP.on {
      background: #b91c1c;
      border-color: #b91c1c;
      color: #fff;
    }
    .foot {
      display: flex;
      justify-content: space-between;
      align-items: center;
      flex-wrap: wrap;
      gap: 12px;
      margin-top: 16px;
    }
    .actions {
      display: flex;
      gap: 8px;
    }
    .btn {
      font: inherit;
      white-space: nowrap;
      display: inline-flex;
      align-items: center;
      gap: 8px;
      padding: 8px 16px;
      border: 1px solid #6b7280;
      border-radius: 6px;
      background: #fff;
      cursor: pointer;
    }
    .btn.primary {
      background: #c4002a;
      border-color: #c4002a;
      color: #fff;
    }
    .link {
      font: inherit;
      padding: 0;
      border: 0;
      background: none;
      color: #c4002a;
      text-decoration: underline;
      cursor: pointer;
      justify-self: start;
    }
    .confirm {
      margin-top: 12px;
      padding: 12px;
      border: 1px solid #92400e;
      border-radius: 6px;
      background: #fef6e4;
    }
    .confirm p {
      margin: 0 0 8px;
    }
    .ok-text {
      color: #14532d;
    }
    :host ::ng-deep .msg {
      display: block;
      margin-bottom: 12px;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
    }
  `,
})
export class OkrCheckinPage {
  private readonly api = inject(OkrApi);
  private readonly problems = inject(ProblemPresenter);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly theme = inject(ThemeService);
  private readonly injector = inject(Injector);
  protected readonly can = inject(OKR_CAN);
  protected readonly levels = LEVELS;

  private readonly wanted = toSignal(this.route.queryParamMap.pipe(map((q) => q.get('kr'))), {
    initialValue: null,
  });

  /** Vùng 1: mọi KR của người xem, đánh dấu KR đã check-in trong chu kỳ (hai lời gọi theo hợp đồng). */
  protected readonly list = rxResource({
    stream: () =>
      forkJoin({
        all: this.api.myKeyResults(),
        need: this.api.myKeyResults({ needCheckin: true }),
      }),
  });
  protected readonly listError = computed(() =>
    this.list.error() ? this.problems.describe(this.list.error()).message : '',
  );
  protected readonly listData = computed(() => (this.list.hasValue() ? this.list.value() : null));
  protected readonly rows = computed<CheckinListRow[]>(() => {
    const d = this.listData();
    if (!d) return [];
    const need = new Set(d.need.items.map((k) => k.id));
    const rows = d.all.items.map((kr) => ({ kr, done: !need.has(kr.id) }));
    return [...rows.filter((r) => !r.done), ...rows.filter((r) => r.done)];
  });
  protected readonly period = computed(() => this.listData()?.all.period ?? null);
  protected readonly due = computed(() => this.listData()?.all.items[0]?.nextCheckinDue ?? null);
  protected readonly periodOpen = computed(() => this.period()?.status !== 'DA_KHOA');

  /** KR đang check-in: theo `?kr=` nếu là KR của người xem, nếu không thì KR đầu tiên chưa check-in. */
  protected readonly selectedId = linkedSignal<string | null>(() => {
    const rows = this.rows();
    const wanted = this.wanted();
    return rows.find((r) => r.kr.id === wanted)?.kr.id ?? rows[0]?.kr.id ?? null;
  });

  protected readonly kr = rxResource({
    params: () => this.selectedId() ?? undefined,
    stream: ({ params }) => this.api.keyResult(params),
  });
  protected readonly detail = computed(() => (this.kr.hasValue() ? this.kr.value() : null));
  protected readonly krError = computed(() =>
    this.kr.error() ? this.problems.describe(this.kr.error()).message : '',
  );

  protected readonly history = rxResource({
    params: () => this.selectedId() ?? undefined,
    stream: ({ params }) => this.api.checkins(params, undefined, 10).pipe(map((p) => p.items)),
  });
  protected readonly historyItems = computed(() =>
    this.history.hasValue() ? this.history.value() : [],
  );

  /** Trường 6: lấy số liệu nguồn khi mở KR có nguồn (QT-03 bước 3); lỗi thì cho nhập tay. */
  protected readonly source = rxResource({
    params: () => {
      const k = this.detail();
      return k?.dataSource ? k.id : undefined;
    },
    stream: ({ params }) => this.api.sourceValue(params),
  });
  protected readonly src = computed(() => (this.source.hasValue() ? this.source.value() : null));
  /** MSG-03-03. */
  protected readonly srcError = computed(() =>
    this.source.error()
      ? `Không lấy được số liệu từ ${this.detail()?.dataSource ?? 'nguồn'}. Đồng chí có thể nhập tay.`
      : null,
  );

  // ---- Biểu mẫu: làm lại khi đổi KR; giá trị mặc định nháp, rồi nguồn, rồi lần trước ----
  protected readonly valueInput = linkedSignal({
    source: () => ({ k: this.detail(), s: this.src() }),
    computation: ({ k, s }) =>
      valueText(k?.myDraftCheckin?.value ?? s?.value ?? k?.lastCheckin?.value ?? k?.currentValue),
  });
  protected readonly confidence = linkedSignal<Confidence | null>(
    () =>
      this.detail()?.myDraftCheckin?.confidence ?? this.detail()?.lastCheckin?.confidence ?? null,
  );
  protected readonly highlights = linkedSignal(
    () => this.detail()?.myDraftCheckin?.highlights ?? '',
  );
  protected readonly blockers = linkedSignal(
    () => this.detail()?.myDraftCheckin?.blockerNote ?? '',
  );
  protected readonly reason = linkedSignal(
    () => this.detail()?.myDraftCheckin?.overrideReason ?? '',
  );
  protected readonly attempted = linkedSignal({
    source: this.selectedId,
    computation: () => false,
  });
  protected readonly pendingLarge = linkedSignal<string | null, string | null>({
    source: this.selectedId,
    computation: () => null,
  });
  protected readonly serverFields = linkedSignal<string | null, Record<string, string>>({
    source: this.selectedId,
    computation: () => ({}),
  });

  protected readonly parsed = computed(() => parseValue(this.valueInput()));
  protected readonly override = computed(() => isOverride(this.src()?.value, this.parsed()));
  protected readonly preview = computed(() => {
    const k = this.detail();
    if (!k || this.parsed() === null) return null;
    return Math.round(
      krProgress({ ...k, baselineValue: k.baselineValue ?? null, currentValue: this.parsed() }) *
        100,
    );
  });

  protected readonly errors = computed(() => {
    const e: Record<string, string> = { ...this.serverFields() };
    if (!this.attempted()) return e;
    if (this.parsed() === null) {
      e['value'] = this.valueInput().trim()
        ? 'Giá trị hiện tại là số, tối đa 4 chữ số thập phân.'
        : 'Vui lòng nhập giá trị hiện tại.';
    }
    if (!this.confidence()) e['confidence'] = 'Vui lòng chọn mức tự tin.';
    if (this.override() && !this.reason().trim()) {
      e['overrideReason'] =
        `Vui lòng ghi lý do khi thay đổi số liệu lấy từ ${this.detail()?.dataSource ?? 'nguồn'}.`;
    }
    return e;
  });

  /** Chế độ "Đảng viên cao tuổi" giảm bước: một bước, ẩn nháp, lịch sử, biểu đồ (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );
  protected readonly canCheckin = computed(() => {
    const k = this.detail();
    return this.can('SCR-0301.check-in') && !!k && k.status === 'DA_DUYET' && this.periodOpen();
  });

  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly historyOpen = signal(false);
  private readonly historyBox = viewChild('historyBox', { read: ElementRef });
  private readonly confirmBox = viewChild<ElementRef<HTMLElement>>('confirmBox');

  constructor() {
    effect(() => this.confirmBox()?.nativeElement.focus());
    effect(() =>
      (this.historyBox()?.nativeElement as HTMLElement | undefined)
        ?.querySelector<HTMLElement>('section')
        ?.focus(),
    );
  }

  protected readonly fmtValue = formatValue;
  protected readonly fmtNumber = formatNumber;
  protected readonly when = formatDateTime;
  protected readonly dueText = (iso: string) => weekdayDate(iso);

  protected select(id: string) {
    this.notice.set(null);
    this.error.set(null);
    this.go(id);
  }

  private go(id: string) {
    this.selectedId.set(id);
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { kr: id },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  protected setValue(v: string) {
    this.valueInput.set(v);
    this.pendingLarge.set(null);
    this.serverFields.update(({ value: _v, overrideReason: _r, ...rest }) => rest);
  }

  protected closeHistory() {
    this.historyOpen.set(false);
    document.getElementById('btn-history')?.focus();
  }

  private body(status: OkrCheckinStatus, confirm: boolean): OkrCheckinCreate {
    const s = this.src();
    return {
      status,
      value: this.parsed()!,
      confidence: this.confidence()!,
      ...(s ? { sourceValue: s.value, sourceFetchedAt: s.fetchedAt } : {}),
      ...(this.override() ? { overrideReason: this.reason().trim() } : {}),
      ...(this.highlights().trim() ? { highlights: this.highlights().trim() } : {}),
      ...(this.blockers().trim() ? { blockerNote: this.blockers().trim() } : {}),
      ...(confirm ? { confirmLargeChange: true } : {}),
    };
  }

  /** Lưu nháp hoặc gửi; gửi thì kiểm BR-03-05 trước, chênh lớn phải xác nhận (MSG-03-02). */
  protected send(status: OkrCheckinStatus, confirm = false) {
    const k = this.detail();
    if (!k || this.busy()) return;
    this.attempted.set(true);
    this.notice.set(null);
    this.error.set(null);
    const first = Object.keys(this.errors())[0];
    if (first) {
      // Ô lý do ghi đè có thể vừa hiện: đợi vẽ xong rồi mới đưa tiêu điểm tới ô lỗi đầu tiên
      afterNextRender(() => document.getElementById(`c-${first}`)?.focus(), {
        injector: this.injector,
      });
      return;
    }
    if (status === 'DA_GUI' && !confirm) {
      const diff = largeChange(
        k.currentValue ?? null,
        this.parsed()!,
        k.baselineValue ?? null,
        k.targetValue,
      );
      if (diff !== null) {
        this.pendingLarge.set(largeChangeMessage(diff));
        return;
      }
    }
    this.pendingLarge.set(null);
    this.busy.set(true);
    this.api.createCheckin(k.id, this.body(status, confirm)).subscribe({
      next: (r) => {
        this.busy.set(false);
        if (status === 'NHAP') {
          this.notice.set(`Đã lưu nháp check-in ${k.code}.`);
          return;
        }
        const code = k.code.replace(/^KR\s*/, '');
        const pct = Math.round(r.objective.progress * 100);
        this.notice.set(
          `Đã gửi check-in KR ${code}. Tiến độ mục tiêu ${r.objective.code} hiện là ${pct}%.`,
        );
        this.next(k.id);
      },
      error: (e) => {
        this.busy.set(false);
        const p = this.problems.describe(e);
        if (p.problem.code === 'OKR-422-LARGE-CHANGE-UNCONFIRMED') {
          this.pendingLarge.set(p.message);
        } else if (Object.keys(p.fields).length) {
          this.serverFields.set(p.fields);
        } else {
          this.error.set(p.message);
        }
      },
    });
  }

  /** Sau khi gửi: tải lại danh sách và chuyển sang KR kế tiếp còn phải check-in. */
  private next(doneId: string) {
    const nextId = this.rows().find((r) => !r.done && r.kr.id !== doneId)?.kr.id ?? doneId;
    this.list.reload();
    this.history.reload();
    if (nextId === doneId) this.kr.reload();
    else this.go(nextId);
  }
}
