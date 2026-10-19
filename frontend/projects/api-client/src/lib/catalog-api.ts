import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { CursorPage, CursorQuery, MAX_PAGE_LIMIT } from './paging';
import {
  CatalogImportJob,
  CatalogItem,
  CatalogItemInput,
  CatalogItemSaveResult,
  CatalogItemStatus,
  CatalogItemsPage,
  Position,
  WorkCatalog,
} from './models/catalog';

export interface CatalogItemsQuery extends CursorQuery {
  /** Chỉ lấy mục áp dụng cho vị trí việc làm của người dùng hiện tại. */
  applicableToMe?: boolean;
  q?: string;
}

/** Lọc mục của một phiên bản danh mục (SCR-0401 vùng 1). */
export interface CatalogVersionItemsQuery extends CursorQuery {
  q?: string;
  positionId?: string;
  difficulty?: number;
  status?: CatalogItemStatus;
}

/**
 * Client cho API `/api/v1/catalog` của MOD-02 (QT-04): thư viện danh mục công việc theo Bảng 3 (SCR-0401),
 * danh sách mục áp dụng cho người dùng (SCR-0503 đăng ký, SCR-0501 giao việc).
 */
@Injectable({ providedIn: 'root' })
export class CatalogApi {
  private readonly http = inject(HttpClient);

  /** Mục đang áp dụng của phiên bản hiệu lực. */
  items(query: CatalogItemsQuery = {}): Observable<CursorPage<CatalogItem>> {
    let params = new HttpParams().set('limit', Math.min(query.limit ?? 50, MAX_PAGE_LIMIT));
    if (query.cursor) params = params.set('cursor', query.cursor);
    if (query.applicableToMe) params = params.set('applicable', 'me');
    if (query.q) params = params.set('q', query.q);
    return this.http.get<CursorPage<CatalogItem>>('/api/v1/catalog/items', { params });
  }

  /** Các phiên bản danh mục người dùng được xem, phiên bản đang áp dụng trước. */
  catalogs(): Observable<CursorPage<WorkCatalog>> {
    return this.http.get<CursorPage<WorkCatalog>>('/api/v1/catalog', {
      params: { limit: MAX_PAGE_LIMIT },
    });
  }

  /** Mục của một phiên bản, có lọc và tổng số. */
  versionItems(
    catalogId: string,
    query: CatalogVersionItemsQuery = {},
  ): Observable<CatalogItemsPage> {
    let params = new HttpParams().set('limit', Math.min(query.limit ?? 20, MAX_PAGE_LIMIT));
    if (query.cursor) params = params.set('cursor', query.cursor);
    if (query.q) params = params.set('q', query.q);
    if (query.positionId) params = params.set('position', query.positionId);
    if (query.difficulty) params = params.set('difficulty', query.difficulty);
    if (query.status) params = params.set('status', query.status);
    return this.http.get<CatalogItemsPage>(
      `/api/v1/catalog/${encodeURIComponent(catalogId)}/items`,
      { params },
    );
  }

  /** Vị trí việc làm có trong danh mục (`org.position`). */
  positions(catalogId: string): Observable<Position[]> {
    return this.http
      .get<{ items: Position[] }>(`/api/v1/catalog/${encodeURIComponent(catalogId)}/positions`)
      .pipe(map((r) => r.items));
  }

  /** Thêm công việc vào phiên bản nháp (POST). */
  addItem(catalogId: string, body: CatalogItemInput): Observable<CatalogItemSaveResult> {
    return this.http.post<CatalogItemSaveResult>(
      `/api/v1/catalog/${encodeURIComponent(catalogId)}/items`,
      body,
    );
  }

  /** Sửa mục (PUT, If-Match); mục đã dùng ở kỳ khoá được lưu ở phiên bản mới (BR-04-13). */
  updateItem(
    catalogId: string,
    item: CatalogItem,
    body: CatalogItemInput,
  ): Observable<CatalogItemSaveResult> {
    return this.http.put<CatalogItemSaveResult>(
      `/api/v1/catalog/${encodeURIComponent(catalogId)}/items/${encodeURIComponent(item.id)}`,
      body,
      { headers: { 'If-Match': `"${item.rowVersion}"` } },
    );
  }

  /** Nhân bản phiên bản: tạo phiên bản n+1 ở trạng thái nháp. */
  cloneVersion(catalogId: string): Observable<WorkCatalog> {
    return this.http.post<WorkCatalog>(
      `/api/v1/catalog/${encodeURIComponent(catalogId)}/versions`,
      null,
    );
  }

  /** Nhập từ Excel: tải tệp, máy chủ kiểm tra ở tác vụ nền, trả 202 + `Location` của tác vụ. */
  importExcel(catalogId: string, file: File): Observable<{ location: string }> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http
      .post(`/api/v1/catalog/${encodeURIComponent(catalogId)}/imports`, form, {
        observe: 'response',
      })
      .pipe(map((res: HttpResponse<unknown>) => ({ location: res.headers.get('Location') ?? '' })));
  }

  /** Trạng thái một tác vụ nhập theo `Location` đã nhận. */
  importJob(location: string): Observable<CatalogImportJob> {
    return this.http.get<CatalogImportJob>(location);
  }

  /** Kết quả nhập Excel gần nhất của danh mục (vùng 5); `null` khi chưa nhập lần nào. */
  latestImport(catalogId: string): Observable<CatalogImportJob | null> {
    return this.http.get<CatalogImportJob | null>(
      `/api/v1/catalog/${encodeURIComponent(catalogId)}/imports/latest`,
    );
  }

  /** Tệp các dòng lỗi của một lần nhập. */
  importErrors(jobId: string): Observable<Blob> {
    return this.http.get(`/api/v1/catalog/imports/${encodeURIComponent(jobId)}/errors`, {
      responseType: 'blob',
    });
  }
}
