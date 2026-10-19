import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { CursorQuery, MAX_PAGE_LIMIT } from './paging';
import { jobLocation } from './job-tracker';
import {
  CellLoad,
  TenancyModel,
  TenantDetail,
  TenantOverview,
  TenantPage,
  TenantStatus,
} from './models/tenant';

/** Lọc danh sách tenant (SCR-0101 vùng 1). */
export interface TenantQuery extends CursorQuery {
  q?: string;
  cell?: string;
  model?: TenancyModel;
  status?: TenantStatus;
}

function filterParams(query: Omit<TenantQuery, 'cursor' | 'limit'>): HttpParams {
  let params = new HttpParams();
  if (query.q) params = params.set('q', query.q);
  if (query.cell) params = params.set('cell', query.cell);
  if (query.model) params = params.set('model', query.model);
  if (query.status) params = params.set('status', query.status);
  return params;
}

/**
 * Client cho danh bạ tenant `/api/v1/config/tenants` của SVC-03 tenant-registry (QT-01, SCR-0101),
 * đọc qua MOD-08 ở vùng điều phối toàn cục; chỉ `platform_admin` trong phiên AAL2 được gọi.
 */
@Injectable({ providedIn: 'root' })
export class TenantApi {
  private readonly http = inject(HttpClient);

  /** Bốn thẻ tổng quan, tenant đang khởi tạo và việc cần xử lý. */
  overview(): Observable<TenantOverview> {
    return this.http.get<TenantOverview>('/api/v1/config/tenants/overview');
  }

  /** `GET /api/v1/config/tenants?cell=&model=&status=&cursor=`. */
  list(query: TenantQuery = {}): Observable<TenantPage> {
    let params = filterParams(query).set('limit', Math.min(query.limit ?? 8, MAX_PAGE_LIMIT));
    if (query.cursor) params = params.set('cursor', query.cursor);
    return this.http.get<TenantPage>('/api/v1/config/tenants', { params });
  }

  /** Tải theo cell (trường 9). */
  cells(): Observable<CellLoad[]> {
    return this.http
      .get<{ items: CellLoad[] }>('/api/v1/config/cells', { params: { limit: MAX_PAGE_LIMIT } })
      .pipe(map((r) => r.items));
  }

  get(id: string): Observable<TenantDetail> {
    return this.http.get<TenantDetail>(`/api/v1/config/tenants/${encodeURIComponent(id)}`);
  }

  /** Xuất danh sách theo bộ lọc hiện tại: 202 + `Location` của tác vụ (theo dõi bằng `JobTracker`). */
  export(query: Omit<TenantQuery, 'cursor' | 'limit'>): Observable<string> {
    return this.http
      .post('/api/v1/config/tenants/export', null, {
        params: filterParams(query),
        observe: 'response',
      })
      .pipe(map((res: HttpResponse<unknown>) => jobLocation(res) ?? ''));
  }

  /** Tải tệp kết quả của tác vụ xuất (`resultLocation` khi job `HOAN_TAT`). */
  download(resultLocation: string): Observable<Blob> {
    return this.http.get(resultLocation.replace(/^https?:\/\/[^/]+/, ''), {
      responseType: 'blob',
    });
  }
}
