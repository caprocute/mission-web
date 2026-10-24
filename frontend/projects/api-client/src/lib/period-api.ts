import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from '@vt/api-client';
import { JobAccepted, acceptedJob } from '@vt/api-client';
import { Job } from '@vt/api-client';
import {
  Period,
  PeriodCreate,
  PeriodKind,
  PeriodLockBatch,
  PeriodLockDiagram,
  PeriodLockEvent,
  PeriodLockFailure,
  PeriodLockOrder,
  PeriodLockOrderCreate,
  PeriodLockProgress,
  PeriodPage,
  PeriodStatus,
} from './models/period';
import { CursorPage, MAX_PAGE_LIMIT } from '@vt/api-client';

const ifMatch = (rowVersion: number) => ({ 'If-Match': `"${rowVersion}"` });

/** Kết quả thao tác trả 202: địa chỉ theo dõi `/api/v1/jobs/{id}` và job ban đầu. */
export type PeriodJobAccepted = JobAccepted;

const accepted = acceptedJob;

/**
 * Client cho `/api/v1/periods` của MOD-08 và saga khoá kỳ của workflow-service (QT-08, SCR-0801),
 * theo `contracts/openapi/modules/config.yaml` (WEB-034). Các thao tác điều khiển saga gửi `If-Match`
 * là `rowVersion` của lệnh khoá kỳ (suy luận, hợp đồng không ghi bản ghi nào); `Idempotency-Key` do
 * interceptor gắn.
 */
@Injectable({ providedIn: 'root' })
export class PeriodApi {
  private readonly http = inject(HttpClient);

  /** Danh sách kỳ của tenant, mới nhất trước. */
  list(query: { kind?: PeriodKind; status?: PeriodStatus } = {}): Observable<Period[]> {
    const params: Record<string, string | number> = { limit: MAX_PAGE_LIMIT };
    for (const [k, v] of Object.entries(query)) if (v) params[k] = v;
    return this.http.get<PeriodPage>(apiUrl('/periods'), { params }).pipe(map((p) => p.items));
  }

  get(id: string): Observable<Period> {
    return this.http.get<Period>(apiUrl('/periods/{id}', { id }));
  }

  /** "Tạo kỳ": lịch mốc theo Policy Pack nếu bỏ trống, máy chủ ghim gói hiệu lực (BR-08-07). */
  create(body: PeriodCreate): Observable<Period> {
    return this.http.post<Period>(apiUrl('/periods'), body);
  }

  /** "Lập lệnh khoá kỳ": gửi lãnh đạo (AC-04) phê duyệt, EVT-21. */
  createLockOrder(periodId: string, body: PeriodLockOrderCreate): Observable<PeriodLockOrder> {
    return this.http.post<PeriodLockOrder>(
      apiUrl('/periods/{id}/lock-orders', { id: periodId }),
      body,
    );
  }

  /** "Phê duyệt lệnh": người duyệt khác người lập, MFA (BR-08-01, BR-08-02). */
  approveLockOrder(periodId: string, order: PeriodLockOrder, comment?: string) {
    return this.http.post<PeriodLockOrder>(
      apiUrl('/periods/{id}/lock-orders/{oid}/approve', { id: periodId, oid: order.id }),
      { comment },
      { headers: ifMatch(order.rowVersion) },
    );
  }

  /** Tiến trình saga khoá kỳ: bước, tổng số hồ sơ, tốc độ, dự kiến hoàn tất, đề nghị huỷ. */
  progress(periodId: string): Observable<PeriodLockProgress> {
    return this.http.get<PeriodLockProgress>(apiUrl('/periods/{id}/lock', { id: periodId }));
  }

  /** "Khởi chạy": tạo saga `chot-ky` với khoá `CK-{năm}{kỳ}`, trả 202 + `Location` của job. */
  start(periodId: string, order: PeriodLockOrder): Observable<PeriodJobAccepted> {
    return this.http
      .post<Job>(
        apiUrl('/periods/{id}/lock', { id: periodId }),
        { lockOrderId: order.id },
        { headers: ifMatch(order.rowVersion), observe: 'response' },
      )
      .pipe(map(accepted));
  }

  batches(periodId: string): Observable<PeriodLockBatch[]> {
    return this.http
      .get<CursorPage<PeriodLockBatch>>(apiUrl('/periods/{id}/lock/batches', { id: periodId }), {
        params: { limit: MAX_PAGE_LIMIT },
      })
      .pipe(map((p) => p.items));
  }

  failures(periodId: string): Observable<PeriodLockFailure[]> {
    return this.http
      .get<CursorPage<PeriodLockFailure>>(apiUrl('/periods/{id}/lock/failures', { id: periodId }), {
        params: { limit: MAX_PAGE_LIMIT },
      })
      .pipe(map((p) => p.items));
  }

  /** Nhật ký sự kiện của saga, mới nhất trước (sổ cái, BR-08-10). */
  events(periodId: string): Observable<PeriodLockEvent[]> {
    return this.http
      .get<CursorPage<PeriodLockEvent>>(apiUrl('/periods/{id}/lock/events', { id: periodId }), {
        params: { limit: MAX_PAGE_LIMIT },
      })
      .pipe(map((p) => p.items));
  }

  diagram(periodId: string): Observable<PeriodLockDiagram> {
    return this.http.get<PeriodLockDiagram>(apiUrl('/periods/{id}/lock/diagram', { id: periodId }));
  }

  /** "Tạm dừng sau bước hiện tại" (MFA, BR-08-02). */
  pause(periodId: string, order: PeriodLockOrder, comment?: string) {
    return this.http.post<PeriodLockProgress>(
      apiUrl('/periods/{id}/lock/pause', { id: periodId }),
      { comment },
      { headers: ifMatch(order.rowVersion) },
    );
  }

  resume(periodId: string, order: PeriodLockOrder) {
    return this.http.post<PeriodLockProgress>(
      apiUrl('/periods/{id}/lock/resume', { id: periodId }),
      null,
      { headers: ifMatch(order.rowVersion) },
    );
  }

  /** "Chạy lại hồ sơ lỗi": lô mới cho hàng đợi lỗi (hoặc các hồ sơ chỉ định), trả 202. */
  retryFailed(periodId: string, profileIds?: string[]): Observable<PeriodJobAccepted> {
    return this.http
      .post<Job>(
        apiUrl('/periods/{id}/lock/retry-failed', { id: periodId }),
        profileIds?.length ? { profileIds } : {},
        { observe: 'response' },
      )
      .pipe(map(accepted));
  }

  /** "Huỷ và bù trừ toàn bộ": tạo đề nghị chờ người duyệt thứ hai (BR-08-09, MSG-08-05). */
  requestCancel(periodId: string, order: PeriodLockOrder, reason: string) {
    return this.http.post<PeriodLockProgress>(
      apiUrl('/periods/{id}/lock/cancel', { id: periodId }),
      { reason },
      { headers: ifMatch(order.rowVersion) },
    );
  }

  /** Duyệt thứ hai đề nghị huỷ (AC-09): saga bù trừ theo thứ tự ngược, trả 202. */
  approveCancel(periodId: string, order: PeriodLockOrder, comment?: string) {
    return this.http
      .post<Job>(
        apiUrl('/periods/{id}/lock/cancel/approve', { id: periodId }),
        { comment },
        { headers: ifMatch(order.rowVersion), observe: 'response' },
      )
      .pipe(map(accepted));
  }
}
