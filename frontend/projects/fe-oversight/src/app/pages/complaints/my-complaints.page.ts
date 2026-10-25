import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  COMPLAINT_KIND_LABEL,
  COMPLAINT_STATUS_LABEL,
  ComplaintApi,
  ComplaintSummary,
  RATING_LEVEL_LABEL,
} from '@vt/api-client/domains';
import { MessageModule } from 'primeng/message';
import { TagModule } from 'primeng/tag';
import { OVERSIGHT_CAN, OVERSIGHT_USER_ID } from '../../oversight-permissions';
import { errorText, fmtDate, fmtDateTime } from '../../oversight-ui';
import { complaintSeverity, daysLeftText } from './complaint-ui';
import { VtSkeletonComponent } from '@vt/design-system';

/**
 * SCR-1201 phần theo dõi của người khiếu nại (QT-12 bước 1, 11): kết quả còn khiếu nại được kèm hạn (BR-12-01,
 * MSG-12-01) và các khiếu nại của tôi. Soạn, gửi, rút, bảo lưu ở trang `complaint-form.page.ts`.
 */
@Component({
  selector: 'vt-my-complaints-page',
  imports: [VtSkeletonComponent, RouterLink, MessageModule, TagModule],
  template: `
    <p class="crumb"><a routerLink="..">Giám sát, hậu kiểm</a></p>
    <h1>Khiếu nại, giải trình của tôi</h1>
    <p class="muted">
      Khiếu nại, giải trình bổ sung hoặc bảo lưu ý kiến về kết quả đánh giá đã ký số. Kết quả hiện
      hành vẫn có hiệu lực cho tới khi có kết luận.
    </p>

    <section class="box" aria-labelledby="el-h">
      <h2 class="h" id="el-h">Kết quả có thể khiếu nại</h2>
      @if (eligible.error()) {
        <p-message severity="error">{{ err(eligible.error()) }}</p-message>
      } @else if (eligible.value(); as list) {
        @if (!list.length) {
          <p class="muted">Không có kết quả nào đang trong thời hạn khiếu nại.</p>
        }
        <ul class="results">
          @for (e of list; track e.profileId) {
            <li>
              <p class="title">
                Xếp loại {{ e.period.label }}:
                <strong>{{ e.rating ? ratingLabel[e.rating] : '—' }}</strong>
                @if (e.score !== undefined) {
                  · {{ score(e.score) }} điểm
                }
              </p>
              <p class="muted small">
                Công bố {{ dt(e.publishedAt) }}
                @if (e.publishDecision) {
                  theo {{ e.publishDecision }}
                }
                · hạn khiếu nại {{ d(e.complaintDeadline) }}
                @if (daysText(e.workingDaysLeft); as t) {
                  ({{ t }})
                }
              </p>
              @if ((e.workingDaysLeft ?? 0) < 0) {
                <!-- MSG-12-01 -->
                <p-message severity="warn" styleClass="mt">
                  Đã quá thời hạn khiếu nại ({{ d(e.complaintDeadline) }}). Đồng chí vẫn có thể gửi
                  ý kiến dưới hình thức bảo lưu ý kiến.
                </p-message>
              }
              @if (can('SCR-1201.soan')) {
                <a
                  class="p-button p-button-sm"
                  routerLink="new"
                  [queryParams]="{ profileId: e.profileId }"
                  >{{ (e.workingDaysLeft ?? 0) < 0 ? 'Gửi ý kiến bảo lưu' : 'Soạn khiếu nại' }}</a
                >
              }
            </li>
          }
        </ul>
      } @else {
        <vt-skeleton variant="list" [rows]="4" label="Đang tải danh sách" />
      }
    </section>

    <section class="box" aria-labelledby="mine-h">
      <h2 class="h" id="mine-h">Khiếu nại đã soạn và đã gửi</h2>
      @if (mine.error()) {
        <p-message severity="error">{{ err(mine.error()) }}</p-message>
      } @else if (rows(); as list) {
        @if (!list.length) {
          <p class="muted">Đồng chí chưa có khiếu nại nào.</p>
        } @else {
          <div class="table-wrap">
            <table class="tbl">
              <caption class="sr-only">
                Khiếu nại của tôi
              </caption>
              <thead>
                <tr>
                  <th scope="col">Mã</th>
                  <th scope="col">Nội dung</th>
                  <th scope="col">Hình thức</th>
                  <th scope="col">Trạng thái</th>
                  <th scope="col">Gửi lúc</th>
                  <th scope="col">Hạn trả lời</th>
                </tr>
              </thead>
              <tbody>
                @for (c of list; track c.id) {
                  <tr>
                    <td>
                      <a [routerLink]="c.id">{{ c.code }}</a>
                    </td>
                    <td>{{ c.subjectLine }}</td>
                    <td>{{ kindLabel[c.kind] }}</td>
                    <td>
                      <p-tag [value]="statusText(c)" [severity]="severity(c)" />
                    </td>
                    <td>{{ c.filedAt ? dt(c.filedAt) : 'Chưa gửi' }}</td>
                    <td>
                      {{ c.responseDeadline ? d(c.responseDeadline) : '—' }}
                      @if (daysText(c.workingDaysLeft); as t) {
                        <span class="muted small block">{{ t }}</span>
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
        }
      } @else {
        <vt-skeleton variant="table" [columns]="5" label="Đang tải danh sách" />
      }
    </section>
  `,
  styleUrls: ['./complaints.css'],
  styles: `
    .results {
      list-style: none;
      padding: 0;
      margin: 0;
      display: grid;
      gap: 12px;
      grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
    }
    .results li {
      border: 1px solid var(--p-content-border-color, #e5e7eb);
      border-radius: 8px;
      padding: 12px;
      display: flex;
      flex-direction: column;
      gap: 8px;
      align-items: flex-start;
    }
    .title {
      margin: 0;
    }
    .block {
      display: block;
    }
  `,
})
export class MyComplaintsPage {
  private readonly api = inject(ComplaintApi);
  private readonly me = inject(OVERSIGHT_USER_ID);
  protected readonly can = inject(OVERSIGHT_CAN);

  protected readonly kindLabel = COMPLAINT_KIND_LABEL;
  protected readonly ratingLabel = RATING_LEVEL_LABEL;

  protected readonly eligible = rxResource({ stream: () => this.api.eligibleProfiles() });
  protected readonly mine = rxResource({ stream: () => this.api.list() });
  /** Máy chủ trả khiếu nại trong phạm vi; trang này chỉ giữ khiếu nại do chính tôi gửi hoặc soạn. */
  protected readonly rows = computed(() => {
    const list = this.mine.hasValue() ? this.mine.value() : undefined;
    const me = this.me();
    return list?.filter((c) => !me || c.complainant?.id === me);
  });

  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected d(iso?: string | null) {
    return fmtDate(iso);
  }
  protected score(n: number) {
    return n.toLocaleString('vi-VN', { maximumFractionDigits: 1 });
  }
  protected daysText(n?: number | null) {
    return daysLeftText(n);
  }
  protected statusText(c: ComplaintSummary) {
    return c.status ? COMPLAINT_STATUS_LABEL[c.status] : 'Nháp';
  }
  protected severity(c: ComplaintSummary) {
    return complaintSeverity(c.status);
  }
  protected err(e: unknown) {
    return errorText(e);
  }
}
