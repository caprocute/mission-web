/*
 * SCR-1201 "Gửi khiếu nại" (QT-12 bước 1, 2, 11; TL-02 Hình 11.3) — đối chiếu đặc tả:
 * Tác nhân AC-06 (`member`), chủ hồ sơ `DA_KY_SO`; web máy tính và di động.
 * Trường: 1 kết quả bị khiếu nại (chỉ kết quả còn hạn); 2 hình thức KHIEU_NAI/GIAI_TRINH/BAO_LUU (quá hạn chỉ
 *   còn BAO_LUU); 3 cấu phần bị khiếu nại (chip, ít nhất 1); 4 lý do, căn cứ 50–8.000 ký tự, C2; 5 đề nghị cụ
 *   thể ≤ 500 ký tự; 6 tài liệu kèm theo (≤ 20 MB mỗi tệp, quét mã độc, SHA-256); 7 bảo lưu ý kiến nếu không
 *   được chấp nhận.
 * Vùng: 1 tiêu đề và dòng thời hạn; 2 kết quả, hình thức; 3 cấu phần, lý do, đề nghị, tệp, bảo lưu; 4 thẻ
 *   "Quy trình xử lý"; 5 thẻ "Ảnh hưởng khi gửi" và ghi chú tách vai trò.
 * Chức năng: Lưu nháp (POST/PUT /oversight/complaints); Thêm tệp (POST /files…); Ký số và gửi
 *   (POST …/{id}/submit, 202, EVT-25, EVT-18); Rút khiếu nại (POST …/{id}/withdraw, EVT-21); Xác nhận bảo lưu
 *   (POST …/{id}/reserve).
 * Quy tắc, thông báo: BR-12-01 (MSG-12-01), BR-12-02 (MSG-12-03), BR-12-03, BR-12-06, BR-12-09, BR-12-10;
 *   MSG-12-02 khi gửi xong; MSG-12-04 khi có văn bản trả lời.
 * Quyền (wiki/15a): SCR-1201.soan, SCR-1201.ky-gui, SCR-1201.rut-bao-luu. Nội dung C2 không lưu cục bộ.
 */
import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { toProblem } from '@vt/api-client';
import {
  COMPLAINT_KIND_HINT,
  COMPLAINT_KIND_LABEL,
  COMPLAINT_LIMITS,
  COMPLAINT_OPEN_STATUSES,
  COMPLAINT_RESOLUTION_LABEL,
  COMPLAINT_STATUS_LABEL,
  ComplainableProfile,
  Complaint,
  ComplaintApi,
  ComplaintInput,
  ComplaintKind,
  FileApi,
  FileRejectedError,
  RATING_LEVEL_LABEL,
} from '@vt/api-client/domains';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { DialogModule } from 'primeng/dialog';
import { MessageModule } from 'primeng/message';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TagModule } from 'primeng/tag';
import { TextareaModule } from 'primeng/textarea';
import { firstValueFrom } from 'rxjs';
import { OVERSIGHT_CAN } from '../../oversight-permissions';
import { errorText, fmtDate, fmtDateTime } from '../../oversight-ui';
import { OVERSIGHT_STEP_UP } from '../../step-up';
import { COMPLAINT_STEPS, complaintSeverity, daysLeftText, fmtNum } from './complaint-ui';
import { ComplaintFlash } from './complaint-flash';

type Busy = 'save' | 'send' | 'upload' | 'withdraw' | 'reserve' | null;

const SIGN_REASON = 'Cần xác thực bổ sung để ký số nội dung khiếu nại.';

interface Attached {
  objectId: string;
  name: string;
  sha256?: string;
}

@Component({
  selector: 'vt-complaint-form-page',
  imports: [
    VtSkeletonComponent,
    FormsModule,
    RouterLink,
    ButtonModule,
    CheckboxModule,
    DialogModule,
    MessageModule,
    SelectModule,
    SelectButtonModule,
    TagModule,
    TextareaModule,
  ],
  template: `
    <p class="crumb"><a routerLink="..">Khiếu nại, giải trình của tôi</a></p>
    @if (complaint.error()) {
      <p-message severity="error">{{ loadError() }}</p-message>
    } @else if (id() && !current()) {
      <vt-skeleton variant="form" [rows]="5" label="Đang tải khiếu nại" />
    } @else {
      <!-- vùng 1 -->
      <header class="head">
        <div>
          <h1>
            @if (current(); as c) {
              {{ kindLabel[c.kind] }} {{ c.code }}
              <p-tag [value]="statusText(c)" [severity]="severity(c)" />
            } @else {
              Gửi khiếu nại kết quả đánh giá
            }
          </h1>
          @if (profile(); as p) {
            <p class="muted">
              Kết quả {{ p.period.label }} công bố {{ d(p.publishedAt) }}
              @if (p.publishDecision) {
                theo {{ p.publishDecision }}
              }
              · hạn gửi khiếu nại {{ d(p.complaintDeadline)
              }}{{ days(p.workingDaysLeft) ? ', ' + days(p.workingDaysLeft) : '' }}
            </p>
          }
        </div>
        @if (canWithdraw()) {
          <div class="actions">
            <p-button
              label="Rút khiếu nại"
              severity="danger"
              [outlined]="true"
              [disabled]="!!busy()"
              (onClick)="openWithdraw()"
            />
          </div>
        }
      </header>

      @if (notice(); as n) {
        <p-message severity="success" role="status">{{ n }}</p-message>
      }
      @if (error(); as e) {
        <p-message severity="error" role="alert">{{ e }}</p-message>
      }
      @if (isDraft() && pastDeadline()) {
        <!-- MSG-12-01 -->
        <p-message severity="warn">
          Đã quá thời hạn khiếu nại ({{ d(profile()?.complaintDeadline) }}). Đồng chí vẫn có thể gửi
          ý kiến dưới hình thức bảo lưu ý kiến.
        </p-message>
      }

      <div class="cols">
        <div>
          @if (isDraft()) {
            <!-- vùng 2 -->
            <section class="box" aria-labelledby="r-h">
              <h2 class="h" id="r-h">Kết quả, hình thức</h2>
              <div class="field">
                <label for="kn-profile" id="kn-profile-l">Kết quả bị khiếu nại (bắt buộc)</label>
                <p-select
                  inputId="kn-profile"
                  ariaLabelledBy="kn-profile-l"
                  [options]="eligibleList()"
                  optionLabel="text"
                  optionValue="value"
                  [ngModel]="profileId2()"
                  (ngModelChange)="pickProfile($event)"
                  placeholder="Chọn kết quả"
                  styleClass="w-full"
                />
                @if (errs()['profileId']; as m) {
                  <p class="err">{{ m }}</p>
                }
              </div>
              <div class="field">
                <span class="label" id="kn-kind-l">Hình thức (bắt buộc)</span>
                <p-selectbutton
                  [options]="kindOptions()"
                  optionLabel="label"
                  optionValue="value"
                  [ngModel]="kind()"
                  (ngModelChange)="kind.set($event)"
                  [allowEmpty]="false"
                  ariaLabelledBy="kn-kind-l"
                />
                <p class="hint">{{ kindHint[kind()] }}</p>
              </div>
            </section>

            <!-- vùng 3 -->
            <section class="box" aria-labelledby="c-h">
              <h2 class="h" id="c-h">Nội dung</h2>
              <div class="field">
                <span class="label" id="kn-targets-l">Cấu phần bị khiếu nại (chọn ít nhất 1)</span>
                <ul class="chips" aria-labelledby="kn-targets-l">
                  @for (t of profile()?.targets ?? []; track t.key) {
                    <li>
                      <button
                        type="button"
                        class="chip"
                        [attr.aria-pressed]="targets().includes(t.key)"
                        (click)="toggleTarget(t.key)"
                      >
                        {{ t.label ?? t.key }}
                      </button>
                    </li>
                  }
                </ul>
                @if (errs()['targets']; as m) {
                  <p class="err" role="alert">{{ m }}</p>
                }
              </div>
              <div class="field">
                <label for="kn-content">Lý do, căn cứ (bắt buộc, 50–8.000 ký tự)</label>
                <textarea
                  pTextarea
                  id="kn-content"
                  rows="6"
                  [maxlength]="limits.contentMax"
                  [ngModel]="content()"
                  (ngModelChange)="content.set($event)"
                  aria-describedby="kn-content-h"
                ></textarea>
                <p class="hint" id="kn-content-h">
                  {{ content().trim().length }} ký tự. Nội dung khiếu nại được bảo mật; mỗi lần xem
                  đều được ghi nhận.
                </p>
                @if (errs()['content']; as m) {
                  <p class="err">{{ m }}</p>
                }
              </div>
              <div class="field">
                <label for="kn-request">Đề nghị cụ thể (bắt buộc, tối đa 500 ký tự)</label>
                <textarea
                  pTextarea
                  id="kn-request"
                  rows="2"
                  [maxlength]="limits.requestMax"
                  [ngModel]="requestText()"
                  (ngModelChange)="requestText.set($event)"
                ></textarea>
                @if (errs()['requestText']; as m) {
                  <p class="err">{{ m }}</p>
                }
              </div>
              <div class="field">
                <label for="kn-file"
                  >Tài liệu kèm theo (không bắt buộc, tối đa 20 MB mỗi tệp)</label
                >
                <input
                  id="kn-file"
                  type="file"
                  accept=".pdf,.docx,.jpg,.jpeg,.png"
                  [disabled]="!!busy()"
                  (change)="onPick($event)"
                />
                @if (busy() === 'upload') {
                  <p class="hint" role="status">Đang tải tệp lên…</p>
                }
                @if (files().length) {
                  <ul class="files">
                    @for (f of files(); track f.objectId) {
                      <li>
                        {{ f.name }}
                        <p-button
                          label="Bỏ"
                          [text]="true"
                          size="small"
                          [ariaLabel]="'Bỏ tệp ' + f.name"
                          (onClick)="removeFile(f.objectId)"
                        />
                      </li>
                    }
                  </ul>
                }
              </div>
              <div class="field check">
                <p-checkbox
                  inputId="kn-reserve"
                  [binary]="true"
                  [ngModel]="reserve()"
                  (ngModelChange)="reserve.set($event)"
                />
                <label for="kn-reserve" class="inline"
                  >Bảo lưu ý kiến nếu khiếu nại không được chấp nhận</label
                >
              </div>
              <div class="actions">
                @if (can('SCR-1201.soan')) {
                  <p-button
                    label="Lưu nháp"
                    severity="contrast"
                    [outlined]="true"
                    [loading]="busy() === 'save'"
                    [disabled]="!!busy()"
                    (onClick)="saveDraft()"
                  />
                }
                @if (can('SCR-1201.ky-gui')) {
                  <p-button
                    label="Ký số và gửi"
                    [loading]="busy() === 'send'"
                    [disabled]="!!busy()"
                    (onClick)="send()"
                  />
                }
              </div>
            </section>
          } @else if (current(); as c) {
            <section class="box" aria-labelledby="v-h">
              <h2 class="h" id="v-h">Nội dung đã gửi</h2>
              <dl class="meta">
                <dt>Kết quả</dt>
                <dd>{{ c.subjectLine }}</dd>
                <dt>Gửi lúc</dt>
                <dd>{{ dt(c.filedAt) }}</dd>
                <dt>Hạn trả lời</dt>
                <dd>
                  {{ d(c.responseDeadline) }}
                  @if (open() && days(c.workingDaysLeft); as t) {
                    ({{ t }})
                  }
                </dd>
                @if (c.handler) {
                  <dt>Người xử lý</dt>
                  <dd>{{ c.handler.name }}{{ c.handlerUnit ? ', ' + c.handlerUnit.name : '' }}</dd>
                }
              </dl>
              <ul class="chips" aria-label="Cấu phần bị khiếu nại">
                @for (t of c.targets ?? []; track t.key) {
                  <li>
                    <span class="chip static">{{ t.label ?? t.key }}</span>
                  </li>
                }
              </ul>
              <h3 class="h3">Lý do, căn cứ</h3>
              <p class="text">{{ c.content }}</p>
              <h3 class="h3">Đề nghị cụ thể</h3>
              <p class="text">{{ c.requestText }}</p>
              @if (c.attachmentObjectIds?.length) {
                <p class="muted small">Kèm {{ c.attachmentObjectIds?.length }} tài liệu.</p>
              }
              @if (c.timeline?.length) {
                <h3 class="h3">Tiến trình</h3>
                <ol class="timeline">
                  @for (t of c.timeline; track $index) {
                    <li>
                      <strong>{{ t.action }}</strong>
                      <span class="muted small">
                        · {{ dt(t.at) }}{{ t.actor ? ' · ' + t.actor.name : '' }}</span
                      >
                    </li>
                  }
                </ol>
              }
            </section>

            @if (answer(); as a) {
              <section class="box" aria-labelledby="a-h">
                <h2 class="h" id="a-h">Văn bản trả lời {{ a.responseDocRef ?? '' }}</h2>
                @if (c.status === 'DA_TRA_LOI') {
                  <!-- MSG-12-04 -->
                  <p-message severity="info">
                    Khiếu nại {{ c.code }} đã có văn bản trả lời. Đồng chí có 5 ngày làm việc để xác
                    nhận hoặc bảo lưu ý kiến.
                  </p-message>
                }
                <p>
                  Kết quả xử lý: <strong>{{ a.outcome ? resolutionLabel[a.outcome] : '—' }}</strong>
                </p>
                @if (a.adjustments?.length) {
                  <ul>
                    @for (x of a.adjustments; track x.targetKey) {
                      <li>
                        {{ targetLabel(x.targetKey) }}: {{ num(x.oldValue) }} thành
                        {{ num(x.newValue) }} ({{ x.reason }})
                      </li>
                    }
                  </ul>
                }
                <p class="text">{{ a.content }}</p>
                @if (c.reservedOpinion) {
                  <h3 class="h3">Ý kiến bảo lưu đã xác nhận</h3>
                  <p class="text">{{ c.reservedOpinion }}</p>
                } @else if (canReserve()) {
                  <div class="field">
                    <label for="bl-opinion">Ý kiến bảo lưu (lưu vào hồ sơ, in trên Mẫu 3)</label>
                    <textarea
                      pTextarea
                      id="bl-opinion"
                      rows="3"
                      [maxlength]="limits.contentMax"
                      [ngModel]="opinion()"
                      (ngModelChange)="opinion.set($event)"
                    ></textarea>
                  </div>
                  <p-button
                    label="Xác nhận bảo lưu"
                    [loading]="busy() === 'reserve'"
                    [disabled]="!opinion().trim() || !!busy()"
                    (onClick)="confirmReserve()"
                  />
                }
              </section>
            }
          }
        </div>

        <aside>
          <!-- vùng 4 -->
          <section class="box" aria-labelledby="s-h">
            <h2 class="h" id="s-h">Quy trình xử lý</h2>
            <ol class="steps">
              @for (s of steps; track s.title) {
                <li>
                  {{ s.title }}
                  <span class="muted small block">{{ s.due }}</span>
                </li>
              }
            </ol>
          </section>
          @if (!simple()) {
            <!-- vùng 5 -->
            <section class="box" aria-labelledby="i-h">
              <h2 class="h" id="i-h">Ảnh hưởng khi gửi</h2>
              <ul class="impact">
                <li>Hồ sơ đánh giá chuyển nhánh khiếu nại; kết quả hiện hành vẫn có hiệu lực.</li>
                <li>Gói hồ sơ năm có hồ sơ này bị chặn ký cho tới khi có kết luận.</li>
                <li>
                  Người xử lý do hệ thống chọn, không phải người đã nghiệm thu, thẩm định, đề xuất
                  hay quyết định hồ sơ của đồng chí.
                </li>
              </ul>
            </section>
          }
        </aside>
      </div>

      <p-dialog
        header="Rút khiếu nại"
        [modal]="true"
        [visible]="withdrawOpen()"
        (visibleChange)="withdrawOpen.set($event)"
        closeAriaLabel="Đóng"
        [style]="{ width: 'min(520px, 95vw)' }"
      >
        <p>Khiếu nại được đóng với lý do rút; hồ sơ đánh giá trở lại trạng thái đã ký số.</p>
        <label for="rut-reason">Lý do rút (bắt buộc, 10–2.000 ký tự)</label>
        <textarea
          pTextarea
          id="rut-reason"
          rows="3"
          maxlength="2000"
          [ngModel]="withdrawReason()"
          (ngModelChange)="withdrawReason.set($event)"
        ></textarea>
        @if (dialogError(); as e) {
          <p-message severity="error">{{ e }}</p-message>
        }
        <ng-template #footer>
          <p-button
            label="Đóng"
            severity="contrast"
            [outlined]="true"
            (onClick)="withdrawOpen.set(false)"
          />
          <p-button
            label="Rút khiếu nại"
            severity="danger"
            [loading]="busy() === 'withdraw'"
            [disabled]="withdrawReason().trim().length < limits.withdrawReasonMin || !!busy()"
            (onClick)="withdraw()"
          />
        </ng-template>
      </p-dialog>
    }
  `,
  styleUrls: ['./complaints.css'],
  styles: `
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 3fr) minmax(260px, 1.3fr);
      gap: 16px;
      align-items: start;
      margin-top: 12px;
    }
    @media (max-width: 900px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    .check {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    label.inline {
      display: inline;
      margin: 0;
      font-weight: 400;
    }
    .files {
      list-style: none;
      padding: 0;
      margin: 6px 0 0;
    }
    .mono {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
    }
    .block {
      display: block;
    }
    .impact {
      margin: 0;
      padding-left: 18px;
    }
    p-dialog textarea {
      width: 100%;
      margin: 4px 0 8px;
    }
  `,
})
export class ComplaintFormPage {
  private readonly api = inject(ComplaintApi);
  private readonly files$ = inject(FileApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly theme = inject(ThemeService);
  private readonly stepUp = inject(OVERSIGHT_STEP_UP);
  private readonly flash = inject(ComplaintFlash);
  protected readonly can = inject(OVERSIGHT_CAN);

  /** Mã khiếu nại từ tuyến `my-complaints/:id`; trang `new` không có. */
  readonly id = input<string>();
  /** Kết quả chọn sẵn từ SCR-0903 hoặc danh sách (`?profileId=`). */
  readonly profileId = input<string>();

  protected readonly limits = COMPLAINT_LIMITS;
  protected readonly kindLabel = COMPLAINT_KIND_LABEL;
  protected readonly kindHint = COMPLAINT_KIND_HINT;
  protected readonly resolutionLabel = COMPLAINT_RESOLUTION_LABEL;
  protected readonly steps = COMPLAINT_STEPS;

  protected readonly eligible = rxResource({ stream: () => this.api.eligibleProfiles() });
  protected readonly complaint = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.get(params),
  });
  protected readonly current = linkedSignal<Complaint | undefined>(() =>
    this.complaint.hasValue() ? this.complaint.value() : undefined,
  );
  protected readonly responses = rxResource({
    params: () => {
      const c = this.current();
      return c && c.status && c.conclusion ? `${c.id}#${c.rowVersion}` : undefined;
    },
    stream: ({ params }) => this.api.responses(params.split('#')[0], 'KET_LUAN'),
  });
  protected readonly answer = computed(() =>
    (this.responses.hasValue() ? this.responses.value() : undefined)?.at(-1),
  );

  protected readonly isDraft = computed(() => !this.id() || this.current()?.status === null);
  protected readonly open = computed(() => {
    const s = this.current()?.status;
    return !!s && COMPLAINT_OPEN_STATUSES.includes(s);
  });
  protected readonly canWithdraw = computed(() => this.open() && this.can('SCR-1201.rut-bao-luu'));
  protected readonly canReserve = computed(() => {
    const c = this.current();
    return c?.status === 'DA_TRA_LOI' && !!c.reserveOpinion && this.can('SCR-1201.rut-bao-luu');
  });
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  // ── trạng thái biểu mẫu; khởi tạo lại khi tải khiếu nại nháp ──
  protected readonly profileId2 = linkedSignal<string>(
    () =>
      this.current()?.profileId ??
      this.profileId() ??
      (this.eligible.hasValue() ? this.eligible.value() : undefined)?.[0]?.profileId ??
      '',
  );
  protected readonly profile = computed<ComplainableProfile | undefined>(() =>
    (this.eligible.hasValue() ? this.eligible.value() : undefined)?.find(
      (e) => e.profileId === this.profileId2(),
    ),
  );
  protected readonly pastDeadline = computed(() => (this.profile()?.workingDaysLeft ?? 0) < 0);
  protected readonly kind = linkedSignal<{ p?: ComplainableProfile; c?: Complaint }, ComplaintKind>(
    {
      source: () => ({ p: this.profile(), c: this.current() }),
      computation: ({ p, c }) => {
        const allowed = p?.allowedKinds ?? [];
        if (c && allowed.includes(c.kind)) return c.kind;
        return allowed.includes('KHIEU_NAI') ? 'KHIEU_NAI' : (allowed[0] ?? 'BAO_LUU');
      },
    },
  );
  protected readonly targets = linkedSignal<{ p?: string; c?: Complaint }, string[]>({
    source: () => ({ p: this.profileId2(), c: this.current() }),
    computation: ({ p, c }) => (c && c.profileId === p ? (c.targets ?? []).map((t) => t.key) : []),
  });
  protected readonly content = linkedSignal(() => this.current()?.content ?? '');
  protected readonly requestText = linkedSignal(() => this.current()?.requestText ?? '');
  protected readonly reserve = linkedSignal(() => this.current()?.reserveOpinion ?? false);
  protected readonly files = linkedSignal<Attached[]>(() =>
    (this.current()?.attachmentObjectIds ?? []).map((objectId, i) => ({
      objectId,
      name: `Tài liệu ${i + 1}`,
    })),
  );

  protected readonly eligibleList = computed(() =>
    ((this.eligible.hasValue() ? this.eligible.value() : undefined) ?? []).map((e) => ({
      value: e.profileId,
      text: `Xếp loại ${e.period.label} · ${e.rating ? RATING_LEVEL_LABEL[e.rating] : '—'}${e.score !== undefined ? ` · ${e.score.toLocaleString('vi-VN')} điểm` : ''}`,
    })),
  );
  protected readonly kindOptions = computed(() =>
    (this.profile()?.allowedKinds ?? []).map((k) => ({ value: k, label: COMPLAINT_KIND_LABEL[k] })),
  );

  protected readonly busy = signal<Busy>(null);
  protected readonly notice = signal<string | null>(this.flash.take());
  protected readonly error = signal<string | null>(null);
  protected readonly errs = signal<Record<string, string>>({});
  protected readonly withdrawOpen = signal(false);
  protected readonly withdrawReason = signal('');
  protected readonly dialogError = signal<string | null>(null);
  protected readonly opinion = signal('');

  protected readonly loadError = computed(() => {
    const p = toProblem(this.complaint.error());
    return p.status === 404 ? 'Không tìm thấy khiếu nại.' : errorText(this.complaint.error());
  });

  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected d(iso?: string | null) {
    return fmtDate(iso);
  }
  protected days(n?: number | null) {
    return daysLeftText(n);
  }
  /** Nhãn cấu phần theo khiếu nại; không tìm thấy thì để trống thay vì hiện khoá kỹ thuật. */
  protected targetLabel(key: string) {
    const c = this.current();
    return c?.targets?.find((t) => t.key === key)?.label ?? 'Cấu phần';
  }
  protected num(v: number | string | null | undefined) {
    return fmtNum(v);
  }
  protected statusText(c: Complaint) {
    return c.status ? COMPLAINT_STATUS_LABEL[c.status] : 'Nháp';
  }
  protected severity(c: Complaint) {
    return complaintSeverity(c.status);
  }

  protected pickProfile(id: string) {
    this.profileId2.set(id);
    this.errs.set({});
  }
  protected toggleTarget(key: string) {
    const cur = this.targets();
    this.targets.set(cur.includes(key) ? cur.filter((k) => k !== key) : [...cur, key]);
  }
  protected removeFile(objectId: string) {
    this.files.set(this.files().filter((f) => f.objectId !== objectId));
  }

  protected onPick(ev: Event) {
    const el = ev.target as HTMLInputElement;
    const f = el.files?.[0];
    el.value = '';
    if (f) void this.upload(f);
  }
  private async upload(f: File) {
    this.error.set(null);
    if (f.size > COMPLAINT_LIMITS.attachmentMaxBytes) {
      this.error.set(`Tệp "${f.name}" vượt 20 MB.`);
      return;
    }
    this.busy.set('upload');
    try {
      const o = await this.files$.upload(f, {
        ownerModule: 'oversight',
        ownerRefId: this.current()?.id,
      });
      this.files.set([
        ...this.files(),
        { objectId: o.objectId, name: o.fileName ?? f.name, sha256: o.sha256 },
      ]);
    } catch (e) {
      this.error.set(e instanceof FileRejectedError ? e.message : errorText(e));
    } finally {
      this.busy.set(null);
    }
  }

  private input(): ComplaintInput {
    const p = this.profile();
    const keys = this.targets();
    const targets = (p?.targets ?? []).filter((t) => keys.includes(t.key));
    return {
      profileId: this.profileId2(),
      kind: this.kind(),
      // bước bị khiếu nại lấy theo cấu phần đầu tiên được chọn (suy luận)
      targetStage: targets[0]?.stage ?? p?.targets[0]?.stage ?? 'THAM_DINH',
      targets,
      content: this.content(),
      requestText: this.requestText(),
      attachmentObjectIds: this.files().map((f) => f.objectId),
      reserveOpinion: this.reserve(),
    };
  }

  /** Kiểm nhanh ở trình duyệt theo bảng trường; máy chủ kiểm lại khi gửi. */
  private validate(): boolean {
    const e: Record<string, string> = {};
    if (!this.profile()) e['profileId'] = 'Chọn kết quả còn trong thời hạn khiếu nại.';
    if (!this.targets().length) e['targets'] = 'Vui lòng chọn ít nhất một cấu phần bị khiếu nại.';
    const n = this.content().trim().length;
    if (n < COMPLAINT_LIMITS.contentMin || n > COMPLAINT_LIMITS.contentMax)
      e['content'] = `Lý do, căn cứ cần từ 50 đến 8.000 ký tự (hiện có ${n}).`;
    if (!this.requestText().trim()) e['requestText'] = 'Nêu đề nghị cụ thể.';
    this.errs.set(e);
    return !Object.keys(e).length;
  }

  private async persist(): Promise<Complaint> {
    const c = this.current();
    const next = await firstValueFrom(
      c ? this.api.update(c, this.input()) : this.api.create(this.input()),
    );
    this.current.set(next);
    return next;
  }

  protected async saveDraft() {
    if (!this.profile()) {
      this.errs.set({ profileId: 'Chọn kết quả còn trong thời hạn khiếu nại.' });
      return;
    }
    this.error.set(null);
    this.notice.set(null);
    this.busy.set('save');
    try {
      const fresh = !this.id();
      const c = await this.persist();
      if (fresh) {
        this.flash.set(`Đã lưu nháp ${c.code}.`);
        await this.router.navigate(['..', c.id], { relativeTo: this.route, replaceUrl: true });
      } else this.notice.set(`Đã lưu nháp lúc ${fmtDateTime(new Date().toISOString()).slice(11)}.`);
    } catch (e) {
      this.fail(e);
    } finally {
      this.busy.set(null);
    }
  }

  protected async send() {
    this.error.set(null);
    this.notice.set(null);
    if (!this.validate()) return;
    if (!(await this.stepUp(SIGN_REASON))) return;
    this.busy.set('send');
    try {
      const fresh = !this.id();
      let c = await this.persist();
      await firstValueFrom(this.api.submit(c));
      c = await firstValueFrom(this.api.get(c.id));
      // MSG-12-02
      const text = `Đã gửi khiếu nại ${c.code}. Kết quả hiện hành vẫn có hiệu lực cho tới khi có kết luận. Hạn trả lời: ${fmtDate(c.responseDeadline)}.`;
      if (fresh) {
        this.flash.set(text);
        await this.router.navigate(['..', c.id], { relativeTo: this.route, replaceUrl: true });
      } else {
        this.current.set(c);
        this.notice.set(text);
      }
    } catch (e) {
      this.fail(e);
    } finally {
      this.busy.set(null);
    }
  }

  protected openWithdraw() {
    this.withdrawReason.set('');
    this.dialogError.set(null);
    this.withdrawOpen.set(true);
  }
  protected withdraw() {
    const c = this.current();
    if (!c) return;
    this.busy.set('withdraw');
    this.api.withdraw(c, this.withdrawReason().trim()).subscribe({
      next: (next) => {
        this.busy.set(null);
        this.withdrawOpen.set(false);
        this.current.set(next);
        this.notice.set(`Đã rút khiếu nại ${next.code}.`);
      },
      error: (e) => {
        this.busy.set(null);
        this.dialogError.set(errorText(e));
      },
    });
  }

  protected confirmReserve() {
    const c = this.current();
    if (!c) return;
    this.busy.set('reserve');
    this.error.set(null);
    this.api.reserve(c, this.opinion().trim()).subscribe({
      next: (next) => {
        this.busy.set(null);
        this.current.set(next);
        this.notice.set('Đã lưu ý kiến bảo lưu vào hồ sơ; ý kiến được in trên Mẫu 3.');
      },
      error: (e) => this.fail(e),
    });
  }

  private fail(e: unknown) {
    this.busy.set(null);
    const p = toProblem(e);
    const fe: Record<string, string> = {};
    for (const x of p.errors ?? []) if (x.field) fe[x.field] = x.message;
    this.errs.set(fe);
    this.error.set(
      p.status === 412 || p.status === 409
        ? `${errorText(e)} Tải lại trang để xem trạng thái mới nhất.`
        : errorText(e),
    );
    if (p.status === 412 || p.status === 409) this.complaint.reload();
  }
}
