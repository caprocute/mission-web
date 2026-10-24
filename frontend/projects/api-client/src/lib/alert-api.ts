import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from '@vt/api-client';
import {
  AlertActionInput,
  AlertFilter,
  AlertPage,
  AnomalyAlert,
  AnomalyThresholdSet,
  AnomalyThresholdUpdate,
} from './models/alert';

const ifMatch = (c: { rowVersion: number }) => ({ 'If-Match': `"${c.rowVersion}"` });

/**
 * Client cảnh báo bất thường của MOD-07 và ngưỡng phát hiện của config-service (QT-14, SCR-1401) theo
 * `oversight.yaml`, `config.yaml` (WEB-044). Mọi hành động xử lý gửi `If-Match` là `rowVersion` của cảnh
 * báo; `Idempotency-Key` do interceptor gắn. Máy chủ ẩn cảnh báo mà người xem là đối tượng (BR-14-03).
 */
@Injectable({ providedIn: 'root' })
export class AlertApi {
  private readonly http = inject(HttpClient);

  /** Một trang cảnh báo, sắp theo mức rồi thời gian, kèm thẻ phân bố 90 ngày (SCR-1401 trường 2, 3). */
  list(filter: AlertFilter = {}, cursor: string | null = null, limit = 50): Observable<AlertPage> {
    let params = new HttpParams().set('limit', limit);
    if (filter.periodId) params = params.set('period', filter.periodId);
    if (filter.unitId) params = params.set('unit', filter.unitId);
    if (filter.signal?.length) params = params.set('signal', filter.signal.join(','));
    if (filter.severity?.length) params = params.set('severity', filter.severity.join(','));
    if (filter.status?.length) params = params.set('status', filter.status.join(','));
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<AlertPage>(apiUrl('/oversight/alerts'), { params });
  }

  /** Chi tiết; lần mở đầu của người xử lý chuyển `MOI` sang `DANG_XEM` ở máy chủ. */
  get(id: string): Observable<AnomalyAlert> {
    return this.http.get<AnomalyAlert>(apiUrl('/oversight/alerts/{id}', { id }));
  }

  /** Xác nhận hợp lệ, giao kiểm tra, kết luận hoặc ghi chú; trả cảnh báo với trạng thái mới. */
  act(a: AnomalyAlert, input: AlertActionInput): Observable<AnomalyAlert> {
    return this.http.post<AnomalyAlert>(
      apiUrl('/oversight/alerts/{id}/actions', { id: a.id }),
      input,
      { headers: ifMatch(a) },
    );
  }

  thresholds(): Observable<AnomalyThresholdSet> {
    return this.http.get<AnomalyThresholdSet>(apiUrl('/config/anomaly-thresholds'));
  }

  /** Lưu phiên bản ngưỡng mới có lý do (BR-14-09). */
  saveThresholds(cur: AnomalyThresholdSet, body: AnomalyThresholdUpdate) {
    return this.http.put<AnomalyThresholdSet>(apiUrl('/config/anomaly-thresholds'), body, {
      headers: ifMatch(cur),
    });
  }
}
