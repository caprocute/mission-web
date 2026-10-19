import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import {
  AppraisalExportInput,
  AppraisalFlow,
  AppraisalProfile,
  AppraisalProfilePage,
  AppraisalProfileStatus,
  AppraisalReviewInput,
  AppraisalScoreTrace,
  AppraisalScoreView,
  AppraisalSelfAssessmentInput,
  AppraisalSignRequestInput,
  DossierFormPreviewRequest,
} from './models/appraisal';
import { Job } from './models/job';

const enc = encodeURIComponent;

/** `Location` của phản hồi 202 (baseline mục 13); thiếu thì suy từ mã job trong thân. */
function accepted(res: HttpResponse<Job | null>): string {
  const loc = res.headers.get('Location');
  if (loc) return loc;
  if (res.body?.id) return `/api/v1/jobs/${res.body.id}`;
  throw new Error('Phản hồi 202 thiếu Location');
}
const ifMatch = (p: Pick<AppraisalProfile, 'rowVersion'>) => ({ 'If-Match': `"${p.rowVersion}"` });

/**
 * Client cho `/api/v1/appraisals` của MOD-04 (QT-09, QT-10) theo hợp đồng
 * `contracts/openapi/modules/appraisals.yaml`: phiếu tự đánh giá SCR-0901 (WEB-035), thẩm định SCR-0902
 * (WEB-036). Mọi thao tác ghi
 * lên hồ sơ gửi `If-Match` bằng `rowVersion` của hồ sơ (điểm chốt AP-05).
 */
@Injectable({ providedIn: 'root' })
export class AppraisalApi {
  private readonly http = inject(HttpClient);

  /** Hồ sơ đánh giá của tôi theo kỳ và luồng (`GET /appraisals/profiles/mine`, AP-03). */
  myProfiles(
    filter: { period?: string; flow?: AppraisalFlow } = {},
  ): Observable<AppraisalProfilePage> {
    const params: Record<string, string> = { limit: '50' };
    if (filter.period) params['period'] = filter.period;
    if (filter.flow) params['flow'] = filter.flow;
    return this.http.get<AppraisalProfilePage>('/api/v1/appraisals/profiles/mine', { params });
  }

  profile(id: string): Observable<AppraisalProfile> {
    return this.http.get<AppraisalProfile>(`/api/v1/appraisals/profiles/${enc(id)}`);
  }

  /** "Lưu nháp" và tự lưu 30 giây: trường 2, 9 đến 12 của SCR-0901; trả hồ sơ mới (ETag mới). */
  saveSelfAssessment(
    p: AppraisalProfile,
    body: AppraisalSelfAssessmentInput,
  ): Observable<AppraisalProfile> {
    return this.http.put<AppraisalProfile>(
      `/api/v1/appraisals/profiles/${enc(p.id)}/self-assessment`,
      body,
      { headers: ifMatch(p) },
    );
  }

  /**
   * "Ký số và nộp": máy chủ kiểm quy tắc, tạo yêu cầu ký ở SVC-10 rồi trả 202 kèm `Location` của
   * job; hồ sơ chỉ sang `CHO_THAM_DINH` khi nhận EVT-18 (BR-09-15). Cần phiên AAL2 (401 step-up).
   */
  submit(
    p: AppraisalProfile,
    body: AppraisalSignRequestInput,
  ): Observable<{ location: string; job: Job }> {
    return this.http
      .post<Job>(`/api/v1/appraisals/profiles/${enc(p.id)}/submit`, body, {
        headers: ifMatch(p),
        observe: 'response',
      })
      .pipe(
        map((res: HttpResponse<Job>) => ({
          location: res.headers.get('Location') ?? `/api/v1/jobs/${res.body!.id}`,
          job: res.body!,
        })),
      );
  }

  /**
   * Hàng đợi thẩm định (SCR-0902, mặc định `status=CHO_THAM_DINH`, sắp theo hạn) trong phạm vi ABAC
   * của người gọi; phân trang con trỏ, tối đa 200 dòng một trang.
   */
  profiles(
    filter: {
      periodId?: string;
      unitId?: string;
      flow?: AppraisalFlow;
      status?: AppraisalProfileStatus[];
      q?: string;
      limit?: number;
      cursor?: string | null;
    } = {},
  ): Observable<AppraisalProfilePage> {
    const params: Record<string, string | string[]> = {
      limit: String(Math.min(filter.limit ?? 50, 200)),
    };
    if (filter.periodId) params['periodId'] = filter.periodId;
    if (filter.unitId) params['unitId'] = filter.unitId;
    if (filter.flow) params['flow'] = filter.flow;
    if (filter.status?.length) params['status'] = filter.status;
    if (filter.q) params['q'] = filter.q;
    if (filter.cursor) params['cursor'] = filter.cursor;
    return this.http.get<AppraisalProfilePage>('/api/v1/appraisals/profiles', { params });
  }

  /** "Trả lại bổ sung": hồ sơ về `TU_DANH_GIA`, trả hồ sơ mới (MSG-09-09). */
  returnProfile(p: AppraisalProfile, reason: string): Observable<AppraisalProfile> {
    return this.http.post<AppraisalProfile>(
      `/api/v1/appraisals/profiles/${enc(p.id)}/return`,
      { reason },
      { headers: ifMatch(p) },
    );
  }

  /**
   * "Ký số và chuyển đề xuất": ghi mức `THAM_DINH`, tạo yêu cầu ký, trả 202 kèm `Location` của job;
   * hồ sơ sang `DA_THAM_DINH` khi nhận EVT-18. Cần phiên AAL2 (401 step-up); OPA chặn người thẩm định
   * đã nghiệm thu nhiệm vụ của hồ sơ (403, MSG-09-08).
   */
  appraise(p: AppraisalProfile, body: AppraisalReviewInput): Observable<{ location: string }> {
    return this.http
      .post<Job | null>(`/api/v1/appraisals/profiles/${enc(p.id)}/appraisal`, body, {
        headers: ifMatch(p),
        observe: 'response',
      })
      .pipe(map((res) => ({ location: accepted(res) })));
  }

  /** "Xuất danh sách" hàng đợi ra XLSX (không gồm trường C2), tác vụ dài 202. */
  exportProfiles(body: AppraisalExportInput): Observable<{ location: string }> {
    return this.http
      .post<Job | null>('/api/v1/appraisals/exports', body, { observe: 'response' })
      .pipe(map((res) => ({ location: accepted(res) })));
  }

  /** Tải tệp kết quả của job (`resultLocation`). */
  download(location: string): Observable<Blob> {
    return this.http.get(location, { responseType: 'blob' });
  }

  /** Ảnh chụp điểm và giải thích; `flow` khác luồng của hồ sơ thì lấy hồ sơ cùng người, cùng kỳ. */
  scores(id: string, flow?: AppraisalFlow): Observable<AppraisalScoreView> {
    const params: Record<string, string> = flow ? { flow } : {};
    return this.http.get<AppraisalScoreView>(`/api/v1/appraisals/profiles/${enc(id)}/scores`, {
      params,
    });
  }

  scoreTrace(id: string, filter: { flow?: AppraisalFlow; taskId?: string } = {}) {
    const params: Record<string, string> = {};
    if (filter.flow) params['flow'] = filter.flow;
    if (filter.taskId) params['taskId'] = filter.taskId;
    return this.http.get<AppraisalScoreTrace>(
      `/api/v1/appraisals/profiles/${enc(id)}/score-trace`,
      { params },
    );
  }

  /** "Xem trước Mẫu 1B": SVC-11 sinh PDF có dấu "Bản nháp" từ dữ liệu đã lưu, không lưu bản ghi. */
  previewForm(body: DossierFormPreviewRequest): Observable<Blob> {
    return this.http.post('/api/v1/dossiers/forms/preview', body, { responseType: 'blob' });
  }
}
