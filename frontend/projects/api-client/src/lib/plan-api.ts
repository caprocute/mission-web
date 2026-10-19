import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { CursorPage, MAX_PAGE_LIMIT } from './paging';
import {
  PlanCheckResult,
  PlanDiff,
  PlanItem,
  PlanItemRef,
  PlanSummary,
  PlanVersion,
  PlanWorkspace,
  SignMethod,
  SignRequest,
} from './models/plan';

/**
 * Client cho `/api/v1/plans` của MOD-02 (QT-04): dòng kế hoạch đã duyệt cho SCR-0501 (WEB-028) và
 * lập kế hoạch quý SCR-0402 (WEB-026), phê duyệt SCR-0403 (WEB-027). Đường dẫn đọc, kiểm tra và
 * trạng thái yêu cầu ký là suy luận, xem wiki/05a.
 */
@Injectable({ providedIn: 'root' })
export class PlanApi {
  private readonly http = inject(HttpClient);

  /** Dòng của phiên bản kế hoạch `DA_DUYET` hiệu lực trong kỳ, chưa sinh nhiệm vụ đã giao. */
  approvedItems(periodId: string): Observable<CursorPage<PlanItemRef>> {
    const params = new HttpParams()
      .set('period', periodId)
      .set('status', 'DA_DUYET')
      .set('limit', MAX_PAGE_LIMIT);
    return this.http.get<CursorPage<PlanItemRef>>('/api/v1/plans/items', { params });
  }

  /** Kế hoạch tập thể của đơn vị người dùng theo kỳ (mới nhất trước). */
  mine(): Observable<PlanSummary[]> {
    return this.http
      .get<CursorPage<PlanSummary>>('/api/v1/plans', { params: { owner: 'unit' } })
      .pipe(map((p) => p.items));
  }

  /** Kế hoạch kèm phiên bản `v` (mặc định phiên bản mới nhất); ETag là `rowVersion` của phiên bản. */
  workspace(planId: string, versionNo?: number): Observable<PlanWorkspace> {
    const url = versionNo
      ? `/api/v1/plans/${encodeURIComponent(planId)}/versions/${versionNo}`
      : `/api/v1/plans/${encodeURIComponent(planId)}`;
    return this.http.get<PlanWorkspace>(url);
  }

  /** Kiểm tra tức thì tập dòng đang soạn (BR-04-02 … BR-04-07), kể cả khi chưa lưu. */
  check(planId: string, versionNo: number, items: PlanItem[]): Observable<PlanCheckResult> {
    return this.http.post<PlanCheckResult>(
      `/api/v1/plans/${encodeURIComponent(planId)}/versions/${versionNo}/check`,
      { items },
    );
  }

  /** "Lưu nháp": `PUT /plans/{id}/versions/{v}` kèm `If-Match`. */
  save(planId: string, version: PlanVersion, items: PlanItem[]): Observable<PlanVersion> {
    return this.http.put<PlanVersion>(
      `/api/v1/plans/${encodeURIComponent(planId)}/versions/${version.versionNo}`,
      { items },
      { headers: { 'If-Match': `"${version.rowVersion}"` } },
    );
  }

  /** "Sao chép từ quý trước": dòng thường xuyên và dòng chưa hoàn thành, hạn dịch theo kỳ. */
  copyFromPrevious(planId: string, version: PlanVersion): Observable<PlanVersion> {
    return this.http.post<PlanVersion>(
      `/api/v1/plans/${encodeURIComponent(planId)}/versions/${version.versionNo}/copy-from`,
      { source: 'PREVIOUS_PERIOD' },
      { headers: { 'If-Match': `"${version.rowVersion}"` } },
    );
  }

  /** "Gợi ý từ OKR": một dòng đề xuất cho mỗi KR chưa có nhiệm vụ (BR-04-07). */
  suggestions(planId: string): Observable<PlanItem[]> {
    return this.http
      .get<CursorPage<PlanItem>>(`/api/v1/plans/${encodeURIComponent(planId)}/suggestions`)
      .pipe(map((p) => p.items));
  }

  /** "Gửi duyệt": kiểm tra lại, khoá, tính mã băm, chuyển `CHO_DUYET` (Idempotency-Key do interceptor gắn). */
  submit(planId: string, version: PlanVersion): Observable<PlanVersion> {
    return this.http.post<PlanVersion>(
      `/api/v1/plans/${encodeURIComponent(planId)}/versions/${version.versionNo}/submit`,
      null,
      { headers: { 'If-Match': `"${version.rowVersion}"` } },
    );
  }

  /** "Tạo điều chỉnh": phiên bản n+1 từ phiên bản đã duyệt, bắt buộc lý do (BR-04-10). */
  createAdjustment(planId: string, changeReason: string): Observable<PlanVersion> {
    return this.http.post<PlanVersion>(`/api/v1/plans/${encodeURIComponent(planId)}/versions`, {
      changeReason,
    });
  }

  /** "Chỉ hiện thay đổi, Toàn bộ": so sánh phiên bản `v` với `base` (mặc định phiên bản hiệu lực). */
  diff(planId: string, versionNo: number, base?: number): Observable<PlanDiff> {
    const params: Record<string, number> = base ? { base } : {};
    return this.http.get<PlanDiff>(
      `/api/v1/plans/${encodeURIComponent(planId)}/versions/${versionNo}/diff`,
      { params },
    );
  }

  /** "Trả lại": chuyển `TRA_LAI` kèm ý kiến bắt buộc (SCR-0403 trường 6, EVT-21). */
  returnPlan(planId: string, versionNo: number, rowVersion: number, comment: string) {
    return this.http.post<PlanVersion>(
      `/api/v1/plans/${encodeURIComponent(planId)}/versions/${versionNo}/return`,
      { comment },
      { headers: { 'If-Match': `"${rowVersion}"` } },
    );
  }

  /**
   * "Duyệt và ký số": máy chủ kiểm BR-04-08 (403), MFA trong 15 phút (401
   * `insufficient_user_authentication`, RFC 9470), tạo yêu cầu ký và trả 202 kèm `Location` của yêu cầu
   * ký; phiên bản chỉ thành `DA_DUYET` khi ký xong (EVT-18 rồi EVT-03).
   */
  approve(
    planId: string,
    versionNo: number,
    rowVersion: number,
    body: { signMethod: SignMethod; comment?: string },
  ): Observable<{ location: string; request: SignRequest }> {
    return this.http
      .post<SignRequest>(
        `/api/v1/plans/${encodeURIComponent(planId)}/versions/${versionNo}/approve`,
        body,
        { headers: { 'If-Match': `"${rowVersion}"` }, observe: 'response' },
      )
      .pipe(
        map((res: HttpResponse<SignRequest>) => ({
          location: res.headers.get('Location') ?? `/api/v1/esign/requests/${res.body!.id}`,
          request: res.body!,
        })),
      );
  }

  /** Trạng thái yêu cầu ký theo `Location` đã nhận (`/api/v1/esign/requests/{id}`). */
  signRequest(location: string): Observable<SignRequest> {
    return this.http.get<SignRequest>(location);
  }
}
