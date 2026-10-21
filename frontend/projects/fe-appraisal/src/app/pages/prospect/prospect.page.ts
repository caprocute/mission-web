/*
 * SCR-1002 Phân nhóm triển vọng (QT-10 bước 8, 9 → SCR-1002 → /api/v1/appraisals/prospect-groups,
 * /api/v1/policies/packs/{id}/prospect-criteria → MOD-04, MOD-05 → EVT-11; WEB-039). Tác nhân AC-04
 * `leader` (trình), AC-07 `appraiser` chuẩn bị; phạm vi đơn vị (Đv), dữ liệu C2 (BR-10-10).
 *
 * Trường (TL-02b SCR-1002):
 *  1 Nhóm: bốn cột kéo thả theo PL 5, mặc định theo gợi ý (BR-10-09)
 *  2 Thứ tự trong nhóm: số nguyên ≥ 1, không trùng trong nhóm
 *  3 Tóm tắt thẻ (≤ 80 ký tự, chỉ đọc)            5 Nhóm gợi ý (ẩn, chỉ đọc)
 *  4 Kế hoạch bồi dưỡng (tham chiếu)              6 Lý do điều chỉnh: 20 đến 1.000 ký tự khi khác gợi ý
 * Chức năng: Kéo thả thẻ (đổi nhóm, thứ tự; giữ trong trang, gửi khi lưu) và phương án bàn phím qua
 *  nút thao tác của thẻ; Xem tiêu chí (GET /policies/packs/{id}/prospect-criteria); Lưu nháp (PUT
 *  .../prospect-groups/{periodId}/{orgUnitId}, If-Match); Trình tập thể lãnh đạo (POST .../submit, EVT-11).
 * Quy tắc: BR-10-07 (chỉ ở kỳ năm), BR-10-08 (gợi ý chỉ để tham khảo, khác gợi ý phải có lý do),
 *  BR-10-09 (không hoàn thành nhiệm vụ năm hoặc có cờ bố trí lại không vào Nhóm 1, 2), BR-10-10 (C2).
 * Thông báo: MSG-10-05 (kỳ quý), MSG-10-06 (vi phạm BR-10-09), MSG-10-07 (thiếu lý do).
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
import {
  AppraisalApi,
  PeriodApi,
  PolicyApi,
  ProspectCriteria,
  ProspectGroupBoard,
  ProspectGroupCode,
  RATING_LEVEL_LABEL,
  toProblem,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { catchError, firstValueFrom, map, of, switchMap } from 'rxjs';
import { APPRAISAL_CAN } from '../../appraisal-permissions';
import { annualPeriodOf } from '../annual/annual-rules';
import { APPRAISAL_CURRENT_UNIT } from './current-unit';
import {
  DEFAULT_GROUP_NAME,
  GROUPS,
  MSG_10_05,
  ProspectCard,
  REASON_MAX,
  differs,
  eligibilityError,
  groupShort,
  inGroup,
  moveCard,
  msg1007,
  prospectProblemMessages,
  rankOf,
  reasonMissing,
  toCards,
  toInput,
} from './prospect-rules';

const STATUS_TEXT: Record<ProspectGroupBoard['status'], string> = {
  NHAP: '',
  DA_TRINH:
    'Danh sách đã trình tập thể lãnh đạo và đang chờ thông qua; trong thời gian này không chỉnh sửa được.',
  DA_KY: 'Danh sách đã được tập thể lãnh đạo thông qua và ký số.',
};

@Component({
  selector: 'vt-prospect-page',
  imports: [RouterLink],
  templateUrl: './prospect.page.html',
  styleUrl: './prospect.page.css',
})
export class ProspectPage {
  private readonly api = inject(AppraisalApi);
  private readonly periodsApi = inject(PeriodApi);
  private readonly policies = inject(PolicyApi);
  private readonly theme = inject(ThemeService);
  private readonly injector = inject(Injector);
  private readonly currentUnit = inject(APPRAISAL_CURRENT_UNIT);
  protected readonly can = inject(APPRAISAL_CAN);

  /** Kỳ năm qua `?period=` (bỏ trống là kỳ năm mới nhất) và đơn vị qua `?unit=`. */
  readonly period = input<string>();
  readonly unit = input<string>();

  protected readonly groups = GROUPS;
  protected readonly levelLabel = RATING_LEVEL_LABEL;
  protected readonly short = groupShort;
  protected readonly differs = differs;
  protected readonly reasonMissing = reasonMissing;
  protected readonly reasonMax = REASON_MAX;
  protected readonly statusText = STATUS_TEXT;

  protected readonly year = rxResource({
    params: () => ({ wanted: this.period() ?? null }),
    stream: ({ params }) =>
      this.periodsApi
        .list({ kind: params.wanted ? undefined : 'NAM' })
        .pipe(map((list) => annualPeriodOf(list, params.wanted))),
  });
  protected readonly yearValue = computed(() => (this.year.hasValue() ? this.year.value() : null));
  protected readonly board = rxResource({
    params: () => {
      const y = this.year.hasValue() ? this.year.value() : null;
      const unit = this.unit() ?? this.currentUnit() ?? 'cua-toi';
      return y ? { period: y.id, unit } : undefined;
    },
    stream: ({ params }) => this.api.prospectBoard(params.period, params.unit),
  });
  /** Bảng đang hiển thị: bản đọc từ máy chủ hoặc bản vừa lưu, trình. */
  protected readonly current = linkedSignal<ProspectGroupBoard | null>(() =>
    this.board.hasValue() ? this.board.value() : null,
  );
  protected readonly cards = linkedSignal<ProspectGroupBoard | null, ProspectCard[]>({
    source: this.current,
    computation: (b) => (b ? toCards(b) : []),
  });
  protected readonly dirty = linkedSignal({ source: this.current, computation: () => false });
  protected readonly quarterLocked = computed(() => {
    const e = this.board.error();
    return !!e && toProblem(e).code === 'APPRAISAL-409-PROSPECT-QUARTER';
  });
  protected readonly loadError = computed(() => !!this.board.error() && !this.quarterLocked());
  protected readonly msg1005 = MSG_10_05;

  /** Tiêu chí PL 5 của gói đang ghim cho kỳ năm; không đọc được thì dùng tên nhóm mặc định. */
  protected readonly criteria = rxResource({
    params: () => (this.year.hasValue() ? (this.year.value() ?? undefined) : undefined),
    stream: ({ params }) => {
      const pin =
        params.pinnedPolicyPacks?.find((p) => p.flow === 'DANG') ?? params.pinnedPolicyPacks?.[0];
      if (!pin) return of(null);
      return this.policies.packs().pipe(
        map((l) => l.items.find((p) => p.code === pin.packCode) ?? null),
        switchMap((p) => (p ? this.policies.prospectCriteria(p.id) : of(null))),
        catchError(() => of(null)),
      );
    },
  });
  private readonly crit = computed<ProspectCriteria | null>(() =>
    this.criteria.hasValue() ? this.criteria.value() : null,
  );
  protected groupName(g: ProspectGroupCode): string {
    return this.crit()?.groups.find((x) => x.groupCode === g)?.name ?? DEFAULT_GROUP_NAME[g];
  }
  protected readonly criteriaRows = computed(() =>
    GROUPS.map((g) => ({
      code: g,
      name: this.groupName(g),
      criteria: this.crit()?.groups.find((x) => x.groupCode === g)?.criteria ?? '',
    })),
  );

  protected readonly editable = computed(
    () => this.current()?.status === 'NHAP' && this.can('SCR-1002.xep-nhom') && !this.busy(),
  );
  protected readonly canSubmit = computed(
    () => this.current()?.status === 'NHAP' && this.can('SCR-1002.trinh'),
  );
  protected readonly adjusted = computed(() => this.cards().filter(differs));
  protected readonly missing = computed(() => this.cards().filter(reasonMissing));

  // Thẻ đang chọn (vùng 4), menu thao tác của thẻ, kéo thả
  protected readonly selectedId = linkedSignal<ProspectCard[], string | null>({
    source: this.cards,
    computation: (cards, prev) =>
      prev?.value && cards.some((c) => c.personId === prev.value)
        ? prev.value
        : (cards.find(differs)?.personId ?? cards[0]?.personId ?? null),
  });
  protected readonly selected = computed(
    () => this.cards().find((c) => c.personId === this.selectedId()) ?? null,
  );
  protected readonly menuFor = signal<string | null>(null);
  protected readonly dragging = signal<string | null>(null);
  protected readonly dropTarget = signal<ProspectGroupCode | null>(null);

  protected readonly busy = signal<null | 'save' | 'submit'>(null);
  protected readonly showCriteria = signal(false);
  protected readonly confirmSubmit = signal(false);
  protected readonly announce = signal('');
  protected readonly notice = signal<string | null>(null);
  protected readonly errors = signal<string[]>([]);
  private returnFocus: HTMLElement | null = null;
  private readonly reasonBox = viewChild<ElementRef<HTMLTextAreaElement>>('reasonBox');
  private readonly dialogFirst = viewChild<ElementRef<HTMLElement>>('dialogFirst');

  /** Chế độ "Đảng viên cao tuổi" giảm bước: bốn nhóm xếp dọc, không kéo thả (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected inGroup(g: ProspectGroupCode): ProspectCard[] {
    return inGroup(this.cards(), g);
  }

  protected rank(c: ProspectCard): number {
    return rankOf(this.cards(), c);
  }

  protected select(c: ProspectCard) {
    this.selectedId.set(c.personId);
  }

  /** Đổi nhóm hoặc thứ tự (kéo thả hoặc bàn phím); chặn BR-10-09 ngay trên trình duyệt. */
  protected move(personId: string, to: ProspectGroupCode, index?: number, keyboard = false) {
    const c = this.cards().find((x) => x.personId === personId);
    if (!c || !this.editable()) return;
    const bad = eligibilityError(c, to, this.crit());
    if (bad) {
      this.errors.set([bad]);
      this.announce.set(bad);
      return;
    }
    this.errors.set([]);
    this.notice.set(null);
    const next = moveCard(this.cards(), personId, to, index);
    this.cards.set(next);
    this.dirty.set(true);
    this.selectedId.set(personId);
    const moved = next.find((x) => x.personId === personId)!;
    let text = `Đã đưa ${c.name} vào ${groupShort(to)}, thứ tự ${rankOf(next, moved)}.`;
    if (reasonMissing(moved)) text += ' ' + msg1007(c.name);
    this.announce.set(text);
    if (keyboard)
      afterNextRender(
        () => document.querySelector<HTMLElement>(`[data-menu-for="${personId}"]`)?.focus(),
        { injector: this.injector },
      );
  }

  protected step(c: ProspectCard, delta: number) {
    const i = this.rank(c) - 1 + delta;
    if (i < 0 || i >= this.inGroup(c.group).length) return;
    this.menuFor.set(null);
    this.move(c.personId, c.group, i, true);
  }

  protected moveTo(c: ProspectCard, g: ProspectGroupCode) {
    this.menuFor.set(null);
    this.move(c.personId, g, undefined, true);
  }

  protected toggleMenu(c: ProspectCard, ev: Event) {
    this.select(c);
    this.returnFocus = ev.target as HTMLElement;
    this.menuFor.update((v) => (v === c.personId ? null : c.personId));
  }

  protected onMenuKey(ev: KeyboardEvent) {
    if (ev.key !== 'Escape') return;
    ev.stopPropagation();
    this.menuFor.set(null);
    this.returnFocus?.focus();
  }

  // Kéo thả bằng chuột hoặc bút
  protected onDragStart(c: ProspectCard, ev: DragEvent) {
    if (!this.editable()) return;
    this.dragging.set(c.personId);
    ev.dataTransfer?.setData('text/plain', c.personId);
    if (ev.dataTransfer) ev.dataTransfer.effectAllowed = 'move';
  }

  protected onDragOver(g: ProspectGroupCode, ev: DragEvent) {
    if (!this.dragging()) return;
    ev.preventDefault();
    this.dropTarget.set(g);
  }

  protected onDrop(g: ProspectGroupCode, ev: DragEvent, before?: ProspectCard) {
    ev.preventDefault();
    ev.stopPropagation();
    const id = this.dragging() ?? ev.dataTransfer?.getData('text/plain');
    this.dragging.set(null);
    this.dropTarget.set(null);
    if (!id) return;
    const list = this.inGroup(g).filter((x) => x.personId !== id);
    const index = before ? list.findIndex((x) => x.personId === before.personId) : undefined;
    this.move(id, g, index === -1 ? undefined : index);
  }

  protected onDragEnd() {
    this.dragging.set(null);
    this.dropTarget.set(null);
  }

  // Vùng 4: lý do, kế hoạch bồi dưỡng của thẻ đang chọn
  protected setReason(v: string) {
    const id = this.selectedId();
    this.cards.update((cs) => cs.map((c) => (c.personId === id ? { ...c, reason: v } : c)));
    this.dirty.set(true);
  }

  protected setPlan(v: string) {
    const id = this.selectedId();
    this.cards.update((cs) => cs.map((c) => (c.personId === id ? { ...c, plan: v } : c)));
    this.dirty.set(true);
  }

  protected focusReason(c: ProspectCard) {
    this.select(c);
    afterNextRender(() => this.reasonBox()?.nativeElement.focus(), { injector: this.injector });
  }

  /** Kiểm BR-10-08 trước khi gửi; thiếu lý do thì chọn thẻ đầu tiên thiếu và đưa tiêu điểm tới ô lý do. */
  private checkReasons(): boolean {
    const miss = this.missing();
    if (!miss.length) return true;
    this.errors.set(miss.map((c) => msg1007(c.name)));
    this.focusReason(miss[0]);
    return false;
  }

  protected async save(): Promise<boolean> {
    const b = this.current();
    if (!b || this.busy() || !this.checkReasons()) return false;
    this.busy.set('save');
    this.errors.set([]);
    try {
      const saved = await firstValueFrom(this.api.saveProspectBoard(b, toInput(this.cards())));
      this.current.set(saved);
      this.notice.set('Đã lưu nháp danh sách phân nhóm.');
      return true;
    } catch (e) {
      this.fail(e);
      return false;
    } finally {
      this.busy.set(null);
    }
  }

  protected openSubmit(ev: Event) {
    if (!this.checkReasons()) return;
    this.returnFocus = ev.target as HTMLElement;
    this.confirmSubmit.set(true);
    afterNextRender(() => this.dialogFirst()?.nativeElement.focus(), { injector: this.injector });
  }

  protected async submit() {
    if (this.dirty() && !(await this.save())) {
      this.confirmSubmit.set(false);
      return;
    }
    const b = this.current();
    if (!b) return;
    this.busy.set('submit');
    try {
      const done = await firstValueFrom(this.api.submitProspectBoard(b));
      this.current.set(done);
      this.notice.set(
        'Đã trình tập thể lãnh đạo danh sách phân nhóm triển vọng. Danh sách bị khoá chỉnh sửa cho tới khi có kết quả thông qua.',
      );
    } catch (e) {
      this.fail(e);
    } finally {
      this.busy.set(null);
      this.confirmSubmit.set(false);
    }
  }

  protected openCriteria(ev: Event) {
    this.returnFocus = ev.target as HTMLElement;
    this.showCriteria.set(true);
    afterNextRender(() => this.dialogFirst()?.nativeElement.focus(), { injector: this.injector });
  }

  protected closeDialogs() {
    if (this.busy()) return;
    this.showCriteria.set(false);
    this.confirmSubmit.set(false);
    const back = this.returnFocus;
    afterNextRender(() => back?.focus(), { injector: this.injector });
  }

  protected onDialogKey(ev: KeyboardEvent) {
    if (ev.key === 'Escape') {
      ev.stopPropagation();
      this.closeDialogs();
    }
  }

  protected reload() {
    this.errors.set([]);
    this.notice.set(null);
    this.board.reload();
  }

  private fail(e: unknown) {
    const p = toProblem(e);
    this.notice.set(null);
    this.errors.set(prospectProblemMessages(p));
  }
}
