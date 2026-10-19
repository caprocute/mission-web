import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CursorPage, CursorQuery, MAX_PAGE_LIMIT } from './paging';
import { ProgressUpdate, TaskDetail, TaskStatus, TaskSummary } from './models/task';

export interface MyTasksQuery extends CursorQuery {
  status?: TaskStatus[];
  overdueOnly?: boolean;
  q?: string;
}

/**
 * Client cho API `/api/v1/tasks` của MOD-02 (mission-core).
 * Luôn gọi HTTP; khi `useMockApi` bật, `mockApiInterceptor` trả fixture `mock/tasks/` (WEB-008).
 */
@Injectable({ providedIn: 'root' })
export class TaskApi {
  private readonly http = inject(HttpClient);

  myTasks(query: MyTasksQuery = {}): Observable<CursorPage<TaskSummary>> {
    const limit = Math.min(query.limit ?? 50, MAX_PAGE_LIMIT);
    let params = new HttpParams().set('limit', limit);
    if (query.cursor) params = params.set('cursor', query.cursor);
    if (query.status?.length) params = params.set('status', query.status.join(','));
    if (query.overdueOnly) params = params.set('overdue', true);
    if (query.q) params = params.set('q', query.q);
    return this.http.get<CursorPage<TaskSummary>>('/api/v1/tasks/mine', { params });
  }

  detail(id: string): Observable<TaskDetail> {
    return this.http.get<TaskDetail>(`/api/v1/tasks/${encodeURIComponent(id)}`);
  }

  /** Cập nhật tiến độ (QT-06). Gửi `If-Match` để phát hiện xung đột ghi đồng thời (409). */
  updateProgress(task: TaskSummary, body: ProgressUpdate): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(
      `/api/v1/tasks/${encodeURIComponent(task.id)}/progress`,
      body,
      {
        headers: { 'If-Match': `"${task.rowVersion}"` },
      },
    );
  }
}
