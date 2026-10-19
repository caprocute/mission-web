import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CursorPage, MAX_PAGE_LIMIT } from './paging';
import { PlanItemRef } from './models/plan';

/** Client tối thiểu cho `/api/v1/plans`; WEB-026 mở rộng cho SCR-0402. */
@Injectable({ providedIn: 'root' })
export class PlanApi {
  private readonly http = inject(HttpClient);

  /** Dòng của phiên bản kế hoạch `DA_DUYET` hiệu lực trong kỳ, chưa sinh nhiệm vụ đã giao. */
  approvedItems(periodId: string): Observable<CursorPage<PlanItemRef>> {
    const params = new HttpParams()
      .set('period', periodId)
      .set('status', 'DA_DUYET')
      .set('limit', MAX_PAGE_LIMIT);
    return this.http.get<CursorPage<PlanItemRef>>('/api/v1/plans/items', { params });
  }
}
