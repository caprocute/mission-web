/*
 * SCR-0601 "Chi tiết nhiệm vụ" (QT-06, TL-02 Hình 8.9) — đối chiếu đặc tả:
 * Đầu trang: tên, mã · nhóm · KR · danh mục · ngày giao, người giao; trạng thái; nút Báo điểm nghẽn, Đề nghị gia hạn,
 *   Nộp sản phẩm (SCR-0603), Cập nhật tiến độ. Bốn thẻ: tiến độ, hạn (ngày làm việc còn lại, hạn tính điểm), sản phẩm
 *   xác nhận, dự báo trễ hạn. MSG-06-03 khi quá hạn, MSG-06-02 khi dự báo Cao, MSG-06-04/MSG-06-10 sau khi gửi.
 * Thẻ: Tổng quan, Tiến độ, Điểm nghẽn, Sản phẩm (sang SCR-0603), Đề nghị (rút lại khi Chờ duyệt), Lịch sử trạng thái.
 * Bên phải: thông tin giao việc, đề nghị đang chờ, vòng đời.
 * API: GET /tasks/{id}; POST /tasks/{id}/progress | /accept | /blockers | /change-requests | /change-requests/{cid}/withdraw.
 * Phần của người giao trên cùng màn hình (TL-02 QT-06 bước 4, 6; QT-05 bước 1c): xử lý điểm nghẽn
 *   (PATCH /tasks/{id}/blockers/{bid}, POST …/escalate), duyệt đề nghị (POST …/change-requests/{cid}/decision),
 *   duyệt nhiệm vụ cán bộ đăng ký (POST /tasks/{id}/registration-decision); nút chỉ hiện khi có quyền theo ma
 *   trận, ràng buộc theo đối tượng (người giao, người duyệt khác người gửi) do máy chủ kiểm.
 */
import { Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  BLOCKER_CAUSE_LABEL,
  BLOCKER_STATUS_LABEL,
  CAUSE_KIND_LABEL,
  CHANGE_REQUEST_STATUS_LABEL,
  CHANGE_REQUEST_TYPE_LABEL,
  ChangeRequest,
  IMPACT_LABEL,
  TASK_GROUP_LABEL,
  TASK_STATUS_LABEL,
  TaskApi,
  TaskBlocker,
  TaskDetail,
  toProblem,
} from '@vt/api-client';
import { ThemeService } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { TabsModule } from 'primeng/tabs';
import { TagModule } from 'primeng/tag';
import { TASK_CAN } from '../task-permissions';
import { TagSeverity, formatDate, statusSeverity } from '../task-ui';
import { BlockerHandleDialog, BlockerHandleMode } from './task-detail/blocker-handle.dialog';
import { BlockerReportDialog } from './task-detail/blocker-report.dialog';
import { ChangeRequestDialog } from './task-detail/change-request.dialog';
import { ManagerDecisionDialog } from './task-detail/manager-decision.dialog';
import { ProgressUpdateDialog } from './task-detail/progress-update.dialog';

const ACTIVE = new Set(['DA_GIAO', 'DANG_THUC_HIEN']);
const RISK_LABEL = { THAP: 'Thấp', VUA: 'Vừa', CAO: 'Cao' } as const;
const RISK_SEVERITY: Record<'THAP' | 'VUA' | 'CAO', TagSeverity> = {
  THAP: 'success',
  VUA: 'warn',
  CAO: 'danger',
};
const CHANNEL_LABEL = {
  WEB: 'Trang web',
  MOBILE: 'Ứng dụng di động',
  TICH_HOP: 'Hệ thống liên thông',
} as const;

/** Hệ số tiến độ C = 1 − 0,05 × số ngày làm việc chậm, không âm (BR-06-01). */
export function coefficient(lateWorkingDays: number): number {
  return Math.max(0, Math.round((1 - 0.05 * Math.max(0, lateWorkingDays)) * 100) / 100);
}

@Component({
  selector: 'vt-task-detail-page',
  imports: [
    RouterLink,
    ButtonModule,
    MessageModule,
    TabsModule,
    TagModule,
    BlockerHandleDialog,
    BlockerReportDialog,
    ChangeRequestDialog,
    ManagerDecisionDialog,
    ProgressUpdateDialog,
  ],
  template: `
    <a routerLink=".." class="back">← Việc của tôi</a>
    @if (task.error()) {
      <p-message severity="error" styleClass="msg">
        <span>{{ loadError() }}</span>
        <button type="button" class="link" (click)="task.reload()">Thử lại</button>
      </p-message>
    } @else if (task.hasValue() && task.value(); as t) {
      <header class="head">
        <div class="title">
          <h1>{{ t.title }}</h1>
          <p class="muted">
            {{ t.code }} · {{ group(t) }}
            @if (t.keyResultTitle) {
              · KR: {{ t.keyResultTitle }}
            }
            @if (t.catalogItemCode) {
              · Danh mục {{ t.catalogItemCode }}
            }
            @if (t.assignedAt) {
              · Giao ngày {{ date(t.assignedAt) }}
            }
            · Người giao: {{ t.assignerName }}
          </p>
        </div>
        <p-tag [value]="statusLabel(t)" [severity]="sev(t)" />
      </header>

      <div class="actions">
        @if (t.status === 'DA_GIAO' && t.role === 'CHU_TRI' && can('SCR-0601.tien-do')) {
          <p-button
            label="Nhận việc"
            icon="pi pi-check"
            [loading]="accepting()"
            (onClick)="accept(t)"
          />
        }
        @if (active(t)) {
          @if (can('SCR-0601.bao-nghen') && !simple()) {
            <p-button
              label="Báo điểm nghẽn"
              icon="pi pi-flag"
              severity="contrast"
              [outlined]="true"
              (onClick)="blockerOpen.set(true)"
            />
          }
          @if (t.role === 'CHU_TRI' && can('SCR-0602.gui') && !simple()) {
            <p-button
              label="Đề nghị gia hạn"
              icon="pi pi-calendar-plus"
              severity="contrast"
              [outlined]="true"
              (onClick)="changeOpen.set(true)"
            />
          }
          @if (can('SCR-0601.nop-sp') && (!simple() || needsProduct(t))) {
            <p-button
              label="Nộp sản phẩm"
              icon="pi pi-upload"
              severity="contrast"
              [outlined]="true"
              routerLink="/evidence"
              [queryParams]="{ task: t.id }"
            />
          }
          @if (can('SCR-0601.tien-do')) {
            <p-button
              [label]="t.role === 'CHU_TRI' ? 'Cập nhật tiến độ' : 'Ghi nhật ký'"
              icon="pi pi-pencil"
              (onClick)="progressOpen.set(true)"
            />
          }
        }
      </div>

      @if (registrationPending(t) && can('SCR-0501.giao')) {
        <section class="box decide" aria-labelledby="dk-h">
          <h2 id="dk-h" class="h">Đăng ký nhiệm vụ chờ duyệt</h2>
          <p>
            Cán bộ đăng ký thực hiện {{ t.plannedQuantity }} {{ t.unit }}, hạn đề xuất
            {{ date(t.dueDate) }}. Duyệt để giao việc, hoặc từ chối kèm lý do.
          </p>
          <div class="actions">
            <p-button label="Duyệt đăng ký" icon="pi pi-check" (onClick)="decide(null, true)" />
            <p-button
              label="Từ chối đăng ký"
              severity="danger"
              [outlined]="true"
              (onClick)="decide(null, false)"
            />
          </div>
        </section>
      }

      @if (notice(); as n) {
        <p-message severity="success" [text]="n" styleClass="msg" role="status" />
      }
      @if (errorText(); as e) {
        <p-message severity="error" [text]="e" styleClass="msg" role="alert" />
      }
      @if (t.overdue && active(t)) {
        <p-message
          severity="error"
          styleClass="msg"
          [text]="
            'Nhiệm vụ đã quá hạn ' +
            late(t) +
            ' ngày làm việc; điểm tiến độ dự kiến còn ' +
            cText(t) +
            '.'
          "
        />
      } @else if (t.delayRisk?.level === 'CAO' && active(t)) {
        <p class="risk-msg" role="status">
          <i class="pi pi-exclamation-triangle" aria-hidden="true"></i> Nhiệm vụ có nguy cơ trễ hạn
          cao: cần khoảng {{ t.delayRisk!.neededWorkingDays }} ngày làm việc, còn
          {{ t.workingDaysLeft }} ngày.
        </p>
      }

      <section class="kpis" aria-label="Chỉ số chính">
        <div class="kpi">
          <span class="k">Tiến độ</span>
          <strong class="v">{{ t.progress }}%</strong>
          <div class="bar" aria-hidden="true"><span [style.width.%]="t.progress"></span></div>
          <span class="muted small">{{ t.doneQuantity }}/{{ t.plannedQuantity }} {{ t.unit }}</span>
        </div>
        <div class="kpi">
          <span class="k">Hạn hoàn thành</span>
          <strong class="v">{{ date(t.dueDate) }}</strong>
          <span class="small" [class.danger]="t.overdue">{{ dueText(t) }}</span>
          <span class="muted small">Hạn tính điểm {{ date(t.scoreDueDate) }}</span>
        </div>
        <div class="kpi">
          <span class="k">Sản phẩm xác nhận</span>
          <strong class="v">{{ t.products.confirmed }}/{{ t.products.required }}</strong>
          <span class="muted small">{{ t.products.pendingReview }} chờ nghiệm thu</span>
        </div>
        <div class="kpi">
          <span class="k">Dự báo trễ hạn</span>
          @if (t.delayRisk; as r) {
            <p-tag [value]="risk(r.level)" [severity]="riskSev(r.level)" styleClass="v-tag" />
            <span class="muted small">{{ r.explanation }}</span>
          } @else {
            <strong class="v">—</strong><span class="muted small">Chưa có dự báo</span>
          }
        </div>
      </section>

      <div class="cols">
        <p-tabs [value]="tab()" (valueChange)="tab.set($any($event))" scrollable>
          <p-tablist>
            <p-tab value="tong-quan">Tổng quan</p-tab>
            <p-tab value="tien-do">Tiến độ ({{ t.progressLog.length }})</p-tab>
            <p-tab value="diem-nghen">Điểm nghẽn ({{ t.blockers.length }})</p-tab>
            @if (!simple()) {
              <p-tab value="san-pham">Sản phẩm, minh chứng</p-tab>
              <p-tab value="de-nghi">Đề nghị ({{ t.changeRequests.length }})</p-tab>
              <p-tab value="lich-su">Lịch sử</p-tab>
            }
          </p-tablist>
          <p-tabpanels>
            <p-tabpanel value="tong-quan">
              <h2 class="h">Nội dung</h2>
              <p class="pre">{{ t.description }}</p>
              @if (t.sourceDoc) {
                <p class="muted">Căn cứ: {{ t.sourceDoc.number }} · {{ t.sourceDoc.title }}</p>
              }
              <h2 class="h">Người thực hiện</h2>
              <ul class="plain">
                <li><strong>Chủ trì:</strong> {{ lead(t) }}</li>
                @for (c of t.coAssignees; track c.personId) {
                  @if (c.role === 'PHOI_HOP') {
                    <li><strong>Phối hợp:</strong> {{ c.name }}</li>
                  }
                }
              </ul>
            </p-tabpanel>
            <p-tabpanel value="tien-do">
              <ol class="timeline">
                @for (l of t.progressLog; track $index) {
                  <li>
                    <div>
                      <strong>{{
                        l.fromPercent !== undefined && l.fromPercent !== l.percent
                          ? l.fromPercent + '% → ' + l.percent + '%'
                          : l.byRole === 'PHOI_HOP'
                            ? 'Nhật ký phối hợp'
                            : l.percent + '%'
                      }}</strong>
                      <span class="muted">
                        · {{ l.by }} · {{ dateTime(l.at) }}
                        @if (l.channel) {
                          · {{ channel(l.channel) }}
                        }
                      </span>
                    </div>
                    <div>{{ l.note }}</div>
                  </li>
                } @empty {
                  <li class="muted">Chưa có cập nhật tiến độ.</li>
                }
              </ol>
            </p-tabpanel>
            <p-tabpanel value="diem-nghen">
              @for (b of t.blockers; track b.id) {
                <article class="row-card">
                  <div class="row-head">
                    <strong>{{ b.code }}</strong>
                    <p-tag [value]="blockerStatus(b)" [severity]="blockerSev(b)" />
                  </div>
                  <p>{{ b.content }}</p>
                  <p class="muted small">
                    {{ cause(b) }} · {{ kind(b.causeClaimed) }} theo người báo · ảnh hưởng
                    {{ impact(b) }}
                    @if (b.handlerName) {
                      · xử lý: {{ b.handlerName }}
                    }
                    @if (b.resolveBy) {
                      · cần xử lý trước {{ date(b.resolveBy) }}
                    }
                    · báo {{ date(b.createdAt) }}
                  </p>
                  @if (b.status !== 'DONG' && can('SCR-0601.xu-ly-nghen')) {
                    <div class="row-actions" role="group" [attr.aria-label]="'Xử lý ' + b.code">
                      <p-button
                        label="Xử lý"
                        severity="contrast"
                        [outlined]="true"
                        size="small"
                        (onClick)="handle(b, 'xu-ly')"
                      />
                      @if (b.status === 'MO' || b.status === 'DANG_XU_LY') {
                        <p-button
                          label="Báo cáo cấp trên"
                          severity="contrast"
                          [outlined]="true"
                          size="small"
                          (onClick)="handle(b, 'cap-tren')"
                        />
                      }
                    </div>
                  }
                </article>
              } @empty {
                <p class="muted">Không có điểm nghẽn.</p>
              }
            </p-tabpanel>
            @if (!simple()) {
              <p-tabpanel value="san-pham">
                <p>
                  Đã xác nhận {{ t.products.confirmed }}/{{ t.products.required }} sản phẩm,
                  {{ t.products.pendingReview }} đang chờ nghiệm thu.
                </p>
                <p class="muted">
                  Danh sách sản phẩm, minh chứng và việc nộp mới nằm ở màn hình Nộp sản phẩm, minh
                  chứng.
                </p>
                @if (can('SCR-0601.nop-sp')) {
                  <a [routerLink]="'/evidence'" [queryParams]="{ task: t.id }"
                    >Mở màn hình nộp sản phẩm</a
                  >
                }
              </p-tabpanel>
              <p-tabpanel value="de-nghi">
                @for (r of t.changeRequests; track r.id) {
                  <article class="row-card">
                    <div class="row-head">
                      <strong>{{ crType(r) }}</strong>
                      <p-tag [value]="crStatus(r)" [severity]="crSev(r)" />
                      @if (r.escalated) {
                        <span class="muted small">chuyển cấp trên duyệt</span>
                      }
                    </div>
                    <p>{{ crDetail(r) }}</p>
                    <p class="muted small">{{ r.reason }}</p>
                    <p class="muted small">
                      Gửi {{ dateTime(r.submittedAt) }} · người duyệt {{ r.approverName }} · nguyên
                      nhân theo người gửi: {{ kind(r.causeClaimed) }}
                      @if (r.causeDecided) {
                        · được xác định: {{ kind(r.causeDecided) }}
                      }
                    </p>
                    @if (r.decisionNote) {
                      <p class="small">Ý kiến người duyệt: {{ r.decisionNote }}</p>
                    }
                    @if (r.status === 'CHO_DUYET' && can('SCR-0601.duyet-de-nghi')) {
                      <div class="row-actions" role="group" aria-label="Quyết định đề nghị">
                        <p-button label="Duyệt" size="small" (onClick)="decide(r, true)" />
                        <p-button
                          label="Từ chối"
                          severity="danger"
                          [outlined]="true"
                          size="small"
                          (onClick)="decide(r, false)"
                        />
                      </div>
                    }
                    @if (r.status === 'CHO_DUYET' && can('SCR-0602.rut')) {
                      <p-button
                        label="Rút lại"
                        severity="contrast"
                        [outlined]="true"
                        size="small"
                        [loading]="withdrawing() === r.id"
                        (onClick)="withdraw(t, r)"
                      />
                    }
                  </article>
                } @empty {
                  <p class="muted">Chưa có đề nghị gia hạn, điều chỉnh.</p>
                }
              </p-tabpanel>
              <p-tabpanel value="lich-su">
                <ol class="timeline">
                  @for (s of t.stateLog; track $index) {
                    <li>
                      <div>
                        <strong
                          >{{ s.from ? status(s.from) + ' → ' : '' }}{{ status(s.to) }}</strong
                        >
                        <span class="muted">· {{ s.actorName }} · {{ dateTime(s.at) }}</span>
                      </div>
                      @if (s.reason) {
                        <div>{{ s.reason }}</div>
                      }
                    </li>
                  } @empty {
                    <li class="muted">Chưa có lịch sử.</li>
                  }
                </ol>
              </p-tabpanel>
            }
          </p-tabpanels>
        </p-tabs>

        <aside class="side">
          <section class="box">
            <h2 class="h">Thông tin</h2>
            <dl>
              @if (t.sourceRef) {
                <dt>Văn bản nguồn</dt>
                <dd>{{ t.sourceRef }}</dd>
              }
              <dt>Đơn vị</dt>
              <dd>{{ t.unitName }}</dd>
              <dt>Người giao</dt>
              <dd>{{ t.assignerName }}</dd>
              <dt>Người nghiệm thu</dt>
              <dd>{{ t.acceptorName }}</dd>
              <dt>Vai trò của đồng chí</dt>
              <dd>{{ t.role === 'CHU_TRI' ? 'Chủ trì' : 'Phối hợp' }}</dd>
              @if (t.difficulty) {
                <dt>Độ khó</dt>
                <dd>{{ t.difficulty }}/5</dd>
              }
              @if (t.standardScore) {
                <dt>Điểm chuẩn</dt>
                <dd>{{ t.standardScore }}</dd>
              }
              <dt>Gia hạn trong quý</dt>
              <dd>{{ t.extensionsUsed }}/{{ t.extensionsMax }} lần</dd>
            </dl>
          </section>
          @if (pending(t); as p) {
            <section class="box">
              <h2 class="h">Đề nghị đang chờ</h2>
              <p>
                <strong>{{ crType(p) }}</strong
                >: {{ crDetail(p) }}
              </p>
              <p class="muted small">Chờ đồng chí {{ p.approverName }} duyệt.</p>
              @if (can('SCR-0601.duyet-de-nghi')) {
                <div class="row-actions" role="group" aria-label="Quyết định đề nghị đang chờ">
                  <p-button label="Duyệt" size="small" (onClick)="decide(p, true)" />
                  <p-button
                    label="Từ chối"
                    severity="danger"
                    [outlined]="true"
                    size="small"
                    (onClick)="decide(p, false)"
                  />
                </div>
              }
            </section>
          }
          @if (!simple()) {
            <section class="box">
              <h2 class="h">Tiến trình xử lý</h2>
              <ol class="steps">
                @for (s of lifecycle; track s) {
                  <li
                    [class.done]="lifeIndex(t) > $index"
                    [class.now]="lifeIndex(t) === $index"
                    [attr.aria-current]="lifeIndex(t) === $index ? 'step' : null"
                  >
                    {{ status(s) }}
                  </li>
                }
              </ol>
            </section>
          }
        </aside>
      </div>

      <vt-progress-update-dialog
        [task]="t"
        [(visible)]="progressOpen"
        (saved)="onSaved($event, 'Đã lưu tiến độ.')"
        (reloadRequested)="reload()"
      />
      <vt-blocker-report-dialog
        [task]="t"
        [(visible)]="blockerOpen"
        (saved)="
          onSaved($event, 'Đã ghi điểm nghẽn và thông báo cho đồng chí ' + t.assignerName + '.')
        "
      />
      <vt-change-request-dialog
        [task]="t"
        [(visible)]="changeOpen"
        (saved)="onSaved($event.task, 'Đã gửi đề nghị tới đồng chí ' + $event.approverName + '.')"
      />
      <vt-blocker-handle-dialog
        [task]="t"
        [blocker]="handling()"
        [mode]="handleMode()"
        [(visible)]="handleOpen"
        (saved)="onBlockerSaved($event.toName)"
      />
      <vt-manager-decision-dialog
        [task]="t"
        [request]="deciding()"
        [approve]="decideApprove()"
        [(visible)]="decideOpen"
        (saved)="onDecided($event)"
      />
    } @else {
      <p class="muted" aria-busy="true">Đang tải nhiệm vụ…</p>
    }
  `,
  styles: `
    .back {
      text-decoration: none;
      color: color-mix(in srgb, var(--vt-color-primary) 82%, black);
    }
    .head {
      display: flex;
      align-items: flex-start;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
    }
    h1 {
      font-size: 1.4rem;
      margin: 12px 0 4px;
    }
    .h {
      font-size: 1.05rem;
      margin: 16px 0 8px;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.9em;
    }
    .danger {
      color: #b91c1c;
      font-weight: 500;
    }
    .actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 12px 0;
    }
    :host ::ng-deep .msg {
      display: block;
      margin: 8px 0;
    }
    .risk-msg {
      margin: 8px 0;
      padding: 10px 14px;
      border-radius: 6px;
      background: #fffbeb;
      border: 1px solid #fcd34d;
      color: #78350f;
    }
    .link {
      background: none;
      border: 0;
      color: inherit;
      text-decoration: underline;
      cursor: pointer;
      margin-left: 8px;
      font: inherit;
    }
    .kpis {
      display: grid;
      grid-template-columns: repeat(4, minmax(0, 1fr));
      gap: 12px;
      margin: 12px 0;
    }
    @media (max-width: 900px) {
      .kpis {
        grid-template-columns: repeat(2, minmax(0, 1fr));
      }
    }
    .kpi {
      display: flex;
      flex-direction: column;
      gap: 4px;
      padding: 12px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
    }
    .k {
      color: #4b5563;
    }
    .v {
      font-size: 1.5rem;
    }
    .bar {
      height: 8px;
      border-radius: 4px;
      background: #e5e7eb;
      overflow: hidden;
    }
    .bar span {
      display: block;
      height: 100%;
      background: var(--vt-color-primary);
    }
    :host ::ng-deep .v-tag {
      align-self: flex-start;
      font-size: 1rem;
    }
    .cols {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
      gap: 16px;
    }
    @media (max-width: 900px) {
      .cols {
        grid-template-columns: 1fr;
      }
    }
    .side {
      display: flex;
      flex-direction: column;
      gap: 12px;
    }
    .box {
      padding: 12px 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
    }
    .box .h {
      margin-top: 0;
    }
    dl {
      display: grid;
      grid-template-columns: auto 1fr;
      gap: 6px 12px;
      margin: 0;
    }
    dt {
      color: #4b5563;
    }
    dd {
      margin: 0;
    }
    .pre {
      white-space: pre-line;
    }
    .plain {
      list-style: none;
      padding: 0;
      margin: 0;
      line-height: 1.8;
    }
    .timeline {
      list-style: none;
      padding: 0;
      margin: 0;
    }
    .timeline li {
      padding: 8px 0 8px 14px;
      border-left: 3px solid var(--p-content-border-color, #e5e7eb);
      margin-bottom: 4px;
    }
    .row-card {
      padding: 12px 0;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
    }
    .row-card p {
      margin: 6px 0;
    }
    .row-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 6px;
    }
    .decide {
      margin: 8px 0;
    }
    .decide .actions {
      margin: 8px 0 0;
    }
    .row-head {
      display: flex;
      align-items: center;
      gap: 8px;
      flex-wrap: wrap;
    }
    .steps {
      list-style: none;
      padding: 0;
      margin: 0;
    }
    .steps li {
      padding: 4px 0 4px 22px;
      position: relative;
      color: #4b5563;
    }
    .steps li::before {
      content: '';
      position: absolute;
      left: 4px;
      top: 50%;
      width: 10px;
      height: 10px;
      margin-top: -5px;
      border-radius: 50%;
      border: 2px solid #9ca3af;
    }
    .steps li.done {
      color: inherit;
    }
    .steps li.done::before {
      background: #15803d;
      border-color: #15803d;
    }
    .steps li.now {
      color: inherit;
      font-weight: 600;
    }
    .steps li.now::before {
      border-color: #1d4ed8;
      background: #1d4ed8;
    }
  `,
})
export class TaskDetailPage {
  readonly id = input.required<string>();
  private readonly api = inject(TaskApi);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(TASK_CAN);

  protected readonly task = rxResource({
    params: () => this.id(),
    stream: ({ params }) => this.api.detail(params),
  });
  protected readonly loadError = computed(() => {
    const p = toProblem(this.task.error());
    return p.status === 404
      ? 'Không tìm thấy nhiệm vụ.'
      : p.status === 403
        ? 'Đồng chí không có quyền xem nhiệm vụ này.'
        : 'Không tải được nhiệm vụ.';
  });
  /** Chế độ người cao tuổi rút gọn bước: chỉ còn cập nhật tiến độ và ba thẻ chính (NFR-11). */
  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );
  protected readonly tab = signal('tong-quan');
  protected readonly notice = signal<string | null>(null);
  protected readonly progressOpen = signal(false);
  protected readonly blockerOpen = signal(false);
  protected readonly changeOpen = signal(false);
  protected readonly accepting = signal(false);
  protected readonly handleOpen = signal(false);
  protected readonly handling = signal<TaskBlocker | null>(null);
  protected readonly handleMode = signal<BlockerHandleMode>('xu-ly');
  protected readonly decideOpen = signal(false);
  protected readonly deciding = signal<ChangeRequest | null>(null);
  protected readonly decideApprove = signal(true);
  protected readonly withdrawing = signal<string | null>(null);

  protected readonly lifecycle = [
    'DA_GIAO',
    'DANG_THUC_HIEN',
    'CHO_NGHIEM_THU',
    'HOAN_THANH',
  ] as const;

  protected active(t: TaskDetail) {
    return ACTIVE.has(t.status);
  }
  protected statusLabel(t: TaskDetail) {
    return TASK_STATUS_LABEL[t.status] + (t.overdue && this.active(t) ? ' · quá hạn' : '');
  }
  protected sev(t: TaskDetail) {
    return statusSeverity(t.status, t.overdue);
  }
  protected status(s: keyof typeof TASK_STATUS_LABEL) {
    return TASK_STATUS_LABEL[s];
  }
  protected group(t: TaskDetail) {
    return TASK_GROUP_LABEL[t.taskGroup];
  }
  protected date(d: string) {
    return formatDate(d, true);
  }
  protected dateTime(d: string) {
    return `${formatDate(d, true)} ${d.length > 10 ? d.slice(11, 16) : ''}`.trim();
  }
  protected late(t: TaskDetail) {
    return Math.max(1, -t.workingDaysLeft);
  }
  protected cText(t: TaskDetail) {
    return coefficient(this.late(t)).toFixed(2).replace('.', ',');
  }
  protected dueText(t: TaskDetail) {
    if (!this.active(t)) return TASK_STATUS_LABEL[t.status];
    if (t.overdue) return `Quá hạn ${this.late(t)} ngày làm việc`;
    return t.workingDaysLeft <= 0 ? 'Hạn hôm nay' : `Còn ${t.workingDaysLeft} ngày làm việc`;
  }
  protected risk(l: 'THAP' | 'VUA' | 'CAO') {
    return RISK_LABEL[l];
  }
  protected riskSev(l: 'THAP' | 'VUA' | 'CAO') {
    return RISK_SEVERITY[l];
  }
  protected channel(c: keyof typeof CHANNEL_LABEL) {
    return CHANNEL_LABEL[c];
  }
  protected lead(t: TaskDetail) {
    return (
      t.coAssignees.find((c) => c.role === 'CHU_TRI')?.name ??
      (t.role === 'CHU_TRI' ? 'Đồng chí' : '—')
    );
  }
  protected kind(k: keyof typeof CAUSE_KIND_LABEL) {
    return CAUSE_KIND_LABEL[k];
  }
  protected cause(b: TaskBlocker) {
    return BLOCKER_CAUSE_LABEL[b.causeCategory];
  }
  protected impact(b: TaskBlocker) {
    return IMPACT_LABEL[b.impactLevel].toLowerCase();
  }
  protected blockerStatus(b: TaskBlocker) {
    return BLOCKER_STATUS_LABEL[b.status];
  }
  protected blockerSev(b: TaskBlocker): TagSeverity {
    return b.status === 'MO' ? 'danger' : b.status === 'DANG_XU_LY' ? 'warn' : 'success';
  }
  protected crType(r: ChangeRequest) {
    return CHANGE_REQUEST_TYPE_LABEL[r.type];
  }
  protected crStatus(r: ChangeRequest) {
    return CHANGE_REQUEST_STATUS_LABEL[r.status];
  }
  protected crSev(r: ChangeRequest): TagSeverity {
    return r.status === 'CHO_DUYET'
      ? 'warn'
      : r.status === 'DA_DUYET'
        ? 'success'
        : r.status === 'TU_CHOI'
          ? 'danger'
          : 'secondary';
  }
  protected crDetail(r: ChangeRequest) {
    switch (r.type) {
      case 'GIA_HAN':
        return r.newDueDate ? `Hạn mới ${formatDate(r.newDueDate, true)}` : 'Gia hạn';
      case 'DOI_SO_LUONG':
        return `Số lượng mới ${r.newQuantity ?? ''}`;
      case 'DOI_SAN_PHAM':
        return `Sản phẩm mới: ${r.newProduct ?? ''}`;
      default:
        return CHANGE_REQUEST_TYPE_LABEL[r.type];
    }
  }
  protected pending(t: TaskDetail) {
    return t.changeRequests.find((r) => r.status === 'CHO_DUYET') ?? null;
  }
  /** Nhiệm vụ cán bộ đăng ký đang chờ người quản lý duyệt (QT-05 bước 1c). */
  protected registrationPending(t: TaskDetail) {
    return t.status === 'NHAP' && t.source === 'DANG_KY';
  }
  /** Còn sản phẩm chưa nộp: chế độ cao tuổi vẫn giữ lối vào trang nộp (NFR-11). */
  protected needsProduct(t: TaskDetail) {
    return t.products.confirmed + t.products.pendingReview < t.products.required;
  }
  protected lifeIndex(t: TaskDetail) {
    return this.lifecycle.indexOf(t.status as (typeof this.lifecycle)[number]);
  }

  protected onSaved(u: TaskDetail, message: string): void {
    this.task.set(u);
    this.errorText.set(null);
    this.notice.set(message);
  }
  protected reload(): void {
    this.progressOpen.set(false);
    this.notice.set(null);
    this.task.reload();
  }

  protected handle(b: TaskBlocker, mode: BlockerHandleMode): void {
    this.handling.set(b);
    this.handleMode.set(mode);
    this.handleOpen.set(true);
  }
  protected onBlockerSaved(toName: string | null): void {
    this.errorText.set(null);
    this.notice.set(
      this.handleMode() === 'cap-tren'
        ? `Đã báo cáo điểm nghẽn lên đồng chí ${toName ?? ''}.`.replace(' .', '.')
        : 'Đã lưu kết quả xử lý điểm nghẽn.',
    );
    // điểm nghẽn mới đổi cả phiên bản nhiệm vụ: tải lại để các thao tác sau gửi đúng If-Match
    this.task.reload();
  }
  protected decide(r: ChangeRequest | null, approve: boolean): void {
    this.deciding.set(r);
    this.decideApprove.set(approve);
    this.decideOpen.set(true);
  }
  protected onDecided(u: TaskDetail): void {
    const r = this.deciding();
    const ok = this.decideApprove();
    this.onSaved(
      u,
      r
        ? ok
          ? 'Đã duyệt đề nghị; người thực hiện được thông báo.'
          : 'Đã từ chối đề nghị; người thực hiện được thông báo.'
        : ok
          ? 'Đã duyệt đăng ký và giao nhiệm vụ.'
          : 'Đã từ chối đăng ký nhiệm vụ.',
    );
  }

  protected accept(t: TaskDetail): void {
    this.accepting.set(true);
    this.api.accept(t).subscribe({
      next: (u) => {
        this.accepting.set(false);
        this.onSaved(u, 'Đã nhận việc.');
      },
      error: (e) => {
        this.accepting.set(false);
        this.actionError(e);
      },
    });
  }
  protected withdraw(t: TaskDetail, r: ChangeRequest): void {
    this.withdrawing.set(r.id);
    this.api.withdrawChangeRequest(t, r.id).subscribe({
      next: (u) => {
        this.withdrawing.set(null);
        this.onSaved(u, 'Đã rút lại đề nghị.');
      },
      error: (e) => {
        this.withdrawing.set(null);
        this.actionError(e);
      },
    });
  }
  protected readonly errorText = signal<string | null>(null);
  private actionError(e: unknown): void {
    this.notice.set(null);
    this.errorText.set(toProblem(e).title);
  }
}
