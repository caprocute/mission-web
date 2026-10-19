import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { CursorQuery, MAX_PAGE_LIMIT } from './paging';
import {
  AssigneeRole,
  BlockerReport,
  ChangeRequestDraft,
  ChangeRequestPreview,
  MyTasksPage,
  MyTasksQuickFilter,
  ProgressBatchItem,
  ProgressBatchResult,
  ProgressUpdate,
  TaskDetail,
  TaskGroup,
  TaskRegistration,
  TaskStatus,
  TaskSummary,
} from './models/task';

export interface MyTasksQuery extends CursorQuery {
  /** Bộ lọc nhanh SCR-0503; máy chủ quy đổi sang điều kiện trạng thái, cờ quá hạn, hạn. */
  filter?: MyTasksQuickFilter;
  status?: TaskStatus[];
  overdueOnly?: boolean;
  role?: AssigneeRole;
  group?: TaskGroup;
  keyResultId?: string;
  period?: string;
  q?: string;
}

/** Kết quả tác vụ dài (202 + `Location`, quy ước API mục 13). */
export interface AcceptedJob {
  location: string;
}

/**
 * Client cho API `/api/v1/tasks` của MOD-02 (mission-core).
 * Luôn gọi HTTP; khi `useMockApi` bật, `mockApiInterceptor` trả fixture `mock/tasks/` (WEB-008).
 */
@Injectable({ providedIn: 'root' })
export class TaskApi {
  private readonly http = inject(HttpClient);

  /**
   * SCR-0503 "Việc của tôi". TL-02 ghi `/api/v1/tasks?assignee=me&period=&filter=&cursor=&limit=20`; đường dẫn giữ
   * `/api/v1/tasks/mine` theo wiki/14 (điểm lệch chờ OpenAPI của WEB-007), tham số lọc theo TL-02.
   */
  myTasks(query: MyTasksQuery = {}): Observable<MyTasksPage> {
    const limit = Math.min(query.limit ?? 20, MAX_PAGE_LIMIT);
    let params = new HttpParams().set('limit', limit);
    if (query.cursor) params = params.set('cursor', query.cursor);
    if (query.period) params = params.set('period', query.period);
    if (query.filter && query.filter !== 'TAT_CA') params = params.set('filter', query.filter);
    if (query.status?.length) params = params.set('status', query.status.join(','));
    if (query.overdueOnly) params = params.set('overdue', true);
    if (query.role) params = params.set('role', query.role);
    if (query.group) params = params.set('group', query.group);
    if (query.keyResultId) params = params.set('kr', query.keyResultId);
    if (query.q) params = params.set('q', query.q);
    return this.http.get<MyTasksPage>('/api/v1/tasks/mine', { params });
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

  /** Nhận việc: `DA_GIAO` → `DANG_THUC_HIEN`, phát EVT-06 (QT-05 bước 8). */
  accept(task: TaskSummary): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(`/api/v1/tasks/${encodeURIComponent(task.id)}/accept`, null, {
      headers: { 'If-Match': `"${task.rowVersion}"` },
    });
  }

  /** Cập nhật tiến độ hàng loạt; mỗi dòng mang `rowVersion` riêng thay cho `If-Match`. */
  updateProgressBatch(items: ProgressBatchItem[]): Observable<ProgressBatchResult[]> {
    return this.http
      .post<{ results: ProgressBatchResult[] }>('/api/v1/tasks/progress-batch', { items })
      .pipe(map((r) => r.results));
  }

  /** Đăng ký nhiệm vụ từ danh mục: tạo nhiệm vụ `NHAP` có cờ đăng ký, phát EVT-21. */
  register(body: TaskRegistration): Observable<TaskSummary> {
    return this.http.post<TaskSummary>('/api/v1/tasks/registrations', body);
  }

  /** Xuất Excel danh sách đang lọc: máy chủ tạo tác vụ, trả 202 + `Location` của tác vụ. */
  exportMyTasks(query: Omit<MyTasksQuery, 'cursor' | 'limit'>): Observable<AcceptedJob> {
    return this.http
      .post('/api/v1/tasks/export', { assignee: 'me', ...query }, { observe: 'response' })
      .pipe(map((res: HttpResponse<unknown>) => ({ location: res.headers.get('Location') ?? '' })));
  }

  /** Báo điểm nghẽn (QT-06 bước 3), phát EVT-21 cho người giao; trả nhiệm vụ đã cập nhật. */
  reportBlocker(task: TaskSummary, body: BlockerReport): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(
      `/api/v1/tasks/${encodeURIComponent(task.id)}/blockers`,
      body,
    );
  }

  /** SCR-0602 "Xem trước hệ quả": tính C cho hai trường hợp khách quan, chủ quan (MSG-06-06). */
  previewChangeRequest(
    task: TaskSummary,
    body: ChangeRequestDraft,
  ): Observable<ChangeRequestPreview> {
    return this.http.post<ChangeRequestPreview>(
      `/api/v1/tasks/${encodeURIComponent(task.id)}/change-requests/preview`,
      body,
    );
  }

  /** Gửi đề nghị gia hạn, điều chỉnh, tạm dừng, huỷ (QT-06 bước 5): `CHO_DUYET`, EVT-21. */
  submitChangeRequest(task: TaskSummary, body: ChangeRequestDraft): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(
      `/api/v1/tasks/${encodeURIComponent(task.id)}/change-requests`,
      body,
    );
  }

  /** Rút lại đề nghị `CHO_DUYET` của mình → `DA_RUT`. */
  withdrawChangeRequest(task: TaskSummary, requestId: string): Observable<TaskDetail> {
    return this.http.post<TaskDetail>(
      `/api/v1/tasks/${encodeURIComponent(task.id)}/change-requests/${encodeURIComponent(requestId)}/withdraw`,
      null,
    );
  }
}
