/*
 * SCR-0903 Phiếu xếp loại có giải thích (QT-09 bước 10, 13 → SCR-0903 → /api/v1/appraisals → MOD-04,
 * SVC-07 → EVT-10, EVT-11; WEB-037). Tác nhân: AC-06 xem kết quả của mình, AC-05 chọn mức đề xuất, AC-07
 * và AC-08 xem (wiki/15a SCR-0903); dữ liệu C2, máy chủ lọc theo phạm vi và chặn bằng 403.
 *
 * Trường (TL-02b SCR-0903), đều chỉ đọc trừ trường 10:
 *  1 Điểm kết quả nhiệm vụ (1 chữ số thập phân)          6 Câu giải thích (≤ 1.000, có điều khoản và phiên bản)
 *  2 Bảng cấu phần: KPI nhóm, điểm tối đa, điểm           7 Phiên bản bộ tiêu chí (nhãn đầu trang, MSG-09-10)
 *  3 Tỷ lệ nhiệm vụ vượt mục tiêu, tỷ lệ mục tiêu O       8 Vết các mức: mức, ngày, người
 *  4 Điều kiện các mức, đạt hay chưa kèm giá trị          9 Mã ảnh chụp điểm và băm đầu vào (khối "Thông tin đối chiếu")
 *  5 Mức đề xuất của hệ thống                            10 Mức đề xuất của quản lý: mặc định bằng mức thẩm định,
 *                                                           khác thì bắt buộc lý do (MSG-09-12)
 * Chức năng: Chuyển thẻ luồng Nhà nước (GET .../scores?flow=NHA_NUOC); Thẻ Vết tính (GET .../score-trace);
 *  Tải Mẫu 1B sau quyết định (GET /dossiers/forms/{id}/file); Ghi mức đề xuất (AC-05, hồ sơ DA_THAM_DINH,
 *  POST .../proposal → CHO_QUYET_DINH, EVT-11); Gửi khiếu nại (chủ hồ sơ, DA_KY_SO, còn hạn → SCR-1201).
 * Quy tắc: BR-09-10 (giải thích hạ mức), BR-09-12 (lý do khác mức), BR-09-14 (tách vai trò, OPA),
 *  BR-09-16 (điểm chỉ do hệ thống tính, không có chức năng sửa điểm).
 * Thông báo: MSG-09-10 (nhãn phiên bản), MSG-09-11 (kết quả đã quyết định, hạn khiếu nại), MSG-09-12.
 * Chữ trên màn hình theo YC-PT-10: không hiện mã kỹ thuật; mã cần cho kiểm thử đặt ở thuộc tính data-*.
 */
import {
  Component,
  DestroyRef,
  ElementRef,
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
import { toProblem } from '@vt/api-client';
import {
  AppraisalApi,
  AppraisalFlow,
  AppraisalProfileView,
  AppraisalScoreGroup,
  AppraisalScoreTaskLine,
  AppraisalScoreTraceStep,
  DossierApi,
  GENERAL_CRITERIA_LEVEL_LABEL,
  RATING_LEVELS,
  RATING_LEVEL_LABEL,
  RatingLevel,
} from '@vt/api-client/domains';
import { permittedOn } from '@vt/auth';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { firstValueFrom } from 'rxjs';
import { APPRAISAL_CAN } from '../../appraisal-permissions';
import { MSG_09_07 } from '../review/review-rules';
import { dmy, vn, vnTime } from '../self-assessment/self-assessment-rules';
import {
  DECIDED,
  FLOW_TAB_LABEL,
  MSG_09_12,
  MSG_PROPOSAL_SOD,
  checkProposal,
  complaintOpen,
  componentGroups,
  conditionGroups,
  conditionText,
  currentLevel,
  exceedCount,
  msg0910,
  pct,
  stepLabel,
  todayVn,
  trailChips,
} from './rating-sheet-rules';

type Tab = AppraisalFlow | 'TRACE';

@Component({
  selector: 'vt-rating-sheet-page',
  imports: [VtSkeletonComponent, RouterLink],
  templateUrl: './rating-sheet.page.html',
  styleUrl: './rating-sheet.page.css',
})
export class RatingSheetPage {
  /** `:id` của tuyến `ket-qua/:id`. */
  readonly id = input.required<string>();

  private readonly api = inject(AppraisalApi);
  private readonly dossiers = inject(DossierApi);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(APPRAISAL_CAN);

  protected readonly levels = RATING_LEVELS;
  protected readonly levelLabel = RATING_LEVEL_LABEL;
  protected readonly criteriaLabel = GENERAL_CRITERIA_LEVEL_LABEL;
  protected readonly flowLabel = FLOW_TAB_LABEL;
  protected readonly msg0912 = MSG_09_12;
  protected readonly vn = vn;
  protected readonly pct = pct;
  protected readonly dmy = dmy;
  protected readonly vnTime = vnTime;
  protected readonly conditionText = conditionText;
  protected readonly stepLabel = stepLabel;

  protected readonly loaded = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.profile(params),
  });
  protected readonly profile = linkedSignal<AppraisalProfileView | undefined>(() =>
    this.loaded.hasValue() ? this.loaded.value() : undefined,
  );

  // Thẻ luồng và thẻ Vết tính
  protected readonly flows = computed<AppraisalFlow[]>(() => {
    const p = this.profile();
    if (!p) return [];
    const all = p.availableFlows?.length ? p.availableFlows : [p.flow];
    return all.includes(p.flow) ? all : [p.flow, ...all];
  });
  protected readonly tab = linkedSignal<string, Tab>({
    source: () => this.id(),
    computation: () => this.profile()?.flow ?? 'DANG',
  });
  /** Luồng của thẻ đang mở; thẻ Vết tính dùng luồng vừa xem trước đó. */
  protected readonly flow = linkedSignal<Tab, AppraisalFlow>({
    source: this.tab,
    computation: (t, prev) => (t === 'TRACE' ? (prev?.value ?? this.profile()?.flow ?? 'DANG') : t),
  });
  protected readonly tabs = computed(() => {
    const list: { key: Tab; label: string }[] = this.flows().map((f) => ({
      key: f,
      label: FLOW_TAB_LABEL[f],
    }));
    if (!this.simple()) list.push({ key: 'TRACE', label: 'Vết tính' });
    return list;
  });

  /** Khoá `id|luồng|luồng của hồ sơ` dạng chuỗi để tài nguyên chỉ tải lại khi khoá thật sự đổi. */
  private readonly scoreKey = computed(() => {
    const p = this.profile();
    return p ? `${p.id}|${this.flow()}|${p.flow}` : undefined;
  });
  protected readonly scores = rxResource({
    params: () => this.scoreKey(),
    stream: ({ params }) => {
      const [id, flow, own] = params.split('|');
      return this.api.scores(id, flow === own ? undefined : (flow as AppraisalFlow));
    },
  });
  protected readonly view = computed(() => (this.scores.hasValue() ? this.scores.value() : null));
  /** Ảnh chụp của luồng còn lại cho ghi chú "hai kết quả lưu riêng" (vùng 3). */
  private readonly otherKey = computed(() => {
    const p = this.profile();
    const o = this.flows().find((f) => f !== this.flow());
    return p && o && !this.simple() ? `${p.id}|${o}` : undefined;
  });
  protected readonly other = rxResource({
    params: () => this.otherKey(),
    stream: ({ params }) => {
      const [id, flow] = params.split('|');
      return this.api.scores(id, flow as AppraisalFlow);
    },
  });
  private readonly traceKey = computed(() =>
    this.tab() === 'TRACE' ? this.scoreKey() : undefined,
  );
  protected readonly trace = rxResource({
    params: () => this.traceKey(),
    stream: ({ params }) => {
      const [id, flow, own] = params.split('|');
      return this.api.scoreTrace(id, flow === own ? {} : { flow: flow as AppraisalFlow });
    },
  });

  // Vùng 1 đến 4
  protected readonly snapshot = computed(() => this.view()?.snapshot);
  protected readonly groups = computed(() => {
    const s = this.snapshot();
    return s ? componentGroups(s) : [];
  });
  protected readonly exceed = computed(() => {
    const s = this.snapshot();
    return s ? exceedCount(s) : { over: 0, total: 0 };
  });
  protected readonly conditions = computed(() => {
    const v = this.view();
    return v ? conditionGroups(v) : [];
  });
  protected readonly systemLevel = computed<RatingLevel | undefined>(
    () => this.view()?.explanation?.systemLevel ?? this.snapshot()?.autoRatingCode,
  );
  protected readonly explanation = computed(() => {
    const v = this.view();
    if (!v) return '';
    if (v.explanation?.text) return v.explanation.text;
    return v.snapshot.downgradeReasons.map((d) => d.text).join(' ');
  });
  protected readonly legalRef = computed(
    () =>
      this.view()?.explanation?.legalRef ??
      this.snapshot()?.downgradeReasons.find((d) => d.legalRef)?.legalRef,
  );
  protected readonly packNote = computed(() => {
    const s = this.snapshot();
    const p = this.profile();
    return s && p ? msg0910(s.policyVersion, p.period.label) : '';
  });
  protected readonly criteriaLevel = computed(
    () =>
      this.snapshot()?.generalCriteriaLevel ??
      this.profile()?.generalCriteria?.find((g) => g.stage === 'TU_DANH_GIA')?.level,
  );
  /** Ghi chú vùng 3: kết quả luồng còn lại, cùng dữ liệu sản phẩm, lưu riêng. */
  protected readonly otherNote = computed(() => {
    const o = this.other.hasValue() ? this.other.value() : null;
    if (!o) return null;
    const s = o.snapshot;
    const name = o.flow === 'NHA_NUOC' ? 'Luồng Nhà nước' : 'Luồng Đảng';
    const parts =
      s.generalCriteriaScore != null && s.kpiScore != null
        ? `${vn(s.generalCriteriaScore)} điểm tiêu chí chung + 70 × ${vn(s.kpiScore / 70, 3)} = ${vn(s.kpiScore)}`
        : `điểm kết quả nhiệm vụ`;
    return {
      head: `${name} (bộ tiêu chí ${s.policyVersion}) dùng cùng dữ liệu sản phẩm: ${parts}`,
      total: vn(s.totalScore),
    };
  });

  // Chi tiết nhiệm vụ của một nhóm (nút "Xem nhiệm vụ")
  protected readonly openGroup = linkedSignal<AppraisalFlow, string | null>({
    source: this.flow,
    computation: () => null,
  });

  /** Chế độ "Đảng viên cao tuổi" giảm bước: ẩn vết tính, chi tiết nhiệm vụ và thông tin đối chiếu (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  // Vết các mức (trường 8, 9)
  protected readonly chips = computed(() => {
    const p = this.profile();
    return p ? trailChips(p) : [];
  });
  protected readonly inputHash = computed(() => {
    const h = this.snapshot()?.inputHash;
    return h ? `${h.slice(0, 4)}…${h.slice(-4)}` : '';
  });

  // Hành động theo ma trận quyền và trạng thái (máy chủ là nơi quyết định cuối cùng)
  protected readonly decided = computed(() => {
    const s = this.profile()?.status;
    return !!s && DECIDED.includes(s);
  });
  protected readonly canDownload = computed(
    () => this.decided() && !!this.profile()?.signedFormId && this.can('SCR-0903.tai-mau-1b'),
  );
  protected readonly canComplain = computed(() => {
    const p = this.profile();
    return !!p && this.can('SCR-0903.khieu-nai') && complaintOpen(p, todayVn());
  });
  protected readonly appraised = computed(() => {
    const p = this.profile();
    return p ? currentLevel(p, 'THAM_DINH') : null;
  });
  protected readonly canPropose = computed(
    () =>
      this.profile()?.status === 'DA_THAM_DINH' &&
      this.can('SCR-0903.de-xuat') &&
      permittedOn(this.profile(), 'SCR-0903.de-xuat') &&
      !!this.appraised(),
  );
  protected readonly finalLevel = computed(() => {
    const p = this.profile();
    return p?.finalRatingCode ?? (p ? currentLevel(p, 'QUYET_DINH') : null);
  });

  // Trường 10: mức đề xuất của quản lý
  protected readonly rating = linkedSignal<RatingLevel>(() => this.appraised() ?? 'HTTNV');
  protected readonly reason = linkedSignal({ source: this.profile, computation: () => '' });
  protected readonly comment = linkedSignal({ source: this.profile, computation: () => '' });
  protected readonly differs = computed(
    () => !!this.appraised() && this.rating() !== this.appraised(),
  );
  protected readonly errors = computed(() =>
    checkProposal(
      { rating: this.rating(), reason: this.reason(), comment: this.comment() },
      this.appraised(),
    ),
  );
  protected readonly tried = linkedSignal({ source: this.profile, computation: () => false });

  protected readonly busy = signal<null | 'propose' | 'download'>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly fileUrl = signal<string | null>(null);

  private readonly fileLink = viewChild<ElementRef<HTMLElement>>('fileLink');
  private readonly noticeBox = viewChild<ElementRef<HTMLElement>>('noticeBox');

  constructor() {
    inject(DestroyRef).onDestroy(() => {
      const url = this.fileUrl();
      if (url) URL.revokeObjectURL(url);
    });
    effect(() => this.fileLink()?.nativeElement.focus());
    effect(() => this.noticeBox()?.nativeElement.focus());
  }

  protected select(t: Tab) {
    this.tab.set(t);
  }

  /** Mũi tên trái/phải, Home, End chuyển thẻ (mẫu tablist của WAI-ARIA). */
  protected tabKey(e: KeyboardEvent, i: number) {
    const list = this.tabs();
    const next =
      e.key === 'ArrowRight'
        ? (i + 1) % list.length
        : e.key === 'ArrowLeft'
          ? (i - 1 + list.length) % list.length
          : e.key === 'Home'
            ? 0
            : e.key === 'End'
              ? list.length - 1
              : -1;
    if (next < 0) return;
    e.preventDefault();
    this.tab.set(list[next].key);
    document.getElementById(`tab-${list[next].key}`)?.focus();
  }

  protected toggleGroup(code: string) {
    this.openGroup.update((c) => (c === code ? null : code));
  }

  /** Esc trong bảng nhiệm vụ đang mở: đóng bảng, trả tiêu điểm về nút "Xem nhiệm vụ". */
  protected closeGroup(code: string) {
    this.openGroup.set(null);
    document.getElementById(`grp-${code}`)?.focus();
  }

  protected taskTitle(id: string | undefined): string {
    if (!id) return '';
    for (const g of this.snapshot()?.groups ?? [])
      for (const t of g.tasks ?? []) if (t.taskId === id) return t.title;
    return '';
  }

  /** Dòng nhiệm vụ của một bước vết tính, để mở minh chứng đã nghiệm thu. */
  protected taskOf(s: AppraisalScoreTraceStep): AppraisalScoreTaskLine | undefined {
    if (!s.taskId) return undefined;
    return this.snapshot()
      ?.groups.flatMap((g) => g.tasks ?? [])
      .find((t) => t.taskId === s.taskId);
  }

  protected groupCount(g: AppraisalScoreGroup) {
    return g.tasks?.length ?? 0;
  }

  /** "Tải Mẫu 1B (PDF/A)": lấy bản đã ký từ máy chủ, hiện liên kết lưu tệp và chuyển tiêu điểm tới đó. */
  protected async download() {
    const id = this.profile()?.signedFormId;
    if (!id) return;
    this.busy.set('download');
    this.error.set(null);
    try {
      const blob = await firstValueFrom(this.dossiers.formFile(id));
      const old = this.fileUrl();
      if (old) URL.revokeObjectURL(old);
      this.fileUrl.set(URL.createObjectURL(blob));
    } catch (e) {
      this.error.set(this.message(e, 'tải Mẫu 1B'));
    } finally {
      this.busy.set(null);
    }
  }

  /** "Ghi mức đề xuất": kiểm lý do khi khác mức thẩm định, gửi lên máy chủ, hồ sơ sang bước quyết định. */
  protected async propose() {
    this.tried.set(true);
    const p = this.profile();
    if (!p || !this.canPropose() || Object.keys(this.errors()).length) {
      if (this.errors()['reason']) document.getElementById('f-reason')?.focus();
      return;
    }
    this.busy.set('propose');
    this.error.set(null);
    this.notice.set(null);
    try {
      const after = await firstValueFrom(
        this.api.propose(p, {
          ratingCode: this.rating(),
          ...(this.differs() ? { reason: this.reason().trim() } : {}),
          ...(this.comment().trim() ? { comment: this.comment().trim() } : {}),
        }),
      );
      const label = RATING_LEVEL_LABEL[this.rating()];
      this.profile.set(after);
      this.notice.set(
        `Đã ghi mức đề xuất "${label}" cho hồ sơ của đồng chí ${after.person.name}. Hồ sơ đã chuyển tới cấp có thẩm quyền quyết định.`,
      );
    } catch (e) {
      this.error.set(this.message(e, 'ghi mức đề xuất'));
    } finally {
      this.busy.set(null);
    }
  }

  protected shown(field: string): string | null {
    return this.tried() ? (this.errors()[field] ?? null) : null;
  }

  private message(e: unknown, action: string): string {
    const p = toProblem(e);
    if (p.status === 403 && p.code === 'APPRAISAL-403-SEPARATION-OF-DUTIES')
      return MSG_PROPOSAL_SOD;
    if (p.status === 403) return `Đồng chí không có quyền ${action} cho hồ sơ này.`;
    if (p.status === 409 && p.code === 'APPRAISAL-409-VERSION-CONFLICT') return MSG_09_07;
    if (p.status === 409) return p.detail ?? 'Hồ sơ đã chuyển bước khác. Vui lòng tải lại trang.';
    if (p.status === 422 && p.errors?.length) return p.errors.map((x) => x.message).join(' ');
    if (p.status === 404) return `Không ${action} được vì không tìm thấy tài liệu.`;
    return (
      [p.title, p.detail].filter(Boolean).join(' ') || `Không ${action} được. Vui lòng thử lại.`
    );
  }
}
