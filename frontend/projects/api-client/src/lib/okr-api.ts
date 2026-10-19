import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CursorPage, MAX_PAGE_LIMIT } from './paging';
import { KeyResultRef } from './models/okr';

/** Client tối thiểu cho `/api/v1/okr`; WEB-021 mở rộng cho cây OKR. */
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
}
