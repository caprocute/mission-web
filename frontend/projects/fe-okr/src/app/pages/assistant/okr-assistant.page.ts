/*
 * SCR-1701 "Trợ lý AI soạn OKR và soát KR" (QT-17; TL-02 Hình 12.12) — đối chiếu đặc tả:
 * Trường: 1 mục tiêu O ≤ 300 ký tự (cảnh báo khi quá số mục tiêu khuyến nghị); 2 căn cứ của O; 3 KR, cảnh báo khi
 *   mơ hồ; 4 chỉ tiêu, trọng số; 5 cờ AI hỗ trợ (`contentSource` khác `HUMAN`); 6 câu hỏi ≤ 2.000 ký tự; 7 căn cứ
 *   chọn từ văn bản đã có (tải tệp mới thuộc SCR-0502, chưa làm ở đây); 8 thẻ gợi ý có nhãn AI, lý do, nguồn.
 * Vùng: 4 bản nháp OKR bên trái; 1 hội thoại, 2 thẻ soát KR, 3 lưu ý liên kết ngang và ô hỏi bên phải.
 * Hành động: Gửi câu hỏi (POST /api/v1/ai/assist/okr; 202 thì theo dõi job, MSG-17-03); Dùng gợi ý (ghi bằng API okr
 *   dưới danh tính người dùng: POST hoặc PUT /api/v1/okr/objectives, rồi POST /api/v1/ai/suggestions/{id}/decision
 *   `CHAP_NHAN`, MSG-17-04); Bỏ qua (decision `BO_QUA`); Sửa bản nháp (SCR-0202); Trình phê duyệt
 *   (POST /api/v1/okr/objectives/{id}/submit, EVT-21).
 * Quy tắc: BR-02-11 (nội dung chèn từ gợi ý mang cờ AI hỗ trợ), BR-17-03, BR-17-07 (trợ lý chỉ đọc, rào chắn),
 *   BR-17-08 (chỉ người dùng ghi và trình). Thông báo: MSG-17-01..04.
 * Quyền (wiki/15a): SCR-1701.hoi, SCR-1701.dung-goi-y, SCR-1701.trinh (AC-05 đơn vị, AC-06 bản thân).
 */
import { HttpErrorResponse } from '@angular/common/http';
import { Component, computed, inject, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  AiApi,
  JobTracker,
  ObjectiveDetail,
  OkrApi,
  OkrAssistResult,
  OkrAssistSuggestion,
  ProblemPresenter,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { Observable, catchError, forkJoin, last, map, of, switchMap, throwError } from 'rxjs';
import { OKR_CAN } from '../../okr-permissions';
import { formatDate } from '../../okr-ui';
import {
  MAX_QUESTION,
  MSG_17_03,
  MSG_17_04,
  aiAssisted,
  applyRewrite,
  msg1701,
  objectiveFromSuggestion,
  vagueCodes,
} from './assistant-rules';

type Decision = 'CHAP_NHAN' | 'BO_QUA';

interface Turn {
  id: number;
  question: string;
  pending: boolean;
  queued: boolean;
  result: OkrAssistResult | null;
  error: string | null;
}

@Component({
  selector: 'vt-okr-assistant-page',
  imports: [RouterLink],
  templateUrl: './okr-assistant.page.html',
  styleUrl: './okr-assistant.page.css',
})
export class OkrAssistantPage {
  private readonly okr = inject(OkrApi);
  private readonly ai = inject(AiApi);
  private readonly jobs = inject(JobTracker);
  private readonly problems = inject(ProblemPresenter);
  private readonly theme = inject(ThemeService);
  private readonly route = inject(ActivatedRoute);
  protected readonly can = inject(OKR_CAN);

  protected readonly max = MAX_QUESTION;
  protected readonly msg1703 = MSG_17_03;
  protected readonly day = (iso: string) => formatDate(iso, true);
  protected readonly aiAssisted = aiAssisted;
  protected readonly pct = (w: number) => Math.round(w * 100);

  private readonly period = this.route.snapshot.queryParamMap.get('period') ?? undefined;
  protected readonly ctx = rxResource({
    stream: () => this.okr.composeContext({ period: this.period }),
  });
  protected readonly tree = rxResource({ stream: () => this.okr.tree({ period: this.period }) });

  /** Bản nháp (`NHAP`) trong kỳ mà người dùng được sửa; mục tiêu không đọc được thì bỏ qua. */
  protected readonly drafts = rxResource({
    params: () =>
      this.tree
        .value()
        ?.objectives.filter((o) => o.status === 'NHAP')
        .map((o) => o.id),
    stream: ({ params: ids }) =>
      ids.length
        ? forkJoin(ids.map((id) => this.okr.objective(id).pipe(catchError(() => of(null))))).pipe(
            map((list) => list.filter((d): d is ObjectiveDetail => !!d && d.status === 'NHAP')),
          )
        : of([] as ObjectiveDetail[]),
  });
  protected readonly draftList = linkedSignal<ObjectiveDetail[] | undefined, ObjectiveDetail[]>({
    source: () => this.drafts.value(),
    computation: (v, prev) => v ?? prev?.value ?? [],
  });
  protected readonly recommendedMax = computed(() => this.tree.value()?.recommendedMax ?? 3);
  protected readonly overLimit = computed(() => this.draftList().length > this.recommendedMax());

  protected readonly turns = signal<Turn[]>([]);
  protected readonly decisions = signal<Record<string, Decision>>({});
  protected readonly question = signal('');
  protected readonly basisIds = signal<string[]>([]);
  protected readonly questionError = signal<string | null>(null);
  protected readonly busy = signal(false);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly errorList = signal<string[]>([]);
  private seq = 0;

  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );
  protected readonly allSuggestions = computed(() =>
    this.turns().flatMap((t) => t.result?.suggestions ?? []),
  );
  protected readonly vague = computed(() => vagueCodes(this.allSuggestions(), this.decisions()));
  protected readonly asking = computed(() => this.turns().some((t) => t.pending));
  protected readonly loadError = computed(() => {
    const e = this.ctx.error() ?? this.tree.error();
    return e ? this.problems.describe(e).message : null;
  });

  protected canEdit() {
    return this.can('SCR-0202.soan-don-vi') || this.can('SCR-0202.soan-ca-nhan');
  }

  protected krTitle(code: string | undefined): string {
    for (const d of this.draftList()) {
      const k = d.keyResults.find((x) => x.code === code);
      if (k) return k.title;
    }
    return code ?? '';
  }

  protected rewriteText(s: OkrAssistSuggestion): string {
    return msg1701(this.krTitle(s.krCode), s.text ?? '');
  }

  protected sourceText(s: OkrAssistSuggestion): string {
    return s.sources.map((x) => (x.location ? `${x.title}, ${x.location}` : x.title)).join('; ');
  }

  protected toggleBasis(id: string) {
    this.basisIds.update((l) => (l.includes(id) ? l.filter((x) => x !== id) : [...l, id]));
  }

  protected onQuestion(v: string) {
    this.question.set(v);
    if (this.questionError() && v.trim()) this.questionError.set(null);
  }

  protected ask() {
    const q = this.question().trim();
    const c = this.ctx.value();
    if (!c) return;
    if (!q) {
      this.questionError.set('Vui lòng nhập câu hỏi cho trợ lý.');
      return;
    }
    if (q.length > MAX_QUESTION) {
      this.questionError.set('Câu hỏi không quá 2.000 ký tự.');
      return;
    }
    const id = ++this.seq;
    this.turns.update((l) => [
      ...l,
      { id, question: q, pending: true, queued: false, result: null, error: null },
    ]);
    this.question.set('');
    this.ai
      .assistOkr({
        question: q,
        periodId: c.period.id,
        unitId: c.unit.id,
        basisIds: this.basisIds(),
        draftObjectiveIds: this.draftList().map((d) => d.id),
      })
      .pipe(
        switchMap((o): Observable<OkrAssistResult> => {
          if (o.kind === 'result') return of(o.result);
          this.patch(id, { queued: true });
          return this.jobs.track(o.location).pipe(
            last(undefined, null),
            switchMap((job) =>
              job?.resultLocation
                ? this.ai.assistResult(job.resultLocation)
                : throwError(() => new Error('Trợ lý chưa trả lời được. Vui lòng hỏi lại.')),
            ),
          );
        }),
      )
      .subscribe({
        next: (result) => this.patch(id, { pending: false, queued: false, result }),
        error: (e) =>
          this.patch(id, {
            pending: false,
            queued: false,
            error: e instanceof HttpErrorResponse ? this.problems.describe(e).message : e.message,
          }),
      });
  }

  protected dismiss(s: OkrAssistSuggestion) {
    this.decide(s, 'BO_QUA', null);
  }

  /** Dùng gợi ý: người dùng ghi vào OKR bằng API okr (trợ lý không có quyền ghi), sau đó báo chấp nhận. */
  protected use(s: OkrAssistSuggestion) {
    const c = this.ctx.value();
    if (!c || this.busy()) return;
    let write: Observable<ObjectiveDetail>;
    if (s.kind === 'MUC_TIEU') {
      const ownerType = this.can('SCR-0202.soan-don-vi') ? 'TAP_THE' : 'CA_NHAN';
      const basis = this.basisIds().length ? this.basisIds() : c.bases.slice(0, 1).map((b) => b.id);
      const no = (this.tree.value()?.objectives.filter((o) => (o.tier ?? 1) === 1).length ?? 0) + 1;
      write = this.okr.createObjective(objectiveFromSuggestion(s, c, ownerType, basis, no));
    } else {
      const d = this.draftList().find((x) => x.keyResults.some((k) => k.code === s.krCode));
      if (!d) {
        this.error.set('Không tìm thấy KR cần sửa trong bản nháp. Vui lòng tải lại trang.');
        return;
      }
      write = this.okr.saveObjective(d.id, d.rowVersion, applyRewrite(d, s.krCode!, s.text ?? ''));
    }
    this.start();
    write.subscribe({
      next: (saved) => {
        const kr = saved.keyResults.find((k) => k.code === s.krCode)?.id ?? saved.id;
        // ui-text-ok: `targetRef` gửi máy chủ để ghi nhật ký gợi ý, không hiện trên giao diện
        const ref = s.kind === 'MUC_TIEU' ? `okr.objective:${saved.id}` : `okr.key_result:${kr}`;
        this.decide(s, 'CHAP_NHAN', ref, MSG_17_04);
        this.tree.reload();
      },
      error: (e) => this.fail(e),
    });
  }

  protected submit(d: ObjectiveDetail) {
    if (this.busy()) return;
    this.start();
    this.okr.submitObjective(d.id).subscribe({
      next: (r) => {
        this.busy.set(false);
        this.notice.set(
          `Đã trình mục tiêu ${d.code} tới ${r.reviewerName}. Hạn duyệt: ${this.day(r.dueDate)}.`,
        );
        this.tree.reload();
      },
      error: (e) => this.fail(e),
    });
  }

  private decide(s: OkrAssistSuggestion, decision: Decision, ref: string | null, ok?: string) {
    if (!ok) this.start();
    this.ai.decide(s.id, { decision, ...(ref ? { targetRef: ref } : {}) }).subscribe({
      next: () => {
        this.busy.set(false);
        this.decisions.update((m) => ({ ...m, [s.id]: decision }));
        if (ok) this.notice.set(ok);
      },
      error: (e) => {
        // Nội dung đã ghi vào bản nháp; chỉ việc ghi nhận quyết định là chưa xong.
        if (ok) this.decisions.update((m) => ({ ...m, [s.id]: decision }));
        this.fail(e);
      },
    });
  }

  private start() {
    this.busy.set(true);
    this.notice.set(null);
    this.error.set(null);
    this.errorList.set([]);
  }

  private fail(e: unknown) {
    this.busy.set(false);
    const p = this.problems.describe(e);
    this.error.set(p.message);
    this.errorList.set((p.problem.errors ?? []).map((x) => x.message).filter(Boolean));
  }

  private patch(id: number, change: Partial<Turn>) {
    this.turns.update((l) => l.map((t) => (t.id === id ? { ...t, ...change } : t)));
  }
}
