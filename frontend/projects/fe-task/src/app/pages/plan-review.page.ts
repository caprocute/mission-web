/*
 * SCR-0403 Phê duyệt kế hoạch (QT-04 bước 6, 7a, 7b; TL-02 Hình 7.14). AC-04 của đơn vị, phiên AAL2.
 * Trường: 1 phiên bản so sánh, 2 loại thay đổi (Thêm, Sửa, Bỏ), 3 lý do thay đổi, 4 lần điều chỉnh n/tối đa,
 * 5 mã băm phiên bản (SHA-256), 6 ý kiến người duyệt (bắt buộc khi trả lại, ≤ 2.000), 7 phương thức ký số
 * (MySign, USB token, chứng thư tổ chức), 8 yêu cầu ký (ẩn).
 * Chức năng: "Chỉ hiện thay đổi / Toàn bộ" (`GET …/versions/{v}/diff?base=`), "Trả lại" (`POST …/return`,
 * EVT-21), "Duyệt và ký số" (`POST …/approve` kèm Idempotency-Key trả 202 Job; mã yêu cầu ký đọc ở
 * `PlanVersion.signRequestId`, rồi theo dõi `GET /esign/requests/{id}`; EVT-18, EVT-03).
 * Dữ liệu (WEB-065): `GET /plans/{id}/versions`, `GET …/versions/{v}` và diff ghép ở client; phiên bản đầu
 * so với kế hoạch rỗng (mọi dòng là Thêm).
 * Quy tắc: BR-04-08 người duyệt khác người lập (403), BR-04-09 ký số bắt buộc, phiên bản đã duyệt bất biến,
 * BR-04-10 lần điều chỉnh, BR-04-11 không bỏ việc đã có minh chứng xác nhận, BR-04-12 hạn nhiệm vụ đang
 * làm không đổi; BR-00-02 MFA trong 15 phút (401 `insufficient_user_authentication`).
 * Thông báo: MSG-04-09 chờ xác nhận trên MySign 120 giây, MSG-04-10 duyệt xong, MSG-04-11, MSG-04-12 (vi
 * phạm do máy chủ trả trong `checks`), MSG-04-13 dịch vụ ký số gián đoạn.
 */
import { Component, DestroyRef, computed, inject, input, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, RouterLink } from '@angular/router';
import {
  EsignRequestStatus,
  PLAN_CHANGE_LABEL,
  PLAN_RECURRENCE_LABEL,
  PlanApi,
  PlanDiffLine,
  PlanItem,
  PlanSignRequest,
  SIGN_METHOD_LABEL,
  SignMethod,
  TASK_GROUP_LABEL,
  toProblem,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TextareaModule } from 'primeng/textarea';
import { firstValueFrom } from 'rxjs';
import { TASK_CAN } from '../task-permissions';
import { formatDate } from '../task-ui';
import { PLAN_STEP_UP } from './plan/step-up';

type View = 'changes' | 'all';
type Busy = 'approve' | 'return' | 'stepup' | null;

/** Phương thức đã liên kết chứng thư; còn lại hiện để biết nhưng chưa chọn được (suy luận, xem wiki/05a). */
const LINKED: SignMethod[] = ['MYSIGN'];
const ORDER = { THEM: 0, SUA: 1, BO: 2, KHONG_DOI: 3 } as const;
/** Yêu cầu ký còn chờ người duyệt xác nhận trên MySign. */
const WAITING: EsignRequestStatus[] = ['TAO', 'CHO_XAC_NHAN'];

@Component({
  selector: 'vt-plan-review-page',
  imports: [DatePipe, FormsModule, RouterLink, ButtonModule, SelectButtonModule, TextareaModule],
  template: `
    @if (!can('SCR-0403.so-sanh')) {
      <p class="msg warn">Anh/chị không có quyền xem xét kế hoạch của đơn vị.</p>
    } @else if (review.error()) {
      <p class="msg err" role="alert">{{ loadError() }}</p>
    } @else if (review.value(); as d) {
      @let t = d.version;
      @let x = d.diff;
      <header class="top">
        <div>
          <h1>
            {{ d.base ? 'Phê duyệt điều chỉnh kế hoạch' : 'Phê duyệt kế hoạch' }}
            {{ t.period.label }}
          </h1>
          <p class="muted sub">
            {{ t.unitName }} ·
            @if (d.base; as b) {
              so sánh phiên bản {{ b.versionNo }} (đã duyệt
              {{ b.approvedAt ? date(b.approvedAt) : '' }}, ký số) với phiên bản
              {{ t.versionNo }} (trình {{ submittedAt() }})
            } @else {
              phiên bản {{ t.versionNo }} (trình {{ submittedAt() }})
            }
          </p>
        </div>
        @if (d.base) {
          <p-selectbutton
            [options]="views"
            optionLabel="label"
            optionValue="value"
            [allowEmpty]="false"
            ariaLabelledBy="view-lbl"
            [(ngModel)]="view"
          />
          <span id="view-lbl" class="sr-only">Chế độ xem</span>
        }
      </header>

      @if (notice(); as n) {
        <p class="msg ok" role="status">{{ n }}</p>
      }
      @if (t.status === 'DA_DUYET') {
        <p class="msg ok">
          Phiên bản {{ t.versionNo }} đã duyệt và ký số lúc
          {{ d.summary?.approvedAt | date: 'dd/MM/yyyy HH:mm' }}; phiên bản đã duyệt không sửa được.
        </p>
      } @else if (t.status === 'TRA_LAI') {
        <p class="msg warn">Kế hoạch đã được trả lại cho người lập: {{ t.reviewComment }}</p>
      } @else if (t.status !== 'CHO_DUYET') {
        <p class="msg info">Phiên bản {{ t.versionNo }} chưa gửi duyệt.</p>
      }

      <section class="cards" aria-label="Tóm tắt thay đổi">
        @if (d.base) {
          <div class="card">
            <span class="num" aria-hidden="true">1</span>
            <span class="lbl">Thêm mới</span><strong class="big add">{{ x.added }}</strong>
          </div>
          <div class="card">
            <span class="lbl">Sửa</span><strong class="big chg">{{ x.modified }}</strong>
          </div>
          <div class="card">
            <span class="lbl">Bỏ</span><strong class="big del">{{ x.removed }}</strong>
          </div>
        } @else {
          <div class="card">
            <span class="num" aria-hidden="true">1</span>
            <span class="lbl">Số công việc</span><strong class="big">{{ x.lines.length }}</strong>
          </div>
        }
        <div class="card">
          <span class="lbl">Tổng điểm then chốt</span>
          <strong class="big"
            >{{ keyMax().before }} <span aria-hidden="true">→</span
            ><span class="sr-only">thành</span> {{ keyMax().after }}</strong
          >
          <span class="muted small">{{
            x.groupWeightChanged || keyMax().before !== keyMax().after
              ? 'đổi tỷ trọng'
              : 'không đổi tỷ trọng'
          }}</span>
        </div>
      </section>

      <div class="layout">
        <section class="box" aria-labelledby="diff-h">
          <h2 class="h" id="diff-h">
            <span class="num" aria-hidden="true">2</span>
            {{ d.base ? 'Khác biệt giữa hai phiên bản' : 'Nội dung kế hoạch' }}
          </h2>
          <div class="scroll" tabindex="0" role="region" aria-label="Bảng so sánh, cuộn ngang">
            <table class="tbl">
              <thead>
                <tr>
                  @if (d.base) {
                    <th scope="col">Thay đổi</th>
                  }
                  <th scope="col">Nội dung công việc</th>
                  @if (d.base; as b) {
                    <th scope="col">Phiên bản {{ b.versionNo }}</th>
                  }
                  <th scope="col">Phiên bản {{ t.versionNo }}</th>
                  @if (d.base) {
                    <th scope="col">Lý do</th>
                  }
                </tr>
              </thead>
              <tbody>
                @for (r of shown(); track $index) {
                  @let it = (r.after ?? r.before)!;
                  <tr [class]="d.base ? r.change : ''">
                    @if (d.base) {
                      <td class="nw kind">{{ changeLabel[r.change] }}</td>
                    }
                    <td class="title">
                      @if (r.change === 'BO') {
                        <s>{{ it.title }}</s
                        ><span class="sr-only"> (bỏ)</span>
                      } @else if (r.change === 'THEM') {
                        <strong>{{ it.title }}</strong>
                      } @else {
                        {{ it.title }}
                      }
                    </td>
                    @if (d.base) {
                      <td>
                        @if (r.before; as b) {
                          @if (r.change === 'BO') {
                            <s>{{ describe(b, r) }}</s>
                          } @else {
                            {{ describe(b, r) }}
                          }
                        } @else {
                          <span aria-label="không có">—</span>
                        }
                      </td>
                    }
                    <td>
                      @if (r.after; as a) {
                        @if (r.change === 'SUA') {
                          <strong>{{ describe(a, r) }}</strong>
                        } @else {
                          {{ describe(a, r) }}
                        }
                      } @else {
                        <span aria-label="không có">—</span>
                      }
                    </td>
                    @if (d.base) {
                      <td>
                        @if (r.change === 'BO') {
                          <s>{{ r.reason || '—' }}</s>
                        } @else {
                          {{ r.change === 'KHONG_DOI' ? '' : r.reason || '—' }}
                        }
                      </td>
                    }
                  </tr>
                } @empty {
                  <tr>
                    <td [attr.colspan]="d.base ? 5 : 2" class="muted">
                      Không có thay đổi nào so với phiên bản đã duyệt.
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @for (c of blocking(); track $index) {
            <p class="msg err">{{ c.message }}</p>
          }
          @if (d.base && !simple()) {
            <p class="msg info note">
              Việc đã có minh chứng được xác nhận không được bỏ khỏi kế hoạch. Hạn của việc đang
              thực hiện không đổi qua điều chỉnh kế hoạch; muốn đổi hạn dùng đề nghị gia hạn (QT-06)
              để giữ đúng cách tính tiến độ.
            </p>
          }
        </section>

        <aside>
          <section class="box" aria-labelledby="info-h">
            <h2 class="h" id="info-h">
              <span class="num" aria-hidden="true">3</span> Thông tin trình
            </h2>
            <dl class="meta">
              <dt>Người trình</dt>
              <dd>{{ d.summary?.submittedByName || '—' }}</dd>
              @if ((x.adjustmentNo ?? 0) > 0) {
                <dt>Lần điều chỉnh</dt>
                <dd>{{ x.adjustmentNo }} / tối đa {{ x.adjustmentsMax }} trong quý</dd>
              }
              <dt>Hiệu lực</dt>
              <dd>Từ ngày duyệt; không hồi tố</dd>
              @if (!simple()) {
                <dt>Mã băm v{{ t.versionNo }}</dt>
                <dd>
                  <code class="hash" [title]="t.contentHash ?? ''"
                    >sha256: {{ shortHash(t.contentHash ?? undefined) }}</code
                  >
                </dd>
              }
            </dl>
          </section>

          @if (t.status === 'CHO_DUYET' && (can('SCR-0403.tra-lai') || can('SCR-0403.duyet-ky'))) {
            <section class="box" aria-labelledby="dec-h">
              <h2 class="h" id="dec-h"><span class="num" aria-hidden="true">4</span> Quyết định</h2>
              <label for="rv-comment" class="sr-only">Ý kiến của người duyệt</label>
              <textarea
                id="rv-comment"
                pTextarea
                rows="4"
                maxlength="2000"
                placeholder="Ý kiến của người duyệt..."
                [ngModel]="comment()"
                (ngModelChange)="comment.set($event); commentError.set(false)"
                [attr.aria-invalid]="commentError()"
                [attr.aria-describedby]="commentError() ? 'rv-comment-err' : null"
                [disabled]="!!busy() || waiting()"
              ></textarea>
              @if (commentError()) {
                <p id="rv-comment-err" class="err-text small">Ghi ý kiến khi trả lại kế hoạch.</p>
              }
              @if (can('SCR-0403.duyet-ky')) {
                <fieldset class="methods">
                  <legend class="sr-only">Phương thức ký số</legend>
                  @for (m of methods(); track m.value) {
                    <label class="method" [class.off]="m.disabled">
                      <input
                        type="radio"
                        name="sign-method"
                        [value]="m.value"
                        [checked]="method() === m.value"
                        [disabled]="m.disabled || !!busy() || waiting()"
                        (change)="method.set(m.value)"
                      />
                      {{ m.label }}
                    </label>
                  }
                </fieldset>
              }

              @if (stepUp()) {
                <div class="msg warn" role="alert">
                  <span
                    >Duyệt kế hoạch là mốc ký số pháp lý. Phiên đăng nhập đã quá 15 phút kể từ lần
                    xác thực gần nhất; vui lòng xác thực bước lên (MFA) rồi ký.</span
                  >
                  <p-button
                    label="Xác thực MFA"
                    size="small"
                    [loading]="busy() === 'stepup'"
                    (onClick)="doStepUp()"
                  />
                </div>
              }
              @if (sign(); as s) {
                @if (waiting()) {
                  <p class="msg info" role="status">
                    Vui lòng xác nhận ký trên ứng dụng MySign trong 120 giây.
                    <span class="muted">Còn {{ secondsLeft() }} giây.</span>
                  </p>
                } @else if (s.status === 'LOI') {
                  <p class="msg warn" role="alert">
                    Dịch vụ ký số đang gián đoạn, chưa gửi được yêu cầu ký. Vui lòng thử lại sau.
                  </p>
                } @else if (s.status === 'TU_CHOI' || s.status === 'HET_HAN') {
                  <p class="msg err" role="alert">
                    Yêu cầu ký {{ s.status === 'HET_HAN' ? 'đã hết hạn' : 'đã bị từ chối' }}. Bấm
                    "Duyệt và ký số" để ký lại.
                  </p>
                }
              }
              @if (error(); as e) {
                <p class="msg err" role="alert">{{ e }}</p>
              }

              <div class="dec-actions">
                @if (can('SCR-0403.tra-lai')) {
                  <p-button
                    label="Trả lại"
                    severity="contrast"
                    [outlined]="true"
                    [loading]="busy() === 'return'"
                    [disabled]="!!busy() || waiting()"
                    (onClick)="returnPlan()"
                  />
                }
                @if (can('SCR-0403.duyet-ky')) {
                  <p-button
                    label="Duyệt và ký số"
                    [loading]="busy() === 'approve' || waiting()"
                    [disabled]="!!busy() || waiting() || blocking().length > 0"
                    (onClick)="approve()"
                  />
                }
              </div>
              @if (can('SCR-0403.duyet-ky') && !simple()) {
                <p class="muted small">
                  <span class="num sm" aria-hidden="true">5</span>
                  Duyệt kế hoạch là mốc ký số pháp lý; yêu cầu xác thực bước lên (MFA) nếu phiên
                  đăng nhập quá 15 phút.
                </p>
              }
            </section>
          }
          <p class="back">
            <a routerLink="/plans" [queryParams]="{ plan: d.planId, v: t.versionNo }"
              >Mở kế hoạch phiên bản {{ t.versionNo }}</a
            >
          </p>
        </aside>
      </div>
    } @else {
      <p class="muted" role="status">Đang tải kế hoạch…</p>
    }
  `,
  styles: `
    :host {
      display: block;
      padding: 16px 24px;
      --p-togglebutton-color: #374151;
    }
    h1 {
      font-size: 1.5rem;
      margin: 0 0 4px;
    }
    .top {
      display: flex;
      justify-content: space-between;
      align-items: flex-start;
      gap: 12px;
      flex-wrap: wrap;
      margin-bottom: 12px;
    }
    .sub {
      margin: 0;
    }
    .cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
      gap: 12px;
      margin-bottom: 12px;
    }
    .card {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      padding: 12px 16px;
      background: var(--p-content-background, #fff);
      display: flex;
      flex-direction: column;
      gap: 4px;
      position: relative;
    }
    .card .num {
      position: absolute;
      top: 12px;
      right: 12px;
    }
    .lbl {
      color: #4b5563;
    }
    .big {
      font-size: 1.9rem;
      line-height: 1.2;
    }
    .big.add {
      color: #15803d;
    }
    .big.chg {
      color: #a16207;
    }
    .big.del {
      color: #b91c1c;
    }
    .layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 340px;
      gap: 16px;
      align-items: start;
    }
    .box {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      padding: 12px 16px;
      background: var(--p-content-background, #fff);
      margin-bottom: 12px;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 10px;
      display: flex;
      align-items: center;
      gap: 8px;
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
    .num.sm {
      width: 18px;
      height: 18px;
      font-size: 0.7rem;
      vertical-align: middle;
    }
    .scroll {
      overflow-x: auto;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
    }
    .tbl th {
      text-align: left;
      color: #4b5563;
      font-weight: 500;
      background: #f9fafb;
    }
    .tbl th,
    .tbl td {
      padding: 10px 8px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
      vertical-align: middle;
    }
    .tbl .title {
      min-width: 200px;
    }
    .tbl .nw {
      white-space: nowrap;
    }
    tr.THEM {
      background: #f0fdf4;
    }
    tr.THEM .kind {
      color: #15803d;
      font-weight: 600;
    }
    tr.SUA {
      background: #fffbeb;
    }
    tr.SUA .kind {
      color: #92400e;
      font-weight: 600;
    }
    tr.BO {
      background: #fef2f2;
      color: #7f1d1d;
    }
    tr.BO .kind {
      font-weight: 600;
    }
    tr.KHONG_DOI .kind {
      color: #4b5563;
    }
    .note {
      margin-top: 12px;
    }
    .meta {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 6px 16px;
      margin: 0;
    }
    .meta dt {
      color: #4b5563;
    }
    .meta dd {
      margin: 0;
    }
    .hash {
      word-break: break-all;
      font-size: 0.85em;
    }
    textarea {
      width: 100%;
    }
    .methods {
      border: 0;
      margin: 10px 0;
      padding: 0;
      display: flex;
      flex-direction: column;
      gap: 6px;
    }
    .method {
      display: flex;
      gap: 8px;
      align-items: center;
    }
    .method input {
      width: 18px;
      height: 18px;
      accent-color: #c8102e;
    }
    .method.off {
      color: #6b7280;
    }
    .dec-actions {
      display: flex;
      justify-content: space-between;
      gap: 8px;
      margin: 10px 0 6px;
    }
    .msg {
      margin: 0 0 10px;
      padding: 10px 14px;
      border-radius: 6px;
      border-left: 4px solid;
      display: flex;
      gap: 10px;
      align-items: center;
      flex-wrap: wrap;
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
    .err-text {
      color: #b91c1c;
      margin: 4px 0 0;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
    }
    .back {
      margin: 0;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
    @media (max-width: 1100px) {
      .layout {
        grid-template-columns: minmax(0, 1fr);
      }
    }
  `,
})
export class PlanReviewPage {
  private readonly api = inject(PlanApi);
  private readonly theme = inject(ThemeService);
  private readonly route = inject(ActivatedRoute);
  private readonly stepUpFn = inject(PLAN_STEP_UP);
  protected readonly can = inject(TASK_CAN);

  /** Mã kế hoạch từ tuyến `/plans/:id/review`; phiên bản và phiên bản so sánh ở `?v=&base=`. */
  readonly id = input.required<string>();
  private readonly query = toSignal(this.route.queryParamMap);

  /**
   * Phiên bản trình, phiên bản gốc và khác biệt (WEB-065). Không ghi `?v=` thì xem phiên bản mới nhất
   * (lối vào từ "Chờ tôi duyệt" và thông báo).
   */
  protected readonly review = rxResource({
    params: () => {
      if (!this.can('SCR-0403.so-sanh')) return undefined;
      const q = this.query();
      return { id: this.id(), v: Number(q?.get('v')) || 0, base: Number(q?.get('base')) || 0 };
    },
    stream: ({ params }) =>
      this.api.review(params.id, params.v || undefined, params.base || undefined),
  });

  protected readonly views: { label: string; value: View }[] = [
    { label: 'Chỉ hiện thay đổi', value: 'changes' },
    { label: 'Toàn bộ', value: 'all' },
  ];
  protected readonly view = signal<View>('changes');
  protected readonly changeLabel = PLAN_CHANGE_LABEL;
  protected readonly comment = signal('');
  protected readonly commentError = signal(false);
  protected readonly method = signal<SignMethod>('MYSIGN');
  protected readonly busy = signal<Busy>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly stepUp = signal(false);
  protected readonly sign = signal<PlanSignRequest | null>(null);
  protected readonly waiting = computed(() => WAITING.includes(this.sign()?.status ?? 'DA_KY'));
  private readonly now = signal(Date.now());

  /** Chế độ người cao tuổi: ẩn mã băm, lý do tổng và phần giải thích quy tắc (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected readonly shown = computed<PlanDiffLine[]>(() => {
    const d = this.review.value();
    if (!d) return [];
    if (!d.base || this.view() === 'all') return d.diff.lines;
    return d.diff.lines
      .filter((r) => r.change !== 'KHONG_DOI')
      .sort((a, b) => ORDER[a.change] - ORDER[b.change]);
  });
  /** Vi phạm chặn duyệt máy chủ trả trong `checks` của phiên bản (BR-04-11, BR-04-12…). */
  protected readonly blocking = computed(() =>
    (this.review.value()?.version.checks ?? []).filter((c) => c.level === 'CHAN'),
  );
  /** Điểm tối đa nhóm then chốt ở phiên bản gốc và phiên bản trình (tỷ trọng Policy Pack, BR-04-06). */
  protected readonly keyMax = computed(() => {
    const d = this.review.value();
    const after = d?.version.groupWeights.THEN_CHOT ?? 0;
    return { before: d?.baseGroupWeights?.THEN_CHOT ?? after, after };
  });
  protected readonly submittedAt = computed(() => {
    const at = this.review.value()?.summary?.submittedAt;
    return at ? formatDate(at) : '';
  });

  protected readonly methods = computed(() =>
    (Object.keys(SIGN_METHOD_LABEL) as SignMethod[]).map((value) => ({
      value,
      disabled: !LINKED.includes(value),
      label:
        SIGN_METHOD_LABEL[value] + (LINKED.includes(value) ? ' (đã liên kết)' : ' (chưa liên kết)'),
    })),
  );

  protected readonly secondsLeft = computed(() => {
    const s = this.sign();
    if (!s) return 0;
    return Math.max(0, Math.ceil((new Date(s.expiresAt).getTime() - this.now()) / 1000));
  });

  protected readonly loadError = computed(() => {
    const p = toProblem(this.review.error());
    return p.status === 404 ? 'Không tìm thấy kế hoạch hoặc phiên bản.' : p.detail || p.title;
  });

  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stopPolling());
  }

  protected date(iso: string) {
    return formatDate(iso);
  }

  protected shortHash(h?: string) {
    return h ? `${h.slice(0, 4)}…${h.slice(-4)}` : '—';
  }

  /** Mô tả một dòng ở một phiên bản: dòng thêm, bỏ hoặc không đổi ghi đủ; dòng sửa chỉ ghi phần đã đổi. */
  protected describe(i: PlanItem, r: PlanDiffLine): string {
    const qty = i.quantity ? `${i.quantity} ${i.unit ?? ''}`.trim() : '';
    const due = i.recurrence
      ? PLAN_RECURRENCE_LABEL[i.recurrence].toLowerCase()
      : `hạn ${formatDate(i.dueDate)}`;
    const score = i.standardScore != null ? `${i.standardScore} điểm` : 'chưa có điểm';
    const lead = i.leadAssignee.name ? `chủ trì ${i.leadAssignee.name}` : '';
    if (r.change === 'SUA') {
      const parts: string[] = [];
      for (const f of r.changedFields ?? []) {
        if (f === 'quantity' || f === 'unit') parts.push(qty);
        else if (f === 'dueDate' || f === 'recurrence') parts.push(due);
        else if (f === 'standardScore') parts.push(score);
        else if (f === 'leadAssignee') parts.push(lead);
        else if (f === 'taskGroup')
          parts.push(`nhóm ${TASK_GROUP_LABEL[i.taskGroup].toLowerCase()}`);
        else if (f === 'productName') parts.push(i.productName);
        else if (f === 'catalogItemId') parts.push(i.catalogItemCode ?? 'ngoài danh mục');
        else if (f === 'coAssignees')
          parts.push(`phối hợp ${(i.coAssignees ?? []).map((u) => u.name).join(', ') || 'không'}`);
        else if (f === 'keyResultId') parts.push('KR liên kết khác');
        else if (f === 'title') parts.push(i.title);
      }
      return [...new Set(parts.filter(Boolean))].join(' · ');
    }
    return [i.catalogItemCode, qty, due, score, lead].filter(Boolean).join(' · ');
  }

  protected async returnPlan() {
    const d = this.review.value();
    if (!d) return;
    const comment = this.comment().trim();
    if (!comment) {
      this.commentError.set(true);
      document.getElementById('rv-comment')?.focus();
      return;
    }
    this.busy.set('return');
    this.error.set(null);
    try {
      await firstValueFrom(
        this.api.returnPlan(d.planId, d.version.versionNo, d.version.rowVersion, comment),
      );
      this.notice.set('Đã trả lại kế hoạch cho người lập kèm ý kiến.');
      this.review.reload();
    } catch (e) {
      this.error.set(this.message(e));
    } finally {
      this.busy.set(null);
    }
  }

  /** BR-00-02: xin MFA bước lên trước (hộp MFA của shell), máy chủ vẫn kiểm lại và có thể trả 401. */
  protected async approve(steppedUp = false) {
    const d = this.review.value();
    if (!d) return;
    this.busy.set('approve');
    this.error.set(null);
    this.sign.set(null);
    if (!steppedUp && !(await this.stepUpFn().catch(() => false))) {
      this.busy.set(null);
      this.error.set('Cần xác thực nhiều lớp (MFA) để ký số. Bấm "Duyệt và ký số" để thử lại.');
      return;
    }
    const no = d.version.versionNo;
    try {
      // 202 Job; yêu cầu ký đọc qua `signRequestId` của phiên bản (hợp đồng WEB-065)
      const { job } = await firstValueFrom(
        this.api.approve(d.planId, no, d.version.rowVersion, {
          signMethod: this.method(),
          comment: this.comment().trim() || undefined,
        }),
      );
      this.stepUp.set(false);
      const v = await firstValueFrom(this.api.version(d.planId, no));
      if (!v.signRequestId) {
        this.error.set(job.problem?.detail || job.message || 'Chưa tạo được yêu cầu ký.');
        return;
      }
      const s = await firstValueFrom(this.api.signRequest(v.signRequestId));
      this.now.set(Date.now());
      this.sign.set(s);
      if (WAITING.includes(s.status)) this.poll(v.signRequestId, no, this.added());
    } catch (e) {
      const p = toProblem(e);
      if (p.status === 401) this.stepUp.set(true);
      else this.error.set(this.message(e));
    } finally {
      this.busy.set(null);
    }
  }

  protected async doStepUp() {
    this.busy.set('stepup');
    const ok = await this.stepUpFn().catch(() => false);
    this.busy.set(null);
    if (!ok) {
      this.error.set('Chưa xác thực bước lên được. Vui lòng thử lại.');
      return;
    }
    this.stepUp.set(false);
    await this.approve(true);
  }

  /** Số nhiệm vụ mới sẽ sinh: dòng thêm (hoặc mọi dòng ở phiên bản đầu), theo MSG-04-10 (suy luận). */
  private added() {
    return this.review.value()?.diff.added ?? 0;
  }

  private poll(signRequestId: string, versionNo: number, n: number) {
    this.stopPolling();
    const tick = async () => {
      this.now.set(Date.now());
      try {
        const s = await firstValueFrom(this.api.signRequest(signRequestId));
        this.sign.set(s);
        if (s.status === 'DA_KY') {
          this.notice.set(
            `Đã duyệt và ký số kế hoạch phiên bản ${versionNo}. Hệ thống đang sinh ${n} nhiệm vụ mới.`,
          );
          this.sign.set(null);
          this.comment.set('');
          this.review.reload();
          return;
        }
        if (!WAITING.includes(s.status)) return;
      } catch (e) {
        this.error.set(this.message(e));
        this.sign.set(null);
        return;
      }
      this.timer = setTimeout(tick, 2000);
    };
    this.timer = setTimeout(tick, 1000);
  }

  private stopPolling() {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }

  private message(e: unknown): string {
    const p = toProblem(e);
    if (p.status === 409) return 'Kế hoạch vừa thay đổi trạng thái. Vui lòng tải lại trang.';
    if (p.status === 422 && p.errors?.length) return p.errors.map((x) => x.message).join(' ');
    return [p.title, p.detail].filter(Boolean).join(' ');
  }
}
