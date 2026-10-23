import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from './api-path';
import { JobAccepted, acceptedJob } from './job-tracker';
import { Job } from './models/job';
import { UiFormVersion } from './models/ui-form';
import {
  UiComponentPage,
  UiFormPage,
  UiFormTestRunRequest,
  UiFormTestRunResult,
  UiFormVersionCreate,
  UiFormVersionUpdate,
  UiPackFormAttach,
  UiPackPage,
  UiPackPreview,
  UiPackVersion,
  UiPackVersionCreate,
  UiPackVersionPage,
  UiPackVersionUpdate,
} from './models/ui-pack';

/** Tham số đường dẫn của một phiên bản UI Pack. */
export interface UiPackRef {
  id: string;
  v: string;
}

/** Tham số đường dẫn của một phiên bản biểu mẫu. */
export interface UiFormRef {
  key: string;
  v: string;
}

const ifMatch = (rowVersion: number) => ({ headers: { 'If-Match': `"${rowVersion}"` } });

/**
 * Client cấu hình giao diện tenant (MOD-08, QT-18, SCR-1801 và SCR-1802) theo `config.yaml`:
 * `/api/v1/ui/packs`, `/api/v1/ui/components`, `/api/v1/ui/forms`. Ghi kèm `Idempotency-Key`
 * (interceptor tự thêm) và `If-Match` theo `rowVersion`; máy chủ kiểm lại tương phản, danh mục
 * component và biểu thức CEL, là nguồn sự thật.
 */
@Injectable({ providedIn: 'root' })
export class UiPackApi {
  private readonly http = inject(HttpClient);

  packs(): Observable<UiPackPage> {
    return this.http.get<UiPackPage>(apiUrl('/ui/packs'), { params: { limit: 50 } });
  }

  versions(id: string): Observable<UiPackVersionPage> {
    return this.http.get<UiPackVersionPage>(apiUrl('/ui/packs/{id}/versions', { id }), {
      params: { limit: 50 },
    });
  }

  /** Tạo bản nháp bằng cách nhân bản phiên bản đang dùng (`cloneFromVersion`) hoặc bộ mặc định. */
  createVersion(id: string, body: UiPackVersionCreate): Observable<UiPackVersion> {
    return this.http.post<UiPackVersion>(apiUrl('/ui/packs/{id}/versions', { id }), body);
  }

  version(r: UiPackRef): Observable<UiPackVersion> {
    return this.http.get<UiPackVersion>(apiUrl('/ui/packs/{id}/versions/{v}', r));
  }

  /** Lưu nháp; 422 `UI-422-CONTRAST` (MSG-18-01), `UI-422-UNKNOWN-COMPONENT` (MSG-18-02). */
  save(r: UiPackRef, rowVersion: number, body: UiPackVersionUpdate): Observable<UiPackVersion> {
    return this.http.put<UiPackVersion>(
      apiUrl('/ui/packs/{id}/versions/{v}', r),
      body,
      ifMatch(rowVersion),
    );
  }

  preview(r: UiPackRef, mode: 'default' | 'elderly' = 'default'): Observable<UiPackPreview> {
    return this.http.get<UiPackPreview>(apiUrl('/ui/packs/{id}/versions/{v}/preview', r), {
      params: { mode },
    });
  }

  /**
   * Gửi duyệt: máy chủ chạy kiểm tra tự động rồi chuyển chờ duyệt hoặc trả về nháp; 202 + `Location`
   * của job, theo dõi bằng `JobTracker`.
   */
  submit(r: UiPackRef, rowVersion: number): Observable<JobAccepted> {
    return this.http
      .post<Job>(apiUrl('/ui/packs/{id}/versions/{v}/submit', r), null, {
        ...ifMatch(rowVersion),
        observe: 'response',
      })
      .pipe(map(acceptedJob));
  }

  /**
   * Duyệt và phát hành (AAL2, người duyệt khác người soạn): 202 + `Location` của job `RENDER`, theo
   * dõi bằng `JobTracker`; xong thì phát EVT-23.
   */
  publish(r: UiPackRef, rowVersion: number, comment?: string): Observable<JobAccepted> {
    return this.http
      .post<Job>(apiUrl('/ui/packs/{id}/versions/{v}/publish', r), comment ? { comment } : {}, {
        ...ifMatch(rowVersion),
        observe: 'response',
      })
      .pipe(map(acceptedJob));
  }

  reject(r: UiPackRef, rowVersion: number, comment: string): Observable<UiPackVersion> {
    return this.http.post<UiPackVersion>(
      apiUrl('/ui/packs/{id}/versions/{v}/reject', r),
      { comment },
      ifMatch(rowVersion),
    );
  }

  /** Quay lui về một phiên bản đã thay thế; 409 `UI-409-ROLLBACK-INCOMPATIBLE` (MSG-18-04). */
  activate(r: UiPackRef, rowVersion: number): Observable<UiPackVersion> {
    return this.http.post<UiPackVersion>(
      apiUrl('/ui/packs/{id}/versions/{v}/activate', r),
      null,
      ifMatch(rowVersion),
    );
  }

  /** Đưa một phiên bản biểu mẫu đã đạt bộ test vào UI Pack nháp. */
  attachForm(r: UiPackRef, rowVersion: number, body: UiPackFormAttach): Observable<UiPackVersion> {
    return this.http.post<UiPackVersion>(
      apiUrl('/ui/packs/{id}/versions/{v}/forms', r),
      body,
      ifMatch(rowVersion),
    );
  }

  components(registry?: string): Observable<UiComponentPage> {
    const params: Record<string, string> = { status: 'HIEU_LUC', limit: '200' };
    if (registry) params['registry'] = registry;
    return this.http.get<UiComponentPage>(apiUrl('/ui/components'), { params });
  }

  forms(): Observable<UiFormPage> {
    return this.http.get<UiFormPage>(apiUrl('/ui/forms'), { params: { limit: 50 } });
  }

  form(r: UiFormRef): Observable<UiFormVersion> {
    return this.http.get<UiFormVersion>(apiUrl('/ui/forms/{key}/versions/{v}', r));
  }

  createFormVersion(key: string, body: UiFormVersionCreate): Observable<UiFormVersion> {
    return this.http.post<UiFormVersion>(apiUrl('/ui/forms/{key}/versions', { key }), body);
  }

  /** Lưu nháp biểu mẫu; 422 `UI-422-CEL-INVALID` (MSG-18-06), 409 `UI-409-SCORED-FIELD` (MSG-18-07). */
  saveForm(r: UiFormRef, rowVersion: number, body: UiFormVersionUpdate): Observable<UiFormVersion> {
    return this.http.put<UiFormVersion>(
      apiUrl('/ui/forms/{key}/versions/{v}', r),
      body,
      ifMatch(rowVersion),
    );
  }

  /** Chạy bộ test trên máy chủ và so với kết quả trình duyệt gửi lên (MSG-18-08). */
  runFormTests(r: UiFormRef, body: UiFormTestRunRequest): Observable<UiFormTestRunResult> {
    return this.http.post<UiFormTestRunResult>(
      apiUrl('/ui/forms/{key}/versions/{v}/test-runs', r),
      body,
    );
  }
}
