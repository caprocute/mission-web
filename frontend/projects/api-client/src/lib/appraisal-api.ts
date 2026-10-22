import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import {
  AnnualRatingExplanation,
  AnnualRatingPage,
  AnnualRatingRow,
  AnnualReassignmentResult,
  AppraisalBatchDecision,
  AppraisalBatchDecisionInput,
  AppraisalBatchDecisionJob,
  AppraisalBatchDecisionPage,
  AppraisalBatchDecisionStatus,
  AppraisalExportInput,
  AppraisalFlow,
  AppraisalProfile,
  AppraisalProfilePage,
  AppraisalProfileStatus,
  AppraisalProposalInput,
  AppraisalReviewInput,
  AppraisalScoreTrace,
  AppraisalScoreView,
  AppraisalSelfAssessmentInput,
  AppraisalSignRequestInput,
  DossierFormPreviewRequest,
  ProspectGroupBoard,
  ProspectGroupBoardInput,
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
const ifMatch = (p: { rowVersion: number }) => ({ 'If-Match': `"${p.rowVersion}"` });

/**
 * Client cho `/api/v1/appraisals` của MOD-04 (QT-09, QT-10) theo hợp đồng
 * `contracts/openapi/modules/appraisals.yaml`: phiếu tự đánh giá SCR-0901 (WEB-035), thẩm định SCR-0902
 * (WEB-036), phiếu xếp loại SCR-0903 (WEB-037), quyết định hàng loạt SCR-0904 (WEB-038), xếp loại năm
 * SCR-1001 và phân nhóm triển vọng SCR-1002 (WEB-039). Mọi thao tác ghi
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
   * của người gọi; phân trang con trỏ, tối đa 200 dòng một trang. Tham số query theo hợp đồng là
   * `period`, `unit` (tham số chung `PeriodId`, `UnitId` của common.yaml), không phải `periodId`, `unitId`.
   */
  profiles(
    filter: {
      periodId?: string;
      unitId?: string;
      flow?: AppraisalFlow;
      status?: AppraisalProfileStatus[];
      subjectGroup?: string;
      q?: string;
      limit?: number;
      cursor?: string | null;
    } = {},
  ): Observable<AppraisalProfilePage> {
    const params: Record<string, string | string[]> = {
      limit: String(Math.min(filter.limit ?? 50, 200)),
    };
    if (filter.periodId) params['period'] = filter.periodId;
    if (filter.subjectGroup) params['subjectGroup'] = filter.subjectGroup;
    if (filter.unitId) params['unit'] = filter.unitId;
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

  /**
   * "Ghi mức đề xuất" của quản lý trực tiếp (SCR-0903): ghi mức `DE_XUAT` khi hồ sơ ở `DA_THAM_DINH`,
   * không ký số; khác mức thẩm định thì bắt buộc lý do (422 MSG-09-12). Trả hồ sơ ở `CHO_QUYET_DINH`.
   */
  propose(p: AppraisalProfile, body: AppraisalProposalInput): Observable<AppraisalProfile> {
    return this.http.post<AppraisalProfile>(
      `/api/v1/appraisals/profiles/${enc(p.id)}/proposal`,
      body,
      { headers: ifMatch(p) },
    );
  }

  /**
   * "Quyết định và ký lô" (SCR-0904): ghi mức `QUYET_DINH` cho 1 đến 1.000 hồ sơ `CHO_QUYET_DINH` và tạo
   * yêu cầu ký lô; trả 202 kèm `Location` của job. Mỗi mục mang `rowVersion` thay cho `If-Match`. Cần
   * phiên AAL2 (401 step-up).
   */
  batchDecide(
    body: AppraisalBatchDecisionInput,
  ): Observable<{ location: string; job: AppraisalBatchDecisionJob | null }> {
    return this.http
      .post<AppraisalBatchDecisionJob | null>('/api/v1/appraisals/batch-decisions', body, {
        observe: 'response',
      })
      .pipe(map((res) => ({ location: accepted(res), job: res.body })));
  }

  /** Các lô quyết định của người dùng hiện tại (SCR-0904 "Theo dõi tiến độ"), mới nhất trước. */
  batchDecisions(
    status: AppraisalBatchDecisionStatus[] = [],
  ): Observable<AppraisalBatchDecisionPage> {
    return this.http.get<AppraisalBatchDecisionPage>('/api/v1/appraisals/batch-decisions', {
      params: status.length ? { status } : {},
    });
  }

  /** Lô quyết định theo mã, hoặc theo đường dẫn `resultLocation` của job ký lô. */
  batchDecision(idOrLocation: string): Observable<AppraisalBatchDecision> {
    const url = idOrLocation.startsWith('/')
      ? idOrLocation
      : `/api/v1/appraisals/batch-decisions/${enc(idOrLocation)}`;
    return this.http.get<AppraisalBatchDecision>(url);
  }

  /** "Huỷ lô": dừng các chữ ký chưa thực hiện; hồ sơ đã ký giữ nguyên quyết định. */
  cancelBatch(
    b: Pick<AppraisalBatchDecision, 'id' | 'rowVersion'>,
  ): Observable<AppraisalBatchDecision> {
    return this.http.post<AppraisalBatchDecision>(
      `/api/v1/appraisals/batch-decisions/${enc(b.id)}/cancel`,
      null,
      { headers: { 'If-Match': `"${b.rowVersion}"` } },
    );
  }

  /**
   * Bảng tổng hợp xếp loại năm của kỳ năm (SCR-1001): bốn quý, điểm năm, mức tự đề xuất, mức hệ thống
   * và quy tắc liên thông, kèm số đếm cho bốn thẻ. `warningsOnly` lọc hồ sơ có cảnh báo liên thông.
   */
  annual(filter: {
    period: string;
    unitId?: string;
    flow?: AppraisalFlow;
    warningsOnly?: boolean;
    q?: string;
    limit?: number;
    cursor?: string | null;
  }): Observable<AnnualRatingPage> {
    const params: Record<string, string> = {
      period: filter.period,
      limit: String(Math.min(filter.limit ?? 200, 200)),
    };
    if (filter.unitId) params['unit'] = filter.unitId;
    if (filter.flow) params['flow'] = filter.flow;
    if (filter.warningsOnly) params['flag'] = 'lien_thong';
    if (filter.q) params['q'] = filter.q;
    if (filter.cursor) params['cursor'] = filter.cursor;
    return this.http.get<AnnualRatingPage>('/api/v1/appraisals/annual', { params });
  }

  /** Giải thích liên thông quý–năm của một hồ sơ năm (vùng 2, 4 của SCR-1001). */
  annualExplanation(id: string): Observable<AnnualRatingExplanation> {
    return this.http.get<AnnualRatingExplanation>(
      `/api/v1/appraisals/annual/${enc(id)}/explanation`,
    );
  }

  /**
   * "Lập đề xuất bố trí lại" cho hồ sơ có cờ hai quý liên tiếp không hoàn thành nhiệm vụ (QĐ khoản
   * 15.6): tạo việc cho cơ quan tổ chức, không đổi mức xếp loại (EVT-04).
   */
  proposeReassignment(
    row: Pick<AnnualRatingRow, 'id' | 'rowVersion'>,
    note?: string,
  ): Observable<AnnualReassignmentResult> {
    return this.http.post<AnnualReassignmentResult>(
      `/api/v1/appraisals/annual/${enc(row.id)}/reassignment-proposal`,
      note ? { note } : {},
      { headers: ifMatch(row) },
    );
  }

  /** Bảng phân nhóm triển vọng của một đơn vị trong kỳ năm (SCR-1002); kỳ quý trả 409. */
  prospectBoard(periodId: string, orgUnitId: string): Observable<ProspectGroupBoard> {
    return this.http.get<ProspectGroupBoard>(
      `/api/v1/appraisals/prospect-groups/${enc(periodId)}/${enc(orgUnitId)}`,
    );
  }

  /** "Lưu nháp" toàn bộ danh sách phân nhóm, kèm `If-Match` của danh sách. */
  saveProspectBoard(
    board: Pick<ProspectGroupBoard, 'periodId' | 'orgUnit' | 'rowVersion'>,
    body: ProspectGroupBoardInput,
  ): Observable<ProspectGroupBoard> {
    return this.http.put<ProspectGroupBoard>(
      `/api/v1/appraisals/prospect-groups/${enc(board.periodId)}/${enc(board.orgUnit.id)}`,
      body,
      { headers: ifMatch(board) },
    );
  }

  /** "Trình tập thể lãnh đạo": khoá chỉnh sửa, danh sách sang `DA_TRINH` (EVT-11). */
  submitProspectBoard(
    board: Pick<ProspectGroupBoard, 'periodId' | 'orgUnit' | 'rowVersion'>,
  ): Observable<ProspectGroupBoard> {
    return this.http.post<ProspectGroupBoard>(
      `/api/v1/appraisals/prospect-groups/${enc(board.periodId)}/${enc(board.orgUnit.id)}/submit`,
      null,
      { headers: ifMatch(board) },
    );
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
