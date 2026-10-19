import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from './api-path';
import {
  ComplaintAdjustment,
  ReopenImpact,
  ReopenRequest,
  ReopenRequestFilter,
  ReopenRequestInput,
} from './models/reopen';
import { CursorPage, MAX_PAGE_LIMIT } from './paging';

const ifMatch = (rowVersion: number) => ({ 'If-Match': `"${rowVersion}"` });

/**
 * Client cho yêu cầu mở lại kỳ hai người duyệt của MOD-07 (QT-13, SCR-1301) theo
 * `contracts/openapi/modules/oversight.yaml` (WEB-043). Thao tác ghi lên một yêu cầu gửi `If-Match` là
 * `rowVersion`; `Idempotency-Key` do interceptor gắn. Phê duyệt và từ chối cần phiên AAL2 (BR-13-04):
 * màn hình gọi xác thực bước lên trước, máy chủ vẫn trả 401 khi thiếu.
 */
@Injectable({ providedIn: 'root' })
export class ReopenApi {
  private readonly http = inject(HttpClient);

  list(filter: ReopenRequestFilter = {}): Observable<ReopenRequest[]> {
    let params = new HttpParams().set('limit', MAX_PAGE_LIMIT);
    if (filter.periodId) params = params.set('periodId', filter.periodId);
    if (filter.status?.length) params = params.set('status', filter.status.join(','));
    if (filter.awaitingMyApproval) params = params.set('awaitingMyApproval', true);
    return this.http
      .get<CursorPage<ReopenRequest>>(apiUrl('/oversight/reopen-requests'), { params })
      .pipe(map((p) => p.items));
  }

  get(id: string): Observable<ReopenRequest> {
    return this.http.get<ReopenRequest>(apiUrl('/oversight/reopen-requests/{id}', { id }));
  }

  /** "Tạo yêu cầu": lưu ở `CHO_DUYET_1`, OPA chỉ định hai người duyệt (EVT-21). */
  create(input: ReopenRequestInput): Observable<ReopenRequest> {
    return this.http.post<ReopenRequest>(apiUrl('/oversight/reopen-requests'), input);
  }

  /** "Tính thử ảnh hưởng": SVC-07 chế độ mô phỏng, không ghi (trường 8, BR-13-06). */
  simulate(id: string, adjustments?: ComplaintAdjustment[]): Observable<ReopenImpact> {
    return this.http.post<ReopenImpact>(
      apiUrl('/oversight/reopen-requests/{id}/simulate', { id }),
      adjustments?.length ? { adjustments } : {},
    );
  }

  /** "Xác thực MFA và duyệt": lượt 1 sang `CHO_DUYET_2`, lượt 2 mở lại kỳ (EVT-18, EVT-22). */
  approve(req: ReopenRequest, comment: string): Observable<ReopenRequest> {
    return this.http.post<ReopenRequest>(
      apiUrl('/oversight/reopen-requests/{id}/approvals', { id: req.id }),
      { comment },
      { headers: ifMatch(req.rowVersion) },
    );
  }

  reject(req: ReopenRequest, comment: string): Observable<ReopenRequest> {
    return this.http.post<ReopenRequest>(
      apiUrl('/oversight/reopen-requests/{id}/reject', { id: req.id }),
      { comment },
      { headers: ifMatch(req.rowVersion) },
    );
  }

  /** "Huỷ yêu cầu": người tạo, trước khi đủ phê duyệt, bắt buộc lý do; chuyển `DA_DONG`. */
  cancel(req: ReopenRequest, reason: string): Observable<ReopenRequest> {
    return this.http.post<ReopenRequest>(
      apiUrl('/oversight/reopen-requests/{id}/cancel', { id: req.id }),
      { reason },
      { headers: ifMatch(req.rowVersion) },
    );
  }
}
