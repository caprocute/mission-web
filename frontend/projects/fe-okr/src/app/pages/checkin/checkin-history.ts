import { Component, input, output } from '@angular/core';
import { CONFIDENCE_LABEL, KrCheckin } from '@vt/api-client';
import { formatDateTime, formatValue } from '../../okr-ui';

const CHANNEL: Record<KrCheckin['channel'], string> = {
  WEB: 'Web',
  MOBILE: 'Di động',
  TICH_HOP: 'Tích hợp',
};

/**
 * SCR-0301 "Lịch sử" (`SCR-0301.lich-su`): các lần check-in đã gửi của KR, mới nhất trước. Check-in
 * đã gửi không sửa được; đính chính bằng lần check-in mới (BR-03-03).
 */
@Component({
  selector: 'vt-checkin-history',
  template: `
    <section class="box" aria-labelledby="h-h" tabindex="-1" (keydown.escape)="closed.emit()">
      <h2 id="h-h" class="sec">Lịch sử check-in {{ code() }}</h2>
      @if (!items().length) {
        <p class="muted">Chưa có lần check-in nào.</p>
      } @else {
        <table>
          <thead>
            <tr>
              <th scope="col">Thời điểm</th>
              <th scope="col">Giá trị</th>
              <th scope="col">Tự tin</th>
              <th scope="col">Kết quả, vướng mắc</th>
              <th scope="col">Kênh</th>
            </tr>
          </thead>
          <tbody>
            @for (c of items(); track c.id) {
              <tr>
                <td>{{ c.weekLabel }} ({{ when(c.checkinAt) }})</td>
                <td>{{ value(c.value) }}</td>
                <td>{{ conf[c.confidence] }}</td>
                <td>
                  {{ c.highlights ?? '' }}
                  @if (c.blockerNote) {
                    <span class="blk">Vướng mắc: {{ c.blockerNote }}</span>
                  }
                </td>
                <td>{{ channel[c.channel] }}</td>
              </tr>
            }
          </tbody>
        </table>
      }
      <button type="button" class="close" (click)="closed.emit()">Đóng lịch sử</button>
    </section>
  `,
  styles: `
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
      margin-bottom: 16px;
    }
    .sec {
      font-size: 1.05rem;
      margin: 0 0 12px;
    }
    table {
      width: 100%;
      border-collapse: collapse;
    }
    th,
    td {
      text-align: left;
      padding: 8px;
      border-bottom: 1px solid #e5e7eb;
      vertical-align: top;
    }
    th {
      background: #f3f4f6;
      font-weight: 600;
    }
    .blk {
      display: block;
      color: #7c4a03;
    }
    .muted {
      color: #4b5563;
    }
    .close {
      margin-top: 12px;
      font: inherit;
      padding: 6px 14px;
      border: 1px solid #6b7280;
      border-radius: 6px;
      background: #fff;
      cursor: pointer;
    }
  `,
})
export class CheckinHistory {
  readonly code = input.required<string>();
  readonly unit = input.required<string>();
  readonly items = input<KrCheckin[]>([]);
  readonly closed = output<void>();
  protected readonly conf = CONFIDENCE_LABEL;
  protected readonly channel = CHANNEL;
  protected readonly when = formatDateTime;
  protected value(n: number | null) {
    return formatValue(n, this.unit());
  }
}
