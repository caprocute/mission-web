/*
 * SCR-1603 Đi xuống tới minh chứng, bước "bấm đơn vị" (QT-16 bước 5; WEB-047). Đường vào: bấm đơn vị ở
 * SCR-1601 hoặc một giá trị theo đơn vị ở dashboard tự dựng (SCR-1602). Số tổng hợp của đơn vị lấy từ lớp
 * ngữ nghĩa (`POST /analytics/query`: `score.diem_kpi_trung_binh`, `score.so_nguoi`, `task.ty_le_dung_han`,
 * lọc theo mã hoặc tên đơn vị). Kho phân tích không lưu định danh cán bộ (`person_key` đã ẩn danh), nên danh
 * sách cán bộ lấy từ MOD-04 (`GET /appraisals/unit-results`, `listUnitAppraisalResults`, AP-09, wiki/14): mỗi
 * dòng có điểm, mức, trạng thái và mã ảnh chụp điểm hiện hành để mở chuỗi truy vết (`trace/DIEM/<mã>`). Danh
 * sách cần mã đơn vị và kỳ; đi xuống từ dashboard tự dựng chỉ có tên đơn vị nên chỉ có số tổng hợp.
 * Quyền: SCR-1603.xem; số liệu và danh sách lọc theo phạm vi của người xem (BR-16-01).
 */
import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import {
  APPRAISAL_PROFILE_STATUS_LABEL,
  AnalyticsApi,
  AppraisalApi,
  DIMENSION,
  MEASURE,
  RATING_LEVEL_LABEL,
  UnitAppraisalResultRow,
  cellNumber,
  resultRows,
} from '@vt/api-client/domains';
import { of } from 'rxjs';
import { MessageModule } from 'primeng/message';
import { DASHBOARD_CAN } from '../dashboard-permissions';
import { eq, errorText, fmtDateTime, fmtNum } from '../dashboard-ui';
import { VtSkeletonComponent } from '@vt/design-system';

@Component({
  selector: 'vt-unit-drill-page',
  imports: [VtSkeletonComponent, RouterLink, MessageModule],
  template: `
    <p class="crumb">
      @if (can('SCR-1601.xem')) {
        <a routerLink=".." [queryParams]="period() ? { period: period() } : {}">Bảng điều hành</a>
      }
    </p>
    <h1>{{ name() || 'Đơn vị' }}</h1>
    <p class="muted">Kết quả đánh giá và nhiệm vụ của đơn vị trong kỳ.</p>
    @if (res.error()) {
      <p-message severity="error" role="alert">{{ err(res.error()) }}</p-message>
    } @else if (res.hasValue()) {
      @if (res.value().stale) {
        <p-message severity="warn" role="status">
          Số liệu cập nhật lúc {{ dt(res.value().dataAsOf) }}, chậm hơn thường lệ.
        </p-message>
      }
      <section class="grid kpis" aria-label="Số liệu của đơn vị">
        <div class="kpi">
          <p class="t">Điểm KPI trung bình</p>
          <p class="v">{{ num(totals().score) }}</p>
        </div>
        <div class="kpi">
          <p class="t">Số cán bộ đã có điểm</p>
          <p class="v">{{ num(totals().people, 0) }}</p>
        </div>
        <div class="kpi">
          <p class="t">Tỷ lệ nhiệm vụ đúng hạn</p>
          <p class="v">{{ num(totals().onTime) }}%</p>
        </div>
      </section>
      <p class="small muted">Số liệu cập nhật lúc {{ dt(res.value().dataAsOf) }}.</p>
    } @else {
      <vt-skeleton variant="stats" label="Đang tải số liệu đơn vị" />
    }
    @if (showPeople()) {
      <section class="box" aria-labelledby="ud-people-h">
        <h2 class="h" id="ud-people-h">Kết quả đánh giá của cán bộ</h2>
        @if (people.error()) {
          <p-message severity="error" role="alert">{{ peopleErr(people.error()) }}</p-message>
        } @else if (people.hasValue()) {
          @if (people.value().items.length) {
            <div class="table-wrap">
              <table class="tbl people">
                <caption class="sr-only">
                  Kết quả đánh giá của từng cán bộ trong đơn vị
                </caption>
                <thead>
                  <tr>
                    <th scope="col">Cán bộ</th>
                    <th scope="col">Đơn vị</th>
                    <th scope="col">Điểm</th>
                    <th scope="col">Mức xếp loại</th>
                    <th scope="col">Trạng thái hồ sơ</th>
                    <th scope="col">Truy vết</th>
                  </tr>
                </thead>
                <tbody>
                  @for (r of people.value().items; track r.profileId) {
                    <tr [attr.data-profile-id]="r.profileId">
                      <th scope="row">{{ r.person.name }}</th>
                      <td>{{ r.orgUnit.name }}</td>
                      <td>{{ num(r.totalScore ?? null) }}</td>
                      <td>{{ rating(r) }}</td>
                      <td>{{ statusLabel(r) }}</td>
                      <td>
                        @if (r.scoreSnapshotId) {
                          <a [routerLink]="['/dashboard/trace', 'DIEM', r.scoreSnapshotId]"
                            >Xem chuỗi truy vết của {{ r.person.name }}</a
                          >
                        } @else {
                          Chưa có điểm
                        }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
            @if (people.value().nextCursor) {
              <p class="small muted">
                Đang hiện {{ people.value().items.length }} cán bộ đầu tiên. Xem đầy đủ ở hồ sơ đánh
                giá của đơn vị.
              </p>
            }
          } @else {
            <p class="muted">Đơn vị chưa có cán bộ nào có hồ sơ đánh giá trong kỳ này.</p>
          }
        } @else {
          <vt-skeleton variant="table" [columns]="6" label="Đang tải kết quả đánh giá của cán bộ" />
        }
      </section>
    } @else {
      <div class="box">
        <p>
          Bảng điều hành chỉ có số liệu tổng hợp của đơn vị. Kết quả đánh giá của từng cán bộ và
          chuỗi từ điểm đánh giá tới minh chứng xem ở hồ sơ đánh giá của cán bộ.
        </p>
      </div>
    }
  `,
  styleUrls: ['../dashboard.css'],
})
export class UnitDrillPage {
  private readonly api = inject(AnalyticsApi);
  private readonly appraisals = inject(AppraisalApi);
  protected readonly can = inject(DASHBOARD_CAN);
  /** Mã đơn vị (từ SCR-1601) hoặc tên đơn vị (từ dashboard tự dựng). */
  readonly id = input<string | null>(null);
  readonly name = input<string | null>(null);
  readonly period = input<string | null>(null);

  protected readonly res = rxResource({
    params: () => ({ id: this.id(), name: this.name(), period: this.period() }),
    stream: ({ params }) =>
      this.api.query({
        measures: [MEASURE.diemKpi, MEASURE.soNguoiCoDiem, MEASURE.dungHan],
        filters: [
          params.id ? eq(DIMENSION.unitId, params.id) : eq(DIMENSION.unitName, params.name ?? ''),
        ],
        ...(params.period ? { periodId: params.period } : {}),
      }),
  });
  /** Danh sách cán bộ cần mã đơn vị và kỳ (AP-09); chỉ hiện khi có quyền xem chuỗi truy vết. */
  protected readonly showPeople = computed(
    () => !!this.id() && !!this.period() && this.can('SCR-1603.xem'),
  );
  protected readonly people = rxResource({
    params: () => ({ unitId: this.id(), period: this.period(), show: this.showPeople() }),
    stream: ({ params }) =>
      params.show && params.unitId && params.period
        ? this.appraisals.unitResults({ unitId: params.unitId, period: params.period, limit: 200 })
        : of({ items: [] as UnitAppraisalResultRow[], nextCursor: null }),
  });
  protected readonly totals = computed(() => {
    const r = this.res.hasValue() ? (resultRows(this.res.value())[0] ?? {}) : {};
    return {
      score: cellNumber(r[MEASURE.diemKpi]),
      people: cellNumber(r[MEASURE.soNguoiCoDiem]),
      onTime: cellNumber(r[MEASURE.dungHan]),
    };
  });

  protected num(v: number | null, digits = 1) {
    return fmtNum(v, digits);
  }
  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected err(e: unknown) {
    return errorText(e, 'Chưa tải được số liệu của đơn vị.');
  }
  protected peopleErr(e: unknown) {
    return errorText(e, 'Chưa tải được kết quả đánh giá của cán bộ trong đơn vị.');
  }
  /** Mức đã quyết định; chưa quyết định thì ghi mức hệ thống gợi ý (YC-PT-10: chỉ tên mức). */
  protected rating(r: UnitAppraisalResultRow) {
    if (r.ratingCode) return RATING_LEVEL_LABEL[r.ratingCode];
    if (r.autoRatingCode) return `${RATING_LEVEL_LABEL[r.autoRatingCode]} (hệ thống gợi ý)`;
    return 'Chưa có';
  }
  protected statusLabel(r: UnitAppraisalResultRow) {
    return APPRAISAL_PROFILE_STATUS_LABEL[r.status] ?? 'Chưa rõ';
  }
}
