import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { CursorPage, CursorQuery, MAX_PAGE_LIMIT } from './paging';
import {
  AssignBatchResult,
  FromDocumentRequest,
  SourceDoc,
  SourceDocStatus,
  SourceDocSummary,
  TaskSuggestion,
} from './models/source-doc';

export interface SourceDocQuery extends CursorQuery {
  status?: SourceDocStatus;
}

/**
 * Văn bản chỉ đạo đến và tạo nhiệm vụ từ văn bản (QT-05, SCR-0502) trên `/api/v1/tasks` của MOD-02.
 * Đường dẫn danh sách và xem văn bản là suy luận (TL-02 chỉ ghi các API ghi), xem wiki/05a.
 */
@Injectable({ providedIn: 'root' })
export class SourceDocApi {
  private readonly http = inject(HttpClient);

  /** "Nhiệm vụ / Văn bản đến" của đơn vị người dùng. */
  list(query: SourceDocQuery = {}): Observable<CursorPage<SourceDocSummary>> {
    let params = new HttpParams().set('limit', Math.min(query.limit ?? 20, MAX_PAGE_LIMIT));
    if (query.cursor) params = params.set('cursor', query.cursor);
    if (query.status) params = params.set('status', query.status);
    return this.http.get<CursorPage<SourceDocSummary>>('/api/v1/tasks/source-docs', { params });
  }

  get(id: string): Observable<SourceDoc> {
    return this.http.get<SourceDoc>(`/api/v1/tasks/source-docs/${encodeURIComponent(id)}`);
  }

  /** "Bỏ qua văn bản": đánh dấu đã xem, không tạo nhiệm vụ, ghi lý do. */
  dismiss(id: string, reason: string): Observable<SourceDoc> {
    return this.http.post<SourceDoc>(
      `/api/v1/tasks/source-docs/${encodeURIComponent(id)}/dismiss`,
      { reason },
    );
  }

  /**
   * "Xác nhận nhiệm vụ": tạo nhiệm vụ `NHAP` từ dữ liệu đã xác nhận, ghi danh sách ô đã sửa.
   * Idempotency-Key do interceptor gắn; trùng đoạn giao việc (BR-05-07) trả 409 kèm MSG-05-09.
   */
  fromDocument(body: FromDocumentRequest): Observable<TaskSuggestion> {
    return this.http.post<TaskSuggestion>('/api/v1/tasks/from-document', body);
  }

  /** "Gộp vào nhiệm vụ cũ": văn bản thành căn cứ bổ sung của nhiệm vụ cũ, phát EVT-21. */
  mergeInto(
    oldTaskId: string,
    body: { sourceDocId: string; suggestionId: string; paragraphNo: number },
  ): Observable<TaskSuggestion> {
    return this.http.post<TaskSuggestion>(
      `/api/v1/tasks/${encodeURIComponent(oldTaskId)}/source-docs`,
      body,
    );
  }

  /** "Xác nhận và giao": giao các nhiệm vụ đã xác nhận, EVT-04 cho mỗi nhiệm vụ. */
  assignBatch(taskIds: string[]): Observable<AssignBatchResult[]> {
    return this.http
      .post<{ results: AssignBatchResult[] }>('/api/v1/tasks/assign-batch', { taskIds })
      .pipe(map((r) => r.results));
  }
}
