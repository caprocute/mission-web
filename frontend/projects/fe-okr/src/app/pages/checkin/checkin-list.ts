import { Component, input, output } from '@angular/core';
import { CONFIDENCE_LABEL, OkrKeyResultListItem } from '@vt/api-client';
import { formatDate, formatValue } from '../../okr-ui';

export interface CheckinListRow {
  kr: OkrKeyResultListItem;
  /** Đã có check-in `DA_GUI` trong chu kỳ hiện tại. */
  done: boolean;
}

/** SCR-0301 vùng 1 "KR của tôi cần check-in" (trường 1): KR chưa check-in trước, rồi KR đã xong. */
@Component({
  selector: 'vt-checkin-list',
  template: `
    <section class="box" aria-labelledby="l-h">
      <h2 id="l-h" class="sec">KR của tôi cần check-in</h2>
      @if (!rows().length) {
        <p class="muted" role="status">
          Đồng chí chưa có KR nào thuộc mục tiêu đã duyệt trong kỳ này.
        </p>
      } @else {
        <ul class="plain">
          @for (r of rows(); track r.kr.id) {
            <li>
              <button
                type="button"
                class="row"
                [class.on]="r.kr.id === selectedId()"
                [attr.aria-pressed]="r.kr.id === selectedId()"
                (click)="selected.emit(r.kr.id)"
              >
                <span class="t"
                  ><strong>{{ r.kr.code }}</strong> · {{ r.kr.title }}</span
                >
                <small>
                  {{ value(r.kr) }}
                  @if (r.kr.lastCheckin; as l) {
                    · {{ r.done ? 'đã check-in' : 'lần cuối' }} {{ day(l.checkinAt) }} ·
                    {{ conf[l.confidence] }}
                  } @else {
                    · chưa check-in
                  }
                  @if (r.done) {
                    <span class="tag">Xong</span>
                  }
                </small>
              </button>
            </li>
          }
        </ul>
      }
    </section>
  `,
  styles: `
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
    .plain {
      list-style: none;
      padding: 0;
      margin: 0;
      display: grid;
      gap: 8px;
    }
    .row {
      display: grid;
      gap: 4px;
      width: 100%;
      text-align: left;
      font: inherit;
      padding: 10px 12px;
      border: 1px solid #d1d5db;
      border-radius: 8px;
      background: #fff;
      cursor: pointer;
    }
    .row.on {
      border: 2px solid #c4002a;
      background: #fdeef1;
    }
    .row:focus-visible {
      outline: 2px solid #c4002a;
      outline-offset: 2px;
    }
    small,
    .muted {
      color: #4b5563;
    }
    .tag {
      margin-left: 6px;
      padding: 1px 8px;
      border-radius: 10px;
      background: #dcfce7;
      color: #14532d;
      font-weight: 600;
    }
  `,
})
export class CheckinList {
  readonly rows = input.required<CheckinListRow[]>();
  readonly selectedId = input<string | null>(null);
  readonly selected = output<string>();
  protected readonly conf = CONFIDENCE_LABEL;
  protected readonly day = (iso: string) => formatDate(iso);
  protected value(k: OkrKeyResultListItem) {
    return formatValue(k.currentValue ?? null, k.unit ?? '');
  }
}
