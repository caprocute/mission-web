/*
 * SCR-1603 Đi xuống tới minh chứng, bước "bấm đơn vị" (QT-16 bước 5; WEB-047). Đường vào: bấm đơn vị ở
 * SCR-1601 hoặc một giá trị theo đơn vị ở dashboard tự dựng (SCR-1602). Số tổng hợp của đơn vị lấy từ lớp
 * ngữ nghĩa (`POST /analytics/query`: `score.diem_kpi_trung_binh`, `score.so_nguoi`, `task.ty_le_dung_han`,
 * lọc theo mã hoặc tên đơn vị). Kho phân tích không lưu định danh cán bộ (`person_key` đã ẩn danh) và hợp
 * đồng chưa có thao tác cho lãnh đạo liệt kê kết quả đánh giá từng cán bộ theo đơn vị (`listAppraisalProfiles`
 * chỉ cho người thẩm định, người quyết định), nên trang chưa có danh sách cán bộ; đề nghị ghi ở wiki/14.
 * Quyền: SCR-1603.xem; số liệu lọc theo phạm vi của người xem (BR-16-01).
 */
import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { AnalyticsApi, DIMENSION, MEASURE, cellNumber, resultRows } from '@vt/api-client/domains';
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
        <a routerLink="..">Bảng điều hành</a>
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
      <div class="box">
        <p>
          Bảng điều hành chỉ có số liệu tổng hợp của đơn vị. Kết quả đánh giá của từng cán bộ và
          chuỗi từ điểm đánh giá tới minh chứng xem ở hồ sơ đánh giá của cán bộ.
        </p>
        <p class="small muted">Số liệu cập nhật lúc {{ dt(res.value().dataAsOf) }}.</p>
      </div>
    } @else {
      <vt-skeleton variant="stats" label="Đang tải số liệu đơn vị" />
    }
  `,
  styleUrls: ['../dashboard.css'],
})
export class UnitDrillPage {
  private readonly api = inject(AnalyticsApi);
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
}
