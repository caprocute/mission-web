/*
 * SCR-1301 "Yêu cầu mở lại kỳ" (QT-13, TL-02 Hình 11.7) — đối chiếu đặc tả:
 * Tác nhân AC-07, AC-10 tạo; AC-08 người duyệt thứ nhất; AC-09 người duyệt thứ hai; web máy tính.
 * Trường: 1 mã MLK-yyyy-nnn; 2 kỳ (chỉ kỳ DA_KHOA); 3 loại hậu kiểm KE_HOACH/DOT_XUAT/PHAN_ANH; 4 phạm vi
 *   (hồ sơ, bảng, trường được sửa; không chọn toàn kỳ nếu không có quyết định hậu kiểm toàn diện); 5 thời
 *   hạn mở 1–10 ngày làm việc; 6 lý do 50–4.000 ký tự; 7 căn cứ (khiếu nại, cảnh báo, quyết định); 8 ảnh
 *   hưởng dự kiến (tính thử, chỉ đọc); 9 người duyệt và trạng thái (họ tên, vai trò, thời điểm, MFA, chữ
 *   ký); 10 ý kiến người duyệt 10–2.000 ký tự.
 * Vùng: 1 tiêu đề, dòng căn cứ; 2 phạm vi; 3 lý do và ảnh hưởng dự kiến; 4 thẻ "Hai người duyệt"; 5 thẻ
 *   duyệt (ý kiến, "Từ chối", "Xác thực MFA và duyệt", việc hệ thống làm khi đủ hai phê duyệt).
 * Chức năng: Tính thử ảnh hưởng (POST …/{id}/simulate); Xác thực MFA và duyệt (POST …/{id}/approvals,
 *   EVT-18, đủ hai thì EVT-22); Từ chối (POST …/{id}/reject, EVT-21); Huỷ yêu cầu (POST …/{id}/cancel).
 *   Tạo yêu cầu ở trang `new` (reopen-create.page.ts).
 * Quy tắc, thông báo: BR-13-02, 03 (403 MSG-13-01), BR-13-04 (step-up MSG-13-02), BR-13-05, BR-13-06;
 *   MSG-13-03 khi đủ hai phê duyệt.
 * Quyền (wiki/15a): SCR-1301.tao, duyet-1, duyet-2, tu-choi, huy; SCR-1302.tra-cuu cho liên kết sổ cái.
 */
import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import {
  PERIOD_STATUS_LABEL,
  REOPEN_APPROVER_ROLE_LABEL,
  REOPEN_AUDIT_KIND_LABEL,
  REOPEN_BASIS_LABEL,
  REOPEN_LIMITS,
  REOPEN_PENDING_STATUSES,
  REOPEN_STATUS_LABEL,
  ReopenApi,
  ReopenApproverSlot,
  ReopenRequest,
  toProblem,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { MessageModule } from 'primeng/message';
import { TagModule } from 'primeng/tag';
import { TextareaModule } from 'primeng/textarea';
import { Observable } from 'rxjs';
import { OVERSIGHT_CAN } from '../../oversight-permissions';
import { errorText, fmtDateTime } from '../../oversight-ui';
import { OVERSIGHT_STEP_UP } from '../../step-up';
import { fieldLabel, reopenSeverity, scopeSummary } from './reopen-ui';

type Busy = 'approve' | 'reject' | 'cancel' | 'simulate' | null;

/** MSG-13-02. */
const MSG_13_02 = 'Cần xác thực bổ sung để phê duyệt mở lại kỳ.';

@Component({
  selector: 'vt-reopen-detail-page',
  imports: [
    FormsModule,
    RouterLink,
    ButtonModule,
    DialogModule,
    MessageModule,
    TagModule,
    TextareaModule,
  ],
  template: `
    <p class="crumb"><a routerLink="..">Hậu kiểm, mở lại kỳ</a></p>
    @if (req.error()) {
      <p-message severity="error">{{ loadError() }}</p-message>
    } @else if (current(); as r) {
      <!-- vùng 1 -->
      <header class="head">
        <div>
          <h1>
            Yêu cầu mở lại kỳ {{ r.code }}
            <p-tag [value]="statusLabel[r.status]" [severity]="severity(r)" />
          </h1>
          <p class="muted">
            Kỳ {{ r.period.label }}
            @if (r.periodStatus) {
              ({{ periodStatus(r) }})
            }
            · tạo bởi {{ r.requestedBy.name }} lúc {{ dt(r.createdAt) }}
            @if (r.basis; as b) {
              · căn cứ: {{ basisLabel[b.kind] }} <strong>{{ b.code }}</strong>
            }
          </p>
        </div>
        <div class="actions">
          @if (can('SCR-1302.tra-cuu') && ledgerTarget(); as t) {
            <a
              class="p-button p-button-outlined p-button-contrast"
              routerLink="../../ledger"
              [queryParams]="t"
              >Xem sổ cái của đối tượng</a
            >
          }
          @if (pending() && can('SCR-1301.huy')) {
            <p-button
              label="Huỷ yêu cầu"
              severity="danger"
              [outlined]="true"
              [disabled]="!!busy()"
              (onClick)="openCancel()"
            />
          }
        </div>
      </header>

      @if (notice(); as n) {
        <p-message severity="success" role="status">{{ n }}</p-message>
      }
      @if (error(); as e) {
        <p-message severity="error" role="alert">{{ e }}</p-message>
      }

      <div class="cols">
        <div>
          <!-- vùng 2 -->
          <section class="box" aria-labelledby="scope-h">
            <h2 class="h" id="scope-h"><span class="num" aria-hidden="true">2</span> Phạm vi</h2>
            <dl class="meta">
              <dt>Loại hậu kiểm</dt>
              <dd>{{ r.auditKind ? auditKind[r.auditKind] : '—' }}</dd>
              <dt>Phạm vi</dt>
              <dd>{{ scope(r) }}</dd>
              <dt>Thời hạn mở</dt>
              <dd>
                @if (r.executedAt) {
                  Đã mở lúc {{ dt(r.executedAt) }}, đến {{ dt(r.openUntil) }}
                  @if (r.relockedAt) {
                    ; đã khoá lại lúc {{ dt(r.relockedAt) }}
                  }
                } @else {
                  Đến {{ dt(r.openUntil) }} (tính theo lịch làm việc của tenant khi đủ phê duyệt)
                }
              </dd>
            </dl>
            <div class="table-wrap">
              <table class="tbl">
                <caption class="sr-only">
                  Đối tượng và dữ liệu được phép sửa
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Đối tượng</th>
                    <th scope="col">Bảng</th>
                    <th scope="col">Dữ liệu được phép sửa</th>
                  </tr>
                </thead>
                <tbody>
                  @for (it of r.scopeDetail?.items ?? []; track $index) {
                    <tr>
                      <td>
                        @if (it.profileId) {
                          Hồ sơ <span class="mono">{{ it.profileId }}</span>
                        } @else if (it.unitId) {
                          Đơn vị <span class="mono">{{ it.unitId }}</span>
                        } @else {
                          Toàn kỳ
                        }
                        @if (it.recordId && !simple()) {
                          <span class="block small muted">bản ghi {{ it.recordId }}</span>
                        }
                      </td>
                      <td class="mono">{{ it.table }}</td>
                      <td>{{ fields(it.table, it.fields) }}</td>
                    </tr>
                  } @empty {
                    <tr>
                      <td colspan="3" class="muted">Không có chi tiết phạm vi.</td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          </section>

          <!-- vùng 3 -->
          <section class="box" aria-labelledby="reason-h">
            <h2 class="h" id="reason-h">
              <span class="num" aria-hidden="true">3</span> Lý do mở lại
            </h2>
            <p class="reason">{{ r.reason }}</p>
            <h3 class="h3" id="impact-h">Ảnh hưởng dự kiến (tính thử, chưa ghi)</h3>
            @if (impact.isLoading()) {
              <p class="muted">Đang tính thử…</p>
            } @else if (impact.error()) {
              <p class="muted">Chưa tính thử được: {{ impactError() }}</p>
            } @else if (impact.value(); as im) {
              <table class="tbl" aria-labelledby="impact-h">
                <thead>
                  <tr>
                    <th scope="col">Chỉ tiêu</th>
                    <th scope="col">Trước</th>
                    <th scope="col">Sau</th>
                  </tr>
                </thead>
                <tbody>
                  @for (row of im.rows; track $index) {
                    <tr>
                      <td>{{ row.metric }}</td>
                      <td>{{ row.before }}</td>
                      <td>
                        <strong>{{ row.after }}</strong>
                        @if (row.after === row.before) {
                          <span class="small muted"> (giữ nguyên)</span>
                        }
                      </td>
                    </tr>
                  }
                  <tr>
                    <td>Biểu mẫu cần sinh lại</td>
                    <td colspan="2">{{ im.formsToRegenerate ?? 0 }}</td>
                  </tr>
                </tbody>
              </table>
              @if (!simple()) {
                <p class="small muted">
                  Tính bằng Policy Pack {{ im.policyVersion }} đã dùng khi khoá kỳ (BR-13-06).
                </p>
              }
            }
            @if (pending()) {
              <p-button
                label="Tính thử lại"
                severity="contrast"
                [text]="true"
                size="small"
                [disabled]="impact.isLoading()"
                (onClick)="impact.reload()"
              />
            }
          </section>
        </div>

        <aside>
          <!-- vùng 4 -->
          <section class="box" aria-labelledby="appr-h">
            <h2 class="h" id="appr-h">
              <span class="num" aria-hidden="true">4</span> Hai người duyệt
            </h2>
            <ol class="slots">
              @for (s of r.approvers ?? []; track s.round) {
                <li [class]="slotTone(r, s)">
                  <div class="slot-head">
                    <strong>{{ s.round }}. {{ roleLabel[s.role] }}</strong>
                    <span class="small">{{ slotState(r, s) }}</span>
                  </div>
                  <div>
                    {{ s.approval?.approver?.name ?? s.assignee?.name ?? 'Chờ OPA chỉ định' }}
                  </div>
                  @if (s.approval; as a) {
                    <div class="small muted">
                      {{ dt(a.decidedAt) }} · MFA {{ a.mfaAcr ?? 'aal2' }} ·
                      {{ a.decision === 'DONG_Y' ? 'đã ký số phê duyệt' : 'đã ký số từ chối' }}
                    </div>
                    @if (a.comment) {
                      <div class="small">“{{ a.comment }}”</div>
                    }
                  }
                </li>
              }
            </ol>
            @if (!simple()) {
              <p class="small muted">
                Hai người duyệt phải khác nhau, khác người tạo yêu cầu và chưa tham gia thẩm định,
                quyết định hồ sơ bị mở (BR-13-02, BR-13-03). Không có cách hạ xuống một người duyệt.
              </p>
            }
          </section>

          <!-- vùng 5 -->
          @if (canDecide()) {
            <section class="box decide" aria-labelledby="dec-h">
              <h2 class="h" id="dec-h">
                <span class="num" aria-hidden="true">5</span>
                {{ r.status === 'CHO_DUYET_1' ? 'Duyệt lượt thứ nhất' : 'Duyệt lượt thứ hai' }}
              </h2>
              <label for="dec-comment">Ý kiến người duyệt (bắt buộc, 10–2.000 ký tự)</label>
              <textarea
                pTextarea
                id="dec-comment"
                rows="4"
                [maxlength]="limits.commentMax"
                [ngModel]="comment()"
                (ngModelChange)="comment.set($event)"
                [attr.aria-describedby]="'dec-hint'"
              ></textarea>
              <p id="dec-hint" class="small muted">
                {{ msg1302 }}
                @if (r.status === 'CHO_DUYET_2' && !simple()) {
                  Khi đủ hai phê duyệt, hệ thống mở lại kỳ {{ r.period.label }} chỉ trong phạm vi đã
                  duyệt đến hết thời hạn, ghi sổ cái và thông báo người được giao sửa dữ liệu.
                }
              </p>
              <div class="actions">
                @if (can('SCR-1301.tu-choi')) {
                  <p-button
                    label="Từ chối"
                    severity="danger"
                    [outlined]="true"
                    [loading]="busy() === 'reject'"
                    [disabled]="!!busy() || !commentOk()"
                    (onClick)="decide('reject')"
                  />
                }
                <p-button
                  label="Xác thực MFA và duyệt"
                  [loading]="busy() === 'approve'"
                  [disabled]="!!busy() || !commentOk()"
                  (onClick)="decide('approve')"
                />
              </div>
            </section>
          }
        </aside>
      </div>

      <p-dialog
        header="Huỷ yêu cầu mở lại kỳ"
        [modal]="true"
        [(visible)]="cancelOpen"
        closeAriaLabel="Đóng"
        [style]="{ width: 'min(520px, 95vw)' }"
      >
        <p>Yêu cầu {{ r.code }} sẽ đóng và không ai duyệt tiếp được.</p>
        <label for="cc-reason">Lý do huỷ (bắt buộc, 10–2.000 ký tự)</label>
        <textarea
          pTextarea
          id="cc-reason"
          rows="3"
          maxlength="2000"
          [ngModel]="cancelReason()"
          (ngModelChange)="cancelReason.set($event)"
        ></textarea>
        @if (dialogError(); as e) {
          <p-message severity="error" role="alert">{{ e }}</p-message>
        }
        <ng-template #footer>
          <p-button
            label="Đóng"
            severity="contrast"
            [outlined]="true"
            (onClick)="cancelOpen.set(false)"
          />
          <p-button
            label="Huỷ yêu cầu"
            severity="danger"
            [loading]="busy() === 'cancel'"
            [disabled]="cancelReason().trim().length < 10"
            (onClick)="cancel()"
          />
        </ng-template>
      </p-dialog>
    } @else {
      <p class="muted">Đang tải yêu cầu…</p>
    }
  `,
  styles: `
    .crumb {
      margin: 0 0 4px;
    }
    .head {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      flex-wrap: wrap;
    }
    h1 {
      font-size: 1.4rem;
      margin: 4px 0;
      display: flex;
      align-items: center;
      gap: 10px;
      flex-wrap: wrap;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.875em;
    }
    .block {
      display: block;
    }
    .mono {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 0.875em;
      word-break: break-all;
    }
    .actions {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      align-items: center;
    }
    a.p-button {
      text-decoration: none;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 3fr) minmax(280px, 2fr);
      gap: 16px;
      align-items: start;
      margin-top: 12px;
    }
    @media (max-width: 1000px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      margin-bottom: 12px;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 8px;
    }
    .h3 {
      font-size: 1rem;
      margin: 12px 0 6px;
    }
    .num {
      display: inline-flex;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: #1e3a8a;
      color: #fff;
      font-size: 0.8rem;
      align-items: center;
      justify-content: center;
      margin-right: 4px;
    }
    .meta {
      display: grid;
      grid-template-columns: max-content 1fr;
      gap: 4px 12px;
      margin: 0 0 8px;
    }
    .meta dt {
      color: #4b5563;
    }
    .meta dd {
      margin: 0;
    }
    .reason {
      white-space: pre-wrap;
      margin: 0 0 8px;
    }
    .table-wrap {
      overflow-x: auto;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
    }
    .tbl th,
    .tbl td {
      text-align: left;
      padding: 6px 8px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
      vertical-align: top;
    }
    .tbl th {
      font-weight: 600;
    }
    .slots {
      list-style: none;
      padding: 0;
      margin: 0 0 8px;
      display: grid;
      gap: 8px;
    }
    .slots li {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-left-width: 4px;
      border-radius: 6px;
      padding: 8px 10px;
    }
    .slots li.done {
      border-left-color: #15803d;
    }
    .slots li.now {
      border-left-color: #b45309;
    }
    .slots li.no {
      border-left-color: #b91c1c;
    }
    .slot-head {
      display: flex;
      justify-content: space-between;
      gap: 8px;
    }
    .decide textarea {
      width: 100%;
      margin: 4px 0;
    }
    p-dialog textarea {
      width: 100%;
      margin: 4px 0 8px;
    }
    label {
      font-weight: 600;
      display: block;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
    :host ::ng-deep .p-button-danger:not(.p-button-outlined) {
      background: #b91c1c;
      border-color: #b91c1c;
    }
    :host ::ng-deep .p-button-danger.p-button-outlined {
      color: #b91c1c;
      border-color: #b91c1c;
    }
    :host ::ng-deep .p-message-success .p-message-text,
    :host ::ng-deep .p-message-success .p-message-icon {
      color: #0f5132;
    }
  `,
})
export class ReopenDetailPage {
  private readonly api = inject(ReopenApi);
  private readonly theme = inject(ThemeService);
  private readonly stepUp = inject(OVERSIGHT_STEP_UP);
  protected readonly can = inject(OVERSIGHT_CAN);

  /** Mã yêu cầu từ tuyến `reopen-requests/:id`. */
  readonly id = input.required<string>();

  protected readonly statusLabel = REOPEN_STATUS_LABEL;
  protected readonly auditKind = REOPEN_AUDIT_KIND_LABEL;
  protected readonly basisLabel = REOPEN_BASIS_LABEL;
  protected readonly roleLabel = REOPEN_APPROVER_ROLE_LABEL;
  protected readonly limits = REOPEN_LIMITS;
  protected readonly msg1302 = MSG_13_02;

  protected readonly req = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.get(params),
  });
  /** Bản đang hiển thị: thay bằng bản máy chủ trả sau mỗi thao tác. */
  protected readonly current = linkedSignal<ReopenRequest | undefined>(() => this.req.value());
  protected readonly pending = computed(() => {
    const s = this.current()?.status;
    return !!s && REOPEN_PENDING_STATUSES.includes(s);
  });
  /** Tính thử chỉ khi còn chờ duyệt; không ghi gì (BR-13-06). */
  protected readonly impact = rxResource({
    params: () => (this.pending() ? this.current()?.id : undefined),
    stream: ({ params }) => this.api.simulate(params),
  });

  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );
  protected readonly busy = signal<Busy>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly comment = signal('');
  protected readonly cancelOpen = signal(false);
  protected readonly cancelReason = signal('');
  protected readonly dialogError = signal<string | null>(null);

  protected readonly commentOk = computed(() => {
    const n = this.comment().trim().length;
    return n >= REOPEN_LIMITS.commentMin && n <= REOPEN_LIMITS.commentMax;
  });
  /** Người duyệt đúng lượt theo ma trận (OPA ở máy chủ kiểm người được chỉ định, BR-13-02). */
  protected readonly canDecide = computed(() => {
    const s = this.current()?.status;
    return (
      (s === 'CHO_DUYET_1' && this.can('SCR-1301.duyet-1')) ||
      (s === 'CHO_DUYET_2' && this.can('SCR-1301.duyet-2'))
    );
  });
  protected readonly ledgerTarget = computed(() => {
    const it = this.current()?.scopeDetail?.items?.find((x) => x.profileId);
    return it?.profileId ? { aggregateType: 'appraisal_profile', aggregateId: it.profileId } : null;
  });
  protected readonly loadError = computed(() => {
    const p = toProblem(this.req.error());
    return p.status === 404 ? 'Không tìm thấy yêu cầu mở lại kỳ.' : errorText(this.req.error());
  });
  protected readonly impactError = computed(() => errorText(this.impact.error()));

  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected severity(r: ReopenRequest) {
    return reopenSeverity(r.status);
  }
  protected scope(r: ReopenRequest) {
    return scopeSummary(r);
  }
  protected periodStatus(r: ReopenRequest) {
    return r.periodStatus ? PERIOD_STATUS_LABEL[r.periodStatus] : '';
  }
  protected fields(table: string, keys?: string[]) {
    return keys?.length ? keys.map((k) => fieldLabel(table, k)).join(', ') : 'Mọi trường';
  }
  protected slotState(r: ReopenRequest, s: ReopenApproverSlot): string {
    if (s.approval?.decision === 'DONG_Y') return 'Đã duyệt';
    if (s.approval?.decision === 'TU_CHOI') return 'Đã từ chối';
    if (r.status === `CHO_DUYET_${s.round}`) return 'Đang chờ duyệt';
    return REOPEN_PENDING_STATUSES.includes(r.status) ? 'Chưa tới lượt' : 'Không còn duyệt';
  }
  protected slotTone(r: ReopenRequest, s: ReopenApproverSlot): string {
    if (s.approval?.decision === 'DONG_Y') return 'done';
    if (s.approval?.decision === 'TU_CHOI') return 'no';
    return r.status === `CHO_DUYET_${s.round}` ? 'now' : '';
  }

  protected async decide(kind: 'approve' | 'reject') {
    const r = this.current();
    if (!r || !this.commentOk()) return;
    this.error.set(null);
    this.notice.set(null);
    if (!(await this.stepUp(MSG_13_02))) return;
    this.busy.set(kind);
    const text = this.comment().trim();
    const call: Observable<ReopenRequest> =
      kind === 'approve' ? this.api.approve(r, text) : this.api.reject(r, text);
    call.subscribe({
      next: (next) => {
        this.busy.set(null);
        this.comment.set('');
        this.current.set(next);
        this.notice.set(this.doneText(kind, next));
      },
      error: (e) => this.fail(e),
    });
  }

  protected openCancel() {
    this.cancelReason.set('');
    this.dialogError.set(null);
    this.cancelOpen.set(true);
  }
  protected cancel() {
    const r = this.current();
    if (!r) return;
    this.busy.set('cancel');
    this.api.cancel(r, this.cancelReason().trim()).subscribe({
      next: (next) => {
        this.busy.set(null);
        this.cancelOpen.set(false);
        this.current.set(next);
        this.notice.set(`Đã huỷ yêu cầu ${next.code}.`);
      },
      error: (e) => {
        this.busy.set(null);
        this.dialogError.set(errorText(e));
      },
    });
  }

  private doneText(kind: 'approve' | 'reject', r: ReopenRequest): string {
    if (kind === 'reject') return `Đã từ chối yêu cầu ${r.code}; người tạo được thông báo.`;
    if (r.status === 'CHO_DUYET_2')
      return `Đã ghi phê duyệt thứ nhất cho ${r.code}; yêu cầu chuyển người duyệt thứ hai.`;
    // MSG-13-03
    const n = r.scope === 'TOAN_KY' ? 'toàn kỳ' : String(r.scopeIds?.length ?? 0);
    return `Yêu cầu ${r.code} đã đủ hai phê duyệt. Kỳ ${r.period.label} được mở lại trong phạm vi ${n} đối tượng đến ${fmtDateTime(r.openUntil)}.`;
  }

  private fail(e: unknown) {
    this.busy.set(null);
    const p = toProblem(e);
    this.error.set(
      p.status === 412 || p.status === 409
        ? `${errorText(e)} Tải lại trang để xem trạng thái mới nhất.`
        : errorText(e),
    );
    if (p.status === 412 || p.status === 409) this.req.reload();
  }
}
