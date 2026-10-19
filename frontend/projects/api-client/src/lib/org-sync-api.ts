import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from './api-path';
import { jobLocation } from './job-tracker';
import {
  DiscrepancyPage,
  DiscrepancyResolution,
  DiscrepancySeverity,
  OrgDiscrepancy,
  OrgSyncRun,
  OrgSyncSchedule,
  ResolveResult,
  SyncSource,
} from './models/org-sync';
import { CursorPage, CursorQuery, MAX_PAGE_LIMIT } from './paging';

export interface DiscrepancyQuery extends CursorQuery {
  field?: string;
  severity?: DiscrepancySeverity;
}

/** Lịch kèm ETag để ghi lại bằng `If-Match`. */
export interface ScheduleSet {
  items: OrgSyncSchedule[];
  etag: string | null;
}

/**
 * Client đồng bộ và đối soát nhân sự của SVC-04 (SCR-0104) theo `org.yaml`. Dữ liệu ngoài chỉ vào
 * qua integration-gateway (BR-01-11); ghi kèm `Idempotency-Key` (interceptor tự thêm); xử lý hàng
 * loạt cần phiên AAL2, máy chủ trả 401 step-up khi thiếu và interceptor của `@vt/auth` xử lý.
 */
@Injectable({ providedIn: 'root' })
export class OrgSyncApi {
  private readonly http = inject(HttpClient);

  runs(q: CursorQuery & { connector?: SyncSource } = {}): Observable<CursorPage<OrgSyncRun>> {
    let params = new HttpParams().set('limit', Math.min(q.limit ?? 20, MAX_PAGE_LIMIT));
    if (q.connector) params = params.set('connector', q.connector);
    if (q.cursor) params = params.set('cursor', q.cursor);
    return this.http.get<CursorPage<OrgSyncRun>>(apiUrl('/org/sync-runs'), { params });
  }

  /** Đồng bộ ngay một nguồn hoặc mọi nguồn: 202 + `Location` (theo dõi bằng `JobTracker`). */
  syncNow(connector?: SyncSource): Observable<string> {
    return this.http
      .post(apiUrl('/org/sync-runs'), connector ? { connector } : {}, { observe: 'response' })
      .pipe(map((res: HttpResponse<unknown>) => jobLocation(res) ?? ''));
  }

  /** Chạy lại thông điệp lỗi trong `integ.dead_letter`: 202 + `Location`. */
  retry(runId: string): Observable<string> {
    return this.http
      .post(apiUrl('/org/sync-runs/{id}/retry', { id: runId }), null, { observe: 'response' })
      .pipe(map((res: HttpResponse<unknown>) => jobLocation(res) ?? ''));
  }

  schedules(): Observable<ScheduleSet> {
    return this.http
      .get<{ items: OrgSyncSchedule[] }>(apiUrl('/org/sync-schedules'), { observe: 'response' })
      .pipe(map((res) => ({ items: res.body?.items ?? [], etag: res.headers.get('ETag') })));
  }

  /** Lưu cả bộ lịch kèm `If-Match`; 409 khi người khác đã đổi, 428 khi thiếu ETag. */
  saveSchedules(cur: ScheduleSet, items: OrgSyncSchedule[]): Observable<ScheduleSet> {
    const headers: Record<string, string> = cur.etag ? { 'If-Match': cur.etag } : {};
    return this.http
      .put<{ items: OrgSyncSchedule[] }>(
        apiUrl('/org/sync-schedules'),
        { items },
        { headers, observe: 'response' },
      )
      .pipe(map((res) => ({ items: res.body?.items ?? [], etag: res.headers.get('ETag') })));
  }

  /** Lệch chưa xử lý, lọc theo trường và mức, phân trang con trỏ. */
  discrepancies(q: DiscrepancyQuery = {}): Observable<DiscrepancyPage> {
    let params = new HttpParams().set('limit', Math.min(q.limit ?? 20, MAX_PAGE_LIMIT));
    if (q.field) params = params.set('field', q.field);
    if (q.severity) params = params.set('severity', q.severity);
    if (q.cursor) params = params.set('cursor', q.cursor);
    return this.http.get<DiscrepancyPage>(apiUrl('/org/discrepancies'), { params });
  }

  /** Lấy theo nguồn hoặc giữ nội bộ cho các dòng đã chọn (EVT-15, EVT-16), tối đa 200 dòng. */
  resolve(
    ids: string[],
    resolution: DiscrepancyResolution,
    note?: string,
  ): Observable<ResolveResult> {
    return this.http.post<ResolveResult>(apiUrl('/org/discrepancies/resolve'), {
      ids,
      resolution,
      note,
    });
  }

  /** Ánh xạ thủ công dòng không khớp người vào hồ sơ đúng (`integ.mapping_xref`, EVT-16). */
  map(id: string, personId: string): Observable<OrgDiscrepancy> {
    return this.http.post<OrgDiscrepancy>(apiUrl('/org/discrepancies/{id}/map', { id }), {
      personId,
    });
  }

  /** Ghi chú, giao cán bộ tổ chức đơn vị kiểm tra (EVT-21). */
  assign(id: string, assigneeId: string, note?: string): Observable<OrgDiscrepancy> {
    return this.http.post<OrgDiscrepancy>(apiUrl('/org/discrepancies/{id}/assign', { id }), {
      assigneeId,
      note,
    });
  }
}
