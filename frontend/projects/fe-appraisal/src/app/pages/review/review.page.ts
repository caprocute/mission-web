/*
 * SCR-0902 Thẩm định (QT-09 bước 7 đến 9 → SCR-0902 → /api/v1/appraisals → MOD-04, SVC-06 → EVT-11; WEB-036).
 * Tác nhân AC-07 `appraiser` trong phạm vi ABAC cây tổ chức (Cd); không thẩm định hồ sơ của chính mình
 * hoặc hồ sơ mình đã nghiệm thu (BR-09-14, OPA ở máy chủ).
 *
 * Trường (TL-02b SCR-0902):
 *  1 Bộ lọc hàng đợi (trạng thái), mặc định Chờ thẩm định       5 Bảng so sánh B, C tự nhận và thẩm định, tô đỏ khi khác
 *  2 Dòng hàng đợi: tên, chức vụ, điểm, mức; sắp theo hạn,      6 Mức thẩm định (4 giá trị), mặc định bằng mức hệ thống;
 *    phân trang con trỏ ≤ 200                                     khác mức tự đề xuất thì bật trường 8
 *  3 Thông tin nộp và chữ ký (thời điểm, nhà cung cấp, băm)      7 Nhận xét thẩm định: bắt buộc, ≤ 4.000, C2
 *  4 Điểm tự tính, điểm sau thẩm định (1 chữ số thập phân)      8 Lý do khác mức tự đề xuất: 20..2.000 khi khác (BR-09-12)
 * Chức năng: Chọn hồ sơ (GET .../profiles/{id}); Mở minh chứng (SCR-0701 theo nhiệm vụ, máy chủ ghi nhật ký
 *  C2); Trả lại bổ sung (POST .../return, bắt buộc lý do, hồ sơ CHO_THAM_DINH); Ký số và chuyển đề xuất
 *  (POST .../appraisal 202, MFA AAL2, chờ EVT-18 → DA_THAM_DINH); Xuất danh sách (POST /appraisals/exports
 *  202, không gồm trường C2).
 * Quy tắc: BR-09-12 (giải trình khác mức), BR-09-14 (tách vai trò), BR-09-15 (ký số), dấu hiệu bất thường
 *  số 1 của QT-14 (gợi ý, không đổi kết quả).
 * Thông báo: MSG-09-06 (khác mức), MSG-09-07 (409 xung đột), MSG-09-08 (OPA từ chối), MSG-09-09 (trả lại xong).
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
  input,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  AppraisalApi,
  AppraisalProfile,
  AppraisalProfileRow,
  AppraisalProfileStatus,
  AppraisalSelfAssessmentView,
  GENERAL_CRITERIA_LEVEL_LABEL,
  JobTracker,
  RATING_LEVELS,
  APPRAISAL_PROFILE_STATUS_LABEL,
  RATING_LEVEL_LABEL,
  RatingLevel,
  toProblem,
} from '@vt/api-client';
import { permittedOn } from '@vt/auth';
import { ThemeService } from '@vt/design-system';
import { firstValueFrom } from 'rxjs';
import { APPRAISAL_CAN } from '../../appraisal-permissions';
import { STEPS, dmy, stepOf, vn, vnTime } from '../self-assessment/self-assessment-rules';
import { APPRAISAL_STEP_UP } from '../self-assessment/step-up';
import {
  MSG_09_06,
  MSG_09_07,
  MSG_09_08,
  QUEUE_FILTERS,
  checkReview,
  compareRows,
  msg0909,
  returnReasonError,
  selfLevel,
  shortHash,
} from './review-rules';

const STEP_UP_REASON =
  'Ký kết quả thẩm định là mốc ký số; vui lòng xác thực nhiều lớp trước khi ký.';
const PROVIDER_LABEL: Partial<Record<string, string>> = {
  MYSIGN: 'MySign',
  VIETTEL_CA: 'Viettel-CA',
  CA_CHINH_PHU: 'CA Chính phủ',
  BAN_CO_YEU: 'Ban Cơ yếu',
};

@Component({
  selector: 'vt-review-page',
  imports: [RouterLink],
  templateUrl: './review.page.html',
  styleUrl: './review.page.css',
})
export class ReviewPage {
  /** `:id` của tuyến `tham-dinh/:id`; trống thì mở hồ sơ đầu hàng đợi. */
  readonly id = input<string>();
  /** Kỳ của hàng đợi qua `?periodId=`, ví dụ kỳ năm khi mở từ "Chuyển thẩm định năm" của SCR-1001. */
  readonly periodId = input<string>();

  private readonly api = inject(AppraisalApi);
  private readonly jobs = inject(JobTracker);
  private readonly theme = inject(ThemeService);
  private readonly stepUp = inject(APPRAISAL_STEP_UP);
  private readonly injector = inject(Injector);
  protected readonly can = inject(APPRAISAL_CAN);

  protected readonly filters = QUEUE_FILTERS;
  protected readonly levels = RATING_LEVELS;
  protected readonly levelLabel = RATING_LEVEL_LABEL;
  protected readonly statusLabel = APPRAISAL_PROFILE_STATUS_LABEL;
  protected readonly criteriaLabel = GENERAL_CRITERIA_LEVEL_LABEL;
  protected readonly steps = STEPS;
  protected readonly msg0906 = MSG_09_06;
  protected readonly dmy = dmy;
  protected readonly vn = vn;
  protected readonly vnTime = vnTime;
  protected readonly shortHash = shortHash;
  protected readonly providerLabel = PROVIDER_LABEL;

  // Vùng 1: hàng đợi (trường 1, 2)
  protected readonly status = signal<AppraisalProfileStatus>('CHO_THAM_DINH');
  private readonly cursor = signal<string | null>(null);
  protected readonly page = rxResource({
    params: () => ({ status: this.status(), cursor: this.cursor(), periodId: this.periodId() }),
    stream: ({ params }) =>
      this.api.profiles({
        status: [params.status],
        periodId: params.periodId,
        cursor: params.cursor,
        limit: 50,
      }),
  });
  /** Các dòng đã tải, nối thêm khi bấm "Tải thêm". */
  protected readonly rows = linkedSignal<
    { items: AppraisalProfileRow[] | null; cursor: string | null },
    AppraisalProfileRow[]
  >({
    source: () => ({
      items: this.page.hasValue() ? this.page.value().items : null,
      cursor: this.cursor(),
    }),
    computation: (src, prev) => {
      if (!src.items) return prev?.value ?? [];
      if (!src.cursor || !prev) return src.items;
      const seen = new Set(prev.value.map((r) => r.id));
      return [...prev.value, ...src.items.filter((r) => !seen.has(r.id))];
    },
  });
  protected readonly nextCursor = computed(() =>
    this.page.hasValue() ? this.page.value().nextCursor : null,
  );
  protected readonly dueDate = computed(() => this.rows().find((r) => r.dueDate)?.dueDate);

  protected readonly selectedId = computed(() => this.id() ?? this.rows()[0]?.id);

  protected readonly loaded = rxResource({
    params: () => this.selectedId(),
    stream: ({ params }) => this.api.profile(params),
  });
  protected readonly profile = linkedSignal<AppraisalProfile | undefined>(() =>
    this.loaded.hasValue() ? this.loaded.value() : undefined,
  );
  protected readonly scores = rxResource({
    params: () => this.profile()?.id,
    stream: ({ params }) => this.api.scores(params),
  });
  protected readonly view = computed(() => (this.scores.hasValue() ? this.scores.value() : null));

  // Vùng 2: thẻ số liệu (trường 3, 4)
  protected readonly self = computed(() => {
    const p = this.profile();
    return p ? selfLevel(p) : null;
  });
  protected readonly claimedScore = computed(
    () => (this.profile()?.selfAssessment as AppraisalSelfAssessmentView | undefined)?.claimedScore,
  );
  protected readonly scoreDiff = computed(() => {
    const v = this.view();
    const c = this.claimedScore();
    return v && c !== undefined ? Math.round((v.snapshot.totalScore - c) * 10) / 10 : null;
  });
  protected readonly criteria = computed(() => {
    const g = this.profile()?.generalCriteria?.find((x) => x.stage === 'TU_DANH_GIA');
    return g ? { ...g, met: g.items.filter((i) => i.met).length } : null;
  });
  protected readonly auto = computed<RatingLevel | undefined>(
    () => this.view()?.snapshot.autoRatingCode,
  );
  protected readonly stepIndex = computed(() => stepOf(this.profile()?.status ?? 'CHO_THAM_DINH'));

  // Vùng 3: so sánh (trường 5)
  protected readonly compare = computed(() => {
    const p = this.profile();
    const v = this.view();
    return p && v ? compareRows(v, p) : [];
  });

  // Vùng 4: kết luận (trường 6 đến 8)
  protected readonly rating = linkedSignal<RatingLevel>(() => this.auto() ?? 'HTTNV');
  protected readonly comment = linkedSignal({ source: this.profile, computation: () => '' });
  protected readonly reason = linkedSignal({ source: this.profile, computation: () => '' });
  protected readonly differs = computed(() => !!this.self() && this.rating() !== this.self());
  protected readonly errors = computed(() =>
    checkReview(
      { rating: this.rating(), comment: this.comment(), reason: this.reason() },
      this.self(),
    ),
  );
  protected readonly showAll = linkedSignal({ source: this.profile, computation: () => false });
  protected readonly touched = linkedSignal<AppraisalProfile | undefined, Record<string, boolean>>({
    source: this.profile,
    computation: () => ({}),
  });

  protected readonly returnOpen = linkedSignal({ source: this.profile, computation: () => false });
  protected readonly returnReason = linkedSignal({ source: this.profile, computation: () => '' });
  protected readonly returnTried = linkedSignal({ source: this.profile, computation: () => false });
  protected readonly returnError = computed(() => returnReasonError(this.returnReason()));

  protected readonly busy = signal<null | 'sign' | 'return' | 'export'>(null);
  protected readonly signing = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly exportUrl = signal<string | null>(null);

  /**
   * Vai trò có quyền và máy chủ cho phép trên hồ sơ (YC-PT-21): người được đánh giá, người đã
   * nghiệm thu minh chứng và người quyết định không thẩm định (BR-09-14).
   */
  protected allowed(key: 'SCR-0902.ky' | 'SCR-0902.tra-lai'): boolean {
    return this.can(key) && permittedOn(this.profile(), key);
  }

  protected readonly reviewable = computed(
    () => this.profile()?.status === 'CHO_THAM_DINH' && !this.signing(),
  );
  protected readonly canSign = computed(
    () => this.reviewable() && this.allowed('SCR-0902.ky') && !!this.auto(),
  );
  protected readonly canReturn = computed(
    () => this.reviewable() && this.allowed('SCR-0902.tra-lai'),
  );
  /** Kết quả thẩm định đã có (hồ sơ đã qua bước 2). */
  protected readonly appraised = computed(() =>
    this.profile()?.ratingTrail.find((r) => r.levelKind === 'THAM_DINH' && r.isCurrent),
  );

  /** Chế độ "Đảng viên cao tuổi" giảm bước: một cột, ẩn bảng so sánh chi tiết và xuất (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  private readonly exportLink = viewChild<ElementRef<HTMLElement>>('exportLink');
  private readonly returnBox = viewChild<ElementRef<HTMLTextAreaElement>>('returnBox');

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      const url = this.exportUrl();
      if (url) URL.revokeObjectURL(url);
    });
    effect(() => this.exportLink()?.nativeElement.focus());
    effect(() => this.returnBox()?.nativeElement.focus());
  }

  protected setStatus(v: string) {
    this.cursor.set(null);
    this.status.set(v as AppraisalProfileStatus);
  }

  protected loadMore() {
    const next = this.nextCursor();
    if (next) this.cursor.set(next);
  }

  protected touch(field: string) {
    this.touched.update((t) => ({ ...t, [field]: true }));
  }

  protected shown(field: string): string | null {
    return this.showAll() || this.touched()[field] ? (this.errors()[field] ?? null) : null;
  }

  protected rowSelected(r: AppraisalProfileRow): boolean {
    return r.id === this.selectedId();
  }

  /** "Ký số và chuyển đề xuất": kiểm trường, MFA, gửi kết quả rồi theo dõi job tới khi EVT-18 về. */
  protected async sign() {
    this.showAll.set(true);
    const p = this.profile();
    if (!p || !this.canSign() || Object.keys(this.errors()).length) return;
    this.busy.set('sign');
    this.error.set(null);
    this.notice.set(null);
    if (!(await this.stepUp(STEP_UP_REASON).catch(() => false))) {
      this.busy.set(null);
      this.error.set('Cần xác thực nhiều lớp để ký số. Bấm "Ký số và chuyển đề xuất" để thử lại.');
      return;
    }
    try {
      const { location } = await firstValueFrom(
        this.api.appraise(p, {
          ratingCode: this.rating(),
          comment: this.comment().trim(),
          ...(this.differs() ? { reason: this.reason().trim() } : {}),
          signProvider: 'MYSIGN',
        }),
      );
      this.signing.set(true);
      this.notice.set('Đã gửi yêu cầu ký. Đồng chí xác nhận ký trên ứng dụng ký số (MySign).');
      this.jobs.track(location).subscribe({
        next: (job) => {
          if (job.status === 'HOAN_TAT') void this.signed(p);
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

  private async signed(before: AppraisalProfile) {
    const p = await firstValueFrom(this.api.profile(before.id)).catch(() => null);
    if (p) this.profile.set(p);
    this.signing.set(false);
    this.busy.set(null);
    this.notice.set(
      `Đã ký số kết quả thẩm định hồ sơ của đồng chí ${before.person.name}. Hồ sơ chuyển sang bước đề xuất.`,
    );
    this.reloadQueue();
  }

  private signFailed(reason: string) {
    this.signing.set(false);
    this.busy.set(null);
    this.notice.set(null);
    const r = reason.charAt(0).toLowerCase() + reason.slice(1).replace(/\.$/, '');
    this.error.set(
      `Ký số không thành công: ${r}. Kết quả thẩm định chưa được ghi, nội dung đã nhập vẫn được giữ.`,
    );
  }

  protected openReturn() {
    this.returnOpen.set(true);
    this.error.set(null);
  }

  protected cancelReturn() {
    this.returnOpen.set(false);
    // nút "Trả lại bổ sung" chỉ hiện lại sau khi vẽ xong
    afterNextRender(() => document.getElementById('btn-return')?.focus(), {
      injector: this.injector,
    });
  }

  /** "Trả lại bổ sung": bắt buộc lý do; thành công hiện MSG-09-09 theo số lần máy chủ trả về. */
  protected async confirmReturn() {
    this.returnTried.set(true);
    const p = this.profile();
    if (!p || this.returnError() || !this.canReturn()) return;
    this.busy.set('return');
    this.error.set(null);
    this.notice.set(null);
    try {
      const after = await firstValueFrom(this.api.returnProfile(p, this.returnReason().trim()));
      this.profile.set(after);
      this.notice.set(
        msg0909(
          after.person.name,
          after.returnCount ?? 1,
          after.returnLimit ?? after.returnCount ?? 1,
        ),
      );
      this.reloadQueue();
    } catch (e) {
      this.error.set(this.message(e));
    } finally {
      this.busy.set(null);
    }
  }

  /** "Xuất danh sách": tác vụ 202, khi xong hiện liên kết tải tệp XLSX (không gồm trường C2). */
  protected async exportList() {
    const period = this.profile()?.period.id ?? this.rows()[0]?.period.id;
    if (!period) return;
    this.busy.set('export');
    this.error.set(null);
    try {
      const { location } = await firstValueFrom(
        this.api.exportProfiles({ periodId: period, status: [this.status()], format: 'XLSX' }),
      );
      this.jobs.track(location).subscribe({
        next: (job) => {
          if (job.status === 'HOAN_TAT' && job.resultLocation)
            void this.fetchExport(job.resultLocation);
          else if (job.status === 'THAT_BAI' || job.status === 'DA_HUY') {
            this.busy.set(null);
            this.error.set(`Không xuất được danh sách: ${job.problem?.title ?? job.message ?? ''}`);
          }
        },
        error: (e) => {
          this.busy.set(null);
          this.error.set(this.message(e));
        },
      });
    } catch (e) {
      this.busy.set(null);
      this.error.set(this.message(e));
    }
  }

  private async fetchExport(location: string) {
    try {
      const blob = await firstValueFrom(this.api.download(location));
      const old = this.exportUrl();
      if (old) URL.revokeObjectURL(old);
      this.exportUrl.set(URL.createObjectURL(blob));
    } catch (e) {
      this.error.set(this.message(e));
    } finally {
      this.busy.set(null);
    }
  }

  private reloadQueue() {
    this.cursor.set(null);
    this.page.reload();
  }

  private message(e: unknown): string {
    const p = toProblem(e);
    if (p.status === 403 && p.code === 'APPRAISAL-403-SEPARATION-OF-DUTIES') return MSG_09_08;
    if (p.status === 403) return 'Đồng chí không có quyền thẩm định hồ sơ này.';
    if (p.status === 409 && p.code === 'APPRAISAL-409-VERSION-CONFLICT') return MSG_09_07;
    if (p.status === 409 && p.code === 'APPRAISAL-409-RETURN-LIMIT')
      return p.detail ?? 'Hồ sơ đã được trả lại đủ số lần tối đa.';
    if (p.status === 409) return 'Hồ sơ không còn ở bước thẩm định. Vui lòng tải lại trang.';
    if (p.status === 422 && p.errors?.length) return p.errors.map((x) => x.message).join(' ');
    return [p.title, p.detail].filter(Boolean).join(' ');
  }
}
