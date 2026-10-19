import { Component, input, output } from '@angular/core';
import { OkrReviewSet } from '@vt/api-client';
import { formatDate } from '../../okr-ui';
import { CheckTag } from './check-tag';

/** Vùng 1 của SCR-0203: hàng chờ theo đơn vị (ngày trình, người trình, số O/KR, kiểm tra tổng hợp). */
@Component({
  selector: 'vt-review-queue',
  imports: [CheckTag],
  template: `
    <section class="box" aria-labelledby="q-h">
      <h2 id="q-h" class="sec"><span class="num" aria-hidden="true">1</span> Hàng chờ</h2>
      @if (!items().length) {
        <p class="muted" role="status">Không còn bộ OKR nào chờ đồng chí duyệt trong kỳ này.</p>
      } @else {
        <div class="hd" aria-hidden="true">
          <span>Đơn vị</span><span>O/KR</span><span>Kiểm tra</span>
        </div>
        <ul class="list">
          @for (s of items(); track s.id) {
            <li>
              <button
                type="button"
                class="row"
                [class.on]="s.id === selectedId()"
                [attr.aria-pressed]="s.id === selectedId()"
                (click)="selected.emit(s.id)"
              >
                <span class="unit">
                  <strong>{{ s.unit.name }}</strong>
                  <small>trình {{ day(s.submittedAt) }} · {{ s.submittedBy.name }}</small>
                </span>
                <span>{{ s.objectiveCount }} / {{ s.krCount }}</span>
                <vt-check-tag [check]="s.summary" />
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
    .muted,
    small {
      color: #4b5563;
    }
    .hd,
    .row {
      display: grid;
      grid-template-columns: minmax(0, 1fr) 56px 104px;
      gap: 8px;
      align-items: center;
      padding: 10px 12px;
    }
    .hd {
      background: #f9fafb;
      font-size: 0.875rem;
      font-weight: 600;
    }
    .list {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .row {
      width: 100%;
      border: 0;
      border-bottom: 1px solid #e5e7eb;
      background: #fff;
      font: inherit;
      text-align: left;
      cursor: pointer;
    }
    .row.on {
      background: #fdecef;
    }
    .row:focus-visible {
      outline: 2px solid #c4002a;
      outline-offset: -2px;
    }
    .unit {
      display: flex;
      flex-direction: column;
      min-width: 0;
    }
  `,
})
export class ReviewQueue {
  readonly items = input.required<OkrReviewSet[]>();
  readonly selectedId = input<string | null>(null);
  readonly selected = output<string>();

  protected readonly day = (iso: string) => formatDate(iso);
}
