import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from './api-path';
import {
  AiSourceList,
  AiSuggestion,
  AiSuggestionPage,
  AnalyticsDashboard,
  AnalyticsDashboardInput,
  AnalyticsDashboardPage,
  AnalyticsQuery,
  AnalyticsQueryResult,
  AnalyticsReportRequest,
  DashboardShareRequest,
  SemanticModel,
  TraceChain,
  TraceExportRequest,
  TraceObjectType,
} from './models/analytics';

const ifMatch = (d: { rowVersion: number }) => ({ 'If-Match': `"${d.rowVersion}"` });
const location = (r: HttpResponse<unknown>) => r.headers.get('Location') ?? '';

/**
 * Client phía đọc SVC-15 (`/api/v1/analytics`, QT-16, SCR-1601 … SCR-1603) theo `platform.yaml` (WEB-047,
 * WEB-048), cùng tóm tắt tuần của SVC-14. Mọi số liệu máy chủ lọc theo phạm vi cây tổ chức của người xem
 * (BR-16-01); dashboard chỉ là định nghĩa, không chứa số liệu. Ghi kèm `If-Match` khi sửa, `Idempotency-Key`
 * do interceptor gắn.
 */
@Injectable({ providedIn: 'root' })
export class AnalyticsApi {
  private readonly http = inject(HttpClient);

  /** Truy vấn chỉ số chuẩn (POST vì thân truy vấn phức tạp; vẫn là thao tác đọc). */
  query(q: AnalyticsQuery): Observable<AnalyticsQueryResult> {
    return this.http.post<AnalyticsQueryResult>(apiUrl('/analytics/query'), q);
  }

  /** Danh mục chỉ số và chiều của phiên bản lớp ngữ nghĩa hiện hành (SCR-1602 vùng 1). */
  semanticModel(): Observable<SemanticModel> {
    return this.http.get<SemanticModel>(apiUrl('/analytics/semantic-model'));
  }

  /** Dashboard của tôi (`mine`) hoặc cả dashboard được chia sẻ cho tôi. */
  dashboards(mine = false, cursor: string | null = null): Observable<AnalyticsDashboardPage> {
    let params = new HttpParams().set('limit', 50);
    if (mine) params = params.set('owner', 'me');
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<AnalyticsDashboardPage>(apiUrl('/analytics/dashboards'), { params });
  }

  /** Định nghĩa (và số liệu khi `includeData`) theo phạm vi của chính người xem (MSG-16-05). */
  dashboard(
    id: string,
    opts: { periodId?: string | null; unitId?: string | null; includeData?: boolean } = {},
  ): Observable<AnalyticsDashboard> {
    let params = new HttpParams().set('includeData', opts.includeData ?? true);
    if (opts.periodId) params = params.set('period', opts.periodId);
    if (opts.unitId) params = params.set('unit', opts.unitId);
    return this.http.get<AnalyticsDashboard>(apiUrl('/analytics/dashboards/{id}', { id }), {
      params,
    });
  }

  createDashboard(body: AnalyticsDashboardInput): Observable<AnalyticsDashboard> {
    return this.http.post<AnalyticsDashboard>(apiUrl('/analytics/dashboards'), body);
  }

  /** Thay toàn bộ định nghĩa; chỉ người tạo được sửa. */
  saveDashboard(d: AnalyticsDashboard, body: AnalyticsDashboardInput) {
    return this.http.put<AnalyticsDashboard>(
      apiUrl('/analytics/dashboards/{id}', { id: d.id }),
      body,
      {
        headers: ifMatch(d),
      },
    );
  }

  share(d: AnalyticsDashboard, body: DashboardShareRequest): Observable<AnalyticsDashboard> {
    return this.http.post<AnalyticsDashboard>(
      apiUrl('/analytics/dashboards/{id}/shares', { id: d.id }),
      body,
      { headers: ifMatch(d) },
    );
  }

  /** Xuất báo cáo PDF, XLSX chạy nền (202); trả `Location` của tác vụ. */
  exportReport(body: AnalyticsReportRequest): Observable<string> {
    return this.http
      .post(apiUrl('/analytics/reports'), body, { observe: 'response' })
      .pipe(map(location));
  }

  /** Chuỗi truy vết quanh một đối tượng (BR-16-03); mắt xích ngoài phạm vi có `masked`. */
  trace(objectType: TraceObjectType, id: string): Observable<TraceChain> {
    return this.http.get<TraceChain>(
      apiUrl('/analytics/trace/{objectType}/{id}', { objectType, id }),
    );
  }

  exportTrace(body: TraceExportRequest): Observable<string> {
    return this.http
      .post(apiUrl('/analytics/trace/exports'), body, { observe: 'response' })
      .pipe(map(location));
  }

  /** Bản tóm tắt tuần mới nhất trong phạm vi (SCR-1601 trường 8); `null` khi chưa có. */
  weeklySummary(opts: { periodId?: string | null; unitId?: string | null } = {}) {
    let params = new HttpParams().set('kind', 'TOM_TAT_TUAN').set('limit', 1);
    if (opts.periodId) params = params.set('period', opts.periodId);
    if (opts.unitId) params = params.set('unit', opts.unitId);
    return this.http
      .get<AiSuggestionPage>(apiUrl('/ai/suggestions'), { params })
      .pipe(map((p): AiSuggestion | null => p.items[0] ?? null));
  }

  /** "Xem dữ liệu đã dùng": nguồn của một tóm tắt, lọc lại theo quyền đọc hiện tại. */
  summarySources(suggestionId: string): Observable<AiSourceList> {
    return this.http.get<AiSourceList>(
      apiUrl('/ai/suggestions/{id}/sources', { id: suggestionId }),
    );
  }
}
