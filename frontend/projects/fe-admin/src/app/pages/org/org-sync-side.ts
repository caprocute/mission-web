import { Component, computed, input, output } from '@angular/core';
import {
  OrgDiscrepancy,
  OrgSyncRun,
  SUGGESTED_ACTION_LABEL,
  SYNC_RUN_STATUS_LABEL,
  SYNC_SOURCE_LABEL,
  hasDeadLetters,
} from '@vt/api-client';
import { fmtDate, fmtDateTime } from '../../admin-ui';

/**
 * Vùng 4 "Chi tiết bản ghi" và nhật ký đồng bộ của SCR-0104: mã ngoài, mã nội bộ, khớp theo, lần
 * đồng bộ, hiệu lực theo quyết định; nhật ký có nút chạy lại lần lỗi (`/org/sync-runs/{id}/retry`).
 */
@Component({
  selector: 'vt-org-sync-side',
  template: `
    <section class="box" aria-labelledby="z4" aria-live="polite">
      <h2 id="z4" class="h">Chi tiết bản ghi</h2>
      @if (row(); as d) {
        <p class="name">{{ d.personName }}</p>
        <dl>
          <dt>Mã tại nguồn</dt>
          <dd>{{ d.externalId }}</dd>
          <dt>Hồ sơ trên hệ thống</dt>
          <dd>{{ d.personId ? 'Đã khớp với hồ sơ hiện có' : 'Chưa có, cần ánh xạ thủ công' }}</dd>
          @if (d.matchedBy) {
            <dt>Khớp theo</dt>
            <dd>{{ d.matchedBy }}</dd>
          }
          <dt>Lần đồng bộ</dt>
          <dd>{{ runText() }}</dd>
          <dt>Hiệu lực từ</dt>
          <dd>{{ d.effectiveFrom ? date(d.effectiveFrom) + ' (theo quyết định)' : 'Không có' }}</dd>
          <dt>Đề xuất</dt>
          <dd>{{ actText(d.suggestedAction) }}</dd>
          @if (d.assigneeName) {
            <dt>Đã giao</dt>
            <dd>{{ d.assigneeName }}</dd>
          }
        </dl>
        @if (!simple()) {
          <p class="info">
            <!-- EVT-15: máy chủ ghi phiên bản mới valid_from = hiệu lực, recorded_from = lúc duyệt -->
            Khi chấp nhận, phân công được cập nhật có hiệu lực từ ngày
            {{ d.effectiveFrom ? date(d.effectiveFrom) : 'ghi trong quyết định' }}, thời điểm ghi
            nhận là lúc duyệt; các bên liên quan nhận được thông báo thay đổi phân công.
          </p>
        }
      } @else {
        <p class="muted">Chọn tên cán bộ trong danh sách để xem chi tiết.</p>
      }
    </section>

    <section class="box" aria-labelledby="z5">
      <h2 id="z5" class="h">Nhật ký đồng bộ</h2>
      <table class="tbl">
        <caption class="sr-only">
          Các lần đồng bộ gần nhất
        </caption>
        <thead>
          <tr>
            <th scope="col">Lần chạy</th>
            <th scope="col">Nguồn</th>
            <th scope="col">Kết quả</th>
          </tr>
        </thead>
        <tbody>
          @for (r of runs(); track r.id) {
            <tr>
              <td>{{ dateTime(r.startedAt) }}</td>
              <td>{{ src[r.connector] }}</td>
              <td>
                <span class="tag" [class]="'tag r-' + r.status">{{ resultText(r) }}</span>
                @if (canRetryRun() && retryable(r)) {
                  <button type="button" class="link" (click)="retry.emit(r)">Chạy lại</button>
                }
              </td>
            </tr>
          }
        </tbody>
      </table>
      <p class="muted small">Dữ liệu của lần đồng bộ lỗi được lưu lại để chạy lại.</p>
    </section>
  `,
  styles: `
    .box {
      padding: 16px;
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      background: #fff;
      margin-bottom: 16px;
    }
    .h {
      font-size: 1.05rem;
      margin: 0 0 12px;
    }
    .name {
      font-weight: 600;
      margin: 0 0 4px;
    }
    dl {
      display: grid;
      grid-template-columns: 7.5em 1fr;
      gap: 6px 12px;
      margin: 0;
    }
    dt {
      color: #4b5563;
    }
    dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .info {
      border-left: 4px solid #2563eb;
      background: #eef4ff;
      padding: 8px 12px;
      margin: 12px 0 0;
    }
    .muted {
      color: #4b5563;
    }
    .small {
      font-size: 0.875em;
    }
    .tbl {
      width: 100%;
      border-collapse: collapse;
    }
    .tbl th,
    .tbl td {
      text-align: left;
      padding: 8px 6px;
      border-bottom: 1px solid #e5e7eb;
    }
    .tag {
      padding: 1px 8px;
      border-radius: 999px;
      font-size: 0.85em;
      background: #e7f6ec;
      color: #146c2e;
    }
    .r-THANH_CONG_MOT_PHAN {
      background: #fff4d6;
      color: #6b4400;
    }
    .r-LOI {
      background: #fde8eb;
      color: #a3001f;
    }
    .link {
      background: none;
      border: 0;
      color: #b8002a;
      text-decoration: underline;
      cursor: pointer;
      font: inherit;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0 0 0 0);
    }
  `,
})
export class OrgSyncSide {
  readonly row = input<OrgDiscrepancy | null>(null);
  readonly runs = input<OrgSyncRun[]>([]);
  readonly simple = input(false);
  readonly canRetryRun = input(false);
  readonly retry = output<OrgSyncRun>();

  protected readonly actText = (a: string) => SUGGESTED_ACTION_LABEL[a] ?? a;
  protected readonly src = SYNC_SOURCE_LABEL;
  protected readonly date = fmtDate;
  protected readonly dateTime = fmtDateTime;
  protected readonly retryable = hasDeadLetters;

  /** Thời điểm lần đồng bộ sinh ra dòng lệch, tra từ nhật ký theo `syncRunId`. */
  protected readonly runText = computed(() => {
    const d = this.row();
    const r = d && this.runs().find((x) => x.id === d.syncRunId);
    return r ? `${fmtDateTime(r.startedAt)} · ${SYNC_SOURCE_LABEL[r.connector]}` : 'Không xác định';
  });

  protected resultText(r: OrgSyncRun): string {
    if (r.status === 'THANH_CONG') return 'Thành công';
    if (r.status === 'THANH_CONG_MOT_PHAN') return `${r.errors} lỗi`;
    if (r.status === 'LOI' && !hasDeadLetters(r)) return 'Lỗi · đã chạy lại';
    return SYNC_RUN_STATUS_LABEL[r.status];
  }
}
