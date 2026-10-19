import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CursorPage, MAX_PAGE_LIMIT } from './paging';
import { KeyResultRef, KrCheckin, OkrTree, OkrTreeView } from './models/okr';

/**
 * Client `/api/v1/okr` (MOD-01). WEB-028 dùng danh sách KR để liên kết nhiệm vụ; WEB-021 thêm cây
 * mục tiêu SCR-0201, lịch sử check-in và xuất mẫu báo cáo. Khi `useMockApi` bật, dữ liệu từ `mock/okr/`.
 */
@Injectable({ providedIn: 'root' })
export class OkrApi {
  private readonly http = inject(HttpClient);

  /** KR của đơn vị trong kỳ, để liên kết nhiệm vụ (SCR-0501 trường 7). */
  keyResults(periodId: string): Observable<CursorPage<KeyResultRef>> {
    const params = new HttpParams()
      .set('period', periodId)
      .set('scope', 'unit')
      .set('limit', MAX_PAGE_LIMIT);
    return this.http.get<CursorPage<KeyResultRef>>('/api/v1/okr/key-results', { params });
  }

  /**
   * Cây mục tiêu của đơn vị trong kỳ (SCR-0201, `/api/v1/okr/objectives?unit=&period=&view=tree`).
   * Bỏ `unit` để lấy đơn vị của ngữ cảnh làm việc; bỏ `period` để lấy kỳ đang `MO`.
   */
  tree(query: { unit?: string; period?: string } = {}): Observable<OkrTree> {
    let params = new HttpParams().set('view', 'tree');
    if (query.unit) params = params.set('unit', query.unit);
    if (query.period) params = params.set('period', query.period);
    return this.http.get<OkrTree>('/api/v1/okr/objectives', { params });
  }

  /** Lịch sử check-in của KR, mới nhất trước (`/api/v1/okr/key-results/{id}/checkins?cursor=`). */
  checkins(krId: string, cursor?: string, limit = 10): Observable<CursorPage<KrCheckin>> {
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<CursorPage<KrCheckin>>(
      `/api/v1/okr/key-results/${encodeURIComponent(krId)}/checkins`,
      { params },
    );
  }

  /**
   * "Xuất mẫu" báo cáo OKR: tác vụ dài, trả 202 + `Location` để theo dõi bằng `JobTracker`
   * (`/api/v1/okr/objectives/export`).
   */
  exportObjectives(body: {
    unitId: string;
    periodId: string;
    view: OkrTreeView;
  }): Observable<HttpResponse<unknown>> {
    return this.http.post('/api/v1/okr/objectives/export', body, { observe: 'response' });
  }
}
