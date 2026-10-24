import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { AiSuggestionDecisionRequest, OkrAssistRequest, OkrAssistResult } from './models/ai-assist';
import { AiSuggestion } from './models/analytics';
import { Job } from '@vt/api-client';
import { DocExtraction, TaskSuggestion } from './models/source-doc';

/** Kết quả hỏi trợ lý: trả lời ngay, hoặc đã xếp hàng (202, MSG-17-03) kèm `Location` của job. */
export type OkrAssistOutcome =
  { kind: 'result'; result: OkrAssistResult } | { kind: 'queued'; location: string };

/**
 * Cổng AI chỉ đọc SVC-14 (`/api/v1/ai`). AI chỉ gợi ý; nội dung chỉ được ghi khi người dùng xác nhận
 * (BR-05-05, ràng buộc 28 của wiki/15a). Đường dẫn đọc kết quả trích là suy luận, xem wiki/05a.
 */
@Injectable({ providedIn: 'root' })
export class AiApi {
  private readonly http = inject(HttpClient);

  /** Nhiệm vụ AI trích từ một văn bản (`ai.suggestion_log`, use_case `GOI_Y_NHIEM_VU`). */
  extraction(sourceDocId: string): Observable<DocExtraction> {
    return this.http.get<DocExtraction>('/api/v1/ai/extractions', { params: { sourceDocId } });
  }

  /** Ghi quyết định bỏ gợi ý kèm lý do (`/api/v1/ai/suggestions/{id}/decision`). */
  reject(suggestionId: string, reason: string): Observable<TaskSuggestion> {
    return this.http.post<TaskSuggestion>(
      `/api/v1/ai/suggestions/${encodeURIComponent(suggestionId)}/decision`,
      { decision: 'BO_QUA', reason },
    );
  }

  /**
   * Hỏi trợ lý soạn mục tiêu và KR từ văn bản căn cứ (SCR-1701): 200 kèm gợi ý có nhãn và nguồn (hoặc `blocked`
   * khi rào chắn chặn, MSG-17-02), 202 khi hàng đợi đầy (MSG-17-03).
   */
  assistOkr(body: OkrAssistRequest): Observable<OkrAssistOutcome> {
    return this.http
      .post<OkrAssistResult | Job>('/api/v1/ai/assist/okr', body, { observe: 'response' })
      .pipe(
        map((res): OkrAssistOutcome => {
          if (res.status === 202) {
            const job = res.body as Job | null;
            const location =
              res.headers.get('Location') ?? (job?.id ? `/api/v1/jobs/${job.id}` : null);
            if (!location) throw new Error('Phản hồi 202 thiếu Location');
            return { kind: 'queued', location };
          }
          return { kind: 'result', result: res.body as OkrAssistResult };
        }),
      );
  }

  /** Đọc kết quả trợ lý ở `resultLocation` của job đã xếp hàng. */
  assistResult(resultLocation: string): Observable<OkrAssistResult> {
    return this.http.get<OkrAssistResult>(resultLocation.replace(/^https?:\/\/[^/]+/, ''));
  }

  /**
   * Ghi quyết định với một gợi ý của trợ lý ("Dùng gợi ý" là `CHAP_NHAN` kèm `targetRef` của bản ghi OKR vừa
   * lưu, "Bỏ qua" là `BO_QUA`); chỉ ghi nhật ký của SVC-14, 409 khi đã có quyết định.
   */
  decide(suggestionId: string, body: AiSuggestionDecisionRequest): Observable<AiSuggestion> {
    return this.http.post<AiSuggestion>(
      `/api/v1/ai/suggestions/${encodeURIComponent(suggestionId)}/decision`,
      body,
    );
  }
}
