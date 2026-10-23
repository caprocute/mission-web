import { HttpClient, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from './api-path';
import { Job } from './models/job';
import {
  AppraisalSimulationInput,
  AppraisalSimulationResult,
  ComplainableProfile,
  Complaint,
  ComplaintConclusionInput,
  ComplaintExplanationInput,
  ComplaintExplanationRequestInput,
  ComplaintFilter,
  ComplaintInput,
  ComplaintRecordKind,
  ComplaintRejectInput,
  ComplaintResponseRecord,
  ComplaintSummary,
} from './models/complaint';
import { CursorPage, MAX_PAGE_LIMIT } from './paging';

const ifMatch = (c: { rowVersion: number }) => ({ 'If-Match': `"${c.rowVersion}"` });

export interface ComplaintJobAccepted {
  location: string;
  job: Job;
}

const accepted = (res: HttpResponse<Job>): ComplaintJobAccepted => ({
  location: res.headers.get('Location') ?? `/api/v1/jobs/${res.body!.id}`,
  job: res.body!,
});

/**
 * Client khiếu nại, giải trình của MOD-07 (QT-12, SCR-1201, SCR-1202) theo
 * `contracts/openapi/modules/oversight.yaml` (WEB-042). Thao tác ghi lên một khiếu nại gửi `If-Match` là
 * `rowVersion`; `Idempotency-Key` do interceptor gắn. "Ký số và gửi" và "Ký số và trả lời" trả 202 và cần
 * phiên AAL2: màn hình gọi xác thực bước lên trước, máy chủ vẫn trả 401 khi thiếu. Nội dung C2 gửi dạng
 * rõ qua TLS, máy chủ mã hoá trường (ADR-06).
 */
@Injectable({ providedIn: 'root' })
export class ComplaintApi {
  private readonly http = inject(HttpClient);

  /** Kết quả của tôi còn khiếu nại được, kèm hạn, hình thức được phép và cấu phần (SCR-1201 trường 1..3). */
  eligibleProfiles(): Observable<ComplainableProfile[]> {
    return this.http
      .get<{ items: ComplainableProfile[] }>(apiUrl('/oversight/complaints/eligible-profiles'))
      .pipe(map((r) => r.items));
  }

  list(filter: ComplaintFilter = {}): Observable<ComplaintSummary[]> {
    let params = new HttpParams().set('limit', MAX_PAGE_LIMIT);
    if (filter.periodId) params = params.set('period', filter.periodId);
    if (filter.status?.length) params = params.set('status', filter.status.join(','));
    if (filter.kind) params = params.set('kind', filter.kind);
    if (filter.assignedToMe) params = params.set('assignedToMe', true);
    if (filter.q) params = params.set('q', filter.q);
    return this.http
      .get<CursorPage<ComplaintSummary>>(apiUrl('/oversight/complaints'), { params })
      .pipe(map((p) => p.items));
  }

  get(id: string): Observable<Complaint> {
    return this.http.get<Complaint>(apiUrl('/oversight/complaints/{id}', { id }));
  }

  /** "Lưu nháp" lần đầu: khiếu nại chưa gửi có `status` là `null`. */
  create(input: ComplaintInput): Observable<Complaint> {
    return this.http.post<Complaint>(apiUrl('/oversight/complaints'), input);
  }

  update(c: Complaint, input: ComplaintInput): Observable<Complaint> {
    return this.http.put<Complaint>(apiUrl('/oversight/complaints/{id}', { id: c.id }), input, {
      headers: ifMatch(c),
    });
  }

  /** "Ký số và gửi": hồ sơ sang `KHIEU_NAI`, khiếu nại `MOI` khi chữ ký xong (EVT-25, EVT-18). */
  submit(c: Complaint): Observable<ComplaintJobAccepted> {
    return this.http
      .post<Job>(
        apiUrl('/oversight/complaints/{id}/submit', { id: c.id }),
        {},
        { headers: ifMatch(c), observe: 'response' },
      )
      .pipe(map(accepted));
  }

  withdraw(c: Complaint, reason: string): Observable<Complaint> {
    return this.http.post<Complaint>(
      apiUrl('/oversight/complaints/{id}/withdraw', { id: c.id }),
      { reason },
      { headers: ifMatch(c) },
    );
  }

  /** "Xác nhận bảo lưu" sau khi có văn bản trả lời (BR-12-09). */
  reserve(c: Complaint, opinion: string): Observable<Complaint> {
    return this.http.post<Complaint>(
      apiUrl('/oversight/complaints/{id}/reserve', { id: c.id }),
      { opinion },
      { headers: ifMatch(c) },
    );
  }

  accept(c: Complaint, note?: string): Observable<Complaint> {
    return this.http.post<Complaint>(
      apiUrl('/oversight/complaints/{id}/accept', { id: c.id }),
      note ? { note } : {},
      { headers: ifMatch(c) },
    );
  }

  reject(c: Complaint, body: ComplaintRejectInput): Observable<Complaint> {
    return this.http.post<Complaint>(
      apiUrl('/oversight/complaints/{id}/reject', { id: c.id }),
      body,
      { headers: ifMatch(c) },
    );
  }

  requestExplanations(c: Complaint, body: ComplaintExplanationRequestInput): Observable<Complaint> {
    return this.http.post<Complaint>(
      apiUrl('/oversight/complaints/{id}/explanation-requests', { id: c.id }),
      body,
      { headers: ifMatch(c) },
    );
  }

  /** Bên liên quan gửi giải trình (SCR-1202 chế độ bên liên quan). */
  submitExplanation(
    c: Complaint,
    body: ComplaintExplanationInput,
  ): Observable<ComplaintResponseRecord> {
    return this.http.post<ComplaintResponseRecord>(
      apiUrl('/oversight/complaints/{id}/explanations', { id: c.id }),
      body,
      { headers: ifMatch(c) },
    );
  }

  responses(id: string, recordKind?: ComplaintRecordKind): Observable<ComplaintResponseRecord[]> {
    let params = new HttpParams().set('limit', MAX_PAGE_LIMIT);
    if (recordKind) params = params.set('recordKind', recordKind);
    return this.http
      .get<CursorPage<ComplaintResponseRecord>>(
        apiUrl('/oversight/complaints/{id}/responses', { id }),
        { params },
      )
      .pipe(map((p) => p.items));
  }

  saveConclusion(c: Complaint, input: ComplaintConclusionInput): Observable<Complaint> {
    return this.http.put<Complaint>(
      apiUrl('/oversight/complaints/{id}/conclusion', { id: c.id }),
      input,
      { headers: ifMatch(c) },
    );
  }

  /** "Ký số và trả lời" (AC-08): `DA_TRA_LOI`, hoặc `DANG_THI_HANH` khi có điều chỉnh (EVT-18, EVT-21). */
  respond(c: Complaint, comment?: string): Observable<ComplaintJobAccepted> {
    return this.http
      .post<Job>(
        apiUrl('/oversight/complaints/{id}/respond', { id: c.id }),
        comment ? { comment } : {},
        { headers: ifMatch(c), observe: 'response' },
      )
      .pipe(map(accepted));
  }

  /** "Tính thử": SVC-07 chế độ mô phỏng với Policy Pack của kỳ, không ghi (BR-12-08, MSG-12-06). */
  simulate(
    profileId: string,
    input: AppraisalSimulationInput,
  ): Observable<AppraisalSimulationResult> {
    return this.http.post<AppraisalSimulationResult>(
      apiUrl('/appraisals/profiles/{id}/simulate', { id: profileId }),
      input,
    );
  }
}
