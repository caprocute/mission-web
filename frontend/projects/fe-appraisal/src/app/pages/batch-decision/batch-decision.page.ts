/*
 * SCR-0904 Quyết định xếp loại hàng loạt và ký số (QT-09 bước 11, 12 → SCR-0904 → /api/v1/appraisals,
 * /api/v1/esign, /api/v1/dossiers → MOD-04, SVC-10, SVC-11 → EVT-11, EVT-18, EVT-12; WEB-038).
 * Tác nhân AC-08 `decider` trong phạm vi ABAC (Cd); MFA bước lên khi ký lô (AAL2).
 *
 * Trường (TL-02b SCR-0904):
 *  1 Bộ lọc khối, đơn vị                          5 Phân bố mức: số người, tỷ lệ; so ngưỡng
 *  2 Chọn hồ sơ: mặc định tất cả, chỉ                `phan_bo.toi_da_htxsnv` của khối (mặc định 20%)
 *    hồ sơ CHO_QUYET_DINH                         6 Số, ngày quyết định: bắt buộc, không trùng trong năm
 *  3 Bốn cột mức: tự đề xuất, thẩm định,          7 Chứng thư người ký: tên, nhà cung cấp, hạn
 *    đề xuất, quyết định                          8 Tiến độ ký lô: đã ký/tổng, lỗi
 *  4 Mức quyết định: mặc định bằng mức đề xuất; đổi mức mở hộp thoại ghi lý do ≥ 20 ký tự
 * Chức năng: Xuất Mẫu 3 (POST /dossiers/forms 202); Đổi mức (hộp thoại); Quyết định và ký lô
 *  (POST /appraisals/batch-decisions 202, MFA); Theo dõi tiến độ (job); Huỷ lô (POST .../cancel, If-Match).
 * Quy tắc: BR-09-12 (lý do đổi mức), BR-09-13 (người đứng đầu sau đơn vị trực thuộc), BR-09-14 (tách vai
 *  trò, OPA ở máy chủ), BR-09-15 (ký số), BR-09-17 (tỷ lệ xuất sắc, phiên hiệu chỉnh).
 * Thông báo: MSG-09-13 (vượt ngưỡng), MSG-09-14 (người đứng đầu quá sớm), MSG-09-15 (tiến độ, lỗi tự thử
 *  lại), MSG-09-16 (hoàn tất).
 */
import {
  Component,
  DestroyRef,
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
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  APPRAISAL_FLOW_LABEL,
  AppraisalApi,
  AppraisalBatchDecision,
  AppraisalProfileRow,
  DossierApi,
  Job,
  JobTracker,
  RATING_LEVELS,
  RATING_LEVEL_LABEL,
  RatingLevel,
  formIdOf,
  toProblem,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import {
  EMPTY,
  Observable,
  expand,
  firstValueFrom,
  reduce,
  switchMap,
  takeWhile,
  timer,
} from 'rxjs';
import { APPRAISAL_CAN } from '../../appraisal-permissions';
import { todayVn } from '../rating-sheet/rating-sheet-rules';
import { dmy, vn } from '../self-assessment/self-assessment-rules';
import { APPRAISAL_STEP_UP } from '../self-assessment/step-up';
import {
  LEVEL_SHORT,
  MAX_FORM,
  batchProblemMessage,
  certificateInfo,
  certificateValid,
  checkBatch,
  distribution,
  headsTooEarly,
  msg0913,
  msg0915,
  msg0916,
  noteOf,
  thresholdOf,
  percent,
  proposedOf,
  reasonError,
  signProviderOf,
  withinLimit,
} from './batch-decision-rules';

const STEP_UP_REASON =
  'Quyết định và ký lô là mốc ký số; vui lòng xác thực nhiều lớp (MFA) trước khi ký.';
const PAGE_SIZE = 10;
/** Số trang tối đa đọc khi nạp danh sách (200 dòng một trang, một lô tối đa 1.000 hồ sơ). */
const MAX_PAGES = 5;
/** Chu kỳ hỏi tiến độ khi mở lại lô đang ký (ms). */
const RESUME_POLL_MS = 3000;

/** Hộp thoại "Đổi mức" đang mở cho một dòng. */
interface ChangeDialog {
  row: AppraisalProfileRow;
  level: RatingLevel;
  prev: RatingLevel | null;
  select: HTMLSelectElement;
}

const groupKey = (r: AppraisalProfileRow) => `${r.period.id}|${r.flow}`;

@Component({
  selector: 'vt-batch-decision-page',
  imports: [RouterLink],
  templateUrl: './batch-decision.page.html',
  styleUrl: './batch-decision.page.css',
})
export class BatchDecisionPage {
  private readonly api = inject(AppraisalApi);
  private readonly dossiers = inject(DossierApi);
  private readonly jobs = inject(JobTracker);
  private readonly theme = inject(ThemeService);
  private readonly stepUp = inject(APPRAISAL_STEP_UP);
  private readonly injector = inject(Injector);
  private readonly destroyRef = inject(DestroyRef);
  protected readonly can = inject(APPRAISAL_CAN);

  protected readonly levels = RATING_LEVELS;
  protected readonly short = LEVEL_SHORT;
  protected readonly full = RATING_LEVEL_LABEL;
  protected readonly flowLabel = APPRAISAL_FLOW_LABEL;
  protected readonly vn = vn;
  protected readonly dmy = dmy;
  protected readonly percent = percent;

  // Trường 1, 2: danh sách hồ sơ chờ quyết định trong phạm vi
  private readonly reloadKey = signal(0);
  protected readonly queue = rxResource({
    params: () => this.reloadKey(),
    stream: () => this.loadAll(),
  });
  protected readonly rows = computed<AppraisalProfileRow[]>(() =>
    this.queue.hasValue() ? this.queue.value() : [],
  );
  protected readonly groups = computed(() => {
    const out = new Map<string, { key: string; label: string; count: number }>();
    for (const r of this.rows()) {
      const key = groupKey(r);
      const g = out.get(key) ?? {
        key,
        label: `${r.period.label} · ${this.flowLabel[r.flow]}`,
        count: 0,
      };
      g.count++;
      out.set(key, g);
    }
    return [...out.values()];
  });
  protected readonly group = linkedSignal(() => this.groups()[0]?.key ?? '');
  protected readonly inGroup = computed(() =>
    this.rows().filter((r) => groupKey(r) === this.group()),
  );
  protected readonly units = computed(() => {
    const m = new Map(this.inGroup().map((r) => [r.orgUnit.id, r.orgUnit.name]));
    return [...m]
      .map(([id, name]) => ({ id, name }))
      .sort((a, b) => a.name.localeCompare(b.name, 'vi'));
  });
  protected readonly unit = linkedSignal({ source: this.group, computation: () => '' });
  // Trường 1: bộ lọc khối (`subjectGroup` của hồ sơ, WEB-063)
  protected readonly subjectGroups = computed(() => {
    const m = new Map<string, string>();
    for (const r of this.inGroup())
      if (r.subjectGroup) m.set(r.subjectGroup.id, r.subjectGroup.name);
    return [...m].map(([id, name]) => ({ id, name }));
  });
  protected readonly subjectGroup = linkedSignal({ source: this.group, computation: () => '' });
  protected readonly visible = computed(() =>
    this.inGroup().filter(
      (r) =>
        (!this.unit() || r.orgUnit.id === this.unit()) &&
        (!this.subjectGroup() || r.subjectGroup?.id === this.subjectGroup()),
    ),
  );
  protected readonly selected = linkedSignal<string, ReadonlySet<string>>({
    source: () =>
      this.visible()
        .map((r) => r.id)
        .join(','),
    computation: () => new Set(this.visible().map((r) => r.id)),
  });
  protected readonly items = computed(() =>
    this.visible().filter((r) => this.selected().has(r.id)),
  );
  protected readonly allSelected = computed(
    () => this.visible().length > 0 && this.items().length === this.visible().length,
  );
  protected readonly someSelected = computed(() => this.items().length > 0 && !this.allSelected());

  protected readonly pageIndex = linkedSignal({ source: this.visible, computation: () => 0 });
  protected readonly pageCount = computed(() =>
    Math.max(1, Math.ceil(this.visible().length / PAGE_SIZE)),
  );
  protected readonly pageRows = computed(() =>
    this.visible().slice(this.pageIndex() * PAGE_SIZE, (this.pageIndex() + 1) * PAGE_SIZE),
  );
  protected readonly title = computed(() => this.inGroup()[0]?.period.label ?? '');

  // Trường 3, 4: mức quyết định, lý do đổi mức
  protected readonly decided = linkedSignal<AppraisalProfileRow[], Record<string, RatingLevel>>({
    source: this.rows,
    computation: () => ({}),
  });
  protected readonly reasons = linkedSignal<AppraisalProfileRow[], Record<string, string>>({
    source: this.rows,
    computation: () => ({}),
  });
  protected readonly dialog = signal<ChangeDialog | null>(null);
  protected readonly dialogReason = signal('');
  protected readonly dialogTried = signal(false);
  protected readonly dialogError = computed(() =>
    this.dialogTried() ? reasonError(this.dialogReason()) : null,
  );

  // Trường 5: phân bố mức của các hồ sơ đang chọn
  protected readonly dist = computed(() =>
    distribution(this.items().map((r) => this.decisionOf(r))),
  );
  protected readonly excellentShare = computed(() => this.dist()[0].share);
  /** Ngưỡng HTXSNV theo Policy Pack của hồ sơ (`maxExcellentShare`); nhiều khối thì lấy ngưỡng chặt nhất. */
  protected readonly threshold = computed(() => thresholdOf(this.items()));
  protected readonly overLimit = computed(() => this.excellentShare() > this.threshold());
  protected readonly calibrationAck = linkedSignal({
    source: this.overLimit,
    computation: () => false,
  });
  protected readonly blockedHeads = computed(
    () => new Set(headsTooEarly(this.inGroup(), this.selected())),
  );

  // Trường 6, 7: số, ngày quyết định; chứng thư
  protected readonly decisionNo = signal('');
  protected readonly decisionDate = signal(todayVn());
  protected readonly certs = rxResource({ stream: () => this.dossiers.myCertificates() });
  protected readonly cert = computed(() => {
    const list = this.certs.hasValue() ? this.certs.value() : [];
    return list.find((c) => certificateValid(c)) ?? list[0] ?? null;
  });
  protected readonly certInfo = computed(() => {
    const c = this.cert();
    return c ? { ...certificateInfo(c), until: dmy(c.notAfter.slice(0, 10)) } : null;
  });
  protected readonly certOk = computed(() => certificateValid(this.cert()));

  // Trường 8: ký lô
  protected readonly busy = signal<null | 'sign' | 'cancel' | 'export'>(null);
  protected readonly signing = signal(false);
  protected readonly batch = signal<AppraisalBatchDecision | null>(null);
  protected readonly job = signal<Job | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly warning = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly formUrl = signal<string | null>(null);
  protected readonly showAll = signal(false);
  private batchLocation: string | null = null;

  // "Theo dõi tiến độ": lô đang ký của người quyết định, mở lại được sau khi tải lại trang
  protected readonly pending = rxResource({
    params: () => this.reloadKey(),
    stream: () => this.api.batchDecisions(['CHO_KY']),
  });
  protected readonly pendingBatches = computed(() =>
    this.pending.hasValue() && !this.signing() ? this.pending.value().items : [],
  );

  protected readonly signedCount = computed(() => this.batch()?.signedCount ?? 0);
  protected readonly failedCount = computed(() => this.batch()?.failedCount ?? 0);
  protected readonly batchTotal = computed(() => this.batch()?.profileCount ?? this.items().length);
  protected readonly progress = computed(() => {
    const b = this.batch();
    if (b) return Math.round((this.signedCount() / b.profileCount) * 100);
    return this.job()?.progress ?? 0;
  });

  protected readonly errors = computed(() =>
    checkBatch({
      selected: this.items().length,
      decisionNo: this.decisionNo(),
      decisionDate: this.decisionDate(),
      overLimit: this.overLimit(),
      calibrationAcknowledged: this.calibrationAck(),
      headBlocked: this.items().some((r) => this.blockedHeads().has(r.id)),
      certificateValid: this.certOk(),
    }),
  );
  protected readonly errorList = computed(() =>
    this.showAll() ? Object.values(this.errors()) : [],
  );

  protected readonly locked = computed(() => this.signing() || this.busy() === 'sign');
  protected readonly canChange = computed(() => this.can('SCR-0904.doi-muc') && !this.locked());
  protected readonly canSign = computed(() => this.can('SCR-0904.ky-lo'));
  protected readonly canCancel = computed(
    () => this.can('SCR-0904.theo-doi-huy') && this.signing() && this.batch()?.status === 'CHO_KY',
  );

  /** Chế độ "Đảng viên cao tuổi" giảm bước: ẩn cột tự đề xuất, thẩm định; bố cục một cột (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  private readonly reasonBox = viewChild<ElementRef<HTMLTextAreaElement>>('reasonBox');
  private readonly formLink = viewChild<ElementRef<HTMLElement>>('formLink');

  constructor() {
    this.destroyRef.onDestroy(() => {
      const url = this.formUrl();
      if (url) URL.revokeObjectURL(url);
    });
    effect(() => this.reasonBox()?.nativeElement.focus());
    effect(() => this.formLink()?.nativeElement.focus());
  }

  private loadAll(): Observable<AppraisalProfileRow[]> {
    let pages = 0;
    const page = (cursor: string | null) =>
      this.api.profiles({ status: ['CHO_QUYET_DINH'], limit: 200, cursor });
    return page(null).pipe(
      expand((p) => (p.nextCursor && ++pages < MAX_PAGES ? page(p.nextCursor) : EMPTY)),
      reduce((acc, p) => [...acc, ...p.items], [] as AppraisalProfileRow[]),
    );
  }

  protected decisionOf(r: AppraisalProfileRow): RatingLevel | null {
    return this.decided()[r.id] ?? proposedOf(r);
  }

  protected note(r: AppraisalProfileRow): string {
    return noteOf(r, this.decisionOf(r), this.blockedHeads().has(r.id));
  }

  protected toggle(r: AppraisalProfileRow, on: boolean) {
    this.selected.update((s) => {
      const next = new Set(s);
      if (on) next.add(r.id);
      else next.delete(r.id);
      return next;
    });
  }

  protected toggleAll(on: boolean) {
    this.selected.set(on ? new Set(this.visible().map((r) => r.id)) : new Set());
  }

  protected setPage(i: number) {
    this.pageIndex.set(Math.min(Math.max(0, i), this.pageCount() - 1));
  }

  /** Đổi ô "Quyết định": về đúng mức đề xuất thì bỏ lý do; mức khác thì mở hộp thoại ghi lý do. */
  protected changeLevel(r: AppraisalProfileRow, ev: Event) {
    const select = ev.target as HTMLSelectElement;
    const level = select.value as RatingLevel;
    if (level === proposedOf(r)) {
      this.decided.update(({ [r.id]: _, ...rest }) => rest);
      this.reasons.update(({ [r.id]: _, ...rest }) => rest);
      return;
    }
    this.dialogReason.set(this.reasons()[r.id] ?? '');
    this.dialogTried.set(false);
    this.dialog.set({ row: r, level, prev: this.decisionOf(r), select });
  }

  protected confirmChange() {
    const d = this.dialog();
    if (!d) return;
    this.dialogTried.set(true);
    if (reasonError(this.dialogReason())) return;
    this.decided.update((m) => ({ ...m, [d.row.id]: d.level }));
    this.reasons.update((m) => ({ ...m, [d.row.id]: this.dialogReason().trim() }));
    this.closeDialog(d);
  }

  protected cancelChange() {
    const d = this.dialog();
    if (!d) return;
    if (d.prev) d.select.value = d.prev;
    this.closeDialog(d);
  }

  private closeDialog(d: ChangeDialog) {
    this.dialog.set(null);
    afterNextRender(() => d.select.focus(), { injector: this.injector });
  }

  /** "Quyết định và ký lô": kiểm trường, MFA một lần, gửi lô rồi theo dõi job tới khi ký xong. */
  protected async sign() {
    this.showAll.set(true);
    if (!this.canSign() || this.locked()) return;
    const first = Object.keys(this.errors())[0];
    if (first) {
      this.focusField(first);
      return;
    }
    const rows = this.items();
    const cert = this.cert()!;
    this.busy.set('sign');
    this.clearMessages();
    if (!(await this.stepUp(STEP_UP_REASON).catch(() => false))) {
      this.busy.set(null);
      this.error.set(
        'Cần xác thực nhiều lớp (MFA) để ký lô. Bấm "Quyết định và ký lô" để thử lại.',
      );
      return;
    }
    const [periodId, flow] = this.group().split('|');
    try {
      const { location, job } = await firstValueFrom(
        this.api.batchDecide({
          periodId,
          flow: flow as AppraisalProfileRow['flow'],
          decisionNo: this.decisionNo().trim(),
          decisionDate: this.decisionDate(),
          signProvider: signProviderOf(cert),
          calibrationAcknowledged: this.overLimit() && this.calibrationAck(),
          items: rows.map((r) => {
            const level = this.decisionOf(r)!;
            const reason = this.reasons()[r.id];
            return {
              profileId: r.id,
              ratingCode: level,
              rowVersion: r.rowVersion,
              ...(level !== proposedOf(r) && reason ? { reason } : {}),
            };
          }),
        }),
      );
      this.batchLocation = job?.resultLocation ?? null;
      this.batch.set(null);
      this.job.set(job);
      this.signing.set(true);
      this.notice.set(
        `Đã gửi yêu cầu ký lô ${rows.length} hồ sơ. Đồng chí xác nhận một lần trên ứng dụng ký số ` +
          `(${certificateInfo(cert).provider}) cho cả lô.`,
      );
      this.jobs
        .track(location)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (j) => void this.onJob(j),
          error: (e) => {
            this.signing.set(false);
            this.busy.set(null);
            this.error.set(
              `Mất kết nối khi theo dõi tiến độ ký: ${toProblem(e).title ?? ''}. Việc ký vẫn tiếp tục ở máy chủ; vui lòng tải lại trang sau ít phút.`,
            );
          },
        });
    } catch (e) {
      this.busy.set(null);
      const p = toProblem(e);
      this.error.set(
        p.status === 401
          ? 'Phiên xác thực nhiều lớp đã hết hạn. Bấm "Quyết định và ký lô" để xác thực lại.'
          : batchProblemMessage(p),
      );
      if (p.code === 'APPRAISAL-409-VERSION-CONFLICT') this.reload();
      if (p.code === 'APPRAISAL-409-DECISION-NO-DUPLICATE') this.focusField('decisionNo');
    }
  }

  private async onJob(j: Job) {
    this.job.set(j);
    const loc = j.resultLocation ?? this.batchLocation;
    if (loc) {
      this.batchLocation = loc;
      const b = await firstValueFrom(this.api.batchDecision(loc)).catch(() => null);
      if (b) this.batch.set(b);
    }
    if (j.status === 'HOAN_TAT' || j.status === 'THAT_BAI' || j.status === 'DA_HUY') this.finish(j);
  }

  private finish(j: Job) {
    const b = this.batch();
    this.signing.set(false);
    this.busy.set(null);
    this.notice.set(null);
    this.showAll.set(false);
    if (!b) {
      this.error.set(
        `Ký lô không thành công: ${j.problem?.title ?? j.message ?? 'yêu cầu ký bị huỷ'}.`,
      );
    } else if (b.status === 'DA_KY') {
      this.notice.set(msg0916(b.decisionNo, b.signedCount ?? b.profileCount));
      this.decisionNo.set('');
    } else if ((b.failedCount ?? 0) > 0 && j.status === 'HOAN_TAT') {
      this.warning.set(msg0915(b.signedCount ?? 0, b.profileCount, b.failedCount ?? 0));
    } else {
      const signed = b.signedCount ?? 0;
      this.notice.set(
        `Đã huỷ lô quyết định ${b.decisionNo}. ${signed} hồ sơ đã ký giữ nguyên quyết định; ` +
          `${b.profileCount - signed} hồ sơ chưa ký vẫn chờ quyết định.`,
      );
    }
    this.reload();
  }

  /** Mở lại một lô đang ký: hỏi lô theo chu kỳ tới khi ký xong hoặc bị huỷ. */
  protected resume(b: AppraisalBatchDecision) {
    if (this.locked()) return;
    this.clearMessages();
    this.batch.set(b);
    this.job.set(null);
    this.signing.set(true);
    this.busy.set('sign');
    this.batchLocation = `/api/v1/appraisals/batch-decisions/${b.id}`;
    timer(0, RESUME_POLL_MS)
      .pipe(
        switchMap(() => this.api.batchDecision(b.id)),
        takeWhile((x) => x.status === 'CHO_KY', true),
        takeUntilDestroyed(this.destroyRef),
      )
      .subscribe({
        next: (x) => {
          this.batch.set(x);
          if (x.status !== 'CHO_KY')
            this.finish({
              id: x.jobId ?? x.id,
              type: 'ESIGN_BATCH',
              status: x.status === 'HUY' ? 'DA_HUY' : 'HOAN_TAT',
              progress: 100,
            } as Job);
        },
        error: (e) => {
          this.signing.set(false);
          this.busy.set(null);
          this.error.set(`Không tải được tiến độ lô: ${toProblem(e).title ?? ''}.`);
        },
      });
  }

  /** "Huỷ lô": dừng các chữ ký chưa thực hiện, hồ sơ đã ký giữ nguyên. */
  protected async cancelBatch() {
    const b = this.batch();
    if (!b || !this.canCancel()) return;
    this.busy.set('cancel');
    try {
      this.batch.set(await firstValueFrom(this.api.cancelBatch(b)));
      this.notice.set('Đang dừng ký lô. Các hồ sơ đã ký giữ nguyên quyết định.');
    } catch (e) {
      this.error.set(batchProblemMessage(toProblem(e)));
    } finally {
      if (this.busy() === 'cancel') this.busy.set(this.signing() ? 'sign' : null);
    }
  }

  /** "Xuất Mẫu 3": sinh bản nháp từ các hồ sơ đang chọn (tối đa 200), khi xong hiện liên kết tải. */
  protected async exportForm() {
    const rows = this.items();
    this.clearMessages();
    if (!rows.length) {
      this.error.set('Chọn ít nhất một hồ sơ để xuất Mẫu 3.');
      return;
    }
    if (rows.length > MAX_FORM) {
      this.error.set(
        'Mẫu 3 xuất tối đa 200 hồ sơ mỗi lần. Vui lòng lọc theo đơn vị rồi xuất từng phần.',
      );
      return;
    }
    this.busy.set('export');
    try {
      const { location } = await firstValueFrom(
        this.dossiers.draftForms({
          formCode: 'MAU_3',
          periodId: rows[0].period.id,
          profileIds: rows.map((r) => r.id),
        }),
      );
      this.jobs
        .track(location)
        .pipe(takeUntilDestroyed(this.destroyRef))
        .subscribe({
          next: (j) => {
            if (j.status === 'HOAN_TAT' && j.resultLocation) void this.fetchForm(j.resultLocation);
            else if (j.status === 'THAT_BAI' || j.status === 'DA_HUY') {
              this.busy.set(null);
              this.error.set(`Không xuất được Mẫu 3: ${j.problem?.title ?? j.message ?? ''}`);
            }
          },
          error: (e) => {
            this.busy.set(null);
            this.error.set(batchProblemMessage(toProblem(e)));
          },
        });
    } catch (e) {
      this.busy.set(null);
      this.error.set(batchProblemMessage(toProblem(e)));
    }
  }

  private async fetchForm(resultLocation: string) {
    try {
      const blob = await firstValueFrom(this.dossiers.formFile(formIdOf(resultLocation)));
      const old = this.formUrl();
      if (old) URL.revokeObjectURL(old);
      this.formUrl.set(URL.createObjectURL(blob));
    } catch (e) {
      this.error.set(batchProblemMessage(toProblem(e)));
    } finally {
      this.busy.set(null);
    }
  }

  protected msg0913() {
    return msg0913(this.excellentShare(), this.threshold());
  }

  protected withinLimit() {
    return withinLimit(this.excellentShare(), this.threshold());
  }

  protected fieldError(name: string): string | null {
    return this.showAll() ? (this.errors()[name] ?? null) : null;
  }

  private focusField(name: string) {
    afterNextRender(() => document.getElementById(`bd-${name}`)?.focus(), {
      injector: this.injector,
    });
  }

  private clearMessages() {
    this.notice.set(null);
    this.warning.set(null);
    this.error.set(null);
  }

  private reload() {
    this.reloadKey.update((n) => n + 1);
  }
}
