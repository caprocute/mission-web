import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { DocExtraction, TaskSuggestion } from './models/source-doc';

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
}
