import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CursorPage, MAX_PAGE_LIMIT } from './paging';
import {
  AiOkrReview,
  KeyResultRef,
  KrCheckin,
  ObjectiveDetail,
  ObjectiveDraft,
  OkrComposeContext,
  OkrHistoryEntry,
  OkrReviewDecisionBody,
  OkrReviewQueue,
  OkrSubmitResult,
  OkrTree,
  OkrTreeView,
} from './models/okr';

/**
 * Client `/api/v1/okr` (MOD-01). WEB-028 dùng danh sách KR để liên kết nhiệm vụ; WEB-021 thêm cây
 * mục tiêu SCR-0201, lịch sử check-in và xuất mẫu báo cáo; WEB-022 thêm soạn, lưu nháp, trình
 * phê duyệt mục tiêu (SCR-0202) và trợ lý AI soát KR; WEB-023 thêm hàng chờ, phê duyệt, trả lại,
 * duyệt hàng loạt và lịch sử luồng duyệt (SCR-0203). Khi `useMockApi` bật, dữ liệu từ `mock/okr/`.
 */
@Injectable({ providedIn: 'root' })
export class OkrApi {
  private readonly http = inject(HttpClient);

  /** KR của đơn vị trong kỳ, để liên kết nhiệm vụ (SCR-0501 trường 7). */
  keyResults(periodId: string): Observable<CursorPage<KeyResultRef>> {
    const params = new HttpParams()
      .set('period', periodId)
      .set('scope', 'unit')
      .set('limit', MAX_PAGE_LIMIT);
    return this.http.get<CursorPage<KeyResultRef>>('/api/v1/okr/key-results', { params });
  }

  /**
   * Cây mục tiêu của đơn vị trong kỳ (SCR-0201, `/api/v1/okr/objectives?unit=&period=&view=tree`).
   * Bỏ `unit` để lấy đơn vị của ngữ cảnh làm việc; bỏ `period` để lấy kỳ đang `MO`.
   */
  tree(query: { unit?: string; period?: string } = {}): Observable<OkrTree> {
    let params = new HttpParams().set('view', 'tree');
    if (query.unit) params = params.set('unit', query.unit);
    if (query.period) params = params.set('period', query.period);
    return this.http.get<OkrTree>('/api/v1/okr/objectives', { params });
  }

  /** Lịch sử check-in của KR, mới nhất trước (`/api/v1/okr/key-results/{id}/checkins?cursor=`). */
  checkins(krId: string, cursor?: string, limit = 10): Observable<CursorPage<KrCheckin>> {
    let params = new HttpParams().set('limit', limit);
    if (cursor) params = params.set('cursor', cursor);
    return this.http.get<CursorPage<KrCheckin>>(
      `/api/v1/okr/key-results/${encodeURIComponent(krId)}/checkins`,
      { params },
    );
  }

  /**
   * "Xuất mẫu" báo cáo OKR: tác vụ dài, trả 202 + `Location` để theo dõi bằng `JobTracker`
   * (`/api/v1/okr/objectives/export`).
   */
  exportObjectives(body: {
    unitId: string;
    periodId: string;
    view: OkrTreeView;
  }): Observable<HttpResponse<unknown>> {
    return this.http.post('/api/v1/okr/objectives/export', body, { observe: 'response' });
  }

  /**
   * Ngữ cảnh soạn mục tiêu (SCR-0202): kỳ, căn cứ, mục tiêu cấp trên, người xác nhận, số mục tiêu
   * đã có và bộ quy tắc soát của Policy Pack. Giả định đường dẫn tới khi có OpenAPI của mission-core.
   */
  composeContext(
    query: { period?: string; objectiveId?: string } = {},
  ): Observable<OkrComposeContext> {
    let params = new HttpParams();
    if (query.period) params = params.set('period', query.period);
    if (query.objectiveId) params = params.set('objective', query.objectiveId);
    return this.http.get<OkrComposeContext>('/api/v1/okr/compose-context', { params });
  }

  /** Mục tiêu cùng KR để sửa (`GET /api/v1/okr/objectives/{id}`). */
  objective(id: string): Observable<ObjectiveDetail> {
    return this.http.get<ObjectiveDetail>(`/api/v1/okr/objectives/${encodeURIComponent(id)}`);
  }

  /** Tạo bản nháp `NHAP` (POST, Idempotency-Key do interceptor gắn). */
  createObjective(draft: ObjectiveDraft): Observable<ObjectiveDetail> {
    return this.http.post<ObjectiveDetail>('/api/v1/okr/objectives', draft);
  }

  /** Lưu nháp, kể cả lưu tự động mỗi 30 giây (PUT, If-Match; 409 khi người khác vừa sửa). */
  saveObjective(
    id: string,
    rowVersion: number,
    draft: ObjectiveDraft,
  ): Observable<ObjectiveDetail> {
    return this.http.put<ObjectiveDetail>(
      `/api/v1/okr/objectives/${encodeURIComponent(id)}`,
      draft,
      {
        headers: { 'If-Match': `"${rowVersion}"` },
      },
    );
  }

  /** Trình phê duyệt: máy chủ kiểm lại toàn bộ quy tắc, chuyển `CHO_DUYET`, tạo tiến trình `duyet-okr`. */
  submitObjective(id: string): Observable<OkrSubmitResult> {
    return this.http.post<OkrSubmitResult>(
      `/api/v1/okr/objectives/${encodeURIComponent(id)}/submit`,
      {},
    );
  }

  /** Trợ lý AI soát KR (SVC-14, AC-12): chỉ đề xuất, không ghi; không gửi dữ liệu C3. */
  aiReview(draft: ObjectiveDraft): Observable<AiOkrReview> {
    return this.http.post<AiOkrReview>('/api/v1/ai/okr/review', {
      title: draft.title,
      keyResults: draft.keyResults.map((k) => ({
        code: k.code,
        title: k.title,
        unit: k.unit,
        targetValue: k.targetValue,
      })),
    });
  }

  /** Hàng chờ phê duyệt OKR của người dùng trong kỳ (SCR-0203 vùng 1, 2; đường dẫn giả định, xem wiki/14). */
  reviewQueue(period?: string): Observable<OkrReviewQueue> {
    const params = period ? new HttpParams().set('period', period) : undefined;
    return this.http.get<OkrReviewQueue>('/api/v1/okr/reviews', { params });
  }

  /** Phê duyệt một mục tiêu: `CHO_DUYET` → `DA_DUYET`, phát EVT-01; 403 `OKR-403-SELF-APPROVAL` khi tự duyệt. */
  approveObjective(
    id: string,
    rowVersion: number,
    body: OkrReviewDecisionBody,
  ): Observable<unknown> {
    return this.http.post(`/api/v1/okr/objectives/${encodeURIComponent(id)}/approve`, body, {
      headers: { 'If-Match': `"${rowVersion}"` },
    });
  }

  /** Trả lại để sửa: `CHO_DUYET` → `NHAP`, ý kiến bắt buộc (BR-02-09, 422 `OKR-422-COMMENT-REQUIRED`). */
  returnObjective(
    id: string,
    rowVersion: number,
    body: OkrReviewDecisionBody,
  ): Observable<unknown> {
    return this.http.post(`/api/v1/okr/objectives/${encodeURIComponent(id)}/return`, body, {
      headers: { 'If-Match': `"${rowVersion}"` },
    });
  }

  /** Duyệt hàng loạt các bộ đủ điều kiện, mỗi mục một giao dịch (202 + Location tới job). */
  approveBatch(
    items: { id: string; rowVersion: number }[],
    body: OkrReviewDecisionBody,
  ): Observable<HttpResponse<unknown>> {
    return this.http.post(
      '/api/v1/okr/objectives/approve-batch',
      { ...body, items },
      {
        observe: 'response',
      },
    );
  }

  /** Lịch sử phiên bản và luồng duyệt của mục tiêu, mới nhất trước. */
  history(id: string): Observable<CursorPage<OkrHistoryEntry>> {
    return this.http.get<CursorPage<OkrHistoryEntry>>(
      `/api/v1/okr/objectives/${encodeURIComponent(id)}/history`,
    );
  }
}
