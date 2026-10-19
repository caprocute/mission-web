/*
 * SCR-0901 Phiếu tự đánh giá (QT-09 → SCR-0901 → /api/v1/appraisals → MOD-04 → EVT-11; WEB-035).
 * Tác nhân AC-06 `member` (ghi khi là chủ hồ sơ và hồ sơ ở `TU_DANH_GIA`); `manager` xem chỉ đọc.
 *
 * Trường (TL-02b SCR-0901):
 *  1 Luồng đánh giá (thẻ DANG / NHA_NUOC, chỉ luồng tenant bật)   8 Mức hệ thống tham khảo (DMN, BR-09-06..10)
 *  2 Tiêu chí chung 9 dòng, mặc định Đáp ứng                     9 Mức tự đề xuất, mặc định bằng trường 8
 *  3 Tỷ lệ đáp ứng (tính trên client)                            10 Giải pháp đột phá: bắt buộc khi HTXSNV, ≤ 2.000, kèm minh chứng
 *  4 Mức tiêu chí chung (BR-09-05)                               11 Giải trình khác mức tham khảo: 20..2.000 khi 9 ≠ 8
 *  5 Bảng nhiệm vụ SL duyệt/KH, B, C, KPI (chỉ đọc)              12 Ưu điểm, hạn chế, hướng khắc phục: bắt buộc, ≤ 2.000, C2, tự lưu 30 giây
 *  6 KPI nhóm, điểm nhóm (BR-09-03)                              13 Hạn nộp, số ngày làm việc còn lại
 *  7 Điểm kết quả nhiệm vụ
 * Trường 9 đến 12 là biểu mẫu động Mẫu 1B (`<vt-form>`, GET /ui/forms/mau-1b/current).
 * Chức năng: Xem trước Mẫu 1B (POST /dossiers/forms/preview); Lưu nháp (PUT .../self-assessment, If-Match);
 *  Vết tính (GET .../score-trace); Chuyển thẻ luồng (GET .../scores?flow=); Ký số và nộp (POST .../submit 202,
 *  yêu cầu ký tạo ở SVC-10, chờ EVT-18 → CHO_THAM_DINH), chỉ bật khi đủ trường bắt buộc, cần MFA (AAL2).
 * Quy tắc: BR-09-01..07, 10, 11 (Policy Pack, máy chủ tính), BR-09-05 (quy đổi tức thời), BR-09-12 (giải
 *  trình), BR-09-15 (ký số), BR-09-16 (không sửa điểm).
 * Thông báo: MSG-09-01 (đang tính), MSG-09-02 (khác mức), MSG-09-03 (thiếu đột phá), MSG-09-04 (nộp xong),
 *  MSG-09-05 (ký lỗi), MSG-09-07 (xung đột cập nhật, 409).
 */
import {
  Component,
  DestroyRef,
  ElementRef,
  InjectionToken,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import {
  APPRAISAL_FLOW_LABEL,
  APPRAISAL_STATUS_LABEL,
  AppraisalApi,
  AppraisalFlow,
  AppraisalProfile,
  AppraisalSelfAssessmentInput,
  EvidenceApi,
  JobTracker,
  RatingLevel,
  UiFormApi,
  toProblem,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { firstValueFrom, map } from 'rxjs';
import { APPRAISAL_CAN } from '../../appraisal-permissions';
import { GeneralCriteria } from './general-criteria';
import { RatingTrail } from './rating-trail';
import { ScoreResult } from './score-result';
import { ScoreTrace } from './score-trace';
import { SelfRating } from './self-rating';
import {
  SELF_FORM_KEY,
  STEPS,
  SelfForm,
  checkSelfForm,
  dmy,
  formRules,
  stepOf,
  vnTime,
} from './self-assessment-rules';
import { APPRAISAL_STEP_UP } from './step-up';

/** Chu kỳ tự lưu nháp (trường 12: 30 giây một lần); kiểm thử thay bằng giá trị nhỏ. */
export const SELF_AUTOSAVE_MS = new InjectionToken<number>('vt.fe-appraisal.autosave', {
  providedIn: 'root',
  factory: () => 30_000,
});

const MSG_09_07 =
  'Hồ sơ vừa được người khác cập nhật. Vui lòng tải lại để xem thay đổi trước khi lưu.';

type Tab = AppraisalFlow | 'LICH_SU';

@Component({
  selector: 'vt-self-assessment-page',
  imports: [RouterLink, GeneralCriteria, ScoreResult, ScoreTrace, SelfRating, RatingTrail],
  templateUrl: './self-assessment.page.html',
  styleUrl: './self-assessment.page.css',
})
export class SelfAssessmentPage {
  /** `:id` của tuyến `tu-danh-gia/:id` (quản lý xem hồ sơ cấp dưới); trống là hồ sơ của tôi. */
  readonly id = input<string>();
  /** `?flow=` chọn thẻ luồng. */
  readonly flow = input<AppraisalFlow>();

  private readonly api = inject(AppraisalApi);
  private readonly evidenceApi = inject(EvidenceApi);
  private readonly uiForms = inject(UiFormApi);
  private readonly jobs = inject(JobTracker);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly theme = inject(ThemeService);
  private readonly stepUp = inject(APPRAISAL_STEP_UP);
  protected readonly can = inject(APPRAISAL_CAN);

  protected readonly steps = STEPS;
  protected readonly flowLabel = APPRAISAL_FLOW_LABEL;
  protected readonly statusLabel = APPRAISAL_STATUS_LABEL;
  protected readonly dmy = dmy;

  protected readonly mine = rxResource({
    stream: () => this.api.myProfiles().pipe(map((p) => p.items)),
  });

  /** Hồ sơ đang mở: theo `:id`, hoặc hồ sơ của tôi ở luồng `?flow=` (mặc định luồng đầu). */
  protected readonly profileId = computed(() => {
    const id = this.id();
    if (id) return id;
    if (!this.mine.hasValue()) return undefined;
    const rows = this.mine.value();
    return (rows.find((r) => r.flow === this.flow()) ?? rows[0])?.id;
  });

  protected readonly loaded = rxResource({
    params: () => this.profileId(),
    stream: ({ params }) => this.api.profile(params),
  });
  /** Bản hồ sơ hiện hành; thay bằng phản hồi của lưu nháp để giữ `rowVersion` mới nhất. */
  protected readonly profile = linkedSignal<AppraisalProfile | undefined>(() =>
    this.loaded.hasValue() ? this.loaded.value() : undefined,
  );

  protected readonly isMine = computed(() => {
    const p = this.profile();
    return !!p && this.mine.hasValue() && this.mine.value().some((r) => r.id === p.id);
  });

  /** Ảnh chụp điểm của luồng hồ sơ (mức tham khảo trường 8). */
  protected readonly own = rxResource({
    params: () => this.profile()?.id,
    stream: ({ params }) => this.api.scores(params),
  });

  /** Thẻ đang chọn; thẻ luồng khác luồng hồ sơ (khi xem theo `:id`) tải ảnh chụp luồng kia. */
  protected readonly tab = linkedSignal<Tab>(() => this.flow() ?? this.profile()?.flow ?? 'DANG');
  private readonly other = rxResource({
    params: () => {
      const p = this.profile();
      const t = this.tab();
      return p && t !== 'LICH_SU' && t !== p.flow ? { id: p.id, flow: t } : undefined;
    },
    stream: ({ params }) => this.api.scores(params.id, params.flow),
  });
  protected readonly shownScores = computed(() => {
    const p = this.profile();
    const r = p && this.tab() !== p.flow ? this.other : this.own;
    return r.hasValue() ? r.value() : undefined;
  });
  protected readonly scoresError = computed(() => {
    const p = this.profile();
    const err = (p && this.tab() !== p.flow ? this.other : this.own).error();
    if (!err) return null;
    const pr = toProblem(err);
    return pr.code === 'APPRAISAL-409-NOT-COMPUTED'
      ? 'Kết quả quý của đồng chí đang được tính, vui lòng quay lại sau.'
      : (pr.title ?? 'Không tải được kết quả tính điểm.');
  });

  protected readonly refRating = computed<RatingLevel | undefined>(() =>
    this.own.hasValue() ? this.own.value().snapshot.autoRatingCode : undefined,
  );

  /** Biểu mẫu động Mẫu 1B đang áp dụng (trường 9 đến 12). */
  protected readonly form = rxResource({ stream: () => this.uiForms.current(SELF_FORM_KEY) });
  private readonly rules = computed(() =>
    this.form.hasValue() ? formRules(this.form.value()) : [],
  );

  // Biểu mẫu (trường 2, 9 đến 12), khởi tạo từ phiếu đã lưu
  private readonly sa = computed(() => this.profile()?.selfAssessment ?? null);
  protected readonly criteria = linkedSignal(() =>
    (this.profile()?.generalCriteria?.find((g) => g.stage === 'TU_DANH_GIA')?.items ?? []).map(
      (c) => ({ ...c }),
    ),
  );
  protected readonly self = linkedSignal<SelfForm>(() => {
    const sa = this.sa();
    return {
      selfRatingCode: sa?.selfRatingCode ?? this.refRating() ?? 'HTTNV',
      breakthrough: sa?.breakthrough ?? '',
      reason: sa?.reason ?? '',
      content: sa?.content ?? '',
    };
  });
  protected readonly evidenceIds = linkedSignal(() => this.sa()?.breakthroughEvidenceIds ?? []);
  protected readonly dirty = linkedSignal({ source: this.profile, computation: () => false });

  protected readonly evidences = rxResource({
    params: () => (this.self().selfRatingCode === 'HTXSNV' ? true : undefined),
    stream: () => this.evidenceApi.list({ status: ['DA_XAC_NHAN'] }).pipe(map((p) => p.items)),
  });

  protected readonly errors = computed(() => {
    const ref = this.refRating();
    if (!ref) return {};
    return checkSelfForm(this.rules(), this.self(), {
      mucThamKhao: ref,
      soMinhChung: this.evidenceIds().length,
    });
  });
  protected readonly missing = computed(() => Object.values(this.errors()));

  protected readonly busy = signal<null | 'save' | 'submit' | 'preview'>(null);
  protected readonly signing = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly savedAt = signal<string | null>(null);
  protected readonly previewUrl = signal<string | null>(null);
  protected readonly traceOpen = signal(false);

  protected readonly editable = computed(() => {
    const p = this.profile();
    return (
      !!p &&
      this.isMine() &&
      p.status === 'TU_DANH_GIA' &&
      this.can('SCR-0901.luu') &&
      !this.signing()
    );
  });
  protected readonly canSign = computed(
    () =>
      this.editable() && this.can('SCR-0901.ky-nop') && !!this.refRating() && this.form.hasValue(),
  );

  protected readonly trace = rxResource({
    params: () => {
      const p = this.profile();
      const t = this.tab();
      return this.traceOpen() && p ? { id: p.id, flow: t === 'LICH_SU' ? p.flow : t } : undefined;
    },
    stream: ({ params }) => this.api.scoreTrace(params.id, { flow: params.flow }),
  });
  protected readonly taskTitles = computed(() => {
    const s = this.shownScores();
    return Object.fromEntries(
      (s?.snapshot.groups ?? []).flatMap((g) => (g.tasks ?? []).map((t) => [t.taskId, t.title])),
    );
  });

  /** Chế độ "Đảng viên cao tuổi" giảm bước: một cột, ẩn vết tính, lưu nháp tay và lịch sử (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );
  protected readonly stepIndex = computed(() => stepOf(this.profile()?.status ?? 'TU_DANH_GIA'));
  protected readonly tabs = computed<Tab[]>(() => [
    ...(this.profile()?.availableFlows ?? []),
    ...(this.simple() ? [] : (['LICH_SU'] as Tab[])),
  ]);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly previewLink = viewChild<ElementRef<HTMLElement>>('previewLink');

  constructor() {
    const ms = inject(SELF_AUTOSAVE_MS);
    const timer = setInterval(() => {
      if (this.editable() && this.dirty() && !this.busy()) void this.save(true);
    }, ms);
    inject(DestroyRef).onDestroy(() => {
      clearInterval(timer);
      const url = this.previewUrl();
      if (url) URL.revokeObjectURL(url);
    });
    effect(() => this.previewLink()?.nativeElement.focus());
  }

  protected tabLabel(t: Tab): string {
    if (t === 'LICH_SU') return 'Lịch sử';
    const scale = t === 'DANG' ? 'thang 100 điểm' : 'thang 30/70';
    return `${this.flowLabel[t]} · ${scale}`;
  }

  protected selectTab(t: Tab) {
    this.tab.set(t);
    if (t !== 'LICH_SU' && !this.id() && this.mine.hasValue()) {
      const target = this.mine.value().find((r) => r.flow === t);
      if (target && target.id !== this.profile()?.id) {
        void this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { flow: t },
          queryParamsHandling: 'merge',
        });
      }
    }
  }

  /** Mũi tên trái, phải, Home, End giữa các thẻ (mẫu tablist WAI-ARIA). */
  protected tabKey(e: KeyboardEvent, i: number) {
    const n = this.tabs().length;
    const next =
      e.key === 'ArrowRight'
        ? (i + 1) % n
        : e.key === 'ArrowLeft'
          ? (i - 1 + n) % n
          : e.key === 'Home'
            ? 0
            : e.key === 'End'
              ? n - 1
              : -1;
    if (next < 0) return;
    e.preventDefault();
    this.selectTab(this.tabs()[next]);
    const list = (e.currentTarget as HTMLElement).parentElement;
    list?.querySelectorAll<HTMLElement>('[role="tab"]')[next]?.focus();
  }

  protected toggle(code: string, met: boolean) {
    this.criteria.update((cs) => cs.map((c) => (c.criteriaCode === code ? { ...c, met } : c)));
    this.markEdited();
  }

  protected edited(v: SelfForm) {
    this.self.set(v);
    this.markEdited();
  }

  protected markEdited() {
    this.dirty.set(true);
    this.notice.set(null);
  }

  protected closeTrace() {
    this.traceOpen.set(false);
    this.host.nativeElement.querySelector<HTMLElement>('vt-score-result .link')?.focus();
  }

  private body(): AppraisalSelfAssessmentInput {
    const f = this.self();
    const top = f.selfRatingCode === 'HTXSNV';
    return {
      criteria: this.criteria().map((c) => ({ criteriaCode: c.criteriaCode, met: c.met })),
      selfRatingCode: f.selfRatingCode,
      breakthrough: top ? f.breakthrough.trim() : '',
      breakthroughEvidenceIds: top ? this.evidenceIds() : [],
      reason: f.selfRatingCode !== this.refRating() ? f.reason.trim() : '',
      content: f.content.trim(),
    };
  }

  /** "Lưu nháp" hoặc tự lưu (`silent`): trả `false` khi lỗi. */
  protected async save(silent = false): Promise<boolean> {
    const p = this.profile();
    if (!p) return false;
    this.busy.set('save');
    if (!silent) this.error.set(null);
    try {
      const saved = await firstValueFrom(this.api.saveSelfAssessment(p, this.body()));
      const keep = {
        criteria: this.criteria(),
        self: this.self(),
        evidenceIds: this.evidenceIds(),
      };
      this.profile.set(saved);
      // giữ nguyên nội dung đang gõ (máy chủ đã nhận đúng nội dung này)
      this.criteria.set(keep.criteria);
      this.self.set(keep.self);
      this.evidenceIds.set(keep.evidenceIds);
      const at = vnTime(saved.selfAssessment?.savedAt ?? new Date().toISOString()).slice(0, 5);
      this.savedAt.set(at);
      if (!silent) this.notice.set(`Đã lưu nháp phiếu tự đánh giá lúc ${at}.`);
      return true;
    } catch (e) {
      this.error.set(this.message(e));
      return false;
    } finally {
      this.busy.set(null);
    }
  }

  /** "Xem trước Mẫu 1B": lưu nháp nếu có thay đổi để bản xem trước đúng dữ liệu đang nhập. */
  protected async preview() {
    const p = this.profile();
    if (!p) return;
    if (this.editable() && this.dirty() && !(await this.save(true))) return;
    this.busy.set('preview');
    this.error.set(null);
    try {
      const blob = await firstValueFrom(
        this.api.previewForm({ formCode: 'MAU_1B', sourceModule: 'appraisal', sourceRefId: p.id }),
      );
      const old = this.previewUrl();
      if (old) URL.revokeObjectURL(old);
      this.previewUrl.set(URL.createObjectURL(blob));
    } catch (e) {
      this.error.set(this.message(e));
    } finally {
      this.busy.set(null);
    }
  }

  /** "Ký số và nộp": lưu, MFA, gửi yêu cầu ký rồi theo dõi job tới khi EVT-18 về (BR-09-15). */
  protected async submit() {
    if (!this.canSign() || this.missing().length) return;
    this.busy.set('submit');
    this.error.set(null);
    this.notice.set(null);
    if (!(await this.save(true))) {
      this.busy.set(null);
      return;
    }
    if (!(await this.stepUp().catch(() => false))) {
      this.busy.set(null);
      this.error.set('Cần xác thực nhiều lớp (MFA) để ký số. Bấm "Ký số và nộp" để thử lại.');
      return;
    }
    this.busy.set('submit');
    try {
      const { location } = await firstValueFrom(
        this.api.submit(this.profile()!, { signProvider: 'MYSIGN', formCode: 'MAU_1B' }),
      );
      this.signing.set(true);
      this.notice.set('Đã gửi yêu cầu ký. Đồng chí xác nhận ký trên ứng dụng ký số (MySign).');
      this.jobs.track(location).subscribe({
        next: (job) => {
          if (job.status === 'HOAN_TAT') this.signed();
          else if (job.status === 'THAT_BAI' || job.status === 'DA_HUY')
            this.signFailed(job.problem?.title ?? job.message ?? 'yêu cầu ký bị huỷ');
        },
        error: (e) => this.signFailed(toProblem(e).title ?? 'mất kết nối tới dịch vụ ký'),
      });
    } catch (e) {
      this.error.set(this.message(e));
      this.busy.set(null);
    }
  }

  private async signed() {
    const p = await firstValueFrom(this.api.profile(this.profile()!.id)).catch(() => null);
    if (p) this.profile.set(p);
    this.mine.reload();
    this.signing.set(false);
    this.busy.set(null);
    const at = p?.selfAssessment?.signedAt ?? new Date().toISOString();
    this.notice.set(
      `Đã nộp phiếu tự đánh giá và ký số thành công lúc ${vnTime(at)}. Phiếu đã chuyển sang bước thẩm định.`,
    );
  }

  private signFailed(reason: string) {
    this.signing.set(false);
    this.busy.set(null);
    this.notice.set(null);
    const r = reason.charAt(0).toLowerCase() + reason.slice(1).replace(/\.$/, '');
    this.error.set(
      `Ký số không thành công: ${r}. Phiếu chưa được nộp, dữ liệu đã nhập vẫn được giữ.`,
    );
  }

  private message(e: unknown): string {
    const p = toProblem(e);
    if (p.status === 409 && p.code === 'APPRAISAL-409-VERSION-CONFLICT') return MSG_09_07;
    if (p.status === 409) return 'Hồ sơ không còn ở bước tự đánh giá. Vui lòng tải lại trang.';
    if (p.status === 403) return 'Đồng chí không có quyền sửa phiếu tự đánh giá này.';
    if (p.status === 422 && p.errors?.length) return p.errors.map((x) => x.message).join(' ');
    return [p.title, p.detail].filter(Boolean).join(' ');
  }
}
