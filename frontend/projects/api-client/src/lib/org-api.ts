import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CursorPage, MAX_PAGE_LIMIT } from './paging';
import { OrgPerson, OrgUnit } from './models/org';

/**
 * Client tối thiểu cho `/api/v1/org` (bản sao cây tổ chức). WEB-028 cần danh sách cán bộ của
 * đơn vị để chọn chủ trì, phối hợp, người nghiệm thu và danh sách đơn vị trực thuộc để giao đồng loạt.
 */
@Injectable({ providedIn: 'root' })
export class OrgApi {
  private readonly http = inject(HttpClient);

  /** Cán bộ trong phạm vi đơn vị của người dùng (`scope=unit`). */
  persons(q?: string): Observable<CursorPage<OrgPerson>> {
    let params = new HttpParams().set('scope', 'unit').set('limit', MAX_PAGE_LIMIT);
    if (q) params = params.set('q', q);
    return this.http.get<CursorPage<OrgPerson>>('/api/v1/org/persons', { params });
  }

  /** Đơn vị: `unit` là đơn vị của người dùng và đơn vị ngang cấp, `children` là đơn vị cấp dưới. */
  units(scope: 'unit' | 'children'): Observable<CursorPage<OrgUnit>> {
    const params = new HttpParams().set('scope', scope).set('limit', MAX_PAGE_LIMIT);
    return this.http.get<CursorPage<OrgUnit>>('/api/v1/org/units', { params });
  }
}
