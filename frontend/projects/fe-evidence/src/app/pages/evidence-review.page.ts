/*
 * SCR-0703 Nghiệm thu chất lượng 5 mức (QT-07 bước 7–9; TL-02 Hình 8.15). AC-05 là người nghiệm thu được
 * chỉ định; phiên AAL2 khi xác nhận.
 * Trường: 1 minh chứng, phiên bản (kiểm toàn vẹn khi mở); 2 tiêu chuẩn chất lượng (Đạt, Chưa đạt, ghi chú);
 * 3 mức B (1; 0,75; 0,5; 0,25; 0, BR-07-07); 4 nguyên nhân chỉnh sửa (Chủ quan, Khách quan khi có phiên bản
 * bị yêu cầu bổ sung); 5 nhận xét ≤ 2.000 ký tự, C2 (BR-07-08); 6 A, B, C, KPI xem trước; 7 người nghiệm thu.
 * Chức năng: "Mở minh chứng" (`POST …/versions/{v}/open-review`), "Xem trước điểm" (`POST
 * /appraisals/score-preview`), "Xác nhận nghiệm thu" (`POST …/accept`, Idempotency-Key, EVT-08), "Yêu cầu
 * bổ sung" (`POST …/request-revision`), "Từ chối" (`POST …/reject`); mở tệp qua `GET
 * /files/{id}/download-url`.
 * Quy tắc: BR-07-05, BR-07-06 người nghiệm thu khác người nộp (403), BR-07-07, BR-07-08, BR-07-11 MFA
 * trong 15 phút (401 `insufficient_user_authentication`). Thông báo MSG-07-09 … MSG-07-13.
 * Hợp đồng tách nguyên nhân chỉnh sửa thành `revisionCause` (theo mức B) và `isObjectiveRevision` (chủ
 * quan, khách quan); ô "Nguyên nhân chỉnh sửa" của TL-02 ánh xạ vào `isObjectiveRevision` (wiki/14).
 */
import {
  Component,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  signal,
  untracked,
} from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { toProblem } from '@vt/api-client';
import {
  EVIDENCE_STATUS_LABEL,
  EvidenceApi,
  EvidenceCriterionResult,
  EvidenceDecisionResult,
  EvidenceVersion,
  FileApi,
  QUALITY_LEVELS,
  QualityLevel,
} from '@vt/api-client/domains';
import { permittedOn } from '@vt/auth';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { firstValueFrom } from 'rxjs';
import { EVIDENCE_CAN } from '../evidence-permissions';
import { REVIEW_STEP_UP } from '../review-step-up';

type Busy = 'accept' | 'revision' | 'reject' | 'open' | null;
type Cause = 'CHU_QUAN' | 'KHACH_QUAN';

/** Kết quả đối chiếu đang nhập; `passed` rỗng là chưa đánh giá. */
interface CriterionRow {
  code: string;
  text: string;
  passed: boolean | null;
  note: string;
}

const num = (x: number, d = 2) =>
  x.toLocaleString('vi-VN', { minimumFractionDigits: d, maximumFractionDigits: 3 });

const fmtDateTime = (iso?: string | null) => {
  if (!iso) return '';
  const d = new Date(iso);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

@Component({
  selector: 'vt-evidence-review-page',
  imports: [VtSkeletonComponent, FormsModule, RouterLink, ButtonModule],
  template: `
    @if (!can('SCR-0703.mo')) {
      <p class="msg warn">Đồng chí không có quyền nghiệm thu minh chứng.</p>
    } @else if (evidence.error()) {
      <p class="msg err" role="alert">{{ loadError() }}</p>
    } @else if (evidence.value(); as e) {
      <header class="top">
        <div>
          <p class="back">
            <a routerLink=".." [queryParams]="{ reviewer: 'me' }"
              >← Minh chứng chờ tôi nghiệm thu</a
            >
          </p>
          <h1>Nghiệm thu sản phẩm</h1>
          <p class="muted sub">
            {{ e.task.code }} · {{ e.task.name }}
            @if (ver(); as v) {
              @if (v.submittedBy) {
                · người thực hiện {{ v.submittedBy.name }}
              }
              · minh chứng phiên bản {{ v.versionNo }} nộp {{ dateTime(v.submittedAt) }}
            }
          </p>
        </div>
        @if (ver(); as v) {
          <span class="chip st">{{ statusLabel[v.status] }}</span>
        }
      </header>

      @for (n of notices(); track n) {
        <p class="msg ok" role="status">{{ n }}</p>
      }
      @if (error(); as err) {
        <p class="msg err" role="alert">{{ err }}</p>
      }

      @if (ver(); as v) {
        @if (v.isDuplicateSuspect && v.duplicateOf; as m) {
          <p class="msg warn">
            Minh chứng có tệp trùng nội dung với {{ m.taskCode }}.
            @if (v.reuseJustification) {
              Giải trình của người nộp: "{{ v.reuseJustification }}".
            }
          </p>
        }
        <div class="layout">
          <div>
            <section class="box" aria-labelledby="ev-h">
              <h2 class="h" id="ev-h">Minh chứng</h2>
              <ul class="files">
                @if (v.sourceDocRef) {
                  <li>
                    <i class="pi pi-file" aria-hidden="true"></i>
                    <span>
                      <strong>Văn bản {{ v.docNumber }}</strong>
                      @if (v.docDate) {
                        · {{ date(v.docDate) }}
                      }
                      <span class="muted"> · {{ v.docSummary }}</span>
                      @if (!elderly()) {
                        <span class="muted small"> · tệp gốc và chữ ký số từ vOffice</span>
                      }
                    </span>
                  </li>
                }
                @if (v.objectId) {
                  <li>
                    <i class="pi pi-paperclip" aria-hidden="true"></i>
                    <span>
                      <strong>{{ v.fileName }}</strong>
                      @if (v.integrity?.verified) {
                        <span class="chip SACH">đã kiểm tra toàn vẹn</span>
                      }
                    </span>
                    @if (v.integrity?.verified) {
                      <p-button
                        label="Mở tệp"
                        icon="pi pi-external-link"
                        size="small"
                        severity="secondary"
                        [outlined]="true"
                        [ariaLabel]="'Mở tệp ' + v.fileName + ' trong thẻ mới'"
                        (onClick)="openFile(v.objectId)"
                      />
                    }
                  </li>
                }
              </ul>
              @if (v.note) {
                <p><span class="muted">Ghi chú của người nộp:</span> {{ v.note }}</p>
              }
            </section>

            <section class="box" aria-labelledby="crit-h">
              <h2 class="h" id="crit-h">Đối chiếu tiêu chuẩn chất lượng</h2>
              @if (rows().length) {
                <div
                  class="scroll"
                  tabindex="0"
                  role="region"
                  aria-label="Bảng tiêu chuẩn chất lượng"
                >
                  <table class="tbl">
                    <thead>
                      <tr>
                        <th scope="col">Tiêu chuẩn</th>
                        <th scope="col">Đánh giá</th>
                        <th scope="col">Ghi chú</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (r of rows(); track r.code; let i = $index) {
                        <tr>
                          <td [id]="'crit-' + i">{{ r.text }}</td>
                          <td class="nw">
                            <fieldset class="pair" [disabled]="!decidable()">
                              <legend class="sr-only">Đánh giá: {{ r.text }}</legend>
                              <label
                                ><input
                                  type="radio"
                                  [name]="'c' + i"
                                  [checked]="r.passed === true"
                                  (change)="setPassed(i, true)"
                                />
                                Đạt</label
                              >
                              <label
                                ><input
                                  type="radio"
                                  [name]="'c' + i"
                                  [checked]="r.passed === false"
                                  (change)="setPassed(i, false)"
                                />
                                Chưa đạt</label
                              >
                            </fieldset>
                          </td>
                          <td>
                            <input
                              class="inp"
                              maxlength="500"
                              [attr.aria-label]="'Ghi chú cho ' + r.text"
                              [disabled]="!decidable()"
                              [ngModel]="r.note"
                              (ngModelChange)="setNote(i, $event)"
                            />
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              } @else {
                <p class="muted">Mục danh mục không có tiêu chuẩn chất lượng riêng.</p>
              }
            </section>
          </div>

          <aside>
            @if (v.acceptance; as a) {
              <section class="box" aria-labelledby="dec-h">
                <h2 class="h" id="dec-h">Kết quả nghiệm thu</h2>
                <dl class="meta">
                  <dt>Quyết định</dt>
                  <dd>{{ statusLabel[a.decision] }}</dd>
                  @if (a.qualityLevel !== null && a.qualityLevel !== undefined) {
                    <dt>Mức chất lượng (B)</dt>
                    <dd>{{ fmt(a.qualityLevel) }}</dd>
                  }
                  @if (a.comment) {
                    <dt>Nhận xét</dt>
                    <dd>{{ a.comment }}</dd>
                  }
                  <dt>Người nghiệm thu</dt>
                  <dd>{{ a.reviewer.name }}, {{ dateTime(a.decidedAt) }}</dd>
                </dl>
              </section>
            } @else {
              <section class="box" aria-labelledby="lv-h">
                <h2 class="h" id="lv-h">Mức chất lượng (B)</h2>
                @if (decidable() && !criteriaDone()) {
                  <p class="muted small" id="lv-hint">
                    Vui lòng đánh giá tất cả tiêu chuẩn chất lượng trước khi chọn mức.
                  </p>
                }
                <fieldset class="levels" [disabled]="!decidable() || !criteriaDone()">
                  <legend class="sr-only">Mức chất lượng</legend>
                  @for (l of levels; track l.value) {
                    <label class="src" [class.on]="level() === l.value">
                      <input
                        type="radio"
                        name="level"
                        [checked]="level() === l.value"
                        (change)="level.set(l.value)"
                      />
                      <span
                        ><strong>{{ fmt(l.value) }}</strong> · {{ l.label }}</span
                      >
                    </label>
                  }
                </fieldset>
                @if (v.hasPriorRevisionRequest) {
                  <fieldset class="pair cause" [disabled]="!decidable()">
                    <legend class="lbl-row">
                      Nguyên nhân chỉnh sửa <span class="req" aria-hidden="true">*</span>
                    </legend>
                    <label
                      ><input
                        type="radio"
                        name="cause"
                        [checked]="cause() === 'CHU_QUAN'"
                        (change)="cause.set('CHU_QUAN')"
                      />
                      Chủ quan</label
                    >
                    <label
                      ><input
                        type="radio"
                        name="cause"
                        [checked]="cause() === 'KHACH_QUAN'"
                        (change)="cause.set('KHACH_QUAN')"
                      />
                      Khách quan</label
                    >
                  </fieldset>
                }
                <label for="cmt" class="lbl-row">
                  Nhận xét
                  @if (commentRequired()) {
                    <span class="req" aria-hidden="true">*</span>
                  }
                </label>
                <textarea
                  id="cmt"
                  class="inp"
                  rows="3"
                  maxlength="2000"
                  aria-describedby="cmt-help"
                  [disabled]="!decidable()"
                  [attr.aria-invalid]="commentMissing()"
                  [ngModel]="comment()"
                  (ngModelChange)="comment.set($event)"
                ></textarea>
                <p id="cmt-help" class="muted small">
                  @if (commentMissing()) {
                    <span class="err-text">Mức chất lượng dưới 1 cần có nhận xét.</span>
                  } @else {
                    Bắt buộc khi mức dưới 1, khi yêu cầu bổ sung hoặc từ chối.
                  }
                </p>
              </section>

              <section class="box" aria-labelledby="pv-h" aria-live="polite">
                <h2 class="h" id="pv-h">
                  Xem trước điểm
                  @if (preview.hasValue() && preview.value()?.policyVersion; as pv) {
                    <span class="muted small">({{ pv }})</span>
                  }
                </h2>
                @if (preview.error()) {
                  <!-- score-preview 422 khi kỳ chưa có lần tính điểm nào chứa nhiệm vụ (wiki 14) -->
                  <p class="muted">
                    Chưa xem trước được điểm của nhiệm vụ này; điểm được tính sau khi xác nhận
                    nghiệm thu.
                  </p>
                } @else if (preview.isLoading()) {
                  <vt-skeleton
                    variant="detail"
                    [rows]="4"
                    [header]="false"
                    label="Đang tính điểm xem trước"
                  />
                } @else if (preview.hasValue() && preview.value(); as p) {
                  <dl class="meta">
                    <dt>Khối lượng (A)</dt>
                    <dd>{{ fmt(p.a) }}</dd>
                    <dt>Chất lượng (B)</dt>
                    <dd>{{ fmt(p.b) }}</dd>
                    <dt>Tiến độ (C)</dt>
                    <dd>{{ fmt(p.c) }}</dd>
                    <dt>KPI nhiệm vụ</dt>
                    <dd>
                      <strong class="kpi">{{ fmt(p.kpi, 3) }}</strong>
                    </dd>
                  </dl>
                  @if (!elderly()) {
                    <p class="muted small">
                      <!-- điểm chính thức do scoring-service tính khi nhận MinhChungDaXacNhan -->
                      Đây là điểm xem trước; điểm chính thức được hệ thống tính sau khi xác nhận
                      nghiệm thu.
                    </p>
                  }
                } @else {
                  <p class="muted">Chọn mức chất lượng để xem trước điểm nhiệm vụ.</p>
                }
              </section>

              @if (decidable()) {
                <div class="actions dec">
                  @if (allowed('SCR-0703.bo-sung-tu-choi')) {
                    <p-button
                      label="Yêu cầu bổ sung"
                      severity="secondary"
                      [outlined]="true"
                      [loading]="busy() === 'revision'"
                      [disabled]="!!busy()"
                      (onClick)="giveBack('revision')"
                    />
                    <p-button
                      label="Từ chối"
                      severity="secondary"
                      [outlined]="true"
                      [loading]="busy() === 'reject'"
                      [disabled]="!!busy()"
                      (onClick)="giveBack('reject')"
                    />
                  }
                  @if (allowed('SCR-0703.xac-nhan')) {
                    <p-button
                      label="Xác nhận nghiệm thu"
                      [loading]="busy() === 'accept'"
                      [disabled]="!!busy() || acceptBlockers().length > 0"
                      [attr.aria-describedby]="acceptBlockers().length ? 'acc-why' : null"
                      (onClick)="accept()"
                    />
                  }
                </div>
                @if (acceptBlockers().length && allowed('SCR-0703.xac-nhan')) {
                  <p class="muted small" id="acc-why">
                    Để xác nhận: {{ acceptBlockers().join('; ') }}.
                  </p>
                }
              }
            }
          </aside>
        </div>
      } @else if (busy() === 'open') {
        <vt-skeleton variant="detail" label="Đang mở minh chứng và kiểm tra toàn vẹn" />
      }
    } @else {
      <vt-skeleton variant="detail" label="Đang tải minh chứng" />
    }
  `,
  styleUrl: './evidence-page.scss',
  styles: `
    .back {
      margin: 0;
    }
    .files {
      list-style: none;
      margin: 0 0 8px;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 10px;
    }
    .files li {
      display: flex;
      gap: 8px;
      align-items: center;
      flex-wrap: wrap;
    }
    .files li > span {
      flex: 1;
    }
    .pair {
      border: 0;
      margin: 0;
      padding: 0;
      display: flex;
      gap: 14px;
    }
    .pair label {
      display: flex;
      gap: 6px;
      align-items: center;
    }
    .pair input {
      width: 18px;
      height: 18px;
      accent-color: #c8102e;
    }
    .cause {
      flex-wrap: wrap;
      margin-top: 10px;
    }
    .levels {
      border: 0;
      margin: 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 8px;
    }
    .levels .src > span {
      display: block;
    }
    .kpi {
      color: #b00d28;
      font-size: 1.1em;
    }
    .dec {
      justify-content: flex-end;
      flex-wrap: wrap;
    }
  `,
})
export class EvidenceReviewPage {
  private readonly api = inject(EvidenceApi);
  private readonly files = inject(FileApi);
  private readonly theme = inject(ThemeService);
  private readonly stepUp = inject(REVIEW_STEP_UP);
  protected readonly can = inject(EVIDENCE_CAN);

  /** Mã minh chứng từ tuyến `/evidence/:id/review`. */
  readonly id = input.required<string>();

  protected readonly statusLabel = EVIDENCE_STATUS_LABEL;
  protected readonly levels = QUALITY_LEVELS;

  protected readonly evidence = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.get(params),
  });

  /** Phiên bản đang nghiệm thu: phiên bản mới nhất, sau khi gọi `open-review` (kiểm toàn vẹn). */
  protected readonly ver = signal<EvidenceVersion | null>(null);
  private openedFor: string | null = null;

  protected readonly rows = linkedSignal<CriterionRow[]>(() =>
    (this.ver()?.qualityCriteria ?? []).map((c) => ({
      code: c.code,
      text: c.text,
      passed: null,
      note: '',
    })),
  );
  protected readonly level = signal<QualityLevel | null>(null);
  protected readonly cause = signal<Cause | null>(null);
  protected readonly comment = signal('');

  protected readonly busy = signal<Busy>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notices = signal<string[]>([]);
  /** Toàn vẹn không bảo đảm (MSG-07-13): chặn mọi quyết định. */
  private readonly integrityFailed = signal(false);

  protected readonly elderly = computed(() => this.theme.mode() === 'elderly');

  /** Vai trò có quyền và máy chủ cho phép trên phiên bản đang nghiệm thu (YC-PT-21). */
  protected allowed(key: 'SCR-0703.xac-nhan' | 'SCR-0703.bo-sung-tu-choi'): boolean {
    return this.can(key) && permittedOn(this.ver(), key);
  }

  protected readonly decidable = computed(() => {
    const v = this.ver();
    return (
      !!v && v.status === 'DANG_NGHIEM_THU' && !!v.integrity?.verified && !this.integrityFailed()
    );
  });

  protected readonly criteriaDone = computed(() => this.rows().every((r) => r.passed !== null));
  protected readonly commentRequired = computed(() => {
    const l = this.level();
    return l !== null && l < 1;
  });
  protected readonly commentMissing = computed(
    () => this.commentRequired() && !this.comment().trim(),
  );

  protected readonly acceptBlockers = computed(() => {
    const out: string[] = [];
    if (!this.criteriaDone()) out.push('đánh giá tất cả tiêu chuẩn');
    if (this.level() === null) out.push('chọn mức chất lượng');
    if (this.ver()?.hasPriorRevisionRequest && !this.cause())
      out.push('chọn nguyên nhân chỉnh sửa');
    if (this.commentMissing()) out.push('ghi nhận xét vì mức dưới 1');
    return out;
  });

  protected readonly preview = rxResource({
    params: () => {
      const v = this.ver();
      const l = this.level();
      if (!v || l === null || !this.decidable()) return undefined;
      return {
        taskId: v.taskId,
        evidenceVersionId: v.id,
        qualityLevel: l,
        revisionCause: QUALITY_LEVELS.find((x) => x.value === l)!.cause,
        ...(v.hasPriorRevisionRequest
          ? { isObjectiveRevision: this.cause() === 'KHACH_QUAN' }
          : {}),
      };
    },
    stream: ({ params }) => this.api.scorePreview(params),
  });

  protected readonly loadError = computed(() => {
    const p = toProblem(this.evidence.error());
    return p.status === 404 ? 'Không tìm thấy minh chứng.' : p.detail || p.title;
  });

  constructor() {
    // khi có minh chứng: nạp phiên bản mới nhất và mở nghiệm thu một lần
    effect(() => {
      const e = this.evidence.value();
      if (e?.currentVersionNo) {
        const n = e.currentVersionNo;
        untracked(() => void this.open(e.id, n));
      }
    });
  }

  protected date(iso?: string | null) {
    return iso ? fmtDateTime(iso).slice(0, 10) : '';
  }

  protected dateTime(iso?: string | null) {
    return fmtDateTime(iso);
  }

  protected fmt(x: number, d = 2) {
    return num(x, d);
  }

  protected setPassed(i: number, passed: boolean) {
    this.rows.update((rs) => rs.map((r, k) => (k === i ? { ...r, passed } : r)));
  }

  protected setNote(i: number, note: string) {
    this.rows.update((rs) => rs.map((r, k) => (k === i ? { ...r, note } : r)));
  }

  private async open(id: string, versionNo: number) {
    const key = `${id}/${versionNo}`;
    if (this.openedFor === key) return;
    this.openedFor = key;
    this.busy.set('open');
    try {
      const v = await firstValueFrom(this.api.version(id, versionNo));
      if (v.status === 'DA_NOP' || v.status === 'DANG_NGHIEM_THU') {
        try {
          this.ver.set(await firstValueFrom(this.api.openReview(id, v)));
        } catch (err) {
          this.ver.set(v);
          this.showError(err);
        }
      } else this.ver.set(v);
    } catch (err) {
      this.showError(err);
    } finally {
      this.busy.set(null);
    }
  }

  protected async openFile(objectId: string) {
    try {
      const u = await firstValueFrom(this.files.downloadUrl(objectId));
      window.open(u.url, '_blank', 'noopener');
    } catch (err) {
      this.showError(err);
    }
  }

  private criteriaResults(): EvidenceCriterionResult[] {
    return this.rows()
      .filter((r) => r.passed !== null)
      .map((r) => ({
        criterionCode: r.code,
        passed: r.passed!,
        ...(r.note.trim() ? { note: r.note.trim() } : {}),
      }));
  }

  /** "Xác nhận nghiệm thu": MFA bước lên trước, gửi lại một lần nếu máy chủ vẫn trả 401. */
  protected async accept() {
    const v = this.ver();
    const l = this.level();
    if (!v || l === null || this.acceptBlockers().length) return;
    this.busy.set('accept');
    this.reset();
    const body = {
      qualityLevel: l,
      criteriaResults: this.criteriaResults(),
      revisionCause: QUALITY_LEVELS.find((x) => x.value === l)!.cause,
      ...(v.hasPriorRevisionRequest ? { isObjectiveRevision: this.cause() === 'KHACH_QUAN' } : {}),
      ...(this.comment().trim() ? { comment: this.comment().trim() } : {}),
    };
    try {
      if (!(await this.stepUp().catch(() => false))) {
        this.error.set('Chưa xác thực nhiều lớp nên chưa xác nhận nghiệm thu.');
        return;
      }
      let res: EvidenceDecisionResult;
      try {
        res = await firstValueFrom(this.api.accept(this.id(), v, body));
      } catch (err) {
        if (toProblem(err).status !== 401 || !(await this.stepUp().catch(() => false))) throw err;
        res = await firstValueFrom(this.api.accept(this.id(), v, body));
      }
      await this.done(res, `Đã xác nhận nghiệm thu mức ${num(l)}. Điểm nhiệm vụ đang được tính.`);
    } catch (err) {
      this.showError(err);
    } finally {
      this.busy.set(null);
    }
  }

  /** "Yêu cầu bổ sung" hoặc "Từ chối": nhận xét bắt buộc (BR-07-08). */
  protected async giveBack(kind: 'revision' | 'reject') {
    const v = this.ver();
    if (!v) return;
    this.reset();
    const comment = this.comment().trim();
    if (!comment) {
      this.error.set(
        kind === 'reject'
          ? 'Ghi lý do từ chối vào ô Nhận xét.'
          : 'Ghi nội dung cần bổ sung vào ô Nhận xét.',
      );
      document.getElementById('cmt')?.focus();
      return;
    }
    this.busy.set(kind);
    const body = { comment, criteriaResults: this.criteriaResults() };
    try {
      const res = await firstValueFrom(
        kind === 'reject'
          ? this.api.reject(this.id(), v, body)
          : this.api.requestRevision(this.id(), v, body),
      );
      await this.done(
        res,
        kind === 'reject'
          ? 'Đã từ chối minh chứng; người nộp được thông báo.'
          : 'Đã yêu cầu bổ sung; người nộp được thông báo để nộp phiên bản mới.',
      );
    } catch (err) {
      this.showError(err);
    } finally {
      this.busy.set(null);
    }
  }

  private async done(res: EvidenceDecisionResult, msg: string) {
    const v = this.ver()!;
    this.ver.set(await firstValueFrom(this.api.version(this.id(), v.versionNo)));
    this.notices.set([msg]);
    this.evidence.reload();
    if (res.taskStatusChanged === 'HOAN_THANH')
      this.notices.update((n) => [...n, 'Nhiệm vụ đã hoàn thành.']);
  }

  private reset() {
    this.error.set(null);
    this.notices.set([]);
  }

  private showError(err: unknown) {
    const p = toProblem(err);
    if (p.code === 'PRODUCT-409-INTEGRITY-FAILED') {
      this.integrityFailed.set(true);
      this.error.set(
        'Toàn vẹn minh chứng không được bảo đảm. Nghiệm thu bị chặn và sự cố đã được báo.',
      );
      return;
    }
    if (p.code === 'PRODUCT-403-SELF-REVIEW') {
      this.error.set(p.detail || p.title);
      return;
    }
    const fields = (p.errors ?? []).map((x) => x.message);
    this.error.set([p.title, ...fields].filter(Boolean).join(' '));
  }
}
