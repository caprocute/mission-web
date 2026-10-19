/*
 * SCR-0801 "Quản lý kỳ và tiến trình khoá kỳ" (QT-08, TL-02 Hình 9.3) — đối chiếu đặc tả:
 * Tác nhân AC-02 (điều khiển), AC-04 (phê duyệt lệnh), AC-09 (duyệt thứ hai khi huỷ), AC-10 (chỉ đọc);
 *   phiên AAL2, máy tính. Đường vào: menu "Quản trị / Kỳ đánh giá".
 * Trường: 1 kỳ (tên, từ ngày, đến ngày, trạng thái MO/DANG_KHOA/DA_KHOA/MO_LAI_HAU_KIEM, "Đã tạo" khi
 *   chưa tới ngày bắt đầu); 2 lịch kỳ (bốn mốc, BR-08-12); 3 Policy Pack ghim (BR-08-07); 4 lệnh khoá kỳ
 *   (người lập, người duyệt, thời điểm, căn cứ; BR-08-01); 5 mã saga; 6 khoá idempotency `CK-{năm}{kỳ}`
 *   (BR-08-08); 7 số hồ sơ, đã tính, lỗi; 8 tốc độ, dự kiến hoàn tất (ngân sách NFR-06); 9 sáu bước saga
 *   (trạng thái, thời gian, kết quả, bù trừ); 10 lô tính điểm; 11 hồ sơ lỗi; 12 minh chứng không tính
 *   trong kỳ (BR-08-05); 13 nhật ký sự kiện, mới nhất trước (BR-08-10).
 * Vùng (Hình 9.3): 1 danh sách kỳ; 2 thông tin lệnh và bốn chỉ số; 3 tiến trình sáu bước với bù trừ;
 *   4 nhật ký sự kiện; 5 nút điều khiển và lưu ý hai người duyệt khi huỷ.
 * Chức năng: Tạo kỳ (POST /periods); Lập lệnh khoá kỳ (POST /periods/{id}/lock-orders, EVT-21); Phê duyệt
 *   lệnh (POST …/lock-orders/{oid}/approve, MFA); Khởi chạy (POST /periods/{id}/lock, 202 + job); Xem BPMN
 *   (GET …/lock/diagram); Tạm dừng sau bước hiện tại, Tiếp tục (POST …/lock/pause, …/lock/resume); Chạy lại
 *   hồ sơ lỗi (POST …/lock/retry-failed, 202); Huỷ và bù trừ toàn bộ (POST …/lock/cancel, duyệt thứ hai
 *   POST …/lock/cancel/approve). "Lịch ngày nghỉ" (/config/holiday-calendars) chưa làm, xem wiki/14.
 * Quy tắc, thông báo: BR-08-01, 02, 03 (bước 0), 05, 07, 08, 09, 10, 12; MSG-08-01 … MSG-08-05, 07, 08.
 * Quyền (wiki/15a): SCR-0801.xem, tao-ky, lap-lenh, duyet-lenh, khoi-chay, dieu-khien, de-nghi-huy,
 *   duyet-huy.
 */
import {
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  linkedSignal,
  signal,
} from '@angular/core';
import { rxResource, takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import {
  LOCK_ORDER_STATUS_LABEL,
  LOCK_STEP_STATUS_LABEL,
  PERIOD_FLOW_LABEL,
  PERIOD_KIND_LABEL,
  PERIOD_STATUS_LABEL,
  Period,
  PeriodApi,
  PeriodLockDiagram,
  PeriodLockProgress,
  PeriodLockStep,
  PeriodLockStepStatus,
  SAGA_ACTIVE_STATUSES,
  SAGA_STATUS_LABEL,
  SCORE_BATCH_STATUS_LABEL,
  SagaStatus,
  fieldErrors,
  toProblem,
} from '@vt/api-client';
import { TEXT_FORBIDDEN } from '@vt/auth';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { TextareaModule } from 'primeng/textarea';
import { Observable, catchError, interval, of } from 'rxjs';
import { ADMIN_CAN } from '../../admin-permissions';
import { fmtDate, fmtDateTime, fmtInt } from '../../admin-ui';
import { PeriodCreateDialog } from './period-create.dialog';
import { PERIOD_POLL_MS, PERIOD_STEP_UP } from './step-up';

/** MSG-08-01 */
export const MSG_08_01 = (period: string, name: string, at: string) =>
  `Lệnh khoá kỳ ${period} đã được phê duyệt bởi đồng chí ${name}. Có thể khởi chạy từ ${at}.`;
/** MSG-08-03 */
export const MSG_08_03 = (n: number) =>
  `Có ${fmtInt(n)} minh chứng chưa được nghiệm thu sẽ không được tính điểm trong kỳ. Người nộp và người nghiệm thu đã được thông báo.`;
/** MSG-08-04 */
export const MSG_08_04 = (step: number, at: string) =>
  `Bước ${step} đang chậm hơn ngân sách. Dự kiến hoàn tất ${at}.`;
/** MSG-08-05; hợp đồng chưa trả tên người duyệt thứ hai nên ghi theo vai trò (wiki/14). */
export const MSG_08_05 = (name = 'người duyệt thứ hai') =>
  `Đề nghị huỷ và hoàn tác cần người duyệt thứ hai. Đã gửi tới ${name}.`;
/** MSG-08-07 */
export const MSG_08_07 = (period: string, at: string) =>
  `Kỳ ${period} đã khoá lúc ${at}. Hồ sơ đánh giá đã sẵn sàng cho bước tự đánh giá.`;
/** MSG-08-08 */
export const MSG_08_08 = (period: string) =>
  `Đã hoàn tác toàn bộ. Kỳ ${period} trở về trạng thái Đang mở.`;

const STEP_ICON: Record<PeriodLockStepStatus, string> = {
  CHO: 'pi pi-circle',
  DANG_CHAY: 'pi pi-spin pi-spinner',
  DAT: 'pi pi-check-circle',
  DAT_CO_CANH_BAO: 'pi pi-exclamation-circle',
  LOI: 'pi pi-times-circle',
  DA_BU_TRU: 'pi pi-replay',
};

/** Mã hoạt động BPMN của sáu bước trong mẫu `chot-ky` (suy luận theo ví dụ của hợp đồng). */
const STEP_ACTIVITY = [
  'buoc0TienDieuKien',
  'buoc1KhoaKy',
  'buoc2TinhDiem',
  'buoc3SinhMau',
  'buoc4KySo',
  'buoc5HoanTat',
];

type Dialog = 'order' | 'cancel' | 'diagram' | null;

const timeOf = (iso?: string | null) => {
  if (!iso) return '';
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? iso
    : `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

@Component({
  selector: 'vt-periods-page',
  imports: [
    FormsModule,
    ButtonModule,
    DialogModule,
    InputTextModule,
    MessageModule,
    TextareaModule,
    PeriodCreateDialog,
  ],
  template: `
    <header class="head">
      <div>
        <h1>Kỳ đánh giá và khoá kỳ</h1>
        <p class="muted">
          Lịch kỳ đánh giá, lệnh khoá kỳ và theo dõi tiến trình khoá kỳ của đơn vị.
        </p>
      </div>
      @if (can('SCR-0801.tao-ky')) {
        <div class="actions">
          <p-button label="Tạo kỳ" icon="pi pi-plus" (onClick)="creating.set(true)" />
        </div>
      }
    </header>

    @if (notice(); as n) {
      <p-message severity="success" [text]="n" styleClass="msg" role="status" />
    }
    @if (actionError(); as e) {
      <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
    }

    <div class="cols">
      <section class="box" aria-labelledby="h-periods">
        <h2 id="h-periods" class="h">Danh sách kỳ</h2>
        @if (periods.error()) {
          <p-message severity="error" styleClass="msg">
            <span>{{ errText(periods.error(), 'Không tải được danh sách kỳ.') }}</span>
            <button type="button" class="link" (click)="periods.reload()">Thử lại</button>
          </p-message>
        } @else if (!periods.hasValue()) {
          <p class="muted">Đang tải…</p>
        } @else {
          <ul class="plist">
            @for (p of periods.value(); track p.id) {
              <li>
                <button
                  type="button"
                  class="pitem"
                  [class.sel]="p.id === selectedId()"
                  [attr.aria-current]="p.id === selectedId() ? 'true' : null"
                  (click)="select(p.id)"
                >
                  <span class="pname">{{ p.name }}</span>
                  <span class="status" [class]="'status ' + periodTone(p)">{{
                    periodStatus(p)
                  }}</span>
                  <span class="muted small block">
                    {{ date(p.startDate) }} – {{ date(p.endDate) }} · {{ kindLabel[p.kind] }}
                    @if (p.reopenCount) {
                      · mở lại hậu kiểm {{ p.reopenCount }} lần
                    }
                  </span>
                </button>
              </li>
            } @empty {
              <li class="muted">Chưa có kỳ đánh giá.</li>
            }
          </ul>
        }
      </section>

      <div class="detail">
        @if (selected(); as p) {
          <section class="box" aria-labelledby="h-order">
            <div class="row-head">
              <h2 id="h-order" class="h">{{ p.name }}: lệnh khoá kỳ</h2>
              <span class="status" [class]="'status ' + periodTone(p)">{{ periodStatus(p) }}</span>
            </div>
            @for (w of p.warnings ?? []; track w) {
              <p class="warnbox">{{ w }}</p>
            }
            <dl class="info">
              <div>
                <dt>Thời gian</dt>
                <dd>{{ date(p.startDate) }} – {{ date(p.endDate) }} · {{ flowLabel[p.flow] }}</dd>
              </div>
              <div>
                <dt>Lịch kỳ</dt>
                <dd>
                  @if (p.milestones; as m) {
                    <span class="block"
                      >Hạn duyệt kế hoạch: {{ dateOr(m.planApprovalDeadline) }}</span
                    >
                    <span class="block">Hạn nộp minh chứng: {{ dateOr(m.evidenceDeadline) }}</span>
                    <span class="block"
                      >Hạn nghiệm thu tồn đọng: {{ dateOr(m.acceptanceBacklogDeadline) }}</span
                    >
                    <span class="block">Ngày khoá dự kiến: {{ dateOr(m.plannedLockDate) }}</span>
                  }
                </dd>
              </div>
              <div>
                <dt>Bộ quy định đánh giá áp dụng</dt>
                <dd>
                  @for (pk of p.pinnedPolicyPacks ?? []; track pk.packVersionId) {
                    <span class="chip">{{ flowLabel[pk.flow] }}, phiên bản {{ pk.version }}</span>
                  } @empty {
                    <span class="muted">Chưa xác định</span>
                  }
                </dd>
              </div>
              @if (p.currentLockOrder; as o) {
                <div>
                  <dt>Lệnh khoá kỳ</dt>
                  <dd>
                    <span class="status" [class]="'status ' + orderTone(o.status)">{{
                      orderLabel[o.status]
                    }}</span>
                    <span class="block">Căn cứ: {{ o.basisDoc }}</span>
                    @if (o.plannedLockAt) {
                      <span class="block">Dự kiến khởi chạy: {{ dateTime(o.plannedLockAt) }}</span>
                    }
                  </dd>
                </div>
                <div>
                  <dt>Người lập</dt>
                  <dd>{{ o.requestedBy.name }} · {{ dateTimeOr(o.requestedAt) }}</dd>
                </div>
                <div>
                  <dt>Người phê duyệt</dt>
                  <dd>
                    @if (o.approvedBy) {
                      {{ o.approvedBy.name }} · {{ dateTimeOr(o.approvedAt) }}
                    } @else {
                      <span class="muted">Chờ lãnh đạo phê duyệt</span>
                    }
                  </dd>
                </div>
                @if (o.startedBy) {
                  <div>
                    <dt>Người khởi chạy</dt>
                    <dd>{{ o.startedBy.name }} · {{ dateTimeOr(o.startedAt) }}</dd>
                  </div>
                }
                <!-- mã saga, khoá chống lặp (BR-08-08) là mã nội bộ: không hiện -->
                <div>
                  <dt>Tiến trình khoá kỳ</dt>
                  <dd>{{ o.sagaId ? 'Đã khởi chạy' : 'Chưa khởi chạy' }}</dd>
                </div>
              } @else if (p.lockedAt) {
                <div>
                  <dt>Đã khoá</dt>
                  <dd>{{ dateTime(p.lockedAt) }} · {{ p.lockedBy?.name }}</dd>
                </div>
              } @else {
                <div>
                  <dt>Lệnh khoá kỳ</dt>
                  <dd class="muted">Chưa lập lệnh</dd>
                </div>
              }
            </dl>
            <div class="actions">
              @if (canOrder(p) && can('SCR-0801.lap-lenh')) {
                <p-button label="Lập lệnh khoá kỳ" icon="pi pi-file-edit" (onClick)="openOrder()" />
              }
              @if (p.currentLockOrder?.status === 'CHO_DUYET' && can('SCR-0801.duyet-lenh')) {
                <p-button
                  label="Phê duyệt lệnh"
                  icon="pi pi-verified"
                  [loading]="busy() === 'approve'"
                  (onClick)="approveOrder(p)"
                />
              }
              @if (p.currentLockOrder?.status === 'DA_DUYET' && can('SCR-0801.khoi-chay')) {
                <p-button
                  label="Khởi chạy"
                  icon="pi pi-play"
                  [loading]="busy() === 'start'"
                  (onClick)="start(p)"
                />
              }
            </div>
          </section>

          @if (progress.error() && !lockGone()) {
            <p-message severity="error" styleClass="msg">
              <span>{{ errText(progress.error(), 'Không tải được tiến trình khoá kỳ.') }}</span>
              <button type="button" class="link" (click)="progress.reload()">Thử lại</button>
            </p-message>
          }
          @if (pr(); as s) {
            <section class="box" aria-labelledby="h-saga">
              <div class="row-head">
                <h2 id="h-saga" class="h">
                  Tiến trình khoá kỳ
                  @if (s.templateVersion) {
                    (quy trình phiên bản {{ s.templateVersion }})
                  }
                </h2>
                <span class="status" [class]="'status ' + sagaTone(s.sagaStatus)" role="status">{{
                  sagaLabel[s.sagaStatus]
                }}</span>
              </div>
              @if (s.sagaStatus === 'HOAN_TAT') {
                <p-message severity="success" [text]="msg0807(p, s)" styleClass="msg" />
              }
              @if (s.sagaStatus === 'DA_BU_TRU') {
                <p-message severity="info" [text]="msg0808(p.name)" styleClass="msg" />
              }
              <div class="cards" aria-label="Chỉ số">
                <div class="card first">
                  <p class="label">Hồ sơ trong kỳ</p>
                  <p class="big">{{ int(s.totals.profiles) }}</p>
                </div>
                <div class="card">
                  <p class="label">Đã tính điểm</p>
                  <p class="big">{{ int(s.totals.scored) }}</p>
                  <p class="muted small">
                    {{ scoredPercent(s) }}%
                    @if (s.ratePerMinute) {
                      · khoảng {{ int(s.ratePerMinute) }} hồ sơ/phút
                    }
                  </p>
                </div>
                <div class="card">
                  <p class="label">Lỗi chờ xử lý</p>
                  <p class="big" [class.bad]="s.totals.failed > 0">{{ int(s.totals.failed) }}</p>
                </div>
                <div class="card">
                  <p class="label">Dự kiến hoàn tất</p>
                  <p class="big">{{ time(s.estimatedFinishAt) || '—' }}</p>
                  @if (s.budgetFinishAt) {
                    <p class="muted small">thời hạn cho phép: {{ time(s.budgetFinishAt) }}</p>
                  }
                </div>
              </div>
              @if (slowStep(s); as n) {
                <p class="warnbox" role="status">{{ msg0804(n, time(s.estimatedFinishAt)) }}</p>
              }
              @if (s.excludedEvidenceCount) {
                <p class="warnbox">{{ msg0803(s.excludedEvidenceCount) }}</p>
              }

              <div class="scroll">
                <table class="tbl steps">
                  <caption class="sr-only">
                    Sáu bước của tiến trình khoá kỳ và việc hoàn tác
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">Bước</th>
                      <th scope="col">Trạng thái</th>
                      <th scope="col">Thời gian</th>
                      <th scope="col">Kết quả</th>
                      <th scope="col">Hoàn tác</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (st of s.steps; track st.stepNo) {
                      <tr [class.cur]="st.status === 'DANG_CHAY'">
                        <th scope="row" class="step-name">{{ st.stepNo }}. {{ st.name }}</th>
                        <td>
                          <span class="status" [class]="'status ' + stepTone(st.status)">
                            <i [class]="stepIcon[st.status]" aria-hidden="true"></i>
                            {{ stepLabel[st.status] }}
                          </span>
                          @if (st.stepNo === 2 && st.status === 'DANG_CHAY') {
                            <span
                              class="meter block"
                              role="progressbar"
                              aria-label="Tiến độ tính điểm"
                              [attr.aria-valuenow]="scoredPercent(s)"
                              aria-valuemin="0"
                              aria-valuemax="100"
                              ><span class="fill" [style.width.%]="scoredPercent(s)"></span
                            ></span>
                          }
                        </td>
                        <td class="small nowrap">
                          @if (st.startedAt) {
                            {{ time(st.startedAt) }}
                            @if (st.finishedAt) {
                              – {{ time(st.finishedAt) }}
                            }
                          } @else {
                            <span class="muted">—</span>
                          }
                        </td>
                        <td class="small">{{ stepNote(st) }}</td>
                        <td class="small">
                          @if (st.compensationAction) {
                            <span [class.muted]="st.status !== 'DA_BU_TRU'">{{
                              st.compensationAction
                            }}</span>
                            @if (st.compensatedAt) {
                              <span class="block"
                                >Đã hoàn tác lúc {{ time(st.compensatedAt) }}</span
                              >
                            }
                          } @else {
                            <span class="muted">Không có</span>
                          }
                        </td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>

              @if (s.cancelRequest; as c) {
                <div class="cancelbox" role="region" aria-label="Đề nghị huỷ và hoàn tác">
                  <p>
                    <strong>Đề nghị huỷ và hoàn tác toàn bộ</strong> của đồng chí
                    {{ c.requestedBy.name }} lúc {{ dateTime(c.requestedAt) }}, chờ người duyệt thứ
                    hai.
                  </p>
                  <p class="small">Lý do: {{ c.reason }}</p>
                  @if (can('SCR-0801.duyet-huy')) {
                    <p-button
                      label="Duyệt huỷ và hoàn tác"
                      icon="pi pi-replay"
                      severity="danger"
                      [loading]="busy() === 'approveCancel'"
                      (onClick)="approveCancel(p, s)"
                    />
                  }
                </div>
              }

              @if (active(s)) {
                <div class="controls" role="group" aria-label="Điều khiển tiến trình khoá kỳ">
                  @if (can('SCR-0801.dieu-khien')) {
                    @if (s.sagaStatus === 'DANG_CHAY' && !s.pauseRequested) {
                      <p-button
                        label="Tạm dừng sau bước hiện tại"
                        icon="pi pi-pause"
                        severity="secondary"
                        [outlined]="true"
                        [loading]="busy() === 'pause'"
                        (onClick)="pause(p, s)"
                      />
                    }
                    @if (s.pauseRequested && !s.cancelRequest) {
                      <p-button
                        [label]="s.sagaStatus === 'CHO_SU_KIEN' ? 'Tiếp tục' : 'Bỏ tạm dừng'"
                        icon="pi pi-play"
                        severity="secondary"
                        [outlined]="true"
                        [loading]="busy() === 'resume'"
                        (onClick)="resume(p, s)"
                      />
                    }
                    @if (s.totals.failed > 0) {
                      <p-button
                        label="Chạy lại hồ sơ lỗi"
                        icon="pi pi-refresh"
                        severity="secondary"
                        [outlined]="true"
                        [loading]="busy() === 'retry'"
                        (onClick)="retry(p)"
                      />
                    }
                  }
                  @if (can('SCR-0801.de-nghi-huy') && s.cancellable && !s.cancelRequest) {
                    <p-button
                      label="Huỷ và hoàn tác toàn bộ"
                      icon="pi pi-times"
                      severity="danger"
                      [outlined]="true"
                      (onClick)="openCancel()"
                    />
                  }
                  @if (!simple()) {
                    <p-button
                      label="Xem sơ đồ quy trình"
                      icon="pi pi-sitemap"
                      severity="secondary"
                      [text]="true"
                      (onClick)="openDiagram(p)"
                    />
                  }
                </div>
                <p class="muted small">
                  <!-- AC-09, BR-08-09 -->
                  Huỷ và hoàn tác toàn bộ cần người duyệt thứ hai và chỉ thực hiện được trước khi
                  bước 4 hoàn tất; sau đó chỉ còn cách mở lại hậu kiểm.
                </p>
              } @else if (!simple()) {
                <p-button
                  label="Xem sơ đồ quy trình"
                  icon="pi pi-sitemap"
                  severity="secondary"
                  [text]="true"
                  (onClick)="openDiagram(p)"
                />
              }
            </section>

            @if (failures.value()?.length) {
              <section class="box" aria-labelledby="h-fail">
                <h2 id="h-fail" class="h">Hồ sơ lỗi ({{ int(failures.value()!.length) }})</h2>
                <div class="scroll">
                  <table class="tbl">
                    <thead>
                      <tr>
                        <th scope="col">Cán bộ</th>
                        <th scope="col">Đơn vị</th>
                        <th scope="col">Lỗi</th>
                        <th scope="col" class="num">Số lần thử</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (f of failures.value(); track f.profileId) {
                        <tr>
                          <td>{{ f.person?.name ?? 'Chưa xác định' }}</td>
                          <td>{{ f.unit?.name }}</td>
                          <td>{{ f.error }}</td>
                          <td class="num">{{ f.attempts }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              </section>
            }

            @if (!simple() && batches.value()?.length) {
              <details class="box">
                <summary class="h">Lô tính điểm ({{ batches.value()!.length }})</summary>
                <div class="scroll">
                  <table class="tbl">
                    <thead>
                      <tr>
                        <th scope="col">Mã lô</th>
                        <th scope="col">Đơn vị</th>
                        <th scope="col" class="num">Số hồ sơ</th>
                        <th scope="col">Trạng thái</th>
                        <th scope="col" class="num">Số lần thử</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (b of batches.value(); track b.id) {
                        <tr>
                          <td class="mono">{{ b.id }}</td>
                          <td>{{ b.unit?.name }}</td>
                          <td class="num">{{ int(b.profileCount) }}</td>
                          <td>{{ batchLabel[b.status] }}</td>
                          <td class="num">{{ b.attempts }}</td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
              </details>
            }

            <section class="box" aria-labelledby="h-events">
              <h2 id="h-events" class="h">Nhật ký sự kiện</h2>
              <ol class="events">
                @for (e of events.value() ?? []; track e.ledgerEntryId ?? e.at + e.event) {
                  <li>
                    <span class="nowrap time">{{ dateTime(e.at) }}</span>
                    <span>
                      {{ e.event }}
                      <!-- e.evt (mã EVT) chỉ dùng tra cứu, không hiện -->
                      @if (e.actor) {
                        <span class="muted small"> · {{ e.actor.name }}</span>
                      }
                    </span>
                  </li>
                } @empty {
                  <li class="muted">Chưa có sự kiện.</li>
                }
              </ol>
            </section>
          } @else if (lockGone() && p.status === 'DA_KHOA') {
            <section class="box">
              <p class="muted">
                Kỳ đã khoá lúc {{ dateTimeOr(p.lockedAt) }}. Tiến trình của lần khoá này không còn
                hiển thị trên màn hình này; tra sổ cái để xem chi tiết.
              </p>
            </section>
          }
        }
      </div>
    </div>

    <vt-period-create-dialog [(visible)]="creating" (created)="onCreated($event)" />

    <p-dialog
      header="Lập lệnh khoá kỳ"
      [modal]="true"
      [visible]="dialog() === 'order'"
      (visibleChange)="!$event && dialog.set(null)"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <label for="lo-basis">Văn bản căn cứ</label>
      <input
        id="lo-basis"
        pInputText
        class="full"
        maxlength="255"
        placeholder="Thông báo 21-TB/ĐUK ngày 28/12/2026"
        [ngModel]="basisDoc()"
        (ngModelChange)="basisDoc.set($event)"
      />
      @if (formErrors()['basisDoc']) {
        <small class="err">{{ formErrors()['basisDoc'] }}</small>
      }
      <label for="lo-at">Thời điểm dự kiến khởi chạy</label>
      <input
        id="lo-at"
        pInputText
        type="datetime-local"
        class="full"
        [ngModel]="plannedLockAt()"
        (ngModelChange)="plannedLockAt.set($event)"
      />
      <p class="muted small">
        <!-- AC-04, BR-08-01 -->
        Lệnh được gửi lãnh đạo phê duyệt; người phê duyệt phải khác người lập.
      </p>
      <ng-template #footer>
        <p-button label="Huỷ" severity="secondary" [text]="true" (onClick)="dialog.set(null)" />
        <p-button
          label="Lập lệnh"
          icon="pi pi-send"
          [loading]="busy() === 'order'"
          (onClick)="createOrder()"
        />
      </ng-template>
    </p-dialog>

    <p-dialog
      header="Huỷ và hoàn tác toàn bộ"
      [modal]="true"
      [visible]="dialog() === 'cancel'"
      (visibleChange)="!$event && dialog.set(null)"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      <p>
        <!-- AC-09 -->
        Hệ thống sẽ hoàn tác lần lượt từ bước gần nhất đã hoàn tất và kỳ trở về trạng thái Đang mở.
        Đề nghị cần người duyệt thứ hai đồng ý.
      </p>
      <label for="cc-reason">Lý do huỷ</label>
      <textarea
        id="cc-reason"
        pTextarea
        rows="4"
        class="full"
        [ngModel]="cancelReason()"
        (ngModelChange)="cancelReason.set($event)"
      ></textarea>
      @if (formErrors()['reason']) {
        <small class="err">{{ formErrors()['reason'] }}</small>
      }
      <ng-template #footer>
        <p-button label="Đóng" severity="secondary" [text]="true" (onClick)="dialog.set(null)" />
        <p-button
          label="Gửi đề nghị huỷ"
          icon="pi pi-send"
          severity="danger"
          [loading]="busy() === 'cancel'"
          (onClick)="requestCancel()"
        />
      </ng-template>
    </p-dialog>

    <p-dialog
      header="Sơ đồ quy trình khoá kỳ"
      [modal]="true"
      [visible]="dialog() === 'diagram'"
      (visibleChange)="!$event && dialog.set(null)"
      [style]="{ width: 'min(560px, 96vw)' }"
      closeAriaLabel="Đóng"
    >
      @if (diagram(); as d) {
        <p class="muted small">
          Quy trình khoá kỳ phiên bản {{ d.templateVersion }}: các bước của quy trình và trạng thái
          hiện tại.
        </p>
        <ol class="bpmn">
          @for (a of activities(d); track a.id) {
            <li [class]="'act-' + a.state">
              {{ a.name }} · <strong>{{ a.label }}</strong>
            </li>
          }
        </ol>
      } @else {
        <p class="muted">Đang tải sơ đồ…</p>
      }
    </p-dialog>
  `,
  styles: `
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    h1 {
      font-size: 1.4rem;
      margin: 4px 0;
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
    .nowrap {
      white-space: nowrap;
    }
    .mono {
      font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
      font-size: 0.875em;
      word-break: break-all;
    }
    .actions,
    .controls {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
    }
    .controls {
      margin: 12px 0 4px;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(240px, 1fr) minmax(0, 3fr);
      gap: 16px;
      align-items: start;
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
    summary.h {
      cursor: pointer;
      font-weight: 600;
    }
    .row-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
      flex-wrap: wrap;
      margin-bottom: 8px;
    }
    .row-head .h {
      margin: 0;
    }
    .plist {
      list-style: none;
      padding: 0;
      margin: 0;
      display: grid;
      gap: 6px;
    }
    .pitem {
      width: 100%;
      text-align: left;
      padding: 10px 12px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 6px;
      background: transparent;
      font: inherit;
      color: inherit;
      cursor: pointer;
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 4px 8px;
      min-height: 44px;
    }
    .pitem.sel {
      border-color: var(--vt-color-primary, #b8002a);
      box-shadow: inset 3px 0 0 var(--vt-color-primary, #b8002a);
    }
    .pname {
      font-weight: 600;
    }
    .info {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
      gap: 10px 16px;
      margin: 0 0 12px;
    }
    .info dt {
      color: #4b5563;
      font-size: 0.875em;
    }
    .info dd {
      margin: 2px 0 0;
    }
    .cards {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
      gap: 12px;
      margin: 12px 0;
    }
    .card {
      padding: 12px 14px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
    }
    .card.first {
      border-top: 3px solid var(--vt-color-primary, #b8002a);
    }
    .card p {
      margin: 0;
    }
    .label {
      color: #4b5563;
    }
    .big {
      font-size: 1.6rem;
      font-weight: 700;
      line-height: 1.3;
    }
    .big.bad {
      color: #9b1c1c;
    }
    .warnbox {
      padding: 8px 12px;
      border: 1px solid #f3d27a;
      border-radius: 6px;
      background: #fff8e1;
      color: #6b4400;
      margin: 0 0 8px;
    }
    .cancelbox {
      padding: 12px;
      border: 1px solid #f5b5b5;
      border-radius: 6px;
      background: #fff5f5;
      margin: 12px 0;
    }
    .cancelbox p {
      margin: 0 0 8px;
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
    }
    .tbl th,
    .tbl td {
      padding: 8px 6px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
      vertical-align: top;
    }
    .tbl .step-name {
      color: inherit;
      font-weight: 600;
    }
    .tbl tr.cur {
      background: #f5f9ff;
    }
    .num {
      text-align: right;
    }
    .chip {
      display: inline-block;
      padding: 1px 8px;
      border-radius: 4px;
      background: #f3f4f6;
      font-size: 0.875em;
      margin: 0 4px 2px 0;
    }
    .status {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 10px;
      border-radius: 999px;
      font-size: 0.875em;
      white-space: nowrap;
    }
    .st-ok {
      background: #e7f6ec;
      color: #146c2e;
    }
    .st-warn {
      background: #fff4d6;
      color: #7a4f00;
    }
    .st-run {
      background: #e6f0ff;
      color: #1e40af;
    }
    .st-wait {
      background: #f3f4f6;
      color: #374151;
    }
    .st-bad {
      background: #fde8e8;
      color: #9b1c1c;
    }
    .meter {
      width: 120px;
      height: 8px;
      border-radius: 4px;
      background: #e5e7eb;
      overflow: hidden;
      margin-top: 6px;
    }
    .meter .fill {
      display: block;
      height: 100%;
      background: #1e40af;
    }
    .events {
      list-style: none;
      padding: 0;
      margin: 0;
      display: grid;
      gap: 8px;
    }
    .events li {
      display: grid;
      grid-template-columns: 9.5em 1fr;
      gap: 8px;
    }
    .events .time {
      color: #4b5563;
      font-size: 0.875em;
    }
    .bpmn {
      padding-left: 1.2em;
      display: grid;
      gap: 6px;
    }
    .act-done strong {
      color: #146c2e;
    }
    .act-active strong {
      color: #1e40af;
    }
    .act-failed strong {
      color: #9b1c1c;
    }
    label {
      display: block;
      margin: 8px 0 4px;
      font-weight: 500;
    }
    .full {
      width: 100%;
    }
    .err {
      color: #9b1c1c;
    }
    .link {
      background: none;
      border: 0;
      padding: 0 0 0 8px;
      color: var(--vt-color-primary, #b8002a);
      text-decoration: underline;
      cursor: pointer;
      font: inherit;
    }
    :host ::ng-deep .msg {
      margin: 8px 0;
      width: 100%;
    }
    /* màu mặc định của preset cho nút nguy hiểm, thông báo thành công chưa đạt tương phản 4,5:1 (WCAG AA) */
    :host ::ng-deep .p-button-danger:not(.p-button-outlined) {
      background: #b91c1c;
      border-color: #b91c1c;
      color: #fff;
    }
    :host ::ng-deep .p-button-danger.p-button-outlined {
      color: #b91c1c;
      border-color: #b91c1c;
    }
    :host ::ng-deep .p-message-success .p-message-text,
    :host ::ng-deep .p-message-success .p-message-icon {
      color: #0f5132;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
  `,
})
export class PeriodsPage {
  private readonly api = inject(PeriodApi);
  private readonly theme = inject(ThemeService);
  private readonly stepUp = inject(PERIOD_STEP_UP);
  protected readonly can = inject(ADMIN_CAN);

  protected readonly kindLabel = PERIOD_KIND_LABEL;
  protected readonly flowLabel = PERIOD_FLOW_LABEL;
  protected readonly orderLabel = LOCK_ORDER_STATUS_LABEL;
  protected readonly sagaLabel = SAGA_STATUS_LABEL;
  protected readonly stepLabel = LOCK_STEP_STATUS_LABEL;
  protected readonly batchLabel = SCORE_BATCH_STATUS_LABEL;
  protected readonly stepIcon = STEP_ICON;
  protected readonly int = fmtInt;
  protected readonly date = fmtDate;
  protected readonly dateTime = fmtDateTime;
  protected readonly time = timeOf;
  protected readonly msg0803 = MSG_08_03;
  protected readonly msg0804 = MSG_08_04;
  protected readonly msg0808 = MSG_08_08;

  /** Chế độ người cao tuổi rút gọn: ẩn lô tính điểm và sơ đồ BPMN (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected readonly periods = rxResource({ stream: () => this.api.list() });

  /** Kỳ đang xem: mặc định kỳ có saga đang chạy, nếu không thì kỳ mới nhất. */
  protected readonly selectedId = linkedSignal<Period[] | undefined, string | null>({
    source: () => (this.periods.hasValue() ? this.periods.value() : undefined),
    computation: (list, prev) => {
      if (!list?.length) return null;
      if (prev?.value && list.some((p) => p.id === prev.value)) return prev.value;
      return (list.find((p) => p.status === 'DANG_KHOA') ?? list[0]).id;
    },
  });

  protected readonly selected = computed(() =>
    this.periods.hasValue()
      ? this.periods.value().find((p) => p.id === this.selectedId())
      : undefined,
  );

  /** Kỳ có lệnh đã khởi chạy (có `sagaId`) mới có tiến trình; kỳ khác trả 404. */
  private readonly lockId = computed(() => {
    const p = this.selected();
    return p?.currentLockOrder?.sagaId ? p.id : undefined;
  });

  protected readonly progress = rxResource({
    params: () => this.lockId(),
    stream: ({ params }) => this.api.progress(params),
  });
  protected readonly failures = rxResource({
    params: () => this.lockId(),
    stream: ({ params }) => this.quiet(this.api.failures(params)),
  });
  protected readonly batches = rxResource({
    params: () => (this.simple() ? undefined : this.lockId()),
    stream: ({ params }) => this.quiet(this.api.batches(params)),
  });
  protected readonly events = rxResource({
    params: () => this.lockId(),
    stream: ({ params }) => this.quiet(this.api.events(params)),
  });

  /** Tiến trình của kỳ đang chọn; giữ giá trị cũ khi đọc lại để màn hình không nháy. */
  protected readonly pr = computed(() => {
    const id = this.lockId();
    const v = this.progress.hasValue() ? this.progress.value() : undefined;
    return v && v.periodId === id ? v : undefined;
  });

  protected readonly lockGone = computed(() => !this.lockId());

  protected readonly busy = signal<string | null>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly actionError = signal<string | null>(null);
  protected readonly formErrors = signal<Record<string, string>>({});
  protected readonly creating = signal(false);
  protected readonly dialog = signal<Dialog>(null);
  protected readonly basisDoc = signal('');
  protected readonly plannedLockAt = signal('');
  protected readonly cancelReason = signal('');
  protected readonly diagram = signal<PeriodLockDiagram | null>(null);

  constructor() {
    const pollMs = inject(PERIOD_POLL_MS);
    let lastStatus: SagaStatus | undefined;
    interval(pollMs)
      .pipe(takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => {
        const s = this.pr();
        if (!s || !this.active(s) || this.progress.isLoading()) return;
        this.reloadSaga();
      });
    // saga đổi trạng thái kết thúc (khoá xong, bù trừ xong): đọc lại danh sách kỳ để cập nhật trạng thái
    effect(() => {
      const s = this.pr()?.sagaStatus;
      if (s && lastStatus && s !== lastStatus && !SAGA_ACTIVE_STATUSES.includes(s))
        this.periods.reload();
      lastStatus = s;
    });
  }

  protected select(id: string) {
    this.selectedId.set(id);
    this.notice.set(null);
    this.actionError.set(null);
  }

  protected active(s: PeriodLockProgress) {
    return SAGA_ACTIVE_STATUSES.includes(s.sagaStatus);
  }

  protected canOrder(p: Period) {
    const o = p.currentLockOrder;
    return (
      (p.status === 'MO' || p.status === 'MO_LAI_HAU_KIEM') &&
      !p.notStarted &&
      (!o || o.status === 'DA_HUY')
    );
  }

  protected periodStatus(p: Period) {
    return p.status === 'MO' && p.notStarted ? 'Đã tạo' : PERIOD_STATUS_LABEL[p.status];
  }

  protected periodTone(p: Period) {
    if (p.status === 'MO' && p.notStarted) return 'st-wait';
    return { MO: 'st-ok', DANG_KHOA: 'st-run', DA_KHOA: 'st-wait', MO_LAI_HAU_KIEM: 'st-warn' }[
      p.status
    ];
  }

  protected orderTone(s: string) {
    return s === 'CHO_DUYET'
      ? 'st-warn'
      : s === 'DA_HUY'
        ? 'st-bad'
        : s === 'DANG_CHAY'
          ? 'st-run'
          : 'st-ok';
  }

  protected sagaTone(s: SagaStatus) {
    return {
      DANG_CHAY: 'st-run',
      CHO_SU_KIEN: 'st-warn',
      HOAN_TAT: 'st-ok',
      DANG_BU_TRU: 'st-warn',
      DA_BU_TRU: 'st-wait',
      LOI_CAN_XU_LY: 'st-bad',
    }[s];
  }

  protected stepTone(s: PeriodLockStepStatus) {
    return {
      CHO: 'st-wait',
      DANG_CHAY: 'st-run',
      DAT: 'st-ok',
      DAT_CO_CANH_BAO: 'st-warn',
      LOI: 'st-bad',
      DA_BU_TRU: 'st-wait',
    }[s];
  }

  protected stepNote(st: PeriodLockStep) {
    const r = st.resultSummary;
    if (!r) return '';
    if (typeof r['note'] === 'string') return r['note'];
    return Object.entries(r)
      .filter(([, v]) => typeof v === 'string' || typeof v === 'number')
      .map(([k, v]) => `${k}: ${v}`)
      .join('; ');
  }

  protected scoredPercent(s: PeriodLockProgress) {
    return s.totals.profiles ? Math.floor((s.totals.scored / s.totals.profiles) * 100) : 0;
  }

  /** Bước đang chạy khi dự kiến hoàn tất vượt ngân sách NFR-06 (MSG-08-04). */
  protected slowStep(s: PeriodLockProgress): number | null {
    if (!this.active(s) || !s.estimatedFinishAt || !s.budgetFinishAt) return null;
    if (s.estimatedFinishAt <= s.budgetFinishAt) return null;
    return s.steps.find((x) => x.status === 'DANG_CHAY')?.stepNo ?? null;
  }

  protected msg0807(p: Period, s: PeriodLockProgress) {
    const at = p.lockedAt ?? s.steps[5]?.finishedAt ?? s.updatedAt;
    return MSG_08_07(p.name, at ? fmtDateTime(at) : '');
  }

  protected dateOr(d?: string | null) {
    return d ? fmtDate(d) : 'theo bộ quy định đánh giá';
  }

  protected dateTimeOr(d?: string | null) {
    return d ? fmtDateTime(d) : '';
  }

  protected errText(err: unknown, fallback: string) {
    return this.problemText(err) || fallback;
  }

  protected activities(d: PeriodLockDiagram) {
    const state = (id: string) =>
      d.failedActivityIds.includes(id)
        ? 'failed'
        : d.activeActivityIds.includes(id)
          ? 'active'
          : d.completedActivityIds.includes(id)
            ? 'done'
            : 'wait';
    const label = { failed: 'Lỗi', active: 'Đang chạy', done: 'Đã xong', wait: 'Chưa chạy' };
    const names = this.pr()?.steps ?? [];
    return STEP_ACTIVITY.map((id, i) => {
      const st = state(id);
      return { id, name: names[i]?.name ?? '', state: st, label: label[st] };
    });
  }

  protected onCreated(p: Period) {
    this.notice.set(
      p.warnings?.length
        ? `Đã tạo kỳ ${p.name}. Lưu ý: ${p.warnings.join(' ')}`
        : `Đã tạo kỳ ${p.name}.`,
    );
    this.periods.reload();
    this.selectedId.set(p.id);
  }

  protected openOrder() {
    this.basisDoc.set('');
    this.plannedLockAt.set('');
    this.formErrors.set({});
    this.dialog.set('order');
  }

  protected openCancel() {
    this.cancelReason.set('');
    this.formErrors.set({});
    this.dialog.set('cancel');
  }

  protected openDiagram(p: Period) {
    this.diagram.set(null);
    this.dialog.set('diagram');
    this.api.diagram(p.id).subscribe({
      next: (d) => this.diagram.set(d),
      error: (e: unknown) => {
        this.dialog.set(null);
        this.fail(e);
      },
    });
  }

  protected createOrder() {
    const p = this.selected();
    if (!p) return;
    const at = this.plannedLockAt();
    this.run('order', () =>
      this.api.createLockOrder(p.id, {
        basisDoc: this.basisDoc().trim(),
        plannedLockAt: at ? new Date(at).toISOString() : undefined,
      }),
    ).then((o) => {
      if (!o) return;
      this.dialog.set(null);
      this.notice.set(`Đã lập lệnh khoá kỳ ${p.name}, chờ lãnh đạo phê duyệt.`);
      this.periods.reload();
    });
  }

  protected async approveOrder(p: Period) {
    const o = p.currentLockOrder;
    if (!o) return;
    if (!(await this.stepUp('Phê duyệt lệnh khoá kỳ cần xác thực nhiều lớp.'))) return;
    const res = await this.run('approve', () => this.api.approveLockOrder(p.id, o));
    if (!res) return;
    const from = res.plannedLockAt ? fmtDateTime(res.plannedLockAt) : 'bây giờ';
    this.notice.set(MSG_08_01(p.name, res.approvedBy?.name ?? '', from));
    this.periods.reload();
  }

  protected async start(p: Period) {
    const o = p.currentLockOrder;
    if (!o) return;
    if (!(await this.stepUp('Khởi chạy tiến trình khoá kỳ cần xác thực nhiều lớp.'))) return;
    const res = await this.run('start', () => this.api.start(p.id, o));
    if (!res) return;
    if (res.job.status === 'THAT_BAI') {
      this.actionError.set(res.job.problem?.detail ?? 'Chưa thể khởi chạy: bước 0 không đạt.');
      return;
    }
    this.notice.set(`Đã khởi chạy tiến trình khoá kỳ ${p.name}. Tiến độ được cập nhật tự động.`);
    this.periods.reload();
  }

  protected async pause(p: Period, s: PeriodLockProgress) {
    if (!(await this.stepUp('Tạm dừng tiến trình khoá kỳ cần xác thực nhiều lớp.'))) return;
    const res = await this.run('pause', () => this.api.pause(p.id, s.lockOrder));
    if (!res) return;
    this.notice.set('Tiến trình sẽ dừng khi bước hiện tại hoàn tất.');
    this.reloadSaga();
  }

  protected async resume(p: Period, s: PeriodLockProgress) {
    const res = await this.run('resume', () => this.api.resume(p.id, s.lockOrder));
    if (!res) return;
    this.notice.set('Tiến trình khoá kỳ tiếp tục chạy.');
    this.reloadSaga();
  }

  protected async retry(p: Period) {
    if (!(await this.stepUp('Chạy lại hồ sơ lỗi cần xác thực nhiều lớp.'))) return;
    const res = await this.run('retry', () => this.api.retryFailed(p.id));
    if (!res) return;
    this.notice.set('Đã tạo lô mới cho các hồ sơ lỗi.');
    this.reloadSaga();
  }

  protected async requestCancel() {
    const p = this.selected();
    const s = this.pr();
    if (!p || !s) return;
    if (!(await this.stepUp('Đề nghị huỷ tiến trình khoá kỳ cần xác thực nhiều lớp.'))) return;
    const res = await this.run('cancel', () =>
      this.api.requestCancel(p.id, s.lockOrder, this.cancelReason().trim()),
    );
    if (!res) return;
    this.dialog.set(null);
    this.notice.set(MSG_08_05());
    this.reloadSaga();
  }

  protected async approveCancel(p: Period, s: PeriodLockProgress) {
    if (!(await this.stepUp('Duyệt huỷ và hoàn tác cần xác thực nhiều lớp.'))) return;
    const res = await this.run('approveCancel', () => this.api.approveCancel(p.id, s.lockOrder));
    if (!res) return;
    this.notice.set('Đã duyệt huỷ; hệ thống đang hoàn tác theo thứ tự ngược.');
    this.reloadSaga();
  }

  private reloadSaga() {
    this.progress.reload();
    this.failures.reload();
    this.batches.reload();
    this.events.reload();
  }

  /** Lỗi đọc danh sách phụ không chặn màn hình; tiến trình chính vẫn báo lỗi riêng. */
  private quiet<T>(o: Observable<T[]>): Observable<T[]> {
    return o.pipe(catchError(() => of([] as T[])));
  }

  private run<T>(key: string, call: () => Observable<T>): Promise<T | null> {
    this.busy.set(key);
    this.notice.set(null);
    this.actionError.set(null);
    this.formErrors.set({});
    return new Promise((resolve) => {
      call().subscribe({
        next: (v) => {
          this.busy.set(null);
          resolve(v);
        },
        error: (e: unknown) => {
          this.busy.set(null);
          const fe = fieldErrors(toProblem(e));
          if (Object.keys(fe).length && this.dialog()) this.formErrors.set(fe);
          else this.fail(e);
          resolve(null);
        },
      });
    });
  }

  private fail(e: unknown) {
    this.actionError.set(this.problemText(e));
    if (toProblem(e).status === 409 || toProblem(e).status === 412) {
      this.periods.reload();
      if (this.lockId()) this.reloadSaga();
    }
  }

  /**
   * Văn bản lỗi: BR-08-01, BR-08-09 (403 `PERIOD-403-SAME-PERSON`) hiện lý do cụ thể của máy chủ; 403
   * khác dùng câu không có quyền chung; còn lại là `title` kèm `detail` của RFC 9457.
   */
  private problemText(e: unknown): string {
    const p = toProblem(e);
    if (p.status === 403)
      return p.code === 'PERIOD-403-SAME-PERSON' && p.detail ? p.detail : TEXT_FORBIDDEN;
    return p.detail ? `${p.title}. ${p.detail}` : p.title;
  }
}
