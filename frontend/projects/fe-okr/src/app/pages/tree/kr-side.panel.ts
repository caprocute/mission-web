import { Component, computed, inject, input, output } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { KeyResult, OkrApi, OkrPeerLink, ProblemPresenter } from '@vt/api-client';
import { ButtonModule } from 'primeng/button';
import { TagModule } from 'primeng/tag';
import { CONFIDENCE_SHORT, confidenceSeverity, formatDate, formatValue } from '../../okr-ui';

/**
 * Bảng bên phải SCR-0201 (Hình 7.3 vùng 4, 5): lịch sử check-in của KR đang chọn, vướng mắc gần
 * nhất, chương trình và nhiệm vụ liên kết (Tầng 2–3, `okr.okr_link`), nút "Check-in KR", liên kết
 * ngang và quy tắc đưa KR tự tin Thấp lên bảng điều hành.
 */
@Component({
  selector: 'vt-kr-side-panel',
  imports: [ButtonModule, TagModule, RouterLink],
  template: `
    @let k = kr();
    <section class="box" aria-labelledby="kr-hist-title">
      <h2 class="h" id="kr-hist-title">{{ k.code }} · lịch sử check-in</h2>
      @if (history.error()) {
        <p class="err" role="alert">{{ historyError() }}</p>
      } @else if (history.isLoading() && !history.hasValue()) {
        <p class="muted" aria-live="polite">Đang tải lịch sử check-in…</p>
      } @else if (rows().length === 0) {
        <p class="muted">KR chưa có lần check-in nào.</p>
      } @else {
        <table class="tbl">
          <caption class="sr-only">
            Lịch sử check-in
            {{
              k.code
            }}, mới nhất trước
          </caption>
          <thead>
            <tr>
              <th scope="col">Tuần</th>
              <th scope="col">Giá trị</th>
              <th scope="col">Tự tin</th>
            </tr>
          </thead>
          <tbody>
            @for (c of rows(); track c.id) {
              <tr>
                <td>{{ c.weekLabel }} ({{ formatDate(c.checkinAt) }})</td>
                <td>{{ formatValue(c.value, k.unit) }}</td>
                <td>
                  <p-tag [severity]="confSev(c.confidence)" [value]="confShort[c.confidence]" />
                </td>
              </tr>
            }
          </tbody>
        </table>
      }
      @if (k.latestBlocker) {
        <h3 class="h3">Vướng mắc gần nhất</h3>
        <p class="blocker">{{ k.latestBlocker }}</p>
      }
      @if (k.linkedWork.length > 0 || k.linkedTaskCount > 0) {
        <h3 class="h3">Chương trình và nhiệm vụ liên kết</h3>
        <ul class="work">
          @for (w of k.linkedWork; track w.id) {
            <li>
              <span>{{ w.kind === 'PROGRAM' ? 'Chương trình' : 'Nhiệm vụ' }} "{{ w.title }}"</span>
              <p-tag [severity]="w.percent >= 50 ? 'success' : 'warn'" [value]="w.percent + '%'" />
            </li>
          }
        </ul>
        @if (k.linkedTaskCount > 0) {
          <a class="link" routerLink="/tasks" [queryParams]="{ krId: k.id }">
            Xem tất cả {{ k.linkedTaskCount }} nhiệm vụ liên kết
          </a>
        }
        <!-- BR-03-07 -->
        <p class="note">Tiến độ nhiệm vụ không tự cộng vào giá trị KR.</p>
      }
      @if (canCheckIn()) {
        <p-button
          [label]="'Check-in ' + k.code"
          icon="pi pi-check-circle"
          styleClass="ci"
          (onClick)="checkIn.emit(k)"
        />
      }
    </section>
    @if (peerLinks().length > 0) {
      <section class="box" aria-labelledby="peer-title">
        <h2 class="h" id="peer-title">Liên kết ngang</h2>
        @for (l of peerLinks(); track l.id) {
          <p>
            {{ l.fromLabel }} phối hợp với
            <strong>{{ l.peerUnitName }} · {{ l.peerCode }} "{{ l.peerTitle }}"</strong>. Thay đổi
            của bên kia được thông báo cho chủ sở hữu KR.
          </p>
        }
      </section>
    }
    <section class="box" aria-labelledby="rule-title">
      <h2 class="h" id="rule-title">Mức tự tin thấp</h2>
      <p>
        KR có mức tự tin <strong>Thấp</strong> hai tuần liên tiếp được đưa lên bảng điều hành của
        lãnh đạo đơn vị.
      </p>
    </section>
  `,
  styles: `
    /* Đỏ thương hiệu đậm hơn token chính (#ee0033 chỉ đạt 4,48:1 với nền trắng, xem WEB-059) để chữ và nền màu đạt WCAG AA */
    :host {
      --okr-accent: #c4002a;
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
      font-size: 0.95rem;
      margin: 12px 0 6px;
    }
    .muted {
      color: #4b5563;
    }
    .err {
      color: #b91c1c;
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
      padding: 8px;
      border-bottom: 1px solid var(--p-content-border-color, #e5e7eb);
    }
    .blocker {
      background: #fef7e6;
      border-left: 3px solid #d97706;
      padding: 8px 12px;
      margin: 0;
    }
    .work {
      margin: 0;
      padding-left: 18px;
    }
    .work li {
      margin-bottom: 4px;
      display: flex;
      gap: 8px;
      align-items: center;
      flex-wrap: wrap;
    }
    .link {
      color: var(--okr-accent);
      font-weight: 600;
    }
    .note {
      color: #4b5563;
      font-size: 0.85rem;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
      white-space: nowrap;
    }
    :host ::ng-deep .ci {
      margin-top: 8px;
    }
  `,
})
export class KrSidePanel {
  private readonly api = inject(OkrApi);
  private readonly problems = inject(ProblemPresenter);

  readonly kr = input.required<KeyResult>();
  readonly canCheckIn = input(false);
  readonly peerLinks = input<OkrPeerLink[]>([]);
  readonly checkIn = output<KeyResult>();

  protected readonly confShort = CONFIDENCE_SHORT;
  protected readonly confSev = confidenceSeverity;
  protected readonly formatDate = formatDate;
  protected readonly formatValue = formatValue;

  protected readonly history = rxResource({
    params: () => this.kr().id,
    stream: ({ params }) => this.api.checkins(params, undefined, 6),
  });
  protected readonly rows = computed(() =>
    this.history.hasValue() ? this.history.value().items : [],
  );
  protected readonly historyError = computed(() =>
    this.history.error() ? this.problems.describe(this.history.error()).message : '',
  );
}
