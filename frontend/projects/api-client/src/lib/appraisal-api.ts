import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import {
  AppraisalFlow,
  AppraisalProfile,
  AppraisalProfilePage,
  AppraisalScoreTrace,
  AppraisalScoreView,
  AppraisalSelfAssessmentInput,
  AppraisalSignRequestInput,
  DossierFormPreviewRequest,
} from './models/appraisal';
import { Job } from './models/job';

const enc = encodeURIComponent;
const ifMatch = (p: Pick<AppraisalProfile, 'rowVersion'>) => ({ 'If-Match': `"${p.rowVersion}"` });

/**
 * Client cho `/api/v1/appraisals` của MOD-04 (QT-09, QT-10) theo hợp đồng
 * `contracts/openapi/modules/appraisals.yaml`: phiếu tự đánh giá SCR-0901 (WEB-035). Mọi thao tác ghi
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
