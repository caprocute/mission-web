/*
 * SCR-1001 Xếp loại năm (QT-10 bước 5, 7 → SCR-1001 → /api/v1/appraisals/annual → MOD-04, SVC-07,
 * MOD-02 → EVT-04; WEB-039). Tác nhân AC-07 `appraiser`; AC-04 `leader`, AC-08 `decider` xem (Cd, C2).
 *
 * Trường (TL-02b SCR-1001):
 *  1 Bộ lọc đơn vị (ABAC)                         6 Mức tự đề xuất năm
 *  2 Công tắc chỉ hiện hồ sơ có cảnh báo           7 Mức hệ thống đề xuất (bảng quyết định năm)
 *  3 Bốn thẻ số liệu (đếm theo cờ)                8 Quy tắc liên thông (≤ 200 ký tự)
 *  4 Mức quý I đến IV (quyết định của từng quý)   9 Giải thích chi tiết: công thức, điều kiện, kết luận
 *  5 Điểm năm (1 chữ số thập phân)
 * Chức năng: Chọn dòng (GET /appraisals/annual/{id}/explanation); Chỉ hiện hồ sơ có cảnh báo liên thông
 *  (GET /appraisals/annual?period=&flag=lien_thong); Chuyển thẩm định năm (mở SCR-0902 với kỳ năm);
 *  Lập đề xuất bố trí lại (POST /appraisals/annual/{id}/reassignment-proposal, If-Match, EVT-04).
 * Quy tắc: BR-10-01 (năm dựa trên bốn quý đã quyết định), BR-10-02 (cách tính điểm năm), BR-10-03 (có quý
 *  không hoàn thành thì không xuất sắc năm), BR-10-04 (hai quý liên tiếp → bố trí lại), BR-10-05, BR-10-06
 *  (quý đã khoá giữ phiên bản quy định).
 * Thông báo: MSG-10-01 (giải thích chặn xuất sắc), MSG-10-02 (bố trí lại), MSG-10-03 (khiếu nại mở, máy
 *  chủ chặn ở bước quyết định), MSG-10-04 (điểm năm tính lại sau hậu kiểm, đến qua thông báo).
 */
import {
  Component,
  ElementRef,
  Injector,
  afterNextRender,
  computed,
  inject,
  input,
  linkedSignal,
  signal,
  viewChild,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { toProblem } from '@vt/api-client';
import { AnnualRatingPage, AnnualRatingRow, AppraisalApi, PeriodApi, RATING_LEVEL_LABEL, RatingLevel } from '@vt/api-client/domains';
import { ThemeService } from '@vt/design-system';
import { EMPTY, Observable, expand, firstValueFrom, map, reduce } from 'rxjs';
import { APPRAISAL_CAN } from '../../appraisal-permissions';
import { LEVEL_SHORT } from '../batch-decision/batch-decision-rules';
import { vn } from '../self-assessment/self-assessment-rules';
import {
  QUARTER_LABEL,
  YEAR_METHOD_LABEL,
  annualPeriodOf,
  hasAwaitingAppraisal,
  linkageOf,
  reassignProblemMessage,
} from './annual-rules';

const PAGE_SIZE = 20;
const MAX_PAGES = 5;

interface Loaded {
  rows: AnnualRatingRow[];
  first: AnnualRatingPage | null;
}

@Component({
  selector: 'vt-annual-page',
  imports: [RouterLink],
  templateUrl: './annual.page.html',
  styleUrl: './annual.page.css',
})
export class AnnualPage {
  private readonly api = inject(AppraisalApi);
  private readonly periodsApi = inject(PeriodApi);
  private readonly theme = inject(ThemeService);
  private readonly injector = inject(Injector);
  protected readonly can = inject(APPRAISAL_CAN);

  /** Kỳ năm chọn qua `?period=`; bỏ trống là kỳ năm mới nhất. */
  readonly period = input<string>();

  protected readonly short = LEVEL_SHORT;
  protected readonly full = RATING_LEVEL_LABEL;
  protected readonly quarterLabel = QUARTER_LABEL;
  protected readonly vn = vn;
  protected readonly linkage = linkageOf;

  protected readonly periods = rxResource({
    params: () => ({ wanted: this.period() ?? null }),
    stream: ({ params }) =>
      this.periodsApi
        .list({ kind: params.wanted ? undefined : 'NAM' })
        .pipe(map((list) => annualPeriodOf(list, params.wanted))),
  });
  protected readonly year = computed(() => (this.periods.hasValue() ? this.periods.value() : null));
  protected readonly yearLabel = computed(() => this.year()?.startDate.slice(0, 4) ?? '');

  // Trường 1, 2: bộ lọc đơn vị, công tắc cảnh báo
  protected readonly unit = signal('');
  protected readonly warningsOnly = signal(false);
  protected readonly data = rxResource({
    params: () => {
      const y = this.year();
      return y ? { period: y.id, unit: this.unit(), flag: this.warningsOnly() } : undefined;
    },
    stream: ({ params }) => this.loadAll(params.period, params.unit, params.flag),
  });
  protected readonly rows = computed(() => (this.data.hasValue() ? this.data.value().rows : []));
  protected readonly counts = computed(() =>
    this.data.hasValue() ? (this.data.value().first?.counts ?? null) : null,
  );
  protected readonly policyNote = computed(() =>
    this.data.hasValue() ? (this.data.value().first?.policyNote ?? '') : '',
  );
  /** Đơn vị xuất hiện trong lần tải không lọc đơn vị; giữ lại khi đang lọc. */
  protected readonly units = linkedSignal<AnnualRatingRow[], { id: string; name: string }[]>({
    source: this.rows,
    computation: (rows, prev) => {
      if (this.unit() && prev) return prev.value;
      const m = new Map(rows.filter((r) => r.orgUnit).map((r) => [r.orgUnit!.id, r.orgUnit!.name]));
      return [...m]
        .map(([id, name]) => ({ id, name }))
        .sort((a, b) => a.name.localeCompare(b.name, 'vi'));
    },
  });

  protected readonly pageIndex = linkedSignal({ source: this.rows, computation: () => 0 });
  protected readonly pageCount = computed(() =>
    Math.max(1, Math.ceil(this.rows().length / PAGE_SIZE)),
  );
  protected readonly pageRows = computed(() =>
    this.rows().slice(this.pageIndex() * PAGE_SIZE, (this.pageIndex() + 1) * PAGE_SIZE),
  );

  // Trường 9: giải thích của hồ sơ đang chọn
  protected readonly selectedId = linkedSignal<AnnualRatingRow[], string | null>({
    source: this.rows,
    computation: (rows, prev) =>
      prev?.value && (!rows.length || rows.some((r) => r.id === prev.value))
        ? prev.value
        : (rows[0]?.id ?? null),
  });
  protected readonly selected = computed(
    () => this.rows().find((r) => r.id === this.selectedId()) ?? null,
  );
  // Hồ sơ chưa có điểm năm (luồng chưa tính xong) thì máy chủ trả 409, nên không hỏi giải thích
  protected readonly explanation = rxResource({
    params: () => {
      const s = this.selected();
      return s && s.annualScore != null ? s.id : undefined;
    },
    stream: ({ params }) => this.api.annualExplanation(params),
  });
  protected readonly ex = computed(() =>
    this.explanation.hasValue() ? this.explanation.value() : null,
  );
  protected readonly method = computed(() => {
    const m = this.ex()?.yearCalcMethod;
    return m ? (YEAR_METHOD_LABEL[m] ?? '') : '';
  });

  protected readonly canAppraise = computed(
    () => this.can('SCR-1001.tham-dinh-nam') && hasAwaitingAppraisal(this.rows()),
  );

  // Lập đề xuất bố trí lại
  protected readonly dialogRow = signal<AnnualRatingRow | null>(null);
  protected readonly note = signal('');
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  private returnFocus: HTMLElement | null = null;
  private readonly noteBox = viewChild<ElementRef<HTMLTextAreaElement>>('noteBox');

  /** Chế độ "Đảng viên cao tuổi" giảm bước: bỏ cột tự đề xuất năm, bố cục một cột (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  private loadAll(period: string, unit: string, flag: boolean): Observable<Loaded> {
    let pages = 0;
    const page = (cursor: string | null) =>
      this.api.annual({ period, unitId: unit || undefined, warningsOnly: flag, cursor });
    return page(null).pipe(
      expand((p) => (p.nextCursor && ++pages < MAX_PAGES ? page(p.nextCursor) : EMPTY)),
      reduce<AnnualRatingPage, Loaded>(
        (acc, p) => ({ rows: [...acc.rows, ...p.items], first: acc.first ?? p }),
        { rows: [], first: null },
      ),
    );
  }

  protected level(l: RatingLevel | null | undefined): string {
    return l ? this.short[l] : '—';
  }

  protected select(r: AnnualRatingRow) {
    this.selectedId.set(r.id);
  }

  protected openReassign(r: AnnualRatingRow, ev: Event) {
    this.returnFocus = ev.target as HTMLElement;
    this.note.set('');
    this.error.set(null);
    this.dialogRow.set(r);
    afterNextRender(() => this.noteBox()?.nativeElement.focus(), { injector: this.injector });
  }

  protected closeDialog() {
    this.dialogRow.set(null);
    const back = this.returnFocus;
    afterNextRender(() => back?.focus(), { injector: this.injector });
  }

  protected async confirmReassign() {
    const r = this.dialogRow();
    if (!r || this.busy()) return;
    if (this.note().length > 1000) {
      this.error.set('Ghi chú tối đa 1.000 ký tự.');
      return;
    }
    this.busy.set(true);
    this.error.set(null);
    try {
      await firstValueFrom(this.api.proposeReassignment(r, this.note().trim() || undefined));
      this.notice.set(
        `Đã lập đề xuất bố trí lại công việc cho đồng chí ${r.person.name} và gửi cơ quan tổ chức (QĐ khoản 15.6).`,
      );
      this.dialogRow.set(null);
      this.data.reload();
    } catch (e) {
      const p = toProblem(e);
      this.error.set(reassignProblemMessage(p));
      if (p.code === 'APPRAISAL-409-VERSION-CONFLICT') this.data.reload();
    } finally {
      this.busy.set(false);
    }
  }

  protected onDialogKey(ev: KeyboardEvent) {
    if (ev.key === 'Escape' && !this.busy()) {
      ev.stopPropagation();
      this.closeDialog();
    }
  }

  protected toggleWarnings() {
    this.warningsOnly.update((v) => !v);
  }
}
