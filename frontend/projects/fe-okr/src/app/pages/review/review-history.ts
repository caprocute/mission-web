import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { OkrApi, OkrHistoryAction, OkrReviewQueue, OkrReviewSet } from '@vt/api-client';
import { formatDate, formatDateTime } from '../../okr-ui';

const ACTION: Record<OkrHistoryAction, string> = {
  TAO: 'tạo',
  LUU_NHAP: 'lưu nháp',
  TRINH: 'trình',
  RUT_LAI: 'rút lại',
  DUYET: 'phê duyệt',
  TRA_LAI: 'trả lại',
  TAO_PHIEN_BAN: 'tạo phiên bản mới',
  DONG: 'đóng',
};

/**
 * Vùng 5 của SCR-0203: lịch sử luồng duyệt của bộ OKR (`GET /okr/objectives/{id}/history` của mục
 * tiêu đầu bộ), kết quả kiểm tra của hệ thống và bước đang chờ người duyệt, kèm mẫu luồng đang áp dụng.
 */
@Component({
  selector: 'vt-review-history',
  template: `
    @let s = set();
    @let q = queue();
    <section class="box" aria-labelledby="h-h">
      <h2 id="h-h" class="sec">Lịch sử luồng duyệt</h2>
      <ol class="tl">
        @for (e of entries(); track $index) {
          <li class="ok">
            {{ when(e.at) }} · {{ e.actor.name }} {{ action[e.action] }}
            @if (e.comment) {
              ({{ e.comment }})
            }
          </li>
        }
        <li class="ok">
          {{ when(s.submittedAt) }} · Hệ thống kiểm tra: {{ s.blockingCount }} lỗi chặn,
          {{ s.warningCount }} cảnh báo
        </li>
        <li class="wait">
          Đang chờ: {{ q.reviewer.name }} ({{ q.reviewer.title }}) · hạn {{ day(q.dueDate) }}
        </li>
      </ol>
      @if (history.error()) {
        <p class="muted">Chưa tải được lịch sử lưu, trình của bộ này.</p>
      }
      <p class="muted note">
        Quy trình phê duyệt OKR phiên bản {{ q.workflow.version }}, áp dụng theo quy định của đơn
        vị. Người trình không được tự duyệt.
      </p>
    </section>
  `,
  styles: `
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      font-size: 0.875rem;
    }
    .sec {
      display: flex;
      align-items: center;
      gap: 8px;
      font-size: 1.05rem;
      margin: 0 0 12px;
    }
    .num {
      display: inline-grid;
      place-items: center;
      width: 22px;
      height: 22px;
      border-radius: 50%;
      background: #111827;
      color: #fff;
      font-size: 0.75rem;
    }
    .tl {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .tl li {
      position: relative;
      padding: 0 0 12px 22px;
      border-left: 2px solid #e5e7eb;
      margin-left: 6px;
    }
    .tl li::before {
      content: '';
      position: absolute;
      left: -8px;
      top: 2px;
      width: 12px;
      height: 12px;
      border-radius: 50%;
      background: #15803d;
    }
    .tl li.wait {
      color: #991b1b;
    }
    .tl li.wait::before {
      background: #c4002a;
    }
    .muted {
      color: #4b5563;
    }
    .note {
      margin: 4px 0 0;
    }
  `,
})
export class ReviewHistory {
  private readonly api = inject(OkrApi);
  readonly set = input.required<OkrReviewSet>();
  readonly queue = input.required<OkrReviewQueue>();
  protected readonly action = ACTION;

  private readonly firstId = computed(() => this.set().objectives[0]?.id ?? null);
  protected readonly history = rxResource({
    params: () => this.firstId() ?? undefined,
    stream: ({ params }) => this.api.history(params),
  });
  /** Cũ nhất trước như một dòng thời gian. */
  protected readonly entries = computed(() =>
    this.history.hasValue() ? [...this.history.value().items].reverse() : [],
  );

  protected readonly when = formatDateTime;
  protected readonly day = (iso?: string) => (iso ? formatDate(iso) : '—');
}
