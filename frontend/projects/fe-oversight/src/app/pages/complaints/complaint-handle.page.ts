/*
 * SCR-1202 "Xử lý khiếu nại" (QT-12 bước 4–10; TL-02 Hình 11.4) — đối chiếu đặc tả:
 * Tác nhân AC-07 người xử lý (được chỉ định); bên liên quan ở chế độ giải trình (AC-05, AC-07); AC-08 ký văn
 *   bản trả lời; web máy tính. OPA kiểm tách vai trò ở mọi thao tác ghi (BR-12-04).
 * Trường: 1 danh sách khiếu nại (mã, người, trạng thái, hạn; sắp theo hạn); 2 nội dung khiếu nại (C2 giải mã
 *   khi xem, ghi nhật ký truy cập); 3 dòng thời gian; 4 bên được yêu cầu giải trình (ít nhất 1); 5 giải
 *   trình đã nhận; 6 kết quả xử lý GIU_NGUYEN/CHAP_NHAN_MOT_PHAN/CHAP_NHAN; 7 điều chỉnh đề nghị (cấu phần,
 *   giá trị cũ, mới, lý do; bắt buộc khi chấp nhận); 8 kết quả tính thử (không lưu); 9 văn bản trả lời (số
 *   văn bản theo mẫu TL-KN-01, phải ký trước khi gửi).
 * Vùng: 1 danh sách; 2 phần đầu khiếu nại, ghi chú C2, dòng thời gian; 3 giải trình; 4 kết luận và tính thử;
 *   5 thi hành kết luận (mở lại kỳ khi kỳ đã khoá).
 * Chức năng: Tiếp nhận hoặc không thụ lý (POST …/accept, …/reject, EVT-21); Yêu cầu giải trình (POST
 *   …/explanation-requests, EVT-21); bên liên quan gửi giải trình (POST …/explanations); Tính thử (POST
 *   /appraisals/profiles/{id}/simulate); Ghi kết luận (PUT …/conclusion); Ký số và trả lời (POST …/respond,
 *   202, EVT-18, EVT-21); Tạo yêu cầu mở lại kỳ (sang SCR-1301 với căn cứ là khiếu nại).
 * Quy tắc, thông báo: BR-12-04 (403 MSG-12-05), BR-12-05, BR-12-07 (MSG-12-07), BR-12-08 (MSG-12-06),
 *   BR-12-10, BR-12-11; MSG-12-08 nhắc hạn do SVC-12 gửi.
 * Quyền (wiki/15a): SCR-1202.tiep-nhan, giai-trinh, tinh-thu, ky-tra-loi, mo-lai. Ghi kết luận dùng
 *   `tiep-nhan` vì ma trận không tách khoá riêng (suy luận).
 */
import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { OrgApi, toProblem } from '@vt/api-client';
import {
  AppraisalSimulationResult,
  COMPLAINT_KIND_LABEL,
  COMPLAINT_REJECT_REASON_LABEL,
  COMPLAINT_RESOLUTION_LABEL,
  COMPLAINT_STATUS_LABEL,
  COMPLAINT_STAGE_LABEL,
  Complaint,
  ComplaintAdjustment,
  ComplaintApi,
  ComplaintRejectReasonCode,
  ComplaintResolution,
  ComplaintResponseRecord,
  ComplaintStatus,
  ExplanationParty,
  RATING_LEVEL_LABEL,
  RatingLevel,
} from '@vt/api-client/domains';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { MessageModule } from 'primeng/message';
import { MultiSelectModule } from 'primeng/multiselect';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TagModule } from 'primeng/tag';
import { TextareaModule } from 'primeng/textarea';
import { forkJoin, map, Observable } from 'rxjs';
import { OVERSIGHT_CAN, OVERSIGHT_USER_ID } from '../../oversight-permissions';
import { errorText, fmtDate, fmtDateTime } from '../../oversight-ui';
import { OVERSIGHT_STEP_UP } from '../../step-up';
import { complaintSeverity, componentOf, daysLeftText, fmtNum, targetValue } from './complaint-ui';

type Busy = 'accept' | 'reject' | 'ask' | 'explain' | 'simulate' | 'conclude' | 'respond' | null;

interface AdjRow {
  key: string;
  label: string;
  on: boolean;
  oldValue: number | null;
  newValue: string;
  reason: string;
}

interface PartyOption {
  value: string;
  label: string;
}

const SIGN_REASON = 'Cần xác thực bổ sung để ký số văn bản trả lời khiếu nại.';

@Component({
  selector: 'vt-complaint-handle-page',
  imports: [
    VtSkeletonComponent,
    FormsModule,
    RouterLink,
    ButtonModule,
    DialogModule,
    InputTextModule,
    MessageModule,
    MultiSelectModule,
    SelectModule,
    SelectButtonModule,
    TagModule,
    TextareaModule,
  ],
  template: `
    <p class="crumb"><a [routerLink]="id() ? '../..' : '..'">Giám sát, hậu kiểm</a></p>
    <h1>Xử lý khiếu nại</h1>
    @if (current(); as c) {
      @if (c.handler) {
        <p class="muted">
          Người xử lý {{ c.handler.name }}{{ c.handlerUnit ? ', ' + c.handlerUnit.name : '' }}, do
          hệ thống chọn và không tham gia nghiệm thu, thẩm định, đề xuất, quyết định hồ sơ liên
          quan.
        </p>
      }
    }

    <div class="grid">
      <!-- vùng 1 -->
      <section class="box list" aria-labelledby="l-h">
        <h2 class="h" id="l-h">Khiếu nại</h2>
        <p-selectbutton
          [options]="views"
          optionLabel="label"
          optionValue="value"
          [ngModel]="view()"
          (ngModelChange)="view.set($event)"
          [allowEmpty]="false"
          ariaLabelledBy="l-h"
        />
        @if (list.error()) {
          <p-message severity="error">{{ err(list.error()) }}</p-message>
        } @else if (list.value(); as rows) {
          @if (!rows.length) {
            <p class="muted">Không có khiếu nại nào.</p>
          }
          <ul class="items">
            @for (r of rows; track r.id) {
              <li [class.sel]="r.id === id()">
                <a
                  [routerLink]="id() ? ['..', r.id] : [r.id]"
                  [attr.aria-current]="r.id === id() ? 'page' : null"
                >
                  <strong>{{ r.code }}</strong> · {{ r.complainant?.name }}
                </a>
                <span class="small">{{ r.subjectLine }}</span>
                <span class="small">
                  <p-tag [value]="statusText(r.status)" [severity]="severity(r.status)" />
                  @if (days(r.workingDaysLeft); as t) {
                    {{ t }}
                  }
                </span>
              </li>
            }
          </ul>
        } @else {
          <vt-skeleton variant="list" [rows]="5" label="Đang tải danh sách khiếu nại" />
        }
      </section>

      @if (!id()) {
        <section class="box">
          <p class="muted">Chọn một khiếu nại trong danh sách để xem và xử lý.</p>
        </section>
      } @else if (complaint.error()) {
        <section class="box">
          <p-message severity="error">{{ loadError() }}</p-message>
        </section>
      } @else if (current(); as c) {
        <div class="main">
          @if (notice(); as n) {
            <p-message severity="success" role="status">{{ n }}</p-message>
          }
          @if (error(); as e) {
            <p-message severity="error" role="alert">{{ e }}</p-message>
          }
          <!-- vùng 2 -->
          <section class="box" aria-labelledby="c-h">
            <h2 class="h" id="c-h">
              {{ c.code }}
              <p-tag [value]="kindLabel[c.kind]" severity="secondary" />
              <p-tag [value]="statusText(c.status)" [severity]="severity(c.status)" />
            </h2>
            @if (!simple()) {
              <p class="muted small">
                Nội dung khiếu nại được bảo mật; mỗi lần xem đều được ghi nhận.
              </p>
            }
            <dl class="meta">
              <dt>Người khiếu nại</dt>
              <dd>{{ c.complainant?.name }}</dd>
              <dt>Kết quả</dt>
              <dd>{{ c.subjectLine }} · bước {{ stageLabel[c.targetStage ?? 'THAM_DINH'] }}</dd>
              <dt>Hạn trả lời</dt>
              <dd>
                {{ d(c.responseDeadline) }}
                @if (days(c.workingDaysLeft); as t) {
                  ({{ t }})
                }
              </dd>
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
            <h3 class="h3">Đề nghị</h3>
            <p class="text">{{ c.requestText }}</p>
            @if (c.attachmentObjectIds?.length) {
              <p class="muted small">Kèm {{ c.attachmentObjectIds?.length }} tài liệu.</p>
            }
            @if (c.reserveOpinion) {
              <p class="small">Người khiếu nại chọn bảo lưu ý kiến nếu không được chấp nhận.</p>
            }
            @if (c.timeline?.length) {
              <h3 class="h3">Dòng thời gian</h3>
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
            @if (c.status === 'MOI' && can('SCR-1202.tiep-nhan')) {
              <div class="actions mt">
                <p-button
                  label="Tiếp nhận"
                  [loading]="busy() === 'accept'"
                  [disabled]="!!busy()"
                  (onClick)="accept()"
                />
                <p-button
                  label="Không thụ lý"
                  severity="danger"
                  [outlined]="true"
                  [disabled]="!!busy()"
                  (onClick)="openReject()"
                />
              </div>
            }
          </section>

          <!-- vùng 3 -->
          <section class="box" aria-labelledby="g-h">
            <h2 class="h" id="g-h">Giải trình</h2>
            @if (records.isLoading() && !asks().length) {
              <vt-skeleton variant="list" [rows]="2" label="Đang tải yêu cầu giải trình" />
            } @else if (asks().length) {
              <ul class="records">
                @for (a of asks(); track a.ask.id) {
                  <li [class.ask]="!a.answer" [class.answer]="!!a.answer">
                    <p class="small">
                      <strong>{{ partyName(a.ask.party) }}</strong>
                      · yêu cầu lúc {{ dt(a.ask.respondedAt) }} · hạn {{ d(a.ask.dueDate) }}
                    </p>
                    <p class="text">{{ a.ask.content }}</p>
                    @if (a.answer; as g) {
                      <p class="small">
                        <strong>Giải trình của {{ g.responder?.name }}</strong> lúc
                        {{ dt(g.respondedAt) }}
                      </p>
                      <p class="text">{{ g.content }}</p>
                      @if (g.attachmentObjectIds?.length) {
                        <p class="muted small">Kèm {{ g.attachmentObjectIds?.length }} tài liệu.</p>
                      }
                    } @else if (
                      a.ask.party?.id === me() &&
                      c.status === 'DANG_GIAI_TRINH' &&
                      can('SCR-1202.giai-trinh')
                    ) {
                      <div class="field">
                        <label [for]="'gt-' + a.ask.id">Nội dung giải trình của đồng chí</label>
                        <textarea
                          pTextarea
                          [id]="'gt-' + a.ask.id"
                          class="gt"
                          rows="3"
                          maxlength="8000"
                          [ngModel]="explainText()"
                          (ngModelChange)="explainText.set($event)"
                        ></textarea>
                      </div>
                      <p-button
                        label="Gửi giải trình"
                        [loading]="busy() === 'explain'"
                        [disabled]="!explainText().trim() || !!busy()"
                        (onClick)="explain(a.ask)"
                      />
                    } @else {
                      <p class="muted small">Chưa nhận giải trình.</p>
                    }
                  </li>
                }
              </ul>
            } @else {
              <p class="muted">Chưa có yêu cầu giải trình.</p>
            }
            @if (canAsk()) {
              <h3 class="h3">Yêu cầu giải trình</h3>
              <div class="field">
                <label for="yc-parties">Bên được yêu cầu giải trình (ít nhất 1)</label>
                <p-multiselect
                  inputId="yc-parties"
                  [options]="partyOptions()"
                  [loading]="orgOptions.isLoading()"
                  optionLabel="label"
                  optionValue="value"
                  [ngModel]="parties()"
                  (ngModelChange)="parties.set($event)"
                  [filter]="true"
                  filterPlaceHolder="Tìm người hoặc đơn vị"
                  placeholder="Chọn người hoặc đơn vị"
                  display="chip"
                  styleClass="w-full"
                />
              </div>
              <div class="field">
                <label for="yc-content">Nội dung yêu cầu</label>
                <textarea
                  pTextarea
                  id="yc-content"
                  rows="3"
                  maxlength="8000"
                  [ngModel]="askText()"
                  (ngModelChange)="askText.set($event)"
                ></textarea>
              </div>
              <div class="field narrow">
                <label for="yc-due">Hạn giải trình (bỏ trống: 3 ngày làm việc)</label>
                <input
                  pInputText
                  id="yc-due"
                  type="date"
                  [ngModel]="askDue()"
                  (ngModelChange)="askDue.set($event)"
                />
              </div>
              <p-button
                label="Gửi yêu cầu giải trình"
                [loading]="busy() === 'ask'"
                [disabled]="!parties().length || !askText().trim() || !!busy()"
                (onClick)="ask()"
              />
            }
          </section>

          <!-- vùng 4 -->
          <section class="box" aria-labelledby="k-h">
            <h2 class="h" id="k-h">Kết luận</h2>
            @if (canConclude()) {
              <div class="field">
                <label for="kl-outcome" id="kl-outcome-l">Kết quả xử lý (bắt buộc)</label>
                <p-select
                  inputId="kl-outcome"
                  ariaLabelledBy="kl-outcome-l"
                  [options]="outcomes"
                  optionLabel="label"
                  optionValue="value"
                  [ngModel]="outcome()"
                  (ngModelChange)="pickOutcome($event)"
                  placeholder="Chọn kết quả"
                />
              </div>
              @if (outcome() && outcome() !== 'GIU_NGUYEN') {
                <div class="table-wrap">
                  <table class="tbl">
                    <caption class="label">
                      Điều chỉnh đề nghị (bắt buộc khi chấp nhận)
                    </caption>
                    <thead>
                      <tr>
                        <th scope="col">Điều chỉnh</th>
                        <th scope="col">Cấu phần</th>
                        <th scope="col">Hiện tại</th>
                        <th scope="col">Giá trị mới</th>
                        <th scope="col">Lý do</th>
                      </tr>
                    </thead>
                    <tbody>
                      @for (r of adj(); track r.key; let i = $index) {
                        <tr>
                          <td>
                            <input
                              type="checkbox"
                              [id]="'adj-on-' + i"
                              [checked]="r.on"
                              (change)="patchAdj(i, { on: !r.on })"
                              [attr.aria-label]="'Điều chỉnh ' + r.label"
                            />
                          </td>
                          <td>
                            <label [for]="'adj-on-' + i" class="plain">{{ r.label }}</label>
                          </td>
                          <td>{{ num(r.oldValue) }}</td>
                          <td>
                            <input
                              pInputText
                              type="text"
                              inputmode="decimal"
                              [id]="'adj-new-' + i"
                              [disabled]="!r.on"
                              [ngModel]="r.newValue"
                              (ngModelChange)="patchAdj(i, { newValue: $event })"
                              [attr.aria-label]="'Giá trị mới của ' + r.label"
                            />
                          </td>
                          <td>
                            <input
                              pInputText
                              type="text"
                              [id]="'adj-reason-' + i"
                              [disabled]="!r.on"
                              [ngModel]="r.reason"
                              (ngModelChange)="patchAdj(i, { reason: $event })"
                              [attr.aria-label]="'Lý do điều chỉnh ' + r.label"
                            />
                          </td>
                        </tr>
                      }
                    </tbody>
                  </table>
                </div>
                @if (can('SCR-1202.tinh-thu')) {
                  <p-button
                    label="Tính thử"
                    severity="contrast"
                    [outlined]="true"
                    [loading]="busy() === 'simulate'"
                    [disabled]="!adjustments().length || !!busy()"
                    (onClick)="simulate()"
                  />
                }
                @if (sim(); as s) {
                  <!-- MSG-12-06 -->
                  <p class="sim" role="status">
                    Kết quả tính thử theo bộ tiêu chí đánh giá đã áp dụng cho kỳ: điểm
                    {{ score(s.before.totalScore) }} thành {{ score(s.after.totalScore) }}; xếp loại
                    {{ rating(s.before.ratingCode) }} thành {{ rating(s.after.ratingCode) }}. Kết
                    quả chưa được ghi nhận.
                    @if (s.explanation && !simple()) {
                      <span class="block">{{ s.explanation }}</span>
                    }
                  </p>
                }
              }
              @if (c.status === 'DA_TIEP_NHAN') {
                <div class="field">
                  <label for="kl-note">Ghi chú kết luận không qua giải trình (bắt buộc)</label>
                  <textarea
                    pTextarea
                    id="kl-note"
                    rows="2"
                    maxlength="2000"
                    [ngModel]="note()"
                    (ngModelChange)="note.set($event)"
                  ></textarea>
                </div>
              }
              <div class="field">
                <label for="kl-text">Nội dung văn bản trả lời</label>
                <textarea
                  pTextarea
                  id="kl-text"
                  rows="5"
                  maxlength="16000"
                  [ngModel]="responseText()"
                  (ngModelChange)="responseText.set($event)"
                ></textarea>
              </div>
              <div class="field narrow">
                <label for="kl-doc">Số văn bản trả lời</label>
                <input
                  pInputText
                  id="kl-doc"
                  type="text"
                  maxlength="200"
                  placeholder="Ví dụ 08-CV/BTC"
                  [ngModel]="docRef()"
                  (ngModelChange)="docRef.set($event)"
                />
              </div>
              @if (concludeError(); as m) {
                <p class="err" role="alert">{{ m }}</p>
              }
              <p-button
                [label]="c.conclusion ? 'Cập nhật kết luận' : 'Ghi kết luận'"
                [loading]="busy() === 'conclude'"
                [disabled]="!!busy()"
                (onClick)="conclude()"
              />
            } @else if (c.conclusion; as k) {
              <p>
                Kết quả xử lý:
                <strong>{{ k.outcome ? resolutionLabel[k.outcome] : '—' }}</strong>
                @if (k.responseDocRef) {
                  · văn bản {{ k.responseDocRef }}
                }
              </p>
              @if (k.adjustments?.length) {
                <ul>
                  @for (x of k.adjustments; track x.targetKey) {
                    <li>
                      {{ x.targetKey }}: {{ num(x.oldValue) }} thành {{ num(x.newValue) }} ({{
                        x.reason
                      }})
                    </li>
                  }
                </ul>
              }
              @if (conclusionText(); as t) {
                <p class="text">{{ t }}</p>
              } @else if (records.isLoading()) {
                <vt-skeleton variant="text" [rows]="3" label="Đang tải nội dung kết luận" />
              }
            } @else {
              <p class="muted">Chưa có kết luận.</p>
            }
            @if (c.conclusion; as k) {
              @if (k.outcome !== 'GIU_NGUYEN' && k.periodLocked) {
                <!-- MSG-12-07 -->
                <p-message severity="warn" styleClass="mt">
                  Kỳ {{ c.period.label }} đã khoá. Điều chỉnh chỉ có hiệu lực sau khi yêu cầu mở lại
                  kỳ được hai người có thẩm quyền phê duyệt.
                </p-message>
              }
              @if (c.status === 'CHO_KET_LUAN' && can('SCR-1202.ky-tra-loi')) {
                <div class="actions mt">
                  <p-button
                    label="Ký số và trả lời"
                    [loading]="busy() === 'respond'"
                    [disabled]="!!busy() || !k.responseDocRef"
                    (onClick)="respond()"
                  />
                  @if (!k.responseDocRef) {
                    <span class="muted small">Cần số văn bản trả lời trước khi ký.</span>
                  }
                </div>
              }
            }
          </section>

          @if (needsReopen()) {
            <!-- vùng 5 -->
            <section class="box" aria-labelledby="t-h">
              <h2 class="h" id="t-h">Thi hành kết luận</h2>
              <p>
                Kỳ {{ c.period.label }} đã khoá nên điều chỉnh phải qua yêu cầu mở lại kỳ có hai
                người duyệt; không có cách sửa trực tiếp.
              </p>
              @if (c.reopenRequestId) {
                <a
                  class="p-button p-button-outlined p-button-contrast"
                  [routerLink]="['../../reopen-requests', c.reopenRequestId]"
                  >Xem yêu cầu mở lại kỳ</a
                >
              } @else if (can('SCR-1202.mo-lai')) {
                <a
                  class="p-button"
                  routerLink="../../reopen-requests/new"
                  [queryParams]="reopenQuery(c)"
                  >Tạo yêu cầu mở lại kỳ</a
                >
              }
            </section>
          }
        </div>
      } @else {
        <vt-skeleton variant="detail" label="Đang tải khiếu nại" />
      }
    </div>

    <p-dialog
      header="Không thụ lý khiếu nại"
      [modal]="true"
      [visible]="rejectOpen()"
      (visibleChange)="rejectOpen.set($event)"
      closeAriaLabel="Đóng"
      [style]="{ width: 'min(560px, 95vw)' }"
    >
      <div class="field">
        <label for="kt-code">Lý do (bắt buộc)</label>
        <p-select
          inputId="kt-code"
          [options]="rejectCodes"
          optionLabel="label"
          optionValue="value"
          [ngModel]="rejectCode()"
          (ngModelChange)="rejectCode.set($event)"
          placeholder="Chọn lý do"
          appendTo="body"
        />
      </div>
      <div class="field">
        <label for="kt-reason">Giải thích (20–2.000 ký tự, gửi người khiếu nại)</label>
        <textarea
          pTextarea
          id="kt-reason"
          rows="3"
          maxlength="2000"
          [ngModel]="rejectReason()"
          (ngModelChange)="rejectReason.set($event)"
        ></textarea>
      </div>
      @if (dialogError(); as e) {
        <p-message severity="error">{{ e }}</p-message>
      }
      <ng-template #footer>
        <p-button
          label="Đóng"
          severity="contrast"
          [outlined]="true"
          (onClick)="rejectOpen.set(false)"
        />
        <p-button
          label="Không thụ lý"
          severity="danger"
          [loading]="busy() === 'reject'"
          [disabled]="!rejectCode() || rejectReason().trim().length < 20 || !!busy()"
          (onClick)="reject()"
        />
      </ng-template>
    </p-dialog>
  `,
  styleUrls: ['./complaints.css'],
  styles: `
    .grid {
      display: grid;
      grid-template-columns: minmax(240px, 1fr) minmax(0, 3fr);
      gap: 16px;
      align-items: start;
      margin-top: 12px;
    }
    @media (max-width: 1000px) {
      .grid {
        grid-template-columns: 1fr;
      }
    }
    .items {
      list-style: none;
      padding: 0;
      margin: 8px 0 0;
      display: grid;
      gap: 6px;
    }
    .items li {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 6px;
      padding: 6px 8px;
      display: flex;
      flex-direction: column;
      gap: 2px;
    }
    .items li.sel {
      border-color: #1e3a8a;
      background: #eff6ff;
    }
    .mt {
      margin-top: 12px;
    }
    .narrow {
      max-width: 280px;
    }
    .block {
      display: block;
    }
    label.plain {
      font-weight: 400;
      margin: 0;
    }
    caption.label {
      text-align: left;
    }
    p-dialog textarea,
    .gt {
      width: 100%;
    }
  `,
})
export class ComplaintHandlePage {
  private readonly api = inject(ComplaintApi);
  private readonly org = inject(OrgApi);
  private readonly theme = inject(ThemeService);
  private readonly stepUp = inject(OVERSIGHT_STEP_UP);
  protected readonly can = inject(OVERSIGHT_CAN);
  protected readonly me = inject(OVERSIGHT_USER_ID);

  /** Khiếu nại đang chọn từ tuyến `complaints/:id`. */
  readonly id = input<string>();

  protected readonly kindLabel = COMPLAINT_KIND_LABEL;
  protected readonly stageLabel = COMPLAINT_STAGE_LABEL;
  protected readonly resolutionLabel = COMPLAINT_RESOLUTION_LABEL;
  protected readonly views = [
    { value: 'mine', label: 'Được giao cho tôi' },
    { value: 'all', label: 'Trong phạm vi' },
  ];
  protected readonly outcomes = (
    Object.keys(COMPLAINT_RESOLUTION_LABEL) as ComplaintResolution[]
  ).map((value) => ({ value, label: COMPLAINT_RESOLUTION_LABEL[value] }));
  protected readonly rejectCodes = (
    Object.keys(COMPLAINT_REJECT_REASON_LABEL) as ComplaintRejectReasonCode[]
  ).map((value) => ({ value, label: COMPLAINT_REJECT_REASON_LABEL[value] }));

  protected readonly view = signal<'mine' | 'all'>('mine');
  /** Tải lại danh sách sau mỗi thao tác đổi trạng thái. */
  private readonly listTick = signal(0);
  protected readonly list = rxResource({
    params: () => ({ mine: this.view() === 'mine', t: this.listTick() }),
    stream: ({ params }) => this.api.list({ assignedToMe: params.mine }),
  });
  protected readonly complaint = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.get(params),
  });
  protected readonly current = linkedSignal<Complaint | undefined>(() =>
    this.complaint.hasValue() ? this.complaint.value() : undefined,
  );
  protected readonly records = rxResource({
    params: () => {
      const c = this.current();
      return c ? `${c.id}#${c.rowVersion}` : undefined;
    },
    stream: ({ params }) => this.api.responses(params.split('#')[0]),
  });
  /** Người, đơn vị trong phạm vi để chọn bên giải trình (gợi ý theo cấu phần chưa có API, suy luận). */
  protected readonly orgOptions = rxResource({
    params: () => (this.canAsk() ? true : undefined),
    stream: () =>
      forkJoin([this.org.persons(), this.org.units('unit'), this.org.units('children')]).pipe(
        map(([p, u, ch]) => ({ persons: p.items, units: [...u.items, ...ch.items] })),
      ),
  });

  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );
  protected readonly busy = signal<Busy>(null);
  protected readonly notice = signal<string | null>(null);
  protected readonly error = signal<string | null>(null);
  protected readonly dialogError = signal<string | null>(null);
  protected readonly concludeError = signal<string | null>(null);

  protected readonly asks = computed(() => {
    const recs = (this.records.hasValue() ? this.records.value() : undefined) ?? [];
    return recs
      .filter((r) => r.recordKind === 'YEU_CAU')
      .map((ask) => ({ ask, answer: recs.find((g) => g.requestId === ask.id) }));
  });
  protected readonly conclusionText = computed(
    () =>
      ((this.records.hasValue() ? this.records.value() : undefined) ?? [])
        .filter((r) => r.recordKind === 'KET_LUAN')
        .at(-1)?.content,
  );
  protected readonly canAsk = computed(() => {
    const s = this.current()?.status;
    return (s === 'DA_TIEP_NHAN' || s === 'DANG_GIAI_TRINH') && this.can('SCR-1202.tiep-nhan');
  });
  protected readonly canConclude = computed(() => {
    const s = this.current()?.status;
    return (
      (s === 'DA_TIEP_NHAN' || s === 'DANG_GIAI_TRINH' || s === 'CHO_KET_LUAN') &&
      this.can('SCR-1202.tiep-nhan')
    );
  });
  protected readonly needsReopen = computed(() => {
    const k = this.current()?.conclusion;
    return !!k && k.outcome !== 'GIU_NGUYEN' && !!k.periodLocked;
  });

  // ── yêu cầu giải trình, giải trình ──
  protected readonly parties = signal<string[]>([]);
  protected readonly askText = signal('');
  protected readonly askDue = signal('');
  protected readonly explainText = signal('');
  protected readonly partyOptions = computed<PartyOption[]>(() => {
    const o = this.orgOptions.hasValue() ? this.orgOptions.value() : undefined;
    if (!o) return [];
    // ui-text-ok: giá trị nội bộ của lựa chọn, màn hình chỉ hiện nhãn
    return [
      ...o.persons.map((p) => ({
        value: `NGUOI:${p.id}`,
        label: `${p.name} · ${p.title}, ${p.unitName}`,
      })),
      // ui-text-ok: giá trị nội bộ của lựa chọn
      ...o.units.map((u) => ({ value: `DON_VI:${u.id}`, label: `Đơn vị: ${u.name}` })),
    ];
  });

  // ── kết luận ──
  protected readonly outcome = linkedSignal<ComplaintResolution | null>(
    () => this.current()?.conclusion?.outcome ?? null,
  );
  protected readonly adj = linkedSignal<AdjRow[]>(() => {
    const c = this.current();
    const saved = c?.conclusion?.adjustments ?? [];
    return (c?.targets ?? []).map((t) => {
      const s = saved.find((a) => a.targetKey === t.key);
      const old = targetValue(t);
      return {
        key: t.key,
        label: t.label ?? t.key,
        on: !!s,
        oldValue: old,
        newValue: s ? String(s.newValue).replace('.', ',') : '',
        reason: s?.reason ?? '',
      };
    });
  });
  protected readonly responseText = linkedSignal(() => this.conclusionText() ?? '');
  protected readonly docRef = linkedSignal(() => this.current()?.conclusion?.responseDocRef ?? '');
  protected readonly note = signal('');
  protected readonly sim = signal<AppraisalSimulationResult | null>(null);
  protected readonly adjustments = computed<ComplaintAdjustment[]>(() =>
    this.adj()
      .filter((r) => r.on && r.newValue.trim())
      .map((r) => ({
        targetKey: r.key,
        oldValue: r.oldValue,
        newValue: this.parseNum(r.newValue),
        reason: r.reason.trim(),
      })),
  );

  // ── không thụ lý ──
  protected readonly rejectOpen = signal(false);
  protected readonly rejectCode = signal<ComplaintRejectReasonCode | null>(null);
  protected readonly rejectReason = signal('');

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
  protected num(v: number | string | null | undefined) {
    return fmtNum(v);
  }
  protected rating(r?: RatingLevel | null) {
    return r ? RATING_LEVEL_LABEL[r] : '—';
  }
  protected score(n: number) {
    return n.toLocaleString('vi-VN', { minimumFractionDigits: 1, maximumFractionDigits: 1 });
  }
  protected statusText(s: ComplaintStatus | null | undefined) {
    return s ? COMPLAINT_STATUS_LABEL[s] : 'Nháp';
  }
  protected severity(s: ComplaintStatus | null | undefined) {
    return complaintSeverity(s);
  }
  protected err(e: unknown) {
    return errorText(e);
  }
  /** Tên bên giải trình: hợp đồng chỉ có mã, nên tra theo danh mục tổ chức và người đã giải trình. */
  protected partyName(p?: ExplanationParty) {
    if (!p) return '—';
    if (p.id === this.me()) return 'Đồng chí';
    const o = this.orgOptions.hasValue() ? this.orgOptions.value() : undefined;
    const fromOrg =
      p.kind === 'NGUOI'
        ? o?.persons.find((x) => x.id === p.id)?.name
        : o?.units.find((x) => x.id === p.id)?.name;
    const fromRecords = ((this.records.hasValue() ? this.records.value() : undefined) ?? []).find(
      (r) => r.recordKind === 'GIAI_TRINH' && r.party?.id === p.id,
    )?.responder?.name;
    return fromOrg ?? fromRecords ?? (p.kind === 'NGUOI' ? 'Cán bộ liên quan' : 'Đơn vị liên quan');
  }
  protected reopenQuery(c: Complaint) {
    return {
      periodId: c.period.id,
      auditKind: 'PHAN_ANH',
      basisKind: 'KHIEU_NAI',
      basisCode: c.code,
      basisId: c.id,
      profileId: c.profileId,
    };
  }

  protected patchAdj(i: number, patch: Partial<AdjRow>) {
    this.adj.set(this.adj().map((r, j) => (j === i ? { ...r, ...patch } : r)));
    this.sim.set(null);
  }
  protected pickOutcome(o: ComplaintResolution) {
    this.outcome.set(o);
    this.sim.set(null);
    if (!this.responseText().trim()) this.responseText.set(this.draftText(o));
  }

  protected accept() {
    this.run(
      'accept',
      (c) => this.api.accept(c),
      (n) => `Đã tiếp nhận khiếu nại ${n.code}.`,
    );
  }
  protected openReject() {
    this.rejectCode.set(null);
    this.rejectReason.set('');
    this.dialogError.set(null);
    this.rejectOpen.set(true);
  }
  protected reject() {
    const c = this.current();
    const code = this.rejectCode();
    if (!c || !code) return;
    this.busy.set('reject');
    this.api.reject(c, { reasonCode: code, reason: this.rejectReason().trim() }).subscribe({
      next: (n) => {
        this.busy.set(null);
        this.rejectOpen.set(false);
        this.done(n, `Đã ghi không thụ lý khiếu nại ${n.code}; người khiếu nại được thông báo.`);
      },
      error: (e) => {
        this.busy.set(null);
        this.dialogError.set(errorText(e));
      },
    });
  }
  protected ask() {
    const parties = this.parties().map((v) => {
      const [kind, id] = v.split(':') as [ExplanationParty['kind'], string];
      return { kind, id };
    });
    this.run(
      'ask',
      (c) =>
        this.api.requestExplanations(c, {
          parties,
          content: this.askText().trim(),
          ...(this.askDue() ? { dueDate: this.askDue() } : {}),
        }),
      (n) => {
        this.parties.set([]);
        this.askText.set('');
        this.askDue.set('');
        return `Đã gửi yêu cầu giải trình cho ${parties.length} bên của khiếu nại ${n.code}.`;
      },
    );
  }
  protected explain(ask: ComplaintResponseRecord) {
    const c = this.current();
    if (!c) return;
    this.busy.set('explain');
    this.error.set(null);
    this.api
      .submitExplanation(c, { requestId: ask.id, content: this.explainText().trim() })
      .subscribe({
        next: () => {
          this.busy.set(null);
          this.explainText.set('');
          this.notice.set('Đã gửi giải trình; người xử lý được thông báo.');
          this.complaint.reload();
          this.listTick.update((t) => t + 1);
        },
        error: (e) => this.fail(e),
      });
  }
  protected simulate() {
    const c = this.current();
    if (!c) return;
    this.busy.set('simulate');
    this.error.set(null);
    this.api
      .simulate(c.profileId, {
        adjustments: this.adjustments().map((a) => ({
          component: componentOf(a.targetKey),
          oldValue: typeof a.oldValue === 'number' ? a.oldValue : undefined,
          newValue: Number(a.newValue),
          reason: a.reason,
        })),
      })
      .subscribe({
        next: (s) => {
          this.busy.set(null);
          this.sim.set(s);
        },
        error: (e) => this.fail(e),
      });
  }
  protected conclude() {
    const o = this.outcome();
    this.concludeError.set(null);
    if (!o) return this.concludeError.set('Chọn kết quả xử lý.');
    const adjustments = o === 'GIU_NGUYEN' ? [] : this.adjustments();
    if (o !== 'GIU_NGUYEN' && !adjustments.length)
      return this.concludeError.set('Kết luận chấp nhận cần ít nhất một điều chỉnh đề nghị.');
    if (adjustments.some((a) => !a.reason))
      return this.concludeError.set('Nêu lý do cho mỗi điều chỉnh.');
    if (!this.responseText().trim()) return this.concludeError.set('Nêu nội dung văn bản trả lời.');
    this.run(
      'conclude',
      (c) =>
        this.api.saveConclusion(c, {
          outcome: o,
          adjustments,
          responseText: this.responseText().trim(),
          responseDocRef: this.docRef().trim() || undefined,
          ...(c.status === 'DA_TIEP_NHAN' ? { noExplanationNote: this.note().trim() } : {}),
        }),
      (n) => `Đã ghi kết luận cho khiếu nại ${n.code}; chờ người có thẩm quyền ký văn bản trả lời.`,
    );
  }
  protected async respond() {
    const c = this.current();
    if (!c) return;
    this.error.set(null);
    this.notice.set(null);
    if (!(await this.stepUp(SIGN_REASON))) return;
    this.busy.set('respond');
    this.api.respond(c).subscribe({
      next: () => {
        this.busy.set(null);
        this.notice.set(
          `Đã ký số văn bản trả lời ${c.conclusion?.responseDocRef ?? ''} và gửi người khiếu nại.`,
        );
        this.complaint.reload();
        this.listTick.update((t) => t + 1);
      },
      error: (e) => this.fail(e),
    });
  }

  private run(
    kind: Busy,
    call: (c: Complaint) => Observable<Complaint>,
    text: (n: Complaint) => string,
  ) {
    const c = this.current();
    if (!c) return;
    this.busy.set(kind);
    this.error.set(null);
    this.notice.set(null);
    call(c).subscribe({
      next: (n) => {
        this.busy.set(null);
        this.done(n, text(n));
      },
      error: (e) => this.fail(e),
    });
  }
  private done(n: Complaint, text: string) {
    this.current.set(n);
    this.notice.set(text);
    this.listTick.update((t) => t + 1);
  }
  private fail(e: unknown) {
    this.busy.set(null);
    const p = toProblem(e);
    this.error.set(
      p.status === 412 || p.status === 409
        ? `${errorText(e)} Tải lại trang để xem trạng thái mới nhất.`
        : errorText(e),
    );
    if (p.status === 412 || p.status === 409) this.complaint.reload();
  }
  private parseNum(v: string): number | string {
    const n = Number(v.trim().replace(',', '.'));
    return Number.isFinite(n) ? n : v.trim();
  }
  /** Câu mở đầu văn bản trả lời theo kết quả (mẫu TL-KN-01 thật do SVC-11 sinh; suy luận). */
  private draftText(o: ComplaintResolution): string {
    const c = this.current();
    const head = `Trả lời khiếu nại ${c?.code ?? ''} của đồng chí ${c?.complainant?.name ?? ''} về ${c?.subjectLine?.toLowerCase() ?? 'kết quả đánh giá'}.`;
    if (o === 'GIU_NGUYEN') return `${head} Sau khi xem xét, giữ nguyên kết quả đánh giá.`;
    return `${head} ${o === 'CHAP_NHAN' ? 'Chấp nhận toàn bộ' : 'Chấp nhận một phần'} nội dung khiếu nại; điều chỉnh như sau: `;
  }
}
