import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, delay, of, throwError } from 'rxjs';
import { CursorPage, CursorQuery, MAX_PAGE_LIMIT } from './paging';
import { RUNTIME_CONFIG } from './runtime-config';
import { ProgressUpdate, TaskDetail, TaskStatus, TaskSummary } from './models/task';
import { MOCK_TASKS } from './mock/mock-tasks';

export interface MyTasksQuery extends CursorQuery {
  status?: TaskStatus[];
  overdueOnly?: boolean;
  q?: string;
}

/**
 * Client cho API `/api/v1/tasks` của MOD-02 (mission-core).
 * Khi `useMockApi` bật, trả dữ liệu giả lập để phát triển giao diện độc lập với backend.
 */
@Injectable({ providedIn: 'root' })
export class TaskApi {
  private readonly http = inject(HttpClient);
  private readonly cfg = inject(RUNTIME_CONFIG);

  myTasks(query: MyTasksQuery = {}): Observable<CursorPage<TaskSummary>> {
    const limit = Math.min(query.limit ?? 50, MAX_PAGE_LIMIT);
    if (this.cfg.useMockApi) {
      let items = MOCK_TASKS.slice();
      if (query.status?.length) items = items.filter((t) => query.status!.includes(t.status));
      if (query.overdueOnly) items = items.filter((t) => t.overdue);
      if (query.q) {
        const q = query.q.toLowerCase();
        items = items.filter((t) => t.title.toLowerCase().includes(q) || t.code.toLowerCase().includes(q));
      }
      return of({ items: items.slice(0, limit), nextCursor: null }).pipe(delay(150));
    }
    let params = new HttpParams().set('limit', limit);
    if (query.cursor) params = params.set('cursor', query.cursor);
    if (query.status?.length) params = params.set('status', query.status.join(','));
    if (query.overdueOnly) params = params.set('overdue', true);
    if (query.q) params = params.set('q', query.q);
    return this.http.get<CursorPage<TaskSummary>>('/api/v1/tasks/mine', { params });
  }

  detail(id: string): Observable<TaskDetail> {
    if (this.cfg.useMockApi) {
      const t = MOCK_TASKS.find((x) => x.id === id);
      return t ? of(t).pipe(delay(100)) : throwError(() => new Error('Không tìm thấy nhiệm vụ'));
    }
    return this.http.get<TaskDetail>(`/api/v1/tasks/${id}`);
  }

  /** Cập nhật tiến độ (QT-06). Gửi `If-Match` để phát hiện xung đột ghi đồng thời (409). */
  updateProgress(task: TaskSummary, body: ProgressUpdate): Observable<TaskDetail> {
    if (this.cfg.useMockApi) {
      const t = MOCK_TASKS.find((x) => x.id === task.id)!;
      t.progress = body.percent;
      t.rowVersion += 1;
      if (t.status === 'DA_GIAO') t.status = 'DANG_THUC_HIEN';
      t.progressLog.unshift({ at: new Date().toISOString(), percent: body.percent, note: body.note, by: 'Tôi' });
      if (body.blocker) t.blockers.push({ id: crypto.randomUUID(), content: body.blocker, resolved: false });
      return of({ ...t }).pipe(delay(200));
    }
    return this.http.post<TaskDetail>(`/api/v1/tasks/${task.id}/progress`, body, {
      headers: { 'If-Match': `"${task.rowVersion}"` },
    });
  }
}
