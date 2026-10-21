import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-path';
import {
  WorkflowDiagram,
  WorkflowHistory,
  WorkflowInboxItem,
  WorkflowInstancePage,
  WorkflowInstanceQuery,
  WorkflowTaskExtend,
  WorkflowTaskReassign,
} from './models/workflow-instance';

/**
 * Hợp đồng chưa trả `rowVersion` của bước trong danh sách tiến trình nên ghi kèm `If-Match: *`
 * (RFC 9110: chỉ cần bản ghi tồn tại); máy chủ vẫn chặn nếu bước đã hoàn thành (wiki/14).
 */
const IF_EXISTS = { headers: { 'If-Match': '*' } };

/**
 * Client giám sát tiến trình theo mẫu (SVC-06, QT-15, BS-01 SCR-1506) theo `platform.yaml`:
 * `/api/v1/workflow/instances`, `/api/v1/workflow/tasks/{taskId}/reassign|extend`. Chỉ hai thao tác
 * ghi có kiểm soát; không có API sửa biến tiến trình hay nhảy bước. Mọi thao tác ghi sổ cái ở máy chủ.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowInstanceApi {
  private readonly http = inject(HttpClient);

  list(q: WorkflowInstanceQuery = {}): Observable<WorkflowInstancePage> {
    const params: Record<string, string | number | boolean> = { limit: q.limit ?? 20 };
    if (q.template) params['template'] = q.template;
    if (q.stuck) params['stuck'] = true;
    if (q.cell) params['cell'] = q.cell;
    if (q.cursor) params['cursor'] = q.cursor;
    return this.http.get<WorkflowInstancePage>(apiUrl('/workflow/instances'), { params });
  }

  diagram(businessKey: string): Observable<WorkflowDiagram> {
    return this.http.get<WorkflowDiagram>(
      apiUrl('/workflow/instances/{businessKey}/diagram', { businessKey }),
    );
  }

  history(businessKey: string): Observable<WorkflowHistory> {
    return this.http.get<WorkflowHistory>(
      apiUrl('/workflow/instances/{businessKey}/history', { businessKey }),
    );
  }

  /** Chuyển người xử lý; 422 `WORKFLOW-422-ASSIGNEE-NOT-ELIGIBLE` khi người nhận ngoài nhóm ứng viên. */
  reassign(taskId: string, body: WorkflowTaskReassign): Observable<WorkflowInboxItem> {
    return this.http.post<WorkflowInboxItem>(
      apiUrl('/workflow/tasks/{taskId}/reassign', { taskId }),
      body,
      IF_EXISTS,
    );
  }

  extend(taskId: string, body: WorkflowTaskExtend): Observable<WorkflowInboxItem> {
    return this.http.post<WorkflowInboxItem>(
      apiUrl('/workflow/tasks/{taskId}/extend', { taskId }),
      body,
      IF_EXISTS,
    );
  }
}
