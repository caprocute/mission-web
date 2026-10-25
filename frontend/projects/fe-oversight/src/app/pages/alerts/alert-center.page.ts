/*
 * SCR-1401 "Trung tâm cảnh báo" (QT-14 bước 4–9; TL-02 Hình 11.11) — đối chiếu đặc tả:
 * Tác nhân AC-07 (`appraiser`), AC-10 (`inspector`); web máy tính. Đường vào: menu "Giám sát › Cảnh báo",
 *   thông báo cảnh báo mức Cao, SCR-1601. Máy chủ ẩn cảnh báo mà người xem là đối tượng (BR-14-03).
 * Trường: 1 lọc dấu hiệu, mức; 2 bảng cảnh báo (mức, dấu hiệu, đối tượng, kỳ, trạng thái; sắp theo mức rồi
 *   thời gian; phân trang con trỏ); 3 phân bố 90 ngày; 4 chuỗi truy vết (danh sách lồng); 5 bảng nguồn
 *   (người nộp, nhiệm vụ, thời điểm); 6 tác động tự động; 7 ghi chú xử lý 20–2.000 ký tự.
 * Vùng: 1 danh sách bên trái; 2 đầu chi tiết và chuỗi truy vết; 3 tác động, ghi chú, nút xử lý; 4 ghi chú
 *   nguyên tắc và thẻ phân bố.
 * Chức năng: chọn cảnh báo (GET /oversight/alerts/{id}, `MOI` sang `DANG_XEM`); Xác nhận hợp lệ, Giao kiểm
 *   tra (POST …/actions, `DANG_XEM`); Kết luận (`inspector`, `DA_GIAO_KIEM_TRA`; có vi phạm thì mở SCR-1301);
 *   Ngưỡng phát hiện (`policy_author`, trang riêng); Mở sổ cái (SCR-1302 `?entryId=`).
 * Quy tắc, thông báo: BR-14-02, BR-14-03, BR-14-04, BR-14-05, BR-14-08; MSG-14-02, MSG-14-03, MSG-14-04.
 * Quyền (wiki/15a): SCR-1401.xem, xu-ly, ket-luan, nguong. Giao diện không hiện mã (YC-PT-10): mã dấu
 *   hiệu, mã nút chuỗi và mã băm nằm ở thuộc tính `data-*`.
 */
import { Component, computed, inject, input, linkedSignal, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { OrgApi, toProblem } from '@vt/api-client';
import {
  ALERT_ACTION_LABEL,
  ALERT_NOTE_LIMITS,
  ALERT_SUBJECT_TYPE_LABEL,
  ALERT_VERDICT_LABEL,
  ANOMALY_ALERT_STATUS_LABEL,
  ANOMALY_OPEN_STATUSES,
  ANOMALY_SEVERITY_LABEL,
  ANOMALY_SIGNALS,
  ANOMALY_SIGNAL_LABEL,
  ANOMALY_SIGNAL_SUGGESTION,
  AlertActionInput,
  AlertApi,
  AlertFilter,
  AlertVerdict,
  AnomalyAlert,
  AnomalyAlertStatus,
  AnomalyAlertSummary,
  AnomalySeverity,
  AnomalySignalCode,
  EVIDENCE_CHAIN_KIND_LABEL,
  EvidenceChainNode,
  PeriodApi,
} from '@vt/api-client/domains';
import { ThemeService, VtSkeletonComponent } from '@vt/design-system';
import { ButtonModule } from 'primeng/button';
import { MessageModule } from 'primeng/message';
import { MultiSelectModule } from 'primeng/multiselect';
import { SelectModule } from 'primeng/select';
import { SelectButtonModule } from 'primeng/selectbutton';
import { TagModule } from 'primeng/tag';
import { TextareaModule } from 'primeng/textarea';
import { NgTemplateOutlet } from '@angular/common';
import { OVERSIGHT_CAN } from '../../oversight-permissions';
import { errorText, fmtDate, fmtDateTime } from '../../oversight-ui';

type View = 'open' | 'closed' | 'all';
type Busy = AlertActionInput['type'] | 'more' | null;

const CLOSED_STATUSES: AnomalyAlertStatus[] = [
  'HOP_LE',
  'CO_VI_PHAM',
  'KHONG_VI_PHAM',
  'TU_DONG_DONG',
  'DONG',
];

@Component({
  selector: 'vt-alert-center-page',
  imports: [
    VtSkeletonComponent,
    FormsModule,
    NgTemplateOutlet,
    RouterLink,
    ButtonModule,
    MessageModule,
    MultiSelectModule,
    SelectModule,
    SelectButtonModule,
    TagModule,
    TextareaModule,
  ],
  template: `
    <p class="crumb"><a [routerLink]="id() ? '../..' : '..'">Giám sát, hậu kiểm</a></p>
    <div class="head">
      <div>
        <h1>Trung tâm cảnh báo bất thường</h1>
        <p class="muted">
          Các dấu hiệu bất thường được phát hiện từ dữ liệu đánh giá.
          @if (counts(); as k) {
            Trong 90 ngày qua có {{ k.newCount }} cảnh báo mới.
          }
        </p>
      </div>
      @if (can('SCR-1401.nguong')) {
        <a
          class="p-button p-button-outlined p-button-contrast"
          [routerLink]="id() ? '../../alert-thresholds' : '../alert-thresholds'"
          >Ngưỡng phát hiện</a
        >
      }
    </div>

    <div class="grid">
      <div>
        <!-- vùng 1 -->
        <section class="box" aria-labelledby="l-h">
          <h2 class="h" id="l-h">Cảnh báo</h2>
          <p-selectbutton
            [options]="views"
            optionLabel="label"
            optionValue="value"
            [ngModel]="view()"
            (ngModelChange)="view.set($event)"
            [allowEmpty]="false"
            ariaLabelledBy="l-h"
          />
          <div class="filters">
            <div class="field">
              <label for="cb-signal">Dấu hiệu</label>
              <p-multiselect
                inputId="cb-signal"
                [options]="signalOptions"
                optionLabel="label"
                optionValue="value"
                [ngModel]="signals()"
                (ngModelChange)="signals.set($event ?? [])"
                placeholder="Tất cả dấu hiệu"
                [showToggleAll]="false"
                [maxSelectedLabels]="1"
                selectedItemsLabel="{0} dấu hiệu"
                styleClass="w-full"
              />
            </div>
            <div class="field">
              <label for="cb-severity">Mức</label>
              <p-select
                inputId="cb-severity"
                [options]="severityOptions"
                optionLabel="label"
                optionValue="value"
                [ngModel]="severity()"
                (ngModelChange)="severity.set($event)"
                styleClass="w-full"
              />
            </div>
          </div>
          @if (list.error()) {
            <p-message severity="error">{{ err(list.error()) }}</p-message>
          } @else if (rows(); as items) {
            @if (!items.length) {
              <p class="muted">Không có cảnh báo nào khớp bộ lọc.</p>
            }
            <ul class="items">
              @for (a of items; track a.id) {
                <li
                  [class.sel]="a.id === id()"
                  [attr.data-signal]="a.signalCode"
                  [attr.data-alert-id]="a.id"
                >
                  <a
                    [routerLink]="id() ? ['..', a.id] : [a.id]"
                    [attr.aria-current]="a.id === id() ? 'page' : null"
                  >
                    {{ signalLabel[a.signalCode] }}
                  </a>
                  <span class="small">{{ a.subject.name ?? subjectType[a.subject.type] }}</span>
                  <span class="small tags">
                    <p-tag
                      [value]="'Mức ' + severityLabel[a.severity].toLowerCase()"
                      [severity]="sevTone(a.severity)"
                    />
                    <p-tag [value]="statusLabel[a.status]" [severity]="statusTone(a.status)" />
                    @if (a.period) {
                      {{ a.period.label }}
                    }
                  </span>
                </li>
              }
            </ul>
            @if (cursor()) {
              <p-button
                label="Tải thêm"
                severity="contrast"
                [text]="true"
                [loading]="busy() === 'more'"
                (onClick)="more()"
              />
            }
          } @else {
            <vt-skeleton variant="list" [rows]="5" label="Đang tải cảnh báo" />
          }
        </section>

        <!-- vùng 4 -->
        <section class="box note" aria-labelledby="n-h">
          <h2 class="h" id="n-h">Nguyên tắc xử lý</h2>
          <p>Cảnh báo chỉ chỉ ra chỗ cần xem, không phải kết luận vi phạm.</p>
          @if (!simple()) {
            <p class="small">
              Đối tượng của cảnh báo không xem được cảnh báo cho tới khi có kết luận kiểm tra. Mọi
              thao tác xử lý phải có lý do và được ghi vào sổ cái.
            </p>
          }
        </section>
        @if (counts(); as k) {
          <section class="box" aria-labelledby="k-h">
            <h2 class="h" id="k-h">Phân bố 90 ngày</h2>
            <dl class="stats">
              <div>
                <dt>Cảnh báo mới</dt>
                <dd>{{ k.newCount }}</dd>
              </div>
              <div>
                <dt>Xác nhận hợp lệ</dt>
                <dd>{{ k.validCount }}</dd>
              </div>
              <div>
                <dt>Giao kiểm tra</dt>
                <dd>{{ k.assignedCount }}</dd>
              </div>
              <div>
                <dt>Có vi phạm sau kiểm tra</dt>
                <dd>{{ k.violationCount }}</dd>
              </div>
            </dl>
          </section>
        }
      </div>

      @if (!id()) {
        <section class="box">
          <p class="muted">Chọn một cảnh báo trong danh sách để xem chuỗi truy vết và xử lý.</p>
        </section>
      } @else if (alert.error()) {
        <section class="box">
          <p-message severity="error">{{ loadError() }}</p-message>
        </section>
      } @else if (current(); as a) {
        <div class="main">
          @if (notice(); as n) {
            <p-message severity="success" role="status">{{ n }}</p-message>
          }
          @if (error(); as e) {
            <p-message severity="error" role="alert">{{ e }}</p-message>
          }

          <!-- vùng 2 -->
          <section class="box" aria-labelledby="d-h" [attr.data-signal]="a.signalCode">
            <h2 class="h" id="d-h">
              {{ signalLabel[a.signalCode] }}
              <p-tag
                [value]="'Mức ' + severityLabel[a.severity].toLowerCase()"
                [severity]="sevTone(a.severity)"
              />
              <p-tag [value]="statusLabel[a.status]" [severity]="statusTone(a.status)" />
            </h2>
            @if (a.status === 'DANG_XEM') {
              <p-message severity="info" styleClass="mb">
                Cảnh báo chỉ chỉ ra chỗ cần xem, không phải kết luận vi phạm. Vui lòng ghi rõ lý do
                xử lý.
              </p-message>
            }
            <dl class="meta">
              <dt>Đối tượng</dt>
              <dd>{{ subjectType[a.subject.type] }}: {{ a.subject.name ?? '—' }}</dd>
              @if (a.period) {
                <dt>Kỳ đánh giá</dt>
                <dd>{{ a.period.label }}</dd>
              }
              <dt>Phát hiện lúc</dt>
              <dd>{{ dt(a.detectedAt) }}</dd>
              <dt>Hướng xử lý gợi ý</dt>
              <dd>{{ suggestion[a.signalCode] }}</dd>
            </dl>

            <h3 class="h3">Chuỗi truy vết</h3>
            <ul class="chain" aria-label="Chuỗi truy vết bằng chứng">
              @for (n of a.evidenceChain; track $index) {
                <ng-container *ngTemplateOutlet="nodeTpl; context: { $implicit: n }" />
              }
            </ul>
            <ng-template #nodeTpl let-n>
              <li [attr.data-kind]="n.kind" [attr.data-id]="n.id">
                <span class="kind">{{ kindLabel(n) }}:</span> {{ n.label ?? '—' }}
                @if (n.kind === 'BAN_GHI_SO_CAI' && can('SCR-1302.tra-cuu')) {
                  <a [routerLink]="ledgerLink()" [queryParams]="{ entryId: n.id }" class="small"
                    >Mở sổ cái</a
                  >
                }
                @if (n.children?.length) {
                  <ul>
                    @for (c of n.children; track $index) {
                      <ng-container *ngTemplateOutlet="nodeTpl; context: { $implicit: c }" />
                    }
                  </ul>
                }
              </li>
            </ng-template>

            @if (a.sources?.length && !simple()) {
              <h3 class="h3">Nguồn dữ liệu</h3>
              <div class="table-wrap">
                <table class="tbl">
                  <caption class="sr-only">
                    Nguồn dữ liệu của cảnh báo
                  </caption>
                  <thead>
                    <tr>
                      <th scope="col">Người nộp</th>
                      <th scope="col">Nhiệm vụ</th>
                      <th scope="col">Thời điểm nộp</th>
                      <th scope="col">Nội dung tệp</th>
                    </tr>
                  </thead>
                  <tbody>
                    @for (s of a.sources; track $index) {
                      <tr [attr.data-sha256]="s.sha256 ?? null">
                        <td>{{ s.submitter?.name ?? '—' }}</td>
                        <td>{{ s.task?.name ?? '—' }}</td>
                        <td>{{ dt(s.submittedAt) }}</td>
                        <td>{{ sameFile(a, s.sha256) }}</td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
            @if (a.ledgerEntryIds?.length && can('SCR-1302.tra-cuu')) {
              <a
                class="p-button p-button-sm p-button-outlined p-button-contrast mt"
                [routerLink]="ledgerLink()"
                [queryParams]="{ entryId: a.ledgerEntryIds![0] }"
                >Mở sổ cái</a
              >
            }
          </section>

          <!-- vùng 3 -->
          <section class="box" aria-labelledby="x-h">
            <h2 class="h" id="x-h">Xử lý</h2>
            @if (autoActions(a).length) {
              <h3 class="h3">Tác động tự động</h3>
              <ul class="plain-list">
                @for (x of autoActions(a); track x.id) {
                  <li>
                    {{ x.note }}
                    <span class="small muted">
                      ({{ x.active ? 'đang áp dụng' : 'đã gỡ' }}, {{ dt(x.actedAt) }})</span
                    >
                  </li>
                }
              </ul>
            }
            @if (history(a).length) {
              <h3 class="h3">Lịch sử xử lý</h3>
              <ul class="records">
                @for (x of history(a); track x.id) {
                  <li
                    [class.answer]="x.action === 'XAC_NHAN_HOP_LE'"
                    [class.ask]="x.action !== 'XAC_NHAN_HOP_LE'"
                  >
                    <strong>{{ actionLabel[x.action] }}</strong>
                    @if (x.verdict) {
                      : {{ verdictLabel[x.verdict] }}
                    }
                    <span class="small muted block">
                      {{ x.actor?.name ?? 'Hệ thống' }} · {{ dt(x.actedAt) }}
                      @if (x.assignedTo) {
                        · giao cho {{ x.assignedTo.name }}
                      }
                    </span>
                    @if (x.note) {
                      <span class="block">{{ x.note }}</span>
                    }
                  </li>
                }
              </ul>
            }

            @if (a.status === 'CO_VI_PHAM') {
              <p-message severity="warn" styleClass="mt">
                @if (periodLocked()) {
                  Kỳ {{ a.period?.label }} đã khoá. Kết luận có vi phạm cần được thi hành qua yêu
                  cầu mở lại kỳ.
                } @else {
                  Kết luận có vi phạm cần được thi hành qua hậu kiểm.
                }
              </p-message>
              @if (can('SCR-1301.tao')) {
                <a
                  class="p-button p-button-sm mt"
                  [routerLink]="reopenLink()"
                  [queryParams]="reopenQuery(a)"
                  >Tạo yêu cầu mở lại kỳ</a
                >
              }
            }

            @if (canHandle(a) || canConclude(a)) {
              @if (canConclude(a)) {
                <div class="field mt">
                  <span class="label" id="cb-verdict-l">Kết luận kiểm tra (bắt buộc)</span>
                  <p-selectbutton
                    [options]="verdicts"
                    optionLabel="label"
                    optionValue="value"
                    [ngModel]="verdict()"
                    (ngModelChange)="verdict.set($event)"
                    ariaLabelledBy="cb-verdict-l"
                  />
                  @if (errs()['verdict']; as m) {
                    <p class="err">{{ m }}</p>
                  }
                </div>
              }
              <div class="field mt">
                <label for="cb-note">Ghi chú xử lý (bắt buộc)</label>
                <textarea
                  pTextarea
                  id="cb-note"
                  rows="4"
                  [maxlength]="limits.max"
                  [ngModel]="note()"
                  (ngModelChange)="note.set($event)"
                  aria-describedby="cb-note-h"
                ></textarea>
                <p class="hint" id="cb-note-h">
                  {{ note().trim().length }} ký tự, cần từ {{ limits.min }} đến
                  {{ limits.max.toLocaleString('vi-VN') }} ký tự.
                </p>
                @if (errs()['note']; as m) {
                  <p class="err">{{ m }}</p>
                }
              </div>
              @if (canHandle(a)) {
                <div class="field narrow">
                  <label for="cb-assignee">Cán bộ cơ quan kiểm tra (khi giao kiểm tra)</label>
                  <p-select
                    inputId="cb-assignee"
                    [options]="inspectors()"
                    optionLabel="label"
                    optionValue="value"
                    [ngModel]="assignee()"
                    (ngModelChange)="assignee.set($event)"
                    [filter]="true"
                    filterBy="label"
                    placeholder="Chọn cán bộ"
                    styleClass="w-full"
                  />
                  @if (errs()['assignedTo']; as m) {
                    <p class="err">{{ m }}</p>
                  }
                </div>
                <div class="actions">
                  <p-button
                    label="Xác nhận hợp lệ"
                    [loading]="busy() === 'XAC_NHAN_HOP_LE'"
                    [disabled]="!!busy()"
                    (onClick)="run('XAC_NHAN_HOP_LE')"
                  />
                  <p-button
                    label="Giao kiểm tra"
                    severity="contrast"
                    [outlined]="true"
                    [loading]="busy() === 'GIAO_KIEM_TRA'"
                    [disabled]="!!busy()"
                    (onClick)="run('GIAO_KIEM_TRA')"
                  />
                </div>
              } @else {
                <div class="actions">
                  <p-button
                    label="Ghi kết luận"
                    [loading]="busy() === 'KET_LUAN'"
                    [disabled]="!!busy()"
                    (onClick)="run('KET_LUAN')"
                  />
                </div>
              }
            } @else if (isOpen(a)) {
              <p class="muted mt">
                {{
                  a.status === 'DA_GIAO_KIEM_TRA'
                    ? 'Cảnh báo đang chờ cơ quan kiểm tra kết luận.'
                    : 'Đồng chí chỉ có quyền xem cảnh báo này.'
                }}
              </p>
            }
          </section>
        </div>
      } @else {
        <vt-skeleton variant="detail" label="Đang tải cảnh báo" />
      }
    </div>
  `,
  styleUrls: ['../complaints/complaints.css'],
  styles: `
    .grid {
      display: grid;
      grid-template-columns: minmax(280px, 1fr) minmax(0, 2.4fr);
      gap: 16px;
      align-items: start;
      margin-top: 12px;
    }
    @media (max-width: 1000px) {
      .grid {
        grid-template-columns: 1fr;
      }
    }
    .filters {
      display: grid;
      gap: 8px;
      margin-top: 8px;
    }
    .filters .field {
      margin: 0;
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
      gap: 4px;
    }
    .items li.sel {
      border-color: #1e3a8a;
      background: #eff6ff;
    }
    .tags {
      display: flex;
      gap: 6px;
      flex-wrap: wrap;
      align-items: center;
    }
    .note {
      border-color: #b45309;
      background: #fff7ed;
      color: #7c2d12;
    }
    .note p {
      margin: 0 0 6px;
    }
    .stats {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 8px;
      margin: 0;
    }
    .stats div {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 6px;
      padding: 8px;
    }
    .stats dt {
      color: #4b5563;
      font-size: 0.875em;
    }
    .stats dd {
      margin: 0;
      font-size: 1.4rem;
      font-weight: 600;
    }
    .chain,
    .chain ul {
      margin: 0;
      padding-left: 20px;
    }
    .chain li {
      margin: 4px 0;
    }
    .kind {
      color: #4b5563;
    }
    .plain-list {
      margin: 0;
      padding-left: 20px;
    }
    .main {
      min-width: 0;
    }
    .mt {
      margin-top: 12px;
    }
    .narrow {
      max-width: 360px;
    }
    .block {
      display: block;
    }
    textarea {
      width: 100%;
    }
    :host ::ng-deep .mb {
      margin-bottom: 8px;
    }
    :host ::ng-deep .p-message-info .p-message-text,
    :host ::ng-deep .p-message-info .p-message-icon {
      color: #1e3a8a;
    }
  `,
})
export class AlertCenterPage {
  private readonly api = inject(AlertApi);
  private readonly org = inject(OrgApi);
  private readonly periods = inject(PeriodApi);
  private readonly theme = inject(ThemeService);
  protected readonly can = inject(OVERSIGHT_CAN);

  readonly id = input<string>();

  protected readonly signalLabel = ANOMALY_SIGNAL_LABEL;
  protected readonly suggestion = ANOMALY_SIGNAL_SUGGESTION;
  protected readonly severityLabel = ANOMALY_SEVERITY_LABEL;
  protected readonly statusLabel = ANOMALY_ALERT_STATUS_LABEL;
  protected readonly subjectType = ALERT_SUBJECT_TYPE_LABEL;
  protected readonly actionLabel = ALERT_ACTION_LABEL;
  protected readonly verdictLabel = ALERT_VERDICT_LABEL;
  protected readonly limits = ALERT_NOTE_LIMITS;

  protected readonly views: { value: View; label: string }[] = [
    { value: 'open', label: 'Đang mở' },
    { value: 'closed', label: 'Đã đóng' },
    { value: 'all', label: 'Tất cả' },
  ];
  protected readonly signalOptions = ANOMALY_SIGNALS.map((value) => ({
    value,
    label: ANOMALY_SIGNAL_LABEL[value],
  }));
  protected readonly severityOptions: { value: AnomalySeverity | ''; label: string }[] = [
    { value: '', label: 'Tất cả các mức' },
    { value: 'CAO', label: 'Mức cao' },
    { value: 'VUA', label: 'Mức vừa' },
    { value: 'THAP', label: 'Mức thấp' },
  ];
  protected readonly verdicts = (['CO_VI_PHAM', 'KHONG_VI_PHAM'] as AlertVerdict[]).map(
    (value) => ({ value, label: ALERT_VERDICT_LABEL[value] }),
  );

  // vùng 1
  protected readonly view = signal<View>('open');
  protected readonly signals = signal<AnomalySignalCode[]>([]);
  protected readonly severity = signal<AnomalySeverity | ''>('');
  private readonly filter = computed<AlertFilter>(() => ({
    status:
      this.view() === 'open'
        ? [...ANOMALY_OPEN_STATUSES]
        : this.view() === 'closed'
          ? CLOSED_STATUSES
          : undefined,
    signal: this.signals().length ? this.signals() : undefined,
    severity: this.severity() ? [this.severity() as AnomalySeverity] : undefined,
  }));
  protected readonly list = rxResource({
    params: () => this.filter(),
    stream: ({ params }) => this.api.list(params),
  });
  private readonly page = computed(() => (this.list.hasValue() ? this.list.value() : undefined));
  protected readonly rows = linkedSignal<AnomalyAlertSummary[] | undefined>(
    () => this.page()?.items,
  );
  protected readonly cursor = linkedSignal<string | null>(() => this.page()?.nextCursor ?? null);
  protected readonly counts = computed(() => this.page()?.counts);

  // vùng 2, 3
  protected readonly alert = rxResource({
    params: () => this.id() || undefined,
    stream: ({ params }) => this.api.get(params),
  });
  protected readonly current = linkedSignal<AnomalyAlert | undefined>(() =>
    this.alert.hasValue() ? this.alert.value() : undefined,
  );
  protected readonly loadError = computed(() => {
    const p = toProblem(this.alert.error());
    return p.status === 404 ? 'Không tìm thấy cảnh báo.' : this.err(this.alert.error());
  });

  /** Người được giao kiểm tra: hợp đồng chưa có danh mục cán bộ cơ quan kiểm tra, tạm dùng danh mục tổ chức. */
  private readonly persons = rxResource({
    params: () => (this.current() && this.canHandle(this.current()!) ? true : undefined),
    stream: () => this.org.persons(),
  });
  protected readonly inspectors = computed(() =>
    ((this.persons.hasValue() ? this.persons.value() : undefined)?.items ?? []).map((p) => ({
      value: p.id,
      label: p.unitName ? `${p.name} · ${p.unitName}` : p.name,
    })),
  );

  /** Trạng thái kỳ để chọn câu MSG-14-04 khi kết luận có vi phạm. */
  private readonly period = rxResource({
    params: () =>
      this.current()?.status === 'CO_VI_PHAM' ? this.current()?.period?.id : undefined,
    stream: ({ params }) => this.periods.get(params),
  });
  protected readonly periodLocked = computed(
    () => this.period.hasValue() && this.period.value()?.status === 'DA_KHOA',
  );

  protected readonly note = linkedSignal<string | undefined, string>({
    source: () => this.id(),
    computation: () => '',
  });
  protected readonly assignee = linkedSignal<string | undefined, string | null>({
    source: () => this.id(),
    computation: () => null,
  });
  protected readonly verdict = linkedSignal<string | undefined, AlertVerdict | null>({
    source: () => this.id(),
    computation: () => null,
  });
  protected readonly errs = linkedSignal<string | undefined, Record<string, string>>({
    source: () => this.id(),
    computation: () => ({}),
  });
  protected readonly notice = linkedSignal<string | undefined, string | null>({
    source: () => this.id(),
    computation: () => null,
  });
  protected readonly error = linkedSignal<string | undefined, string | null>({
    source: () => this.id(),
    computation: () => null,
  });
  protected readonly busy = signal<Busy>(null);

  protected readonly simple = computed(
    () => this.theme.mode() === 'elderly' && this.theme.reducedSteps(),
  );

  protected isOpen(a: AnomalyAlert) {
    return ANOMALY_OPEN_STATUSES.includes(a.status);
  }
  protected canHandle(a: AnomalyAlert) {
    return a.status === 'DANG_XEM' && this.can('SCR-1401.xu-ly');
  }
  protected canConclude(a: AnomalyAlert) {
    return a.status === 'DA_GIAO_KIEM_TRA' && this.can('SCR-1401.ket-luan');
  }
  protected autoActions(a: AnomalyAlert) {
    return a.actions.filter((x) => x.action === 'TU_DONG');
  }
  protected history(a: AnomalyAlert) {
    return a.actions.filter((x) => x.action !== 'TU_DONG');
  }
  protected kindLabel(n: EvidenceChainNode) {
    return EVIDENCE_CHAIN_KIND_LABEL[n.kind] ?? 'Dữ liệu';
  }
  /** Bảng nguồn không hiện mã băm; chỉ cho biết tệp có trùng nội dung với tệp khác trong cảnh báo. */
  protected sameFile(a: AnomalyAlert, sha?: string) {
    if (!sha) return '—';
    const n = (a.sources ?? []).filter((s) => s.sha256 === sha).length - 1;
    return n > 0 ? `Trùng với ${n} tệp khác` : 'Không trùng';
  }
  protected ledgerLink() {
    return this.id() ? '../../ledger' : '../ledger';
  }
  protected reopenLink() {
    return this.id() ? '../../reopen-requests/new' : '../reopen-requests/new';
  }
  /** Truyền sẵn căn cứ cho SCR-1301; hồ sơ lấy từ nút điểm khi đối tượng là cá nhân (suy luận). */
  protected reopenQuery(a: AnomalyAlert) {
    const profile =
      a.subject.type === 'CA_NHAN' ? a.evidenceChain.find((n) => n.kind === 'DIEM')?.id : undefined;
    return {
      periodId: a.period?.id,
      auditKind: 'DOT_XUAT',
      basisKind: 'CANH_BAO',
      basisCode: `Cảnh báo ngày ${fmtDate(a.detectedAt)}`,
      basisId: a.id,
      ...(profile ? { profileId: profile } : {}),
    };
  }
  protected sevTone(s: AnomalySeverity): 'danger' | 'warn' | 'info' {
    return s === 'CAO' ? 'danger' : s === 'VUA' ? 'warn' : 'info';
  }
  protected statusTone(
    s: AnomalyAlertStatus,
  ): 'info' | 'warn' | 'success' | 'danger' | 'secondary' {
    switch (s) {
      case 'MOI':
        return 'info';
      case 'DANG_XEM':
      case 'DA_GIAO_KIEM_TRA':
        return 'warn';
      case 'CO_VI_PHAM':
        return 'danger';
      case 'HOP_LE':
      case 'KHONG_VI_PHAM':
        return 'success';
      default:
        return 'secondary';
    }
  }
  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  /** Lỗi tách vai trò (BR-14-08) chưa có MSG riêng: hiện nguyên văn câu máy chủ trả. */
  protected err(e: unknown) {
    const p = toProblem(e);
    return p.status === 403 && p.detail ? p.detail : errorText(e);
  }

  protected more() {
    const c = this.cursor();
    if (!c) return;
    this.busy.set('more');
    this.api.list(this.filter(), c).subscribe({
      next: (p) => {
        this.rows.set([...(this.rows() ?? []), ...p.items]);
        this.cursor.set(p.nextCursor);
        this.busy.set(null);
      },
      error: (e) => {
        this.busy.set(null);
        this.error.set(this.err(e));
      },
    });
  }

  private validate(type: AlertActionInput['type']): boolean {
    const e: Record<string, string> = {};
    const n = this.note().trim().length;
    if (n < this.limits.min || n > this.limits.max)
      e['note'] =
        `Ghi chú xử lý cần từ ${this.limits.min} đến ${this.limits.max.toLocaleString('vi-VN')} ký tự (hiện có ${n}).`;
    if (type === 'GIAO_KIEM_TRA' && !this.assignee())
      e['assignedTo'] = 'Chọn cán bộ cơ quan kiểm tra được giao.';
    if (type === 'KET_LUAN' && !this.verdict()) e['verdict'] = 'Chọn có hoặc không vi phạm.';
    this.errs.set(e);
    return !Object.keys(e).length;
  }

  protected run(type: 'XAC_NHAN_HOP_LE' | 'GIAO_KIEM_TRA' | 'KET_LUAN') {
    const a = this.current();
    this.notice.set(null);
    this.error.set(null);
    if (!a || !this.validate(type)) return;
    const input: AlertActionInput = { type, note: this.note().trim() };
    if (type === 'GIAO_KIEM_TRA') input.assignedTo = this.assignee()!;
    if (type === 'KET_LUAN') input.verdict = this.verdict()!;
    const blocked = this.autoActions(a).some((x) => x.active) ? (a.sources?.length ?? 0) : 0;
    this.busy.set(type);
    this.api.act(a, input).subscribe({
      next: (r) => {
        this.busy.set(null);
        this.current.set(r);
        this.note.set('');
        this.errs.set({});
        this.notice.set(this.doneText(type, r, blocked));
        this.list.reload();
      },
      error: (e) => {
        this.busy.set(null);
        const p = toProblem(e);
        if (p.errors?.length) {
          this.errs.set(Object.fromEntries(p.errors.map((x) => [x.field ?? 'note', x.message])));
          return;
        }
        this.error.set(this.err(e));
        if (p.status === 409 || p.status === 412) this.alert.reload();
      },
    });
  }

  private doneText(type: AlertActionInput['type'], r: AnomalyAlert, blocked: number) {
    if (type === 'XAC_NHAN_HOP_LE')
      // MSG-14-03
      return blocked
        ? `Đã xác nhận hợp lệ. ${blocked} minh chứng được tính điểm trở lại.`
        : 'Đã xác nhận hợp lệ. Cảnh báo đã được đóng.';
    if (type === 'GIAO_KIEM_TRA') {
      const to = r.actions.at(-1)?.assignedTo?.name;
      return to ? `Đã giao kiểm tra cho đồng chí ${to}.` : 'Đã giao kiểm tra.';
    }
    return `Đã ghi kết luận: ${r.status === 'CO_VI_PHAM' ? 'có vi phạm' : 'không vi phạm'}.`;
  }
}
