/*
 * SCR-1603 Đi xuống tới minh chứng, bước "bấm đơn vị, rồi cán bộ" (QT-16 bước 5; WEB-047). Đường vào: bấm
 * đơn vị ở SCR-1601 hoặc một giá trị theo đơn vị ở dashboard tự dựng (SCR-1602). Danh sách cán bộ của đơn vị
 * và điểm lấy từ lớp ngữ nghĩa (`POST /analytics/query`, chiều `person.name`, `score_snapshot.id`, lọc
 * theo mã hoặc tên đơn vị; suy luận, wiki/14); bấm cán bộ mở chuỗi truy vết từ điểm (`trace/DIEM/{id}`).
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
    <p class="muted">
      Kết quả đánh giá của cán bộ trong đơn vị. Chọn một cán bộ để xem chuỗi từ điểm đánh giá tới
      minh chứng.
    </p>
    @if (res.error()) {
      <p-message severity="error" role="alert">{{ err(res.error()) }}</p-message>
    } @else if (res.hasValue()) {
      @if (res.value().stale) {
        <p-message severity="warn" role="status">
          Số liệu cập nhật lúc {{ dt(res.value().dataAsOf) }}, chậm hơn thường lệ.
        </p-message>
      }
      <div class="box">
        <div class="table-wrap">
          <table class="tbl">
            <caption class="sr-only">
              Cán bộ của đơn vị và điểm đánh giá
            </caption>
            <thead>
              <tr>
                <th scope="col">Cán bộ</th>
                <th scope="col">Điểm KPI</th>
                <th scope="col">Tỷ lệ nhiệm vụ đúng hạn</th>
                <th scope="col"><span class="sr-only">Thao tác</span></th>
              </tr>
            </thead>
            <tbody>
              @for (p of people(); track p.scoreId) {
                <tr [attr.data-score-id]="p.scoreId">
                  <td>{{ p.name }}</td>
                  <td>{{ num(p.score) }}</td>
                  <td>{{ num(p.onTime) }}%</td>
                  <td>
                    <a [routerLink]="['../trace', 'DIEM', p.scoreId]"
                      >Xem chuỗi truy vết<span class="sr-only"> của {{ p.name }}</span></a
                    >
                  </td>
                </tr>
              } @empty {
                <tr>
                  <td colspan="4">Chưa có kết quả đánh giá của đơn vị trong kỳ này.</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="small muted">Số liệu cập nhật lúc {{ dt(res.value().dataAsOf) }}.</p>
      </div>
    } @else {
      <vt-skeleton variant="table" [columns]="5" label="Đang tải số liệu đơn vị" />
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
        measures: [MEASURE.diemKpi, MEASURE.dungHan],
        dimensions: [DIMENSION.person, DIMENSION.scoreId],
        filters: [
          params.id ? eq(DIMENSION.unitId, params.id) : eq(DIMENSION.unitName, params.name ?? ''),
        ],
        ...(params.period ? { periodId: params.period } : {}),
        limit: 200,
      }),
  });
  protected readonly people = computed(() =>
    this.res.hasValue()
      ? resultRows(this.res.value())
          .map((r) => ({
            name: String(r[DIMENSION.person]),
            scoreId: String(r[DIMENSION.scoreId]),
            score: cellNumber(r[MEASURE.diemKpi]),
            onTime: cellNumber(r[MEASURE.dungHan]),
          }))
          .sort((a, b) => (b.score ?? 0) - (a.score ?? 0))
      : [],
  );

  protected num(v: number | null) {
    return fmtNum(v);
  }
  protected dt(iso?: string | null) {
    return fmtDateTime(iso);
  }
  protected err(e: unknown) {
    return errorText(e, 'Chưa tải được số liệu của đơn vị.');
  }
}
