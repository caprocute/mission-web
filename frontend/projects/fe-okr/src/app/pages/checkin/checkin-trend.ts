import { Component, computed, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { KrCheckin, OkrKeyResultDetail } from '@vt/api-client';
import { formatDate, formatValue } from '../../okr-ui';

interface Bar {
  key: string;
  label: string;
  value: number;
  height: number;
  current: boolean;
  tone: 'past' | 'ok' | 'now';
}

/**
 * SCR-0301 vùng 5: diễn biến KR theo các lần check-in (tối đa 5 lần gần nhất) với đường chỉ tiêu,
 * cột đỏ là giá trị đang nhập; nhiệm vụ liên kết và lời nhắc BR-03-07. Biểu đồ vẽ bằng CSS, có bảng
 * ẩn cho trình đọc màn hình.
 */
@Component({
  selector: 'vt-checkin-trend',
  imports: [RouterLink],
  template: `
    @let k = kr();
    <section class="box" aria-labelledby="t-h">
      <h2 id="t-h" class="sec">Diễn biến {{ k.code }}</h2>
      <div class="chart" aria-hidden="true">
        <div class="target" [style.bottom.%]="targetPos()"></div>
        @for (b of bars(); track b.key) {
          <div class="col">
            <div class="bar" [class]="b.tone" [style.height.%]="b.height"></div>
            <span>{{ b.label }}</span>
          </div>
        }
      </div>
      <table class="sr-only">
        <caption>
          Giá trị
          {{
            k.code
          }}
          theo lần check-in
        </caption>
        <tbody>
          @for (b of bars(); track b.key) {
            <tr>
              <th scope="row">{{ b.label }}</th>
              <td>{{ fmt(b.value) }}</td>
            </tr>
          }
        </tbody>
      </table>
      <p class="note">
        Đường ngang là chỉ tiêu {{ fmt(k.targetValue) }}. Cột đỏ là giá trị đang check-in.
      </p>
    </section>
    <section class="box" aria-labelledby="w-h">
      <h2 id="w-h" class="sec">Nhiệm vụ liên kết</h2>
      @if (k.linkedWork?.length) {
        <ul class="work">
          @for (w of k.linkedWork; track w.id) {
            <li>
              {{ w.title }}
              <span class="pct" [class.done]="w.percent >= 100">
                {{ w.percent >= 100 ? 'Hoàn thành' : w.percent + '%' }}
              </span>
            </li>
          }
        </ul>
      } @else {
        <p class="note">Chưa có nhiệm vụ liên kết.</p>
      }
      @if ((k.linkedTaskCount ?? 0) > (k.linkedWork?.length ?? 0)) {
        <a class="link" routerLink="/tasks" [queryParams]="{ krId: k.id }">
          Xem tất cả {{ k.linkedTaskCount }} nhiệm vụ liên kết
        </a>
      }
      <!-- BR-03-07 -->
      <p class="note">
        Tiến độ nhiệm vụ không tự cộng vào KR: KR đo kết quả, không đo khối lượng công việc.
      </p>
    </section>
  `,
  styles: `
    :host {
      display: grid;
      gap: 16px;
    }
    .box {
      padding: 16px;
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      background: var(--p-content-background, #fff);
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
    .chart {
      position: relative;
      display: flex;
      align-items: flex-end;
      gap: 8px;
      height: 150px;
      border-bottom: 1px solid #9ca3af;
    }
    .col {
      flex: 1;
      height: 100%;
      display: flex;
      flex-direction: column;
      justify-content: flex-end;
      align-items: center;
    }
    .col span {
      font-size: 0.75rem;
      color: #4b5563;
    }
    .bar {
      width: 100%;
      max-width: 56px;
      min-height: 2px;
    }
    .past {
      background: #e5e7eb;
    }
    .ok {
      background: #16a34a;
    }
    .now {
      background: #c4002a;
    }
    .target {
      position: absolute;
      left: 0;
      right: 0;
      border-top: 2px dashed #111827;
    }
    .note {
      color: #4b5563;
      font-size: 0.85rem;
      margin: 8px 0 0;
    }
    .work {
      margin: 0;
      padding-left: 18px;
      display: grid;
      gap: 6px;
    }
    .pct {
      padding: 1px 8px;
      border-radius: 10px;
      background: #fef6e4;
      color: #7c4a03;
      font-weight: 600;
    }
    .pct.done {
      background: #dcfce7;
      color: #14532d;
    }
    .link {
      display: inline-block;
      margin-top: 8px;
      color: #c4002a;
    }
    .sr-only {
      position: absolute;
      width: 1px;
      height: 1px;
      overflow: hidden;
      clip: rect(0, 0, 0, 0);
      white-space: nowrap;
    }
  `,
})
export class CheckinTrend {
  readonly kr = input.required<OkrKeyResultDetail>();
  /** Lịch sử check-in `DA_GUI`, mới nhất trước. */
  readonly history = input<KrCheckin[]>([]);
  /** Giá trị đang nhập (đã đọc được), `null` khi ô trống hoặc sai định dạng. */
  readonly current = input<number | null>(null);

  private readonly scale = computed(() => {
    const vals = [this.kr().targetValue, ...this.history().map((c) => c.value ?? 0)];
    const cur = this.current();
    if (cur !== null) vals.push(cur);
    return Math.max(...vals.map(Math.abs), 1) * 1.1;
  });

  protected readonly targetPos = computed(() => (this.kr().targetValue / this.scale()) * 100);

  protected readonly bars = computed<Bar[]>(() => {
    const past = this.history()
      .filter((c) => c.value !== null)
      .slice(0, 5)
      .reverse();
    const last = past.length - 1;
    const bars: Bar[] = past.map((c, i) => ({
      key: c.id,
      label: formatDate(c.checkinAt),
      value: c.value!,
      height: (Math.abs(c.value!) / this.scale()) * 100,
      current: false,
      tone: i === last ? 'ok' : 'past',
    }));
    const cur = this.current();
    if (cur !== null) {
      bars.push({
        key: 'now',
        label: 'Lần này',
        value: cur,
        height: (Math.abs(cur) / this.scale()) * 100,
        current: true,
        tone: 'now',
      });
    }
    return bars;
  });

  protected fmt(n: number) {
    return formatValue(n, this.kr().unit);
  }
}
