import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-path';
import { CursorPage, MAX_PAGE_LIMIT } from './paging';
import { MyOrgProfile, OrgPerson, OrgUnit, WorkContext } from './models/org';

/**
 * Client `/api/v1/org` (SVC-04, bản sao cây tổ chức). WEB-028 cần danh sách cán bộ của đơn vị để
 * chọn chủ trì, phối hợp, người nghiệm thu và danh sách đơn vị trực thuộc để giao đồng loạt.
 * WEB-006 (QT-00) cần danh sách phân công và ghi ngữ cảnh phiên (SCR-0001 "Chọn phân công",
 * SCR-0002 "Đổi phân công"). Khi `useMockApi` bật, dữ liệu từ `mock/org/`.
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

  /** Hồ sơ của người dùng hiện tại kèm phân công còn hiệu lực (QT-00 bước 7). */
  me(): Observable<MyOrgProfile> {
    return this.http.get<MyOrgProfile>(apiUrl('/org/me'));
  }

  /** Ghi ngữ cảnh phiên (phân công đang dùng); máy chủ đưa claim `org` vào token sau khi làm mới. */
  setContext(assignmentId: string): Observable<WorkContext> {
    return this.http.put<WorkContext>(apiUrl('/org/me/context'), { assignmentId });
  }
}
