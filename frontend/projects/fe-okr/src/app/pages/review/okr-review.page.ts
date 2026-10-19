/*
 * SCR-0203 "Phê duyệt OKR" (QT-02 bước 7–8; TL-02 Hình 7.5) — đối chiếu đặc tả:
 * Trường: 1 hàng chờ theo đơn vị (ngày trình, người trình, số O/KR, kiểm tra; sắp theo ngày trình;
 *   `flowable.wf_task_projection`); 2 phiên bản (`okr.objective.version_no`, chỉ đọc); 3 kết quả kiểm tra
 *   (Đạt, cảnh báo, lỗi; MOD-01 tính lại khi mở); 4 ý kiến phê duyệt ≤ 2.000 ký tự, bắt buộc khi trả lại
 *   (BR-02-09); 5 thông báo liên kết ngang (mặc định bật); 6 lịch sử luồng duyệt (chỉ đọc).
 * Vùng: 1 hàng chờ; 2 chi tiết bộ OKR, "Xem cây"; 3 ý kiến; 4 "Trả lại để sửa", "Phê duyệt"; 5 lịch sử, mẫu luồng.
 * Hành động: Phê duyệt (người duyệt khác người soạn; POST /api/v1/okr/objectives/{id}/approve, If-Match,
 *   Idempotency-Key; EVT-01); Trả lại để sửa (có ý kiến; POST .../{id}/return; EVT-21); Duyệt hàng loạt
 *   (bộ không cảnh báo; POST /api/v1/okr/objectives/approve-batch, 202); Xem cây (SCR-0201 chỉ đọc).
 * Quy tắc: BR-02-08 (người duyệt không là người soạn: ẩn "Phê duyệt", API 403), BR-02-09 (trả lại phải có ý kiến).
 * Thông báo: MSG-02-09 (đã duyệt), MSG-02-10 (thiếu ý kiến khi trả lại), MSG-02-11 (tự duyệt), MSG-02-12 (còn n bộ).
 * Quyền (wiki/15a): SCR-0203.xem, SCR-0203.duyet, SCR-0203.tra-lai, SCR-0203.duyet-lo (AC-04 `leader`).
 */
import {
  Component,
  ElementRef,
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
  Job,
  JobProgressComponent,
  JobTracker,
  OkrApi,
  OkrReviewDecisionBody,
  OkrReviewSet,
  ProblemPresenter,
  jobLocation,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { Observable, concat, map, toArray } from 'rxjs';
import { OKR_CAN } from '../../okr-permissions';
import { formatDate } from '../../okr-ui';
import { ReviewDetail } from './review-detail';
import { ReviewHistory } from './review-history';
import { ReviewQueue } from './review-queue';

const MAX_COMMENT = 2000;

@Component({
  selector: 'vt-okr-review-page',
  imports: [
    FormsModule,
    ButtonModule,
    MessageModule,
    SelectModule,
    JobProgressComponent,
    ReviewQueue,
    ReviewDetail,
    ReviewHistory,
  ],
  template: `
    @if (queue.error()) {
      <p-message severity="error" styleClass="msg">{{ loadError() }}</p-message>
    } @else if (!data()) {
      <p class="muted" role="status">Đang tải hàng chờ phê duyệt…</p>
    } @else {
      @let q = data()!;
      <header class="head">
        <div>
          <h1>Phê duyệt mục tiêu, OKR</h1>
          <p class="muted small">
            {{ q.period.label }} · {{ q.items.length }} bộ OKR chờ duyệt · hạn duyệt theo lịch kỳ
            {{ day(q.dueDate) }}
          </p>
        </div>
        <div class="actions">
          <label for="r-period" class="sr-only">Kỳ</label>
          <p-select
            inputId="r-period"
            [options]="q.periods"
            optionLabel="label"
            optionValue="id"
            [ngModel]="q.period.id"
            (ngModelChange)="setPeriod($event)"
          />
          @if (!simple() && can('SCR-0203.duyet-lo') && eligible().length) {
            <p-button
              label="Duyệt hàng loạt (đủ điều kiện)"
              severity="secondary"
              #batchToggle
              [attr.aria-expanded]="batchOpen()"
              (onClick)="batchOpen() ? closeBatch() : batchOpen.set(true)"
            />
          }
        </div>
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
      @if (batchOpen()) {
        <section
          class="box batch"
          aria-labelledby="b-h"
          tabindex="-1"
          #batchBox
          (keydown.escape)="closeBatch()"
        >
          <h2 id="b-h" class="sec">Duyệt hàng loạt các bộ đủ điều kiện</h2>
          <p class="muted small">
            Chỉ gồm bộ "Đạt", không cảnh báo và không do đồng chí soạn. Mỗi bộ được duyệt riêng; bộ
            không duyệt được không ảnh hưởng tới các bộ khác.
          </p>
          <ul class="plain">
            @for (s of eligible(); track s.id) {
              <li>
                <label>
                  <input
                    type="checkbox"
                    [checked]="batchPicked().has(s.id)"
                    (change)="toggleBatch(s.id)"
                  />
                  {{ s.unit.name }} ({{ s.objectiveCount }} mục tiêu, {{ s.krCount }} KR)
                </label>
              </li>
            }
          </ul>
          @if (batchJob(); as j) {
            <vt-job-progress [job]="j" label="Duyệt hàng loạt" labelId="b-job" />
          }
          <div class="actions">
            <p-button
              label="Huỷ"
              severity="secondary"
              [disabled]="busy()"
              (onClick)="closeBatch()"
            />
            <p-button
              [label]="'Phê duyệt ' + batchPicked().size + ' bộ'"
              styleClass="primary"
              [disabled]="!batchPicked().size || busy()"
              [loading]="busy()"
              (onClick)="approveBatch()"
            />
          </div>
        </section>
      }

      <div class="cols">
        <vt-review-queue
          [items]="q.items"
          [selectedId]="selectedId()"
          (selected)="select($event)"
        />
        <div>
          @if (selected(); as s) {
            <vt-review-detail [set]="s" [periodLabel]="q.period.label" />
            <div class="pair">
              <section class="box" aria-labelledby="c-h">
                <h2 id="c-h" class="sec">Ý kiến phê duyệt</h2>
                <label for="r-comment" class="sr-only">
                  Ý kiến phê duyệt, bắt buộc khi trả lại
                </label>
                <textarea
                  id="r-comment"
                  class="inp"
                  rows="4"
                  [attr.maxlength]="maxComment"
                  placeholder="Nhập ý kiến (bắt buộc khi trả lại)…"
                  [attr.aria-invalid]="!!commentError()"
                  [attr.aria-describedby]="commentError() ? 'r-comment-err' : null"
                  [ngModel]="comment()"
                  (ngModelChange)="comment.set($event); commentError.set(null)"
                ></textarea>
                @if (commentError(); as ce) {
                  <p id="r-comment-err" class="err small" role="alert">{{ ce }}</p>
                }
                <label class="chk">
                  <input
                    type="checkbox"
                    [ngModel]="notifyPeers()"
                    (ngModelChange)="notifyPeers.set($event)"
                  />
                  Thông báo cho người soạn và các đơn vị liên kết ngang
                </label>
                @if (s.viewerIsAuthor) {
                  <p class="warn small" role="note">
                    Đồng chí là người soạn bộ OKR này nên không thể phê duyệt.
                  </p>
                }
                <div class="actions decide">
                  @if (can('SCR-0203.tra-lai')) {
                    <p-button
                      label="Trả lại để sửa"
                      severity="secondary"
                      [disabled]="busy()"
                      (onClick)="returnSet(s)"
                    />
                  }
                  @if (can('SCR-0203.duyet') && !s.viewerIsAuthor) {
                    <p-button
                      label="Phê duyệt"
                      styleClass="primary"
                      [disabled]="busy()"
                      [loading]="busy()"
                      (onClick)="approve(s)"
                    />
                  }
                </div>
              </section>
              <vt-review-history [set]="s" [queue]="q" />
            </div>
          }
        </div>
      </div>
    }
  `,
  styles: `
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      margin-bottom: 12px;
    }
    h1 {
      font-size: 1.4rem;
      margin: 4px 0;
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
    .warn {
      color: #7c4a03;
      background: #fef6e4;
      border-radius: 4px;
      padding: 4px 8px;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px;
    }
    .decide {
      margin-top: 12px;
    }
    :host ::ng-deep .p-button.primary {
      background: #c4002a;
      border-color: #c4002a;
    }
    .ok-text {
      color: #14532d;
    }
    :host ::ng-deep .msg {
      margin-bottom: 12px;
      display: block;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(280px, 0.8fr) minmax(0, 2fr);
      gap: 16px;
      align-items: start;
    }
    .pair {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 16px;
      margin-top: 16px;
    }
    @media (max-width: 1100px) {
      .cols,
      .pair {
        grid-template-columns: 1fr;
      }
    }
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
    }
    .batch {
      margin-bottom: 12px;
    }
    .sec {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 1.05rem;
      margin: 0 0 12px;
    }
    .num {
      display: inline-grid;
      place-items: center;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: #111827;
      color: #fff;
      font-size: 0.75rem;
    }
    .inp {
      font: inherit;
      width: 100%;
      box-sizing: border-box;
      padding: 8px 10px;
      border: 1px solid #9ca3af;
      border-radius: 6px;
    }
    .inp:focus-visible {
      outline: 2px solid #c4002a;
    }
    .chk {
      display: flex;
      gap: 8px;
      align-items: center;
      margin-top: 8px;
    }
    .plain {
      list-style: none;
      padding: 0;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
    }
  `,
})
export class OkrReviewPage {
  private readonly api = inject(OkrApi);
  private readonly jobs = inject(JobTracker);
  private readonly problems = inject(ProblemPresenter);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(OKR_CAN);
  protected readonly maxComment = MAX_COMMENT;

  private readonly query = toSignal(
    this.route.queryParamMap.pipe(
      map((q) => ({ period: q.get('period') ?? undefined, set: q.get('set') })),
    ),
    { initialValue: { period: undefined as string | undefined, set: null as string | null } },
  );
  private readonly period = computed(() => this.query().period);

  protected readonly queue = rxResource({
    params: () => ({ period: this.period() }),
    stream: ({ params }) => this.api.reviewQueue(params.period),
  });
  protected readonly data = computed(() => (this.queue.hasValue() ? this.queue.value() : null));
  protected readonly loadError = computed(() =>
    this.queue.error() ? this.problems.describe(this.queue.error()).message : '',
  );

  /** Chế độ "Đảng viên cao tuổi" giảm bước: ẩn duyệt hàng loạt, giữ duyệt từng bộ (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  /** Bộ đang xem: theo `?set=` nếu còn trong hàng chờ, nếu không thì bộ trình sớm nhất. */
  protected readonly selectedId = linkedSignal<string | null>(() => {
    const items = this.data()?.items ?? [];
    const wanted = this.query().set;
    return items.find((s) => s.id === wanted)?.id ?? items[0]?.id ?? null;
  });
  protected readonly selected = computed(
    () => this.data()?.items.find((s) => s.id === this.selectedId()) ?? null,
  );

  /** Ý kiến, thông báo ngang làm lại khi đổi bộ. */
  protected readonly comment = linkedSignal<string | null, string>({
    source: this.selectedId,
    computation: () => '',
  });
  protected readonly notifyPeers = linkedSignal<string | null, boolean>({
    source: this.selectedId,
    computation: () => true,
  });
  protected readonly commentError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);

  /** Bộ đủ điều kiện duyệt hàng loạt: không lỗi, không cảnh báo, không do người xem soạn. */
  protected readonly eligible = computed(() =>
    (this.data()?.items ?? []).filter(
      (s) => s.blockingCount === 0 && s.warningCount === 0 && !s.viewerIsAuthor,
    ),
  );
  protected readonly batchOpen = signal(false);
  private readonly batchBox = viewChild<ElementRef<HTMLElement>>('batchBox');
  private readonly batchToggle = viewChild('batchToggle', { read: ElementRef });
  protected readonly batchPicked = linkedSignal<ReadonlySet<string>>(
    () => new Set(this.eligible().map((s) => s.id)),
  );
  protected readonly batchJob = signal<Job | null>(null);

  protected readonly day = (iso: string) => formatDate(iso, true);

  protected setPeriod(id: string) {
    this.notice.set(null);
    this.error.set(null);
    this.router.navigate([], { relativeTo: this.route, queryParams: { period: id, set: null } });
  }

  protected select(id: string) {
    this.selectedId.set(id);
    this.commentError.set(null);
    this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { set: id },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }

  /** Mở bảng duyệt hàng loạt thì đưa tiêu điểm vào bảng để Esc đóng được ngay (WCAG 2.4.3). */
  private readonly focusBatch = effect(() => this.batchBox()?.nativeElement.focus());

  /** Đóng bảng và trả tiêu điểm về nút mở. */
  protected closeBatch() {
    this.batchOpen.set(false);
    (this.batchToggle()?.nativeElement as HTMLElement | undefined)
      ?.querySelector('button')
      ?.focus();
  }

  protected toggleBatch(id: string) {
    this.batchPicked.update((x) => {
      const next = new Set(x);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  private body(): OkrReviewDecisionBody {
    const c = this.comment().trim();
    return { ...(c ? { comment: c } : {}), notifyPeers: this.notifyPeers() };
  }

  /**
   * Duyệt một bộ: mỗi mục tiêu của bộ là một nhiệm vụ duyệt `duyet-okr` riêng, gửi lần lượt để mục
   * lỗi dừng phần còn lại và người duyệt thấy đúng lỗi (giả định, xem wiki/14).
   */
  protected approve(s: OkrReviewSet) {
    const body = this.body();
    this.run(
      s,
      (id, rv) => this.api.approveObjective(id, rv, body),
      `Đã phê duyệt OKR của ${s.unit.name}. Check-in KR đã được mở.`,
    );
  }

  protected returnSet(s: OkrReviewSet) {
    if (!this.comment().trim()) {
      this.commentError.set('Vui lòng nhập ý kiến khi trả lại.');
      document.getElementById('r-comment')?.focus();
      return;
    }
    const body = this.body();
    this.run(
      s,
      (id, rv) => this.api.returnObjective(id, rv, body),
      `Đã trả lại bộ OKR của ${s.unit.name} để sửa.`,
    );
  }

  private run(
    s: OkrReviewSet,
    call: (id: string, rowVersion: number) => Observable<unknown>,
    done: string,
  ) {
    this.busy.set(true);
    this.notice.set(null);
    this.error.set(null);
    concat(...s.objectives.map((o) => call(o.id, o.rowVersion)))
      .pipe(toArray())
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.notice.set(done);
          this.queue.reload();
        },
        error: (e) => {
          this.busy.set(false);
          this.error.set(this.problems.describe(e).message);
          this.queue.reload();
        },
      });
  }

  protected approveBatch() {
    const sets = this.eligible().filter((s) => this.batchPicked().has(s.id));
    const items = sets.flatMap((s) =>
      s.objectives.map((o) => ({ id: o.id, rowVersion: o.rowVersion })),
    );
    if (!items.length) return;
    this.busy.set(true);
    this.notice.set(null);
    this.error.set(null);
    this.api.approveBatch(items, this.body()).subscribe({
      next: (res) => {
        const loc = jobLocation(res);
        if (!loc) {
          this.busy.set(false);
          return;
        }
        this.jobs.track(loc).subscribe({
          next: (j) => this.batchJob.set(j),
          error: (e) => {
            this.busy.set(false);
            this.error.set(this.problems.describe(e).message);
          },
          complete: () => {
            this.busy.set(false);
            const j = this.batchJob();
            this.notice.set(`${j?.message ?? 'Đã duyệt hàng loạt'} của ${sets.length} bộ OKR.`);
            this.batchOpen.set(false);
            this.batchJob.set(null);
            this.queue.reload();
          },
        });
      },
      error: (e) => {
        this.busy.set(false);
        this.error.set(this.problems.describe(e).message);
      },
    });
  }
}
