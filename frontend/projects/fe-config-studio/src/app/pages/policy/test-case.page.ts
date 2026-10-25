import { Component, computed, effect, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { JobAccepted } from '@vt/api-client';
import {
  PackVersionRef,
  PeriodApi,
  PolicyApi,
  PolicyDiffChange,
  PolicyPackVersion,
  PolicyTestCaseResult,
  PolicyTestRunSummary,
  RATING_LEVEL_LABEL,
  RatingLevel,
  policyVersionLabel,
} from '@vt/api-client/domains';
import { ButtonModule } from 'primeng/button';
import { combineLatest, map, of } from 'rxjs';
import { STUDIO_ACCESS } from '../../studio-permissions';
import { fmtDate, fmtNum } from '../../studio-ui';
import { PARAM_LABEL, PolicySubmitDialog, VALUE_LABEL } from './policy-dialogs';
import { PolicyWorkspace } from './policy-workspace';
import { AddTestCaseDialog, ApprovePolicyDialog, RejectPolicyDialog } from './test-case-dialogs';
import { VtSkeletonComponent } from '@vt/design-system';

type Filter = 'ALL' | 'PASS' | 'FAIL';

/** Một lần chạy trong lịch sử, kèm phiên bản chứa nó (bản đang mở hoặc bản đang áp dụng). */
interface HistoryItem {
  ref: PackVersionRef;
  run: PolicyTestRunSummary;
}

const HISTORY_LIMIT = 10;

/** Cùng lần chạy và cùng kết quả: tránh tải lại chi tiết khi lịch sử được đọc lại. */
const sameRun = (a: HistoryItem | null, b: HistoryItem | null) =>
  a?.run.id === b?.run.id && a?.run.result === b?.run.result;

const time = (iso: string | null | undefined) => {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (x: number) => String(x).padStart(2, '0');
  return `${p(d.getDate())}/${p(d.getMonth() + 1)} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

/**
 * Config Studio · Test case (SCR-1503, QT-15). Đối chiếu đặc tả TL-02b:
 * - Trường: 1 bộ test (mã, số ca, người ký, ngày); 2 kết quả lần chạy (đạt/tổng, thời gian);
 *   3 khớp hai engine; 4 kiểm thử thuộc tính (số bộ dữ liệu, bất biến); 5 bảng ca (lọc phía client);
 *   6 lịch sử 10 lần gần nhất; 7 chi tiết lỗi (ca, kỳ vọng, thực tế, khác biệt cấu hình).
 * - Chức năng: Thêm ca kiểm thử (`policy_author`, BR-15-08); Chạy lại (202, BR-15-07); Lọc ca;
 *   Gửi phê duyệt phát hành khi `DAT_KIEM_THU`; Ký số phê duyệt (AC-08, `CHO_PHE_DUYET`, AAL2,
 *   BR-15-09) và từ chối có ý kiến.
 * - Thông báo: MSG-15-06 (có ca không đạt), MSG-15-07 (hai engine khác nhau, hiển thị là "máy chủ" và
 *   "trình duyệt" theo YC-PT-10), MSG-15-08 (đã phát hành).
 * Người phê duyệt xem ở chế độ chỉ đọc. Máy chủ quyết định trạng thái và quyền.
 */
@Component({
  selector: 'vt-test-case-page',
  imports: [
    VtSkeletonComponent,
    ButtonModule,
    PolicySubmitDialog,
    AddTestCaseDialog,
    ApprovePolicyDialog,
    RejectPolicyDialog,
  ],
  template: `
    <div class="top">
      @if (suiteRes.isLoading() && !suite()) {
        <vt-skeleton class="sub" variant="text" [rows]="1" label="Đang tải bộ ca mẫu" />
      } @else {
        <p class="sub" data-testid="suite" [attr.data-suite]="suite()?.code">
          {{ suiteText() }}
          @if (suite()?.pending; as n) {
            <span class="pending">{{ n }} ca mới đang chờ ký duyệt.</span>
          }
        </p>
      }
      <div class="toolbar">
        @if (canAdd()) {
          <p-button
            label="Thêm ca kiểm thử"
            severity="contrast"
            [outlined]="true"
            data-testid="add-case"
            (onClick)="showAdd.set(true)"
          />
        }
        @if (canRun()) {
          <p-button
            label="Chạy lại"
            severity="contrast"
            [outlined]="true"
            data-testid="rerun"
            [loading]="ws.running()"
            [disabled]="!ws.editable()"
            (onClick)="ws.runTests()"
          />
        }
        @if (canSubmit()) {
          <p-button
            label="Gửi phê duyệt phát hành"
            data-testid="submit"
            [disabled]="ws.status() !== 'DAT_KIEM_THU' || ws.running()"
            (onClick)="showSubmit.set(true)"
          />
        }
        @if (canDecide()) {
          <p-button
            label="Từ chối"
            severity="contrast"
            [outlined]="true"
            data-testid="reject"
            [disabled]="ws.publishing()"
            (onClick)="showReject.set(true)"
          />
          <p-button
            label="Ký số phê duyệt"
            data-testid="approve"
            [loading]="ws.publishing()"
            (onClick)="showApprove.set(true)"
          />
        }
      </div>
    </div>
    @if (readonlyHint(); as h) {
      <p class="hint" data-testid="readonly">{{ h }}</p>
    }
    @if (notice(); as n) {
      <p class="ok" role="status" data-testid="notice">{{ n }}</p>
    }
    @if (ws.publishing()) {
      <p class="info" role="status">Đang phát hành phiên bản. Vui lòng chờ trong giây lát…</p>
    }

    <section class="cards" aria-label="Kết quả kiểm thử">
      <div class="kpi" [class.bad]="run()?.result === 'KHONG_DAT'" data-testid="card-run">
        @if (ws.running() && isLatestSelected()) {
          <span class="lbl">Lần chạy mới</span>
          <strong class="big">Đang chạy…</strong>
        } @else if (run(); as r) {
          <span class="lbl">Lần chạy #{{ r.runNo }} · bản {{ vlabel(r.version) }}</span>
          <strong class="big">{{ r.passed }}/{{ r.total }}</strong>
          <span class="muted">
            {{ percent(r.passed, r.total) }} đạt
            @if (r.durationMs) {
              · {{ seconds(r.durationMs) }} giây
            }
          </span>
        } @else {
          <span class="lbl">Kết quả lần chạy</span>
          <strong class="big muted">Chưa chạy</strong>
        }
      </div>
      <div class="kpi" [class.bad]="engineBad()" data-testid="card-engine">
        <span class="lbl">Khớp giữa máy chủ và trình duyệt</span>
        @if (detail(); as d) {
          <strong class="big">{{ d.engineMatchedCount ?? matched(d.cases) }}/{{ d.total }}</strong>
          <span class="muted">{{
            engineBad() ? 'Có ca cho kết quả khác nhau' : 'Hai nơi tính cho cùng kết quả'
          }}</span>
        } @else {
          <strong class="big muted">—</strong>
        }
      </div>
      <div
        class="kpi"
        [class.bad]="!!detail()?.propertyResult?.violations"
        data-testid="card-property"
      >
        <span class="lbl">Kiểm thử thuộc tính</span>
        @if (detail()?.propertyResult; as pr) {
          <strong class="big">{{ num(pr.datasets, 0) }}</strong>
          <span class="muted">
            bộ dữ liệu ngẫu nhiên,
            {{
              pr.violations ? pr.violations + ' lần vi phạm điều kiện' : 'không vi phạm điều kiện'
            }}
          </span>
          @if (pr.invariants?.length) {
            <ul class="inv">
              @for (i of pr.invariants; track i) {
                <li>
                  <code>{{ i }}</code>
                </li>
              }
            </ul>
          }
        } @else {
          <strong class="big muted">—</strong>
        }
      </div>
      <div class="kpi" data-testid="card-impact">
        <span class="lbl">Tính lại kỳ đã khoá</span>
        @if (detail()?.impact; as im) {
          <strong class="mid">{{ lockedHead(im.lockedPeriodNote) }}</strong>
          <span class="muted">{{ lockedTail(im.lockedPeriodNote) }}</span>
          <span class="muted">
            So với bản {{ vlabel(im.baseVersion) }}: {{ im.changedScoreCount ?? 0 }} ca đổi điểm,
            {{ im.changedRatingCount }} ca đổi mức.
          </span>
        } @else {
          <strong class="mid">Không áp dụng</strong>
          <span class="muted">Phiên bản chỉ có hiệu lực từ kỳ chưa bắt đầu.</span>
        }
      </div>
    </section>

    @if (failWarning(); as w) {
      <p class="warn" role="alert" data-testid="msg-15-06">{{ w }}</p>
    }
    @for (m of engineMessages(); track m) {
      <p class="err" role="alert" data-testid="msg-15-07">{{ m }}</p>
    }

    <div class="grid">
      <section class="card" aria-labelledby="h-cases">
        <div class="card-head">
          <h2 id="h-cases">Kết quả từng ca ({{ shown().length }}/{{ cases().length }} hiển thị)</h2>
          <div class="filter" role="group" aria-label="Lọc ca">
            @for (f of filters; track f.key) {
              <button
                type="button"
                [attr.aria-pressed]="filter() === f.key"
                [attr.data-filter]="f.key"
                (click)="filter.set(f.key)"
              >
                {{ f.label }}
              </button>
            }
          </div>
        </div>
        @if (detailRes.error()) {
          <p class="err" role="alert">Không tải được kết quả lần chạy. Vui lòng thử lại.</p>
        } @else if (cases().length) {
          <div class="scroll" tabindex="0" role="region" aria-label="Bảng kết quả các ca kiểm thử">
            <table data-testid="cases">
              <thead>
                <tr>
                  <th scope="col">Mã ca</th>
                  <th scope="col">Tình huống</th>
                  <th scope="col">Kỳ vọng</th>
                  <th scope="col">Thực tế</th>
                  <th scope="col">Kết quả</th>
                </tr>
              </thead>
              <tbody>
                @for (c of shown(); track c.goldenTestCaseId) {
                  <tr [attr.data-case]="c.caseCode" [class.fail]="!c.passed">
                    <th scope="row">{{ c.caseCode }}</th>
                    <td>{{ c.description }}</td>
                    <td>{{ outcome(c.expected) }}</td>
                    <td>{{ outcome(c.actual) }}</td>
                    <td>
                      <span class="res" [class.no]="!c.passed">
                        {{ c.passed ? '✓ Đạt' : '✕ Không đạt' }}
                      </span>
                    </td>
                  </tr>
                } @empty {
                  <tr>
                    <td colspan="5" class="muted">Không có ca nào theo bộ lọc.</td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        } @else if (ws.running()) {
          <p class="muted">Đang chạy kiểm thử, kết quả từng ca sẽ hiện khi chạy xong.</p>
        } @else if (!history().length && !historyRes.isLoading()) {
          <p class="muted">Phiên bản chưa được chạy kiểm thử.</p>
        } @else {
          <vt-skeleton variant="table" [columns]="4" label="Đang tải lịch sử kiểm thử" />
        }
      </section>

      <aside class="side">
        <section class="card" aria-labelledby="h-hist">
          <h2 id="h-hist">Lịch sử chạy</h2>
          @if (history().length) {
            <ul class="hist" data-testid="history">
              @for (h of history(); track h.run.id) {
                <li>
                  <button
                    type="button"
                    [attr.aria-pressed]="selectedId() === h.run.id"
                    [attr.data-run]="h.run.runNo"
                    (click)="selectedId.set(h.run.id)"
                  >
                    <span class="no" [class.bad]="h.run.result === 'KHONG_DAT'"
                      >#{{ h.run.runNo }}</span
                    >
                    <span>
                      @if (h.run.result === 'DANG_CHAY') {
                        Đang chạy
                      } @else {
                        {{ h.run.passed }}/{{ h.run.total }}
                      }
                      · {{ time(h.run.startedAt) }}
                    </span>
                    <span class="ver">
                      bản {{ vlabel(h.run.version) }}
                      @if (h.ref.v !== ws.ref()?.v) {
                        (đang áp dụng)
                      }
                    </span>
                  </button>
                </li>
              }
            </ul>
          } @else {
            <p class="muted">Chưa có lần chạy nào.</p>
          }
        </section>

        @if (failRes.isLoading() && !failDetail()) {
          <section class="card fail" aria-label="Lỗi ở lần chạy không đạt">
            <vt-skeleton variant="text" [rows]="3" label="Đang tải chi tiết lần chạy không đạt" />
          </section>
        } @else if (failDetail(); as f) {
          <section class="card fail" aria-labelledby="h-fail" data-testid="fail-detail">
            <h2 id="h-fail">Lỗi ở lần #{{ f.runNo }}{{ fixedLater() ? ' (đã sửa)' : '' }}</h2>
            @for (c of failedCases(f.cases); track c.goldenTestCaseId) {
              <p>
                <strong>{{ c.caseCode }}</strong> · kỳ vọng {{ outcome(c.expected) }}, thực tế
                {{ outcome(c.actual) }}
              </p>
            }
            @if (f.configDiff?.changes?.length) {
              <p class="muted">Thay đổi cấu hình so với lần chạy trước:</p>
              <pre
                class="diff"
              >@for (ch of f.configDiff!.changes; track $index) {<span class="minus">- {{ changeTitle(ch) }}: {{ value(ch.before) }}</span>
<span class="plus">+ {{ changeTitle(ch) }}: {{ value(ch.after) }}</span>
}</pre>
            }
          </section>
        }

        <p class="note">
          Chỉ khi toàn bộ ca đạt và máy chủ, trình duyệt cho cùng kết quả, phiên bản mới chuyển sang
          "Đạt kiểm thử" và được gửi phê duyệt phát hành.
        </p>
      </aside>
    </div>

    <vt-policy-submit-dialog [(visible)]="showSubmit" (sent)="onSent()" />
    <vt-add-test-case-dialog [(visible)]="showAdd" (created)="onCreated($event.caseCode)" />
    <vt-approve-policy-dialog [(visible)]="showApprove" (approved)="onApproved($event)" />
    <vt-reject-policy-dialog [(visible)]="showReject" (rejected)="onRejected()" />
  `,
  styles: `
    :host {
      display: block;
    }
    .top {
      display: flex;
      flex-wrap: wrap;
      gap: 8px 16px;
      align-items: flex-start;
      justify-content: space-between;
      margin-bottom: 8px;
    }
    .sub {
      color: #4b5563;
      margin: 0;
      flex: 1 1 360px;
    }
    .pending {
      color: #92400e;
      font-weight: 500;
    }
    .toolbar {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      justify-content: flex-end;
    }
    .hint {
      text-align: right;
      color: #4b5563;
      margin: 0 0 12px;
    }
    .ok,
    .info,
    .warn,
    .err,
    .note {
      padding: 10px 12px;
      border-radius: 6px;
      margin: 0 0 12px;
    }
    .ok {
      background: #dcfce7;
      color: #14532d;
    }
    .info,
    .note {
      background: #eff6ff;
      border-left: 4px solid #1d4ed8;
      color: #1e3a8a;
    }
    .warn {
      background: #fef3c7;
      color: #78350f;
    }
    .err {
      background: #fee2e2;
      color: #7f1d1d;
    }
    .cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 12px;
      margin-bottom: 16px;
    }
    .kpi {
      border: 1px solid #e5e7eb;
      border-top: 3px solid #15803d;
      border-radius: 8px;
      padding: 14px 16px;
      background: #fff;
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;
    }
    .kpi.bad {
      border-top-color: #b4001f;
    }
    .lbl {
      color: #374151;
    }
    .big {
      font-size: 2rem;
      line-height: 1.2;
      color: #15803d;
    }
    .mid {
      font-size: 1.25rem;
      color: #111827;
    }
    .kpi.bad .big {
      color: #b4001f;
    }
    .big.muted {
      color: #6b7280;
    }
    .muted {
      color: #4b5563;
    }
    .inv {
      margin: 4px 0 0;
      padding-left: 18px;
      font-size: 0.875rem;
    }
    code,
    pre {
      font-family: ui-monospace, Menlo, Consolas, monospace;
    }
    .grid {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(280px, 1fr);
      gap: 16px;
      align-items: start;
    }
    @media (max-width: 1100px) {
      .grid {
        grid-template-columns: minmax(0, 1fr);
      }
    }
    .card {
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 16px;
      background: #fff;
      margin-bottom: 16px;
      min-width: 0;
    }
    .card.fail {
      border-color: #b4001f;
    }
    .card-head {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
      justify-content: space-between;
      margin-bottom: 8px;
    }
    h2 {
      font-size: 1.0625rem;
      margin: 0 0 8px;
    }
    .card-head h2 {
      margin: 0;
    }
    .filter {
      display: flex;
      gap: 4px;
    }
    .filter button,
    .hist button {
      font: inherit;
      cursor: pointer;
      min-height: 40px;
    }
    .filter button {
      border: 1px solid #cbd5e1;
      background: #fff;
      border-radius: 999px;
      padding: 4px 14px;
      color: #374151;
    }
    .filter button[aria-pressed='true'] {
      background: #b4001f;
      border-color: #b4001f;
      color: #fff;
    }
    .scroll {
      overflow-x: auto;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      min-width: 640px;
    }
    th,
    td {
      text-align: left;
      vertical-align: top;
      padding: 10px 8px;
      border-bottom: 1px solid #e5e7eb;
    }
    thead th {
      background: #f8fafc;
      font-weight: 500;
      color: #374151;
    }
    tbody th {
      white-space: nowrap;
    }
    tr.fail {
      background: #fff1f2;
    }
    .res {
      display: inline-block;
      white-space: nowrap;
      padding: 2px 8px;
      border-radius: 999px;
      background: #dcfce7;
      color: #14532d;
      font-weight: 500;
    }
    .res.no {
      background: #fee2e2;
      color: #7f1d1d;
    }
    .hist {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .hist button {
      width: 100%;
      display: grid;
      grid-template-columns: auto 1fr auto;
      gap: 8px;
      align-items: center;
      text-align: left;
      border: 0;
      border-bottom: 1px solid #e5e7eb;
      background: none;
      padding: 8px 4px;
      color: #111827;
    }
    .hist button[aria-pressed='true'] {
      background: #f1f5f9;
    }
    .no {
      background: #dcfce7;
      color: #14532d;
      border-radius: 6px;
      padding: 2px 6px;
      font-weight: 600;
    }
    .no.bad {
      background: #fee2e2;
      color: #7f1d1d;
    }
    .ver {
      color: #4b5563;
      font-size: 0.875rem;
    }
    .diff {
      background: #0f172a;
      color: #e2e8f0;
      padding: 10px 12px;
      border-radius: 6px;
      white-space: pre-wrap;
      font-size: 0.875rem;
    }
    .minus {
      color: #fecaca;
    }
    .plus {
      color: #bbf7d0;
    }
  `,
})
export class TestCasePage {
  private readonly api = inject(PolicyApi);
  private readonly periodApi = inject(PeriodApi);
  protected readonly ws = inject(PolicyWorkspace);
  protected readonly access = inject(STUDIO_ACCESS);
  protected readonly vlabel = policyVersionLabel;
  protected readonly date = fmtDate;
  protected readonly time = time;
  protected readonly filters: { key: Filter; label: string }[] = [
    { key: 'ALL', label: 'Tất cả' },
    { key: 'PASS', label: 'Đạt' },
    { key: 'FAIL', label: 'Không đạt' },
  ];

  protected readonly canAdd = computed(() => this.access.author('SCR-1503.chay'));
  protected readonly canRun = computed(() => this.access.author('SCR-1503.chay'));
  protected readonly canSubmit = computed(() => this.access.author('SCR-1501.gui-duyet'));
  protected readonly canDecide = computed(
    () => this.ws.status() === 'CHO_PHE_DUYET' && this.access.author('SCR-1503.ky-duyet'),
  );
  protected readonly readonlyHint = computed(() => {
    if (this.canRun()) {
      return this.ws.editable() || !this.ws.status()
        ? null
        : 'Phiên bản đã khoá nội dung, chỉ xem kết quả kiểm thử.';
    }
    return this.canDecide()
      ? 'Đồng chí xem kết quả kiểm thử trước khi ký phê duyệt hoặc từ chối phát hành.'
      : 'Đồng chí được xem kết quả kiểm thử, không chạy lại.';
  });

  protected readonly showSubmit = signal(false);
  protected readonly showAdd = signal(false);
  protected readonly showApprove = signal(false);
  protected readonly showReject = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly filter = signal<Filter>('ALL');

  protected readonly suiteRes = rxResource({
    params: () => this.ws.ref()?.id,
    stream: ({ params }) => this.api.testCases(params),
  });
  protected readonly suite = computed(() => this.suiteRes.value()?.suite);
  /** SCR-1503 trường 1: số ca, người ký duyệt, ngày ký; mỗi ca chạy trên hai engine. */
  protected readonly suiteText = computed(() => {
    const s = this.suite();
    if (!s) return '';
    const by = s.approvedBy
      ? `, được ${s.approvedBy} ký duyệt${s.approvedAt ? ` ngày ${fmtDate(s.approvedAt)}` : ''}`
      : '';
    return `Bộ ca mẫu gồm ${s.total} ca${by}. Mỗi ca được tính trên máy chủ và trên trình duyệt để đối chiếu.`;
  });

  /** Lần chạy của bản đang mở và của bản đang áp dụng, mới nhất trước (SCR-1503 trường 6). */
  protected readonly historyRes = rxResource({
    params: () => {
      const ref = this.ws.ref();
      if (!ref) return undefined;
      this.ws.runsChanged();
      return { ref, published: this.ws.published()?.version ?? null };
    },
    stream: ({ params }) => {
      const own = this.api.testRuns(params.ref, HISTORY_LIMIT);
      const pubRef = params.published ? { id: params.ref.id, v: params.published } : null;
      const pub = pubRef ? this.api.testRuns(pubRef, HISTORY_LIMIT) : of({ items: [] });
      return combineLatest([own, pub]).pipe(
        map(([a, b]) =>
          [
            ...a.items.map((run) => ({ ref: params.ref, run })),
            ...b.items.map((run) => ({ ref: pubRef!, run })),
          ]
            .sort((x, y) => y.run.startedAt.localeCompare(x.run.startedAt))
            .slice(0, HISTORY_LIMIT),
        ),
      );
    },
  });
  /** Giữ lịch sử cũ trong lúc tải lại, để thẻ chi tiết lỗi không nháy. */
  private readonly loadedHistory = linkedSignal<HistoryItem[] | undefined, HistoryItem[]>({
    source: () => this.historyRes.value(),
    computation: (v, prev) => v ?? prev?.value ?? [],
  });
  protected readonly history = computed<HistoryItem[]>(() => {
    const items = this.loadedHistory();
    const live = this.ws.lastRun.value();
    // lần chạy đang chạy hiện ngay ở đầu lịch sử, trước khi danh sách được tải lại
    if (live && this.ws.ref() && !items.some((h) => h.run.id === live.id)) {
      return [{ ref: this.ws.ref()!, run: live }, ...items];
    }
    return items;
  });
  protected readonly selectedId = linkedSignal(() => this.history()[0]?.run.id ?? null);
  private readonly selected = computed(
    () => this.history().find((h) => h.run.id === this.selectedId()) ?? null,
    { equal: sameRun },
  );
  protected readonly isLatestSelected = computed(
    () => this.selectedId() === this.history()[0]?.run.id,
  );
  protected readonly run = computed(() => this.selected()?.run ?? null);

  protected readonly detailRes = rxResource({
    params: () => {
      const s = this.selected();
      return s && s.run.result !== 'DANG_CHAY' ? { ref: s.ref, id: s.run.id } : undefined;
    },
    stream: ({ params }) => this.api.testRun(params.ref, params.id),
  });
  protected readonly detail = computed(() => this.detailRes.value() ?? null);
  protected readonly cases = computed(() => this.detail()?.cases ?? []);
  protected readonly shown = computed(() => {
    const f = this.filter();
    return this.cases().filter((c) => f === 'ALL' || (f === 'PASS') === c.passed);
  });
  protected readonly engineBad = computed(() => {
    const d = this.detail();
    return !!d && (d.engineMatchedCount ?? this.matched(d.cases)) < d.total;
  });

  /** MSG-15-06 cho lần chạy đang xem khi có ca không đạt. */
  protected readonly failWarning = computed(() => {
    const r = this.run();
    if (!r || r.result !== 'KHONG_DAT') return null;
    const own = this.selected()?.ref.v === this.ws.ref()?.v;
    return (
      `Lần chạy #${r.runNo}: ${r.passed}/${r.total} ca đạt.` +
      (own ? ' Phiên bản chưa đạt kiểm thử nên chưa thể gửi phê duyệt.' : '')
    );
  });

  /** MSG-15-07: ca cho kết quả khác nhau giữa máy chủ và trình duyệt. */
  protected readonly engineMessages = computed(() =>
    this.cases()
      .filter((c) => c.engineMatch === false)
      .map(
        (c) =>
          `Ca ${c.caseCode} cho kết quả khác nhau giữa máy chủ (${this.outcome(c.actual)}) và trình duyệt (${this.outcome(c.actualJs)}).`,
      ),
  );

  /** Lần chạy lỗi để xem chi tiết: lần đang xem nếu lỗi, không thì lần lỗi gần nhất. */
  private readonly failItem = computed(
    () => {
      const s = this.selected();
      if (s?.run.result === 'KHONG_DAT') return s;
      return this.history().find((h) => h.run.result === 'KHONG_DAT') ?? null;
    },
    { equal: sameRun },
  );
  protected readonly failRes = rxResource({
    params: () => {
      const f = this.failItem();
      return f ? { ref: f.ref, id: f.run.id } : undefined;
    },
    stream: ({ params }) => this.api.testRun(params.ref, params.id),
  });
  protected readonly failDetail = computed(() => this.failRes.value() ?? null);
  /** Đã có lần chạy đạt sau lần lỗi trên cùng phiên bản. */
  protected readonly fixedLater = computed(() => {
    const f = this.failItem();
    if (!f) return false;
    return this.history().some(
      (h) => h.ref.v === f.ref.v && h.run.result === 'DAT' && h.run.startedAt > f.run.startedAt,
    );
  });

  constructor() {
    // tải lại bộ ca khi lần chạy kết thúc (ca mới được ký duyệt có thể vào bộ)
    effect(() => {
      this.ws.runsChanged();
      this.suiteRes.reload();
    });
  }

  protected matched(cases: PolicyTestCaseResult[] | undefined) {
    return (cases ?? []).filter((c) => c.engineMatch !== false).length;
  }

  protected failedCases(cases: PolicyTestCaseResult[] | undefined) {
    return (cases ?? []).filter((c) => !c.passed);
  }

  protected percent(a: number, b: number) {
    return b ? `${fmtNum((a / b) * 100, 1)}%` : '—';
  }

  protected seconds(ms: number) {
    return fmtNum(ms / 1000, 1);
  }

  protected num(n: number, d: number) {
    return fmtNum(n, d);
  }

  /** "Không áp dụng, v2.1 chỉ hiệu lực…" → tiêu đề "Không áp dụng" và phần giải thích. */
  protected lockedHead(note: string | undefined) {
    return (note ?? 'Không áp dụng').split(/[,.;]/)[0].trim();
  }

  protected lockedTail(note: string | undefined) {
    const n = note ?? '';
    const i = n.search(/[,.;]/);
    const tail = i < 0 ? '' : n.slice(i + 1).trim();
    return tail.charAt(0).toLocaleUpperCase('vi') + tail.slice(1);
  }

  /** Kỳ vọng hoặc kết quả của một ca bằng lời: "Hoàn thành tốt nhiệm vụ · 91,2 điểm", "Tiến độ = 0,9". */
  protected outcome(o: Record<string, unknown> | null | undefined): string {
    if (!o) return '—';
    const parts: string[] = [];
    if (typeof o['muc'] === 'string') {
      parts.push(RATING_LEVEL_LABEL[o['muc'] as RatingLevel] ?? String(o['muc']));
    }
    if (typeof o['diem'] === 'number') parts.push(`${fmtNum(o['diem'], 2)} điểm`);
    for (const [k, v] of Object.entries(o)) {
      if (k === 'muc' || k === 'diem') continue;
      parts.push(`${this.ws.label(k)} = ${this.value(v)}`);
    }
    return parts.join(' · ') || '—';
  }

  protected changeTitle(c: PolicyDiffChange) {
    const p = (c.path ?? '').replace(/^params\./, '');
    // ui-text-ok: tham số, giá trị và lựa chọn do người soạn chính sách tự đặt, không có bảng nhãn cố định
    return c.kind === 'PARAM' ? (PARAM_LABEL[p] ?? p) : this.ws.label(c.key);
  }

  protected value(v: unknown): string {
    if (v === null || v === undefined) return '—';
    if (typeof v === 'number') return fmtNum(v, 3);
    // ui-text-ok: tham số, giá trị và lựa chọn do người soạn chính sách tự đặt, không có bảng nhãn cố định
    if (typeof v === 'string') return VALUE_LABEL[v] ?? v;
    return JSON.stringify(v);
  }

  protected onSent() {
    this.notice.set('Đã gửi phê duyệt phát hành. Phiên bản chờ cấp có thẩm quyền phê duyệt.');
    this.ws.version.reload();
    this.ws.pack.reload();
  }

  protected onCreated(code: string) {
    this.notice.set(`Đã thêm ca ${code}. Ca sẽ được tính vào kết quả sau khi được ký duyệt.`);
    this.suiteRes.reload();
  }

  protected onApproved(accepted: JobAccepted) {
    this.notice.set(null);
    this.ws.waitPublished(accepted, (v) => this.published(v));
  }

  protected onRejected() {
    this.notice.set('Đã từ chối phát hành. Phiên bản trở về bản nháp để người soạn chỉnh sửa.');
    this.ws.version.reload();
    this.ws.pack.reload();
  }

  /** MSG-15-08: tên kỳ hiệu lực tra theo ngày bắt đầu, không có thì dùng ngày. */
  private published(v: PolicyPackVersion) {
    const from = v.effectiveFrom ?? '';
    const say = (ky: string) =>
      this.notice.set(
        `Phiên bản ${policyVersionLabel(v.version)} đã được phát hành, áp dụng từ kỳ ${ky}. Các kỳ đã khoá tiếp tục dùng phiên bản cũ.`,
      );
    this.periodApi.list({ status: 'MO' }).subscribe({
      next: (ps) => say(ps.find((p) => p.startDate === from)?.name ?? fmtDate(from)),
      error: () => say(fmtDate(from)),
    });
  }
}
