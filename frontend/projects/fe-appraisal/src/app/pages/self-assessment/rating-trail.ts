import { Component, input } from '@angular/core';
import { AppraisalRatingProposal, RATING_LEVEL_LABEL } from '@vt/api-client/domains';
import { vnTime } from './self-assessment-rules';

const KIND: Record<AppraisalRatingProposal['levelKind'], string> = {
  TU_DE_XUAT: 'Tự đề xuất',
  THAM_DINH: 'Thẩm định',
  DE_XUAT: 'Đề xuất',
  QUYET_DINH: 'Quyết định',
};

/** SCR-0901 thẻ "Lịch sử": vết bốn mức của hồ sơ (BR-09-12), theo thời gian. */
@Component({
  selector: 'vt-rating-trail',
  template: `
    <section class="box" aria-labelledby="rt-h">
      <h2 id="rt-h">Lịch sử mức xếp loại</h2>
      @if (!items().length) {
        <p class="muted">Chưa có mức nào được ghi. Mức tự đề xuất được ghi khi phiếu ký số xong.</p>
      } @else {
        <ol>
          @for (r of items(); track r.id) {
            <li>
              <b>{{ kind[r.levelKind] }}</b
              >: {{ label[r.ratingCode] }} ({{ r.ratingCode }}) · {{ r.actor.name }} ·
              {{ when(r.proposedAt) }}
              @if (r.signedAt) {
                · đã ký số
              }
              @if (!r.isCurrent) {
                · không còn hiện hành
              }
              @if (r.reason) {
                <p class="muted">Giải trình: {{ r.reason }}</p>
              }
            </li>
          }
        </ol>
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
    h2 {
      font-size: 1.05rem;
      margin: 0 0 8px;
    }
    li {
      margin-bottom: 8px;
    }
    .muted {
      color: #4b5563;
      margin: 4px 0;
    }
  `,
})
export class RatingTrail {
  readonly items = input<AppraisalRatingProposal[]>([]);
  protected readonly kind = KIND;
  protected readonly label = RATING_LEVEL_LABEL;
  protected readonly when = vnTime;
}
