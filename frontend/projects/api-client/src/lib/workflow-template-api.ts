import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-path';
import { Job } from './models/job';
import {
  WorkflowTemplate,
  WorkflowTemplateCreate,
  WorkflowTemplatePage,
  WorkflowTemplateRolloutList,
  WorkflowTemplateStatus,
  WorkflowTemplateUpdate,
} from './models/workflow-template';

const ifMatch = (rowVersion: number) => ({ headers: { 'If-Match': `"${rowVersion}"` } });

/**
 * Client thư viện mẫu luồng (SVC-06, QT-15, BS-01 SCR-1505) theo `platform.yaml`
 * `/api/v1/workflow/templates`. Máy chủ chạy lại linter khi lưu và quyết định; ghi kèm
 * `Idempotency-Key` (interceptor tự thêm) và `If-Match` theo `rowVersion`.
 */
@Injectable({ providedIn: 'root' })
export class WorkflowTemplateApi {
  private readonly http = inject(HttpClient);

  list(status?: WorkflowTemplateStatus): Observable<WorkflowTemplatePage> {
    const params: Record<string, string | number> = { limit: 100 };
    if (status) params['status'] = status;
    return this.http.get<WorkflowTemplatePage>(apiUrl('/workflow/templates'), { params });
  }

  get(id: string): Observable<WorkflowTemplate> {
    return this.http.get<WorkflowTemplate>(apiUrl('/workflow/templates/{id}', { id }));
  }

  /** Tạo mẫu mới, hoặc phiên bản kế tiếp khi có `basedOnTemplateId`; 422 kèm `errors[].rule`. */
  create(body: WorkflowTemplateCreate): Observable<WorkflowTemplate> {
    return this.http.post<WorkflowTemplate>(apiUrl('/workflow/templates'), body);
  }

  save(id: string, rowVersion: number, body: WorkflowTemplateUpdate): Observable<WorkflowTemplate> {
    return this.http.put<WorkflowTemplate>(
      apiUrl('/workflow/templates/{id}', { id }),
      body,
      ifMatch(rowVersion),
    );
  }

  /** Chạy bộ test kịch bản và thử tải rút gọn trên engine kiểm thử riêng: 202, đạt thì `DAT_KIEM_THU`. */
  runTests(id: string): Observable<Job> {
    return this.http.post<Job>(apiUrl('/workflow/templates/{id}/test', { id }), {});
  }

  /** Đề nghị phát hành: tạo merge request vào repo mẫu luồng, mẫu sang `CHO_PHE_DUYET`. */
  submit(id: string, rowVersion: number, comment?: string): Observable<WorkflowTemplate> {
    return this.http.post<WorkflowTemplate>(
      apiUrl('/workflow/templates/{id}/submit', { id }),
      comment ? { comment } : {},
      ifMatch(rowVersion),
    );
  }

  /** Phê duyệt bốn mắt (AAL2): 202, saga triển khai cuốn chiếu theo cell. */
  approve(id: string, rowVersion: number, comment?: string): Observable<Job> {
    return this.http.post<Job>(
      apiUrl('/workflow/templates/{id}/approve', { id }),
      comment ? { comment } : {},
      ifMatch(rowVersion),
    );
  }

  rollouts(id: string): Observable<WorkflowTemplateRolloutList> {
    return this.http.get<WorkflowTemplateRolloutList>(
      apiUrl('/workflow/templates/{id}/rollouts', { id }),
    );
  }
}
