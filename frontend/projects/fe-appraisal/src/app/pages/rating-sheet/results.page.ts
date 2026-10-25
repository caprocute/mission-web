import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  APPRAISAL_FLOW_LABEL,
  AppraisalApi,
  AppraisalProfileRow,
  AppraisalProfileStatus,
  RATING_LEVEL_LABEL,
  RatingLevel,
} from '@vt/api-client/domains';
import { map } from 'rxjs';
import { APPRAISAL_CAN } from '../../appraisal-permissions';
import { vn } from '../self-assessment/self-assessment-rules';
import { PROFILE_STATUS_LABEL } from './rating-sheet-rules';
import { VtSkeletonComponent } from '@vt/design-system';

/** Hồ sơ của tôi chưa có kết quả để xem trên phiếu xếp loại. */
const NOT_YET: readonly AppraisalProfileStatus[] = ['CHUA_LAP', 'TU_DANH_GIA'];
/** Hồ sơ trong phạm vi mà thẩm định viên, cấp quyết định xem trên phiếu (wiki/15a `SCR-0903.xem`). */
const UNIT_VIEW: AppraisalProfileStatus[] = [
  'DA_THAM_DINH',
  'CHO_QUYET_DINH',
  'DA_QUYET_DINH',
  'DA_KY_SO',
];

/**
 * Trang "Kết quả xếp loại" (menu Đánh giá › Kết quả xếp loại, đường vào của SCR-0903; WEB-037): kết quả
 * của tôi (AC-06) và danh sách hồ sơ trong phạm vi: hồ sơ chờ đề xuất mức với quản lý trực tiếp (AC-05),
 * hồ sơ đã thẩm định với thẩm định viên và cấp quyết định (AC-07, AC-08). Chọn một dòng mở phiếu xếp loại.
 * Máy chủ lọc theo phạm vi ABAC; danh sách ở đây chỉ để chọn nhanh.
 */
@Component({
  selector: 'vt-results-page',
  imports: [VtSkeletonComponent, RouterLink],
  template: `
    <div class="top">
      <h1>Kết quả xếp loại</h1>
      @if (can('SCR-0904.xem')) {
        <a class="go" routerLink="../quyet-dinh">Quyết định xếp loại hàng loạt</a>
      }
      @if (can('SCR-1001.xem')) {
        <a class="go" routerLink="../xep-loai-nam">Xếp loại năm</a>
      }
      @if (can('SCR-1002.xem')) {
        <a class="go" routerLink="../phan-nhom">Phân nhóm triển vọng</a>
      }
    </div>

    <section class="card" aria-labelledby="mine-h">
      <h2 id="mine-h">Kết quả của tôi</h2>
      @if (mine.error()) {
        <p class="msg err" role="alert">
          Không tải được kết quả của đồng chí. Vui lòng tải lại trang.
        </p>
      } @else if (!mine.hasValue()) {
        <vt-skeleton variant="table" [columns]="4" label="Đang tải kết quả" />
      } @else {
        @for (r of mine.value(); track r.id) {
          <a class="row" [routerLink]="['./', r.id]" [attr.data-profile-id]="r.id">
            <span class="who">
              <b>{{ r.period.label }}</b>
              <span class="muted">{{ flowLabel[r.flow] }}</span>
            </span>
            <span class="lv">{{ level(r) }}</span>
            <span class="st">{{ statusLabel[r.status] }}</span>
          </a>
        } @empty {
          <p class="muted">Đồng chí chưa có kết quả xếp loại nào để xem.</p>
        }
      }
    </section>

    @if (unitTitle(); as title) {
      <section class="card" aria-labelledby="unit-h">
        <h2 id="unit-h">{{ title }}</h2>
        @if (unit.error()) {
          <p class="msg err" role="alert">
            Không tải được danh sách hồ sơ. Vui lòng tải lại trang.
          </p>
        } @else if (!unit.hasValue()) {
          <vt-skeleton variant="table" [columns]="4" label="Đang tải kết quả" />
        } @else {
          @for (r of unit.value(); track r.id) {
            <a class="row" [routerLink]="['./', r.id]" [attr.data-profile-id]="r.id">
              <span class="who">
                <b>{{ r.person.name }}</b>
                <span class="muted"
                  >{{ r.jobTitle }} · {{ r.period.label }} · {{ flowLabel[r.flow] }}</span
                >
              </span>
              <span class="lv">
                @if (r.totalScore !== null && r.totalScore !== undefined) {
                  {{ vn(r.totalScore) }} điểm ·
                }
                {{ level(r) }}
              </span>
              <span class="st">{{ statusLabel[r.status] }}</span>
            </a>
          } @empty {
            <p class="muted">Không có hồ sơ nào.</p>
          }
        }
      </section>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: 16px;
    }
    h1 {
      font-size: 1.5rem;
      margin: 0;
    }
    .top {
      display: flex;
      justify-content: space-between;
      gap: 12px;
      flex-wrap: wrap;
      align-items: center;
    }
    .go {
      padding: 8px 16px;
      border: 1px solid #9ca3af;
      border-radius: 6px;
      color: inherit;
      text-decoration: none;
    }
    h2 {
      font-size: 1.1rem;
      margin: 0 0 8px;
    }
    .card {
      border: 1px solid #e5e7eb;
      border-radius: 8px;
      padding: 16px;
      background: #fff;
    }
    .muted {
      color: #4b5563;
      margin: 0;
    }
    .msg.err {
      padding: 10px 14px;
      border-radius: 6px;
      background: #fee2e2;
      color: #991b1b;
    }
    .row {
      display: grid;
      grid-template-columns: minmax(0, 2fr) minmax(0, 1.4fr) minmax(0, 1fr);
      gap: 12px;
      align-items: center;
      padding: 12px 8px;
      border-top: 1px solid #f3f4f6;
      color: inherit;
      text-decoration: none;
    }
    .row:hover {
      background: #fdf2f4;
    }
    .row:focus-visible {
      outline: 3px solid #1d4ed8;
      outline-offset: -3px;
    }
    .who {
      display: grid;
      gap: 2px;
    }
    .st {
      justify-self: start;
      font-size: 0.85rem;
      padding: 2px 10px;
      border-radius: 10px;
      background: #f3f4f6;
    }
    @media (max-width: 640px) {
      .row {
        grid-template-columns: 1fr;
        gap: 4px;
      }
    }
  `,
})
export class ResultsPage {
  private readonly api = inject(AppraisalApi);
  protected readonly can = inject(APPRAISAL_CAN);

  protected readonly flowLabel = APPRAISAL_FLOW_LABEL;
  protected readonly statusLabel = PROFILE_STATUS_LABEL;
  protected readonly vn = vn;

  protected readonly mine = rxResource({
    stream: () =>
      this.api.myProfiles().pipe(map((p) => p.items.filter((r) => !NOT_YET.includes(r.status)))),
  });

  /** Quản lý trực tiếp thấy hồ sơ chờ đề xuất; thẩm định viên, cấp quyết định thấy hồ sơ đã thẩm định. */
  private readonly unitStatus = computed<AppraisalProfileStatus[] | null>(() => {
    if (this.can('SCR-0903.de-xuat')) return ['DA_THAM_DINH'];
    if (this.can('SCR-0902.xem') || this.can('SCR-0904.xem')) return UNIT_VIEW;
    return null;
  });
  protected readonly unitTitle = computed(() => {
    const s = this.unitStatus();
    if (!s) return null;
    return s.length === 1 ? 'Hồ sơ chờ đồng chí đề xuất mức' : 'Hồ sơ đã thẩm định trong phạm vi';
  });
  protected readonly unit = rxResource({
    params: () => this.unitStatus() ?? undefined,
    stream: ({ params }) =>
      this.api.profiles({ status: params, limit: 200 }).pipe(map((p) => p.items)),
  });

  /** Mức mới nhất đã có của hồ sơ: quyết định, đề xuất, thẩm định, rồi mức hệ thống. */
  protected level(r: AppraisalProfileRow): string {
    const l: RatingLevel | null | undefined =
      r.levels?.decided ?? r.levels?.proposed ?? r.levels?.appraised ?? r.autoRatingCode;
    return l ? RATING_LEVEL_LABEL[l] : 'Chưa có mức';
  }
}
