import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CursorPage, CursorQuery, MAX_PAGE_LIMIT } from './paging';
import { CatalogItem } from './models/catalog';

export interface CatalogItemsQuery extends CursorQuery {
  /** Chỉ lấy mục áp dụng cho vị trí việc làm của người dùng hiện tại. */
  applicableToMe?: boolean;
  q?: string;
}

/**
 * Client cho API `/api/v1/catalog` của MOD-02 (QT-04, SCR-0401).
 * WEB-030 chỉ dùng danh sách mục áp dụng cho người dùng để đăng ký nhiệm vụ trên SCR-0503.
 */
@Injectable({ providedIn: 'root' })
export class CatalogApi {
  private readonly http = inject(HttpClient);

  items(query: CatalogItemsQuery = {}): Observable<CursorPage<CatalogItem>> {
    let params = new HttpParams().set('limit', Math.min(query.limit ?? 50, MAX_PAGE_LIMIT));
    if (query.cursor) params = params.set('cursor', query.cursor);
    if (query.applicableToMe) params = params.set('applicable', 'me');
    if (query.q) params = params.set('q', query.q);
    return this.http.get<CursorPage<CatalogItem>>('/api/v1/catalog/items', { params });
  }
}
