/*
 * SCR-0502 "Tạo nhiệm vụ từ văn bản chỉ đạo" (QT-05 bước 1–7, TL-02 Hình 8.4) — đối chiếu đặc tả:
 * Trường: 1 số, ký hiệu (NĐ 30/2020); 2 ngày văn bản, nơi nhận; 3 trích yếu, toàn văn; 4 đoạn nguồn (BR-05-07);
 *   5 tên ≤ 500; 6 chủ trì (BR-05-01); 7 phối hợp (không trùng chủ trì); 8 hạn trong kỳ; 9 sản phẩm ≤ 255;
 *   10 danh mục, KR gợi ý; 11 độ tin cậy 0–1; 12 trạng thái xác nhận (BR-05-05); 13 ô người đã sửa (ẩn).
 * Chức năng: Sửa (lưu khi xác nhận); Bỏ nhiệm vụ này (POST /ai/suggestions/{id}/decision); Gộp vào nhiệm vụ cũ
 *   (POST /tasks/{oldId}/source-docs, EVT-21); Xác nhận nhiệm vụ (POST /tasks/from-document + Idempotency-Key);
 *   Xác nhận và giao (POST /tasks/assign-batch, EVT-04); Bỏ qua văn bản (POST /tasks/source-docs/{id}/dismiss).
 * Quy tắc: BR-05-05, BR-05-06 (gộp hoặc ghi lý do, MSG-05-03), BR-05-07. Thông báo: MSG-05-06 … MSG-05-10.
 * Quyền (wiki/15a): SCR-0502.nhan-van-ban (xem), SCR-0502.xac-nhan, SCR-0502.xac-nhan-giao (AC-05 của đơn vị nhận).
 */
import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  AiApi,
  AssignBatchResult,
  CatalogApi,
  OkrApi,
  OrgApi,
  SOURCE_DOC_STATUS_LABEL,
  SOURCE_SYSTEM_LABEL,
  SimilarTask,
  SourceDocApi,
  TaskApi,
  TaskSuggestion,
  TaskSuggestionFields,
  toProblem,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { TagModule } from 'primeng/tag';
import { TextareaModule } from 'primeng/textarea';
import { map, of } from 'rxjs';
import { TASK_CAN } from '../task-permissions';
import { formatDate } from '../task-ui';
import { ReasonDialog } from './from-document/reason.dialog';
import { SuggestionCard, editedFields } from './from-document/suggestion-card';

type Busy = 'confirm' | 'merge' | 'reject' | 'assign' | 'dismiss' | null;

@Component({
  selector: 'vt-from-document-page',
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    ButtonModule,
    MessageModule,
    TagModule,
    TextareaModule,
    ReasonDialog,
    SuggestionCard,
  ],
  template: `
    @if (!can('SCR-0502.nhan-van-ban')) {
      <p-message severity="warn" text="Chức năng này dành cho lãnh đạo đơn vị nhận văn bản." />
    } @else if (doc.error()) {
      <p-message severity="error" [text]="loadError()" />
      <p><a routerLink="/tasks/documents">Về danh sách văn bản đến</a></p>
    } @else if (doc.value(); as d) {
      <header class="top">
        <div>
          <p class="crumb"><a routerLink="/tasks/documents">Văn bản đến</a> / {{ d.docNumber }}</p>
          <h1>Tạo nhiệm vụ từ văn bản chỉ đạo</h1>
          <p class="muted">
            Văn bản nhận từ {{ system(d.sourceSystem) }} qua integration-gateway ·
            {{ suggestions().length }} nhiệm vụ do AI trích, {{ pending().length }} chờ người xác
            nhận
          </p>
        </div>
        <div class="actions">
          @if (d.status === 'CHO_XU_LY' && can('SCR-0502.xac-nhan')) {
            <p-button
              label="Bỏ qua văn bản"
              severity="contrast"
              [outlined]="true"
              (onClick)="openDismiss()"
            />
          }
          @if (d.status === 'CHO_XU_LY' && can('SCR-0502.xac-nhan-giao')) {
            <p-button
              [label]="'Xác nhận và giao ' + toAssign().length + ' nhiệm vụ'"
              [disabled]="!toAssign().length || !!busy()"
              [loading]="busy() === 'assign'"
              (onClick)="assignAll()"
            />
          }
        </div>
      </header>

      @if (d.status !== 'CHO_XU_LY') {
        <p class="msg info">
          Văn bản {{ statusLabel(d.status).toLowerCase()
          }}{{ d.dismissReason ? ': ' + d.dismissReason : '.' }}
        </p>
      }
      <div aria-live="polite">
        @if (notice()) {
          <p class="msg ok" role="status">{{ notice() }}</p>
        }
        @for (m of warnings(); track $index) {
          <p class="msg warn">{{ m }}</p>
        }
        @if (error()) {
          <p class="msg err" role="alert">{{ error() }}</p>
        }
      </div>

      <div class="layout">
        <section class="doc" aria-labelledby="doc-title">
          <h2 id="doc-title" class="h">
            <span class="num" aria-hidden="true">1</span>
            {{ d.docNumber }} · {{ date(d.docDate) }}
            <p-tag [value]="system(d.sourceSystem)" severity="info" />
            <span class="muted small"
              >· nhận {{ d.receivedAt | date: 'dd/MM HH:mm' }} · {{ d.format }}</span
            >
          </h2>
          <div class="paper">
            <p class="issuer">{{ d.issuerHeader }}</p>
            <p class="star" aria-hidden="true">*</p>
            <p class="title">{{ d.title }}</p>
            <p>{{ d.preamble }}</p>
            @for (p of d.paragraphs; track p.no) {
              @let s = byParagraph().get(p.no);
              <p
                class="para"
                [class.hl]="p.assignment"
                [class.active]="!!s && s.id === selectedId()"
              >
                @if (s) {
                  <button
                    type="button"
                    class="para-link"
                    [title]="'Xem nhiệm vụ ' + indexOf(s)"
                    [attr.aria-pressed]="s.id === selectedId()"
                    (click)="select(s)"
                  >
                    {{ p.no }}. <span class="u">{{ p.text }}</span>
                  </button>
                } @else {
                  {{ p.no }}. <span [class.u]="p.assignment">{{ p.text }}</span>
                }
              </p>
            }
            <p class="muted">{{ d.recipients }}</p>
          </div>
        </section>

        <section class="ai" aria-labelledby="ai-title">
          @let x = extraction.value();
          <div class="ai-head">
            <h2 id="ai-title" class="h">
              <span class="num" aria-hidden="true">2</span>
              <span class="ai-label"
                >Nội dung do AI tạo
                @if (x && !simple()) {
                  · mô hình {{ x.model }} · trích lúc {{ x.extractedAt | date: 'dd/MM HH:mm' }}
                }
              </span>
            </h2>
            <p class="muted small msg07">
              Ô viền tím do AI điền. Sửa ô nào thì ô đó thành dữ liệu do người nhập.
            </p>
          </div>

          @if (extraction.isLoading()) {
            <p class="muted">Đang tải nhiệm vụ AI trích…</p>
          } @else if (x?.status === 'DANG_TRICH') {
            <p class="muted">AI đang trích nhiệm vụ từ văn bản này, vui lòng mở lại sau ít phút.</p>
          } @else if (x?.status === 'KHONG_TRICH_DUOC') {
            <p class="msg warn">
              Không trích được nhiệm vụ từ văn bản này. Vui lòng tạo nhiệm vụ thủ công.
            </p>
            @if (can('SCR-0501.soan') && d.status === 'CHO_XU_LY') {
              <p class="mt">
                <a routerLink="/tasks/assign" class="p-button p-component manual"
                  >Tạo nhiệm vụ thủ công</a
                >
              </p>
            }
          } @else {
            @for (s of suggestions(); track s.id; let i = $index) {
              <vt-suggestion-card
                [suggestion]="s"
                [index]="i + 1"
                [selected]="s.id === selectedId()"
                [editing]="s.id === editingId()"
                [simple]="simple()"
                [units]="units()"
                [catalog]="catalog()"
                [keyResults]="keyResults()"
                [maxDate]="period()?.endDate"
                [errors]="s.id === selectedId() ? fieldErrors() : {}"
                (pick)="select(s)"
                (fieldsChange)="edit(s, $event)"
              />
            }

            @if (selected(); as s) {
              @let n = indexOf(s);
              @if (s.decision === 'CHO_XAC_NHAN' && s.similar.length) {
                @let old = s.similar[0];
                <div class="dup" role="note">
                  <span class="num" aria-hidden="true">3</span>
                  <div>
                    <p>
                      Nhiệm vụ {{ n }} gần giống <strong>{{ old.code }}</strong> "{{ old.title }}"
                      ({{ statusOf(old) }}, chủ trì {{ old.leadName }}, độ tương đồng
                      {{ ratio(old.similarity) }}). Chọn: gộp vào nhiệm vụ cũ, hoặc vẫn tạo mới có
                      ghi lý do.
                    </p>
                    @if (can('SCR-0502.xac-nhan')) {
                      <label for="sim-reason">Lý do vẫn tạo nhiệm vụ mới</label>
                      <textarea
                        id="sim-reason"
                        pTextarea
                        rows="2"
                        maxlength="1000"
                        class="full"
                        [ngModel]="reasonOf(s)"
                        (ngModelChange)="setReason(s, $event)"
                        [attr.aria-invalid]="!!fieldErrors()['similarReason']"
                      ></textarea>
                    }
                  </div>
                </div>
              }

              @if (can('SCR-0502.xac-nhan') && d.status === 'CHO_XU_LY') {
                <div class="bar" role="group" [attr.aria-label]="'Xử lý nhiệm vụ ' + n">
                  <span class="num" aria-hidden="true">4</span>
                  @if (s.decision === 'CHO_XAC_NHAN') {
                    <p-button
                      [label]="s.id === editingId() ? 'Xong sửa' : 'Sửa'"
                      severity="contrast"
                      [outlined]="true"
                      (onClick)="toggleEdit(s)"
                    />
                    <p-button
                      label="Bỏ nhiệm vụ này"
                      severity="contrast"
                      [outlined]="true"
                      [disabled]="!!busy()"
                      (onClick)="rejectOpen.set(true)"
                    />
                    @if (s.similar.length) {
                      <p-button
                        [label]="'Gộp vào ' + s.similar[0].code"
                        severity="contrast"
                        [outlined]="true"
                        [loading]="busy() === 'merge'"
                        [disabled]="!!busy()"
                        (onClick)="merge(s, s.similar[0])"
                      />
                    }
                    <span class="spacer"></span>
                    <p-button
                      [label]="'Xác nhận nhiệm vụ ' + n"
                      [outlined]="true"
                      [loading]="busy() === 'confirm'"
                      [disabled]="!!busy()"
                      (onClick)="confirm(s)"
                    />
                  } @else if (s.decision === 'DA_XAC_NHAN' && s.taskStatus === 'NHAP') {
                    @if (can('SCR-0501.soan')) {
                      <a
                        [routerLink]="['/tasks/assign', s.taskId]"
                        class="p-button p-component p-button-outlined p-button-contrast link-btn"
                        >Sửa đầy đủ ở Giao việc</a
                      >
                    }
                    <p-button
                      label="Bỏ nhiệm vụ này"
                      severity="contrast"
                      [outlined]="true"
                      [disabled]="!!busy()"
                      (onClick)="rejectOpen.set(true)"
                    />
                    <span class="muted small"
                      >Đã tạo {{ s.taskCode }} ở trạng thái Nháp, chờ giao.</span
                    >
                  } @else {
                    <span class="muted small">{{ closedNote(s) }}</span>
                  }
                </div>
              }
            }
          }
        </section>
      </div>

      <vt-reason-dialog
        [(visible)]="rejectOpen"
        header="Bỏ nhiệm vụ này"
        [intro]="
          'Nhiệm vụ ' +
          indexOf(selected()) +
          ' sẽ không được tạo. Quyết định và lý do được ghi vết.'
        "
        confirmLabel="Bỏ nhiệm vụ"
        [busy]="busy() === 'reject'"
        [error]="dialogError()"
        (confirmed)="reject($event)"
      />
      <vt-reason-dialog
        [(visible)]="dismissOpen"
        header="Bỏ qua văn bản"
        [intro]="'Văn bản ' + d.docNumber + ' được đánh dấu đã xem và không tạo thêm nhiệm vụ.'"
        confirmLabel="Bỏ qua văn bản"
        [busy]="busy() === 'dismiss'"
        [error]="dialogError()"
        (confirmed)="dismiss($event)"
      />
    } @else {
      <p class="muted">Đang tải văn bản…</p>
    }
  `,
  styles: `
    :host {
      display: block;
      padding: 16px 24px;
    }
    .top {
      display: flex;
      justify-content: space-between;
      gap: 16px;
      flex-wrap: wrap;
      align-items: flex-start;
      margin-bottom: 12px;
    }
    h1 {
      font-size: 1.5rem;
      margin: 0 0 4px;
    }
    .crumb {
      margin: 0 0 4px;
      font-size: 0.9em;
    }
    .actions {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
    }
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 5fr) minmax(0, 7fr);
      gap: 16px;
      align-items: start;
    }
    .doc {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      padding: 12px 16px;
      background: var(--p-content-background, #fff);
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 10px;
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }
    .num {
      display: inline-grid;
      place-items: center;
      flex: none;
      width: 24px;
      height: 24px;
      border-radius: 50%;
      background: #111827;
      color: #fff;
      font-size: 0.8rem;
    }
    .paper {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 6px;
      padding: 16px 20px;
      font-family: 'Times New Roman', serif;
      font-size: 1.05rem;
      line-height: 1.6;
    }
    .issuer,
    .title {
      text-align: center;
      font-weight: 700;
      margin: 0;
    }
    .title {
      margin: 8px 0 12px;
    }
    .star {
      text-align: center;
      margin: 0;
    }
    .u {
      text-decoration: underline;
      text-decoration-color: #6d28d9;
      text-decoration-thickness: 2px;
      text-underline-offset: 3px;
    }
    .para-link {
      font: inherit;
      color: inherit;
      text-align: left;
      background: none;
      border: 0;
      padding: 0;
      cursor: pointer;
      display: block;
      width: 100%;
    }
    .para-link:focus-visible {
      outline: 2px solid #6d28d9;
    }
    .para.active {
      background: #f5f3ff;
      border-radius: 4px;
    }
    .ai-head {
      display: grid;
      grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
      gap: 12px;
      align-items: start;
    }
    .ai-label {
      color: #6d28d9;
      font-size: 0.95rem;
    }
    .msg07 {
      margin: 0 0 10px;
    }
    .dup {
      display: flex;
      gap: 10px;
      align-items: flex-start;
      border-left: 4px solid #b45309;
      background: #fffbeb;
      padding: 10px 14px;
      border-radius: 6px;
      margin-bottom: 12px;
    }
    .dup p {
      margin: 0 0 8px;
    }
    .dup label {
      display: block;
      font-weight: 500;
      margin-bottom: 4px;
    }
    .bar {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }
    .spacer {
      flex: 1;
    }
    .full {
      width: 100%;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
    }
    .mt {
      margin-top: 12px;
    }
    .msg {
      margin: 0 0 10px;
      padding: 10px 14px;
      border-radius: 6px;
      border-left: 4px solid;
    }
    .msg.ok {
      color: #14532d;
      background: #f0fdf4;
      border-color: #15803d;
    }
    .msg.warn {
      color: #78350f;
      background: #fffbeb;
      border-color: #b45309;
    }
    .msg.err {
      color: #7f1d1d;
      background: #fef2f2;
      border-color: #b91c1c;
    }
    .msg.info {
      color: #1e3a8a;
      background: #eff6ff;
      border-color: #1d4ed8;
    }
    .link-btn,
    .manual {
      text-decoration: none;
    }
    @media (max-width: 1100px) {
      .layout,
      .ai-head {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class FromDocumentPage {
  private readonly docsApi = inject(SourceDocApi);
  private readonly ai = inject(AiApi);
  private readonly taskApi = inject(TaskApi);
  private readonly orgApi = inject(OrgApi);
  private readonly okrApi = inject(OkrApi);
  private readonly catalogApi = inject(CatalogApi);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(TASK_CAN);

  /** Mã văn bản từ tuyến `/tasks/documents/:id`. */
  readonly id = input.required<string>();

  protected readonly doc = rxResource({
    params: () => (this.can('SCR-0502.nhan-van-ban') ? this.id() : undefined),
    stream: ({ params }) => this.docsApi.get(params),
  });
  protected readonly extraction = rxResource({
    params: () => (this.can('SCR-0502.nhan-van-ban') ? this.id() : undefined),
    stream: ({ params }) => this.ai.extraction(params),
  });
  private readonly template = rxResource({ stream: () => this.taskApi.newDraft() });
  private readonly unitsRes = rxResource({ stream: () => this.orgApi.units('unit') });
  private readonly catalogRes = rxResource({ stream: () => this.catalogApi.items({ limit: 200 }) });
  private readonly krRes = rxResource({
    params: () => this.template.value()?.period.id,
    stream: ({ params }) =>
      params ? this.okrApi.keyResults(params) : of({ items: [], nextCursor: null }),
  });

  protected readonly period = computed(() => this.template.value()?.period);
  protected readonly units = computed(() =>
    (this.unitsRes.value()?.items ?? []).map((u) => ({ value: u.id, label: u.name })),
  );
  protected readonly catalog = computed(() =>
    (this.catalogRes.value()?.items ?? []).map((c) => ({
      value: c.id,
      label: `${c.code} ${c.name}`,
    })),
  );
  protected readonly keyResults = computed(() =>
    (this.krRes.value()?.items ?? []).map((k) => ({
      value: k.id,
      label: `${k.code} · ${k.title}`,
    })),
  );

  /** Bản làm việc của các nhiệm vụ trích: ô người giao sửa giữ ở đây tới khi xác nhận. */
  protected readonly suggestions = linkedSignal<TaskSuggestion[]>(
    () => this.extraction.value()?.suggestions ?? [],
  );
  protected readonly pending = computed(() =>
    this.suggestions().filter((s) => s.decision === 'CHO_XAC_NHAN'),
  );
  /** Nhiệm vụ đã xác nhận, còn `NHAP`: "Xác nhận và giao" chỉ giao các nhiệm vụ này (BR-05-05). */
  protected readonly toAssign = computed(() =>
    this.suggestions().filter((s) => s.decision === 'DA_XAC_NHAN' && s.taskStatus === 'NHAP'),
  );
  protected readonly selectedId = linkedSignal<string | null>(() => {
    const list = this.suggestions();
    return (list.find((s) => s.decision === 'CHO_XAC_NHAN') ?? list[0])?.id ?? null;
  });
  protected readonly selected = computed(
    () => this.suggestions().find((s) => s.id === this.selectedId()) ?? null,
  );
  protected readonly byParagraph = computed(
    () => new Map(this.suggestions().map((s) => [s.paragraphNo, s] as const)),
  );

  protected readonly editingId = signal<string | null>(null);
  private readonly reasons = signal<Record<string, string>>({});
  protected readonly busy = signal<Busy>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly warnings = signal<string[]>([]);
  protected readonly error = signal<string | null>(null);
  protected readonly fieldErrors = signal<Record<string, string>>({});
  protected readonly dialogError = signal<string | null>(null);
  protected readonly rejectOpen = signal(false);
  protected readonly dismissOpen = signal(false);

  /** Chế độ người cao tuổi: ẩn độ tin cậy, mô hình, danh mục và KR gợi ý (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected readonly loadError = computed(() => {
    const p = toProblem(this.doc.error());
    return p.status === 404 ? 'Không tìm thấy văn bản.' : p.title;
  });

  protected system(s: keyof typeof SOURCE_SYSTEM_LABEL) {
    return SOURCE_SYSTEM_LABEL[s];
  }
  protected statusLabel(s: keyof typeof SOURCE_DOC_STATUS_LABEL) {
    return SOURCE_DOC_STATUS_LABEL[s];
  }
  protected date(d: string) {
    return formatDate(d, true);
  }
  protected ratio(x: number) {
    return x.toFixed(2).replace('.', ',');
  }
  protected statusOf(t: SimilarTask) {
    return t.status === 'DANG_THUC_HIEN'
      ? 'đang thực hiện'
      : t.status === 'DA_GIAO'
        ? 'đã giao'
        : t.status;
  }
  protected indexOf(s: TaskSuggestion | null) {
    return s ? this.suggestions().indexOf(s) + 1 : 0;
  }
  protected reasonOf(s: TaskSuggestion) {
    return this.reasons()[s.id] ?? '';
  }
  protected setReason(s: TaskSuggestion, v: string) {
    this.reasons.update((r) => ({ ...r, [s.id]: v }));
  }
  protected closedNote(s: TaskSuggestion): string {
    if (s.decision === 'DA_BO') return 'Đã bỏ nhiệm vụ này.';
    if (s.decision === 'DA_GOP') return `Văn bản đã thành căn cứ bổ sung của ${s.taskCode}.`;
    return `Đã giao ${s.taskCode}.`;
  }

  protected select(s: TaskSuggestion) {
    if (this.selectedId() !== s.id) {
      this.editingId.set(null);
      this.fieldErrors.set({});
      this.error.set(null);
    }
    this.selectedId.set(s.id);
  }

  protected toggleEdit(s: TaskSuggestion) {
    this.editingId.update((id) => (id === s.id ? null : s.id));
  }

  protected edit(s: TaskSuggestion, fields: TaskSuggestionFields) {
    this.replace({ ...s, fields });
  }

  private replace(next: TaskSuggestion) {
    this.suggestions.update((list) => list.map((x) => (x.id === next.id ? next : x)));
  }

  private begin(kind: Busy) {
    this.busy.set(kind);
    this.error.set(null);
    this.notice.set(null);
    this.warnings.set([]);
    this.fieldErrors.set({});
    this.dialogError.set(null);
  }

  private fail(e: unknown, inDialog = false) {
    const p = toProblem(e);
    this.busy.set(null);
    const fields: Record<string, string> = {};
    for (const x of p.errors ?? []) if (x.field) fields[x.field] = x.message;
    if (inDialog) {
      this.dialogError.set(p.errors?.[0]?.message ?? p.title);
      return;
    }
    this.fieldErrors.set(fields);
    // Lỗi theo ô sửa được thì mở chế độ sửa để người dùng thấy ngay.
    if (Object.keys(fields).some((k) => k !== 'similarReason'))
      this.editingId.set(this.selectedId());
    this.error.set(p.errors?.length ? p.errors.map((x) => x.message).join(' ') : p.title);
  }

  protected confirm(s: TaskSuggestion) {
    this.begin('confirm');
    const n = this.indexOf(s);
    this.docsApi
      .fromDocument({
        sourceDocId: this.id(),
        suggestionId: s.id,
        paragraphNo: s.paragraphNo,
        fields: s.fields,
        editedFields: editedFields(s),
        similarReason: this.reasonOf(s).trim() || undefined,
      })
      .subscribe({
        next: (saved) => {
          this.busy.set(null);
          this.editingId.set(null);
          this.replace(saved);
          this.notice.set(`Đã xác nhận nhiệm vụ ${n}, tạo ${saved.taskCode} ở trạng thái Nháp.`);
          const next = this.pending()[0];
          if (next) this.selectedId.set(next.id);
        },
        error: (e) => this.fail(e),
      });
  }

  protected merge(s: TaskSuggestion, old: SimilarTask) {
    this.begin('merge');
    this.docsApi
      .mergeInto(old.id, { sourceDocId: this.id(), suggestionId: s.id, paragraphNo: s.paragraphNo })
      .subscribe({
        next: (saved) => {
          this.busy.set(null);
          this.replace(saved);
          this.notice.set(
            `Đã gộp nhiệm vụ ${this.indexOf(saved)} vào ${old.code}; người chủ trì ${old.leadName} được thông báo.`,
          );
          this.doc.reload();
        },
        error: (e) => this.fail(e),
      });
  }

  protected reject(reason: string) {
    const s = this.selected();
    if (!s) return;
    this.begin('reject');
    this.ai.reject(s.id, reason).subscribe({
      next: (saved) => {
        this.busy.set(null);
        this.rejectOpen.set(false);
        this.replace(saved);
        this.notice.set(`Đã bỏ nhiệm vụ ${this.indexOf(saved)}.`);
        this.doc.reload();
      },
      error: (e) => this.fail(e, true),
    });
  }

  protected openDismiss() {
    this.dialogError.set(null);
    this.dismissOpen.set(true);
  }

  protected dismiss(reason: string) {
    this.begin('dismiss');
    this.docsApi.dismiss(this.id(), reason).subscribe({
      next: (d) => {
        this.busy.set(null);
        this.dismissOpen.set(false);
        this.doc.set(d);
      },
      error: (e) => this.fail(e, true),
    });
  }

  /** "Xác nhận và giao": giao các nhiệm vụ đã xác nhận; nhiệm vụ còn chờ được nêu theo MSG-05-08. */
  protected assignAll() {
    const ids = this.toAssign().map((s) => s.taskId!);
    const waiting = this.pending().map(
      (s) => `Nhiệm vụ ${this.indexOf(s)} chưa được xác nhận nên chưa giao được.`,
    );
    this.begin('assign');
    this.docsApi
      .assignBatch(ids)
      .pipe(map((results) => this.applyAssign(results)))
      .subscribe({
        next: (failed) => {
          this.busy.set(null);
          this.warnings.set([...failed, ...waiting]);
          this.doc.reload();
        },
        error: (e) => this.fail(e),
      });
  }

  private applyAssign(results: AssignBatchResult[]): string[] {
    const ok = results.filter((r) => r.assigned);
    this.suggestions.update((list) =>
      list.map((s) => {
        const r = results.find((x) => x.taskId === s.taskId && s.decision === 'DA_XAC_NHAN');
        return r ? { ...s, taskStatus: r.status } : s;
      }),
    );
    if (ok.length)
      this.notice.set(`Đã giao ${ok.length} nhiệm vụ: ${ok.map((r) => r.code).join(', ')}.`);
    return results
      .filter((r) => !r.assigned)
      .map((r) => `${r.code || 'Nhiệm vụ'} chưa giao được: ${r.message ?? ''}`.trim());
  }
}
