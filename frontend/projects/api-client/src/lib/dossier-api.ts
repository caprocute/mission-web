import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from './api-path';
import { DossierDraftFormsRequest, EsignCertificate } from './models/appraisal';
import {
  DossierComment,
  DossierCommentCreate,
  DossierCommentPage,
  DossierMeeting,
  DossierMeetingCreate,
  DossierMeetingPage,
  DossierMeetingStatus,
  DossierMeetingUpdate,
  DossierMinutesRequest,
  DossierRemediationItem,
  DossierRemediationItemPage,
  DossierRemediationItemUpdate,
  DossierSpecialCasePage,
} from './models/dossier';
import { Job } from './models/job';
import { MAX_PAGE_LIMIT } from './paging';

const ifMatch = (o: { rowVersion: number }) => ({ 'If-Match': `"${o.rowVersion}"` });

/**
 * Client cho `/api/v1/dossiers` của MOD-06 và `/api/v1/esign` của SVC-10 (QT-09, QT-11) theo hợp đồng
 * `contracts/openapi/modules/dossiers.yaml`: tải tệp biểu mẫu đã sinh (SCR-0903 "Tải Mẫu 1B", WEB-037),
 * sinh Mẫu 3 nháp và đọc chứng thư của người ký (SCR-0904, WEB-038); hội nghị kiểm điểm, góp ý, biên bản,
 * khắc phục hạn chế và trường hợp đặc thù (SCR-1101, WEB-040). Thao tác ghi lên hội nghị và hạn chế gửi
 * `If-Match` bằng `rowVersion`.
 */
@Injectable({ providedIn: 'root' })
export class DossierApi {
  private readonly http = inject(HttpClient);

  /** Tệp PDF/A của biểu mẫu (bản đã nhúng chữ ký nếu đã ký); máy chủ ghi nhật ký truy cập. */
  formFile(id: string): Observable<Blob> {
    return this.http.get(`/api/v1/dossiers/forms/${encodeURIComponent(id)}/file`, {
      responseType: 'blob',
    });
  }

  /**
   * Sinh biểu mẫu nháp từ danh sách hồ sơ đang chọn (tối đa 200 hồ sơ), ví dụ "Xuất Mẫu 3"; trả 202 kèm
   * `Location` của job, mã biểu mẫu nằm ở `resultLocation` khi job hoàn tất.
   */
  draftForms(body: DossierDraftFormsRequest): Observable<{ location: string }> {
    return this.http.post<Job | null>('/api/v1/dossiers/forms', body, { observe: 'response' }).pipe(
      map((res: HttpResponse<Job | null>) => {
        const loc =
          res.headers.get('Location') ?? (res.body?.id ? `/api/v1/jobs/${res.body.id}` : null);
        if (!loc) throw new Error('Phản hồi 202 thiếu Location');
        return { location: loc };
      }),
    );
  }

  /** Hội nghị kiểm điểm trong phạm vi người gọi (đường vào "Kiểm điểm" của SCR-1101). */
  meetings(
    filter: { periodId?: string; unitId?: string; status?: DossierMeetingStatus } = {},
  ): Observable<DossierMeetingPage> {
    const params: Record<string, string | number> = { limit: MAX_PAGE_LIMIT };
    if (filter.periodId) params['period'] = filter.periodId;
    if (filter.unitId) params['unit'] = filter.unitId;
    if (filter.status) params['status'] = filter.status;
    return this.http.get<DossierMeetingPage>(apiUrl('/dossiers/meetings'), { params });
  }

  meeting(id: string): Observable<DossierMeeting> {
    return this.http.get<DossierMeeting>(apiUrl('/dossiers/meetings/{id}', { id }));
  }

  /** Lập kế hoạch kiểm điểm (QT-11 bước 1); máy chủ phát hiện trường hợp đặc thù và sinh dự thảo. */
  createMeeting(body: DossierMeetingCreate): Observable<DossierMeeting> {
    return this.http.post<DossierMeeting>(apiUrl('/dossiers/meetings'), body);
  }

  /** Dời ngày, sửa địa điểm, thành phần hoặc ghi lý do giữ ngày họp (BR-11-01). */
  updateMeeting(m: DossierMeeting, body: DossierMeetingUpdate): Observable<DossierMeeting> {
    return this.http.patch<DossierMeeting>(apiUrl('/dossiers/meetings/{id}', { id: m.id }), body, {
      headers: ifMatch(m),
    });
  }

  /** "Gửi dự thảo": trả hội nghị kèm kết quả kiểm tra thời hạn (MSG-11-01 khi không đạt). */
  sendDraft(m: DossierMeeting): Observable<DossierMeeting> {
    return this.http.post<DossierMeeting>(
      apiUrl('/dossiers/meetings/{id}/send-draft', { id: m.id }),
      null,
      { headers: ifMatch(m) },
    );
  }

  comments(id: string): Observable<DossierCommentPage> {
    return this.http.get<DossierCommentPage>(apiUrl('/dossiers/meetings/{id}/comments', { id }), {
      params: { limit: MAX_PAGE_LIMIT },
    });
  }

  /** "Gửi góp ý" (thành viên) hoặc ghi ý kiến tại hội nghị (thư ký, `phase = HOI_NGHI`). */
  addComment(id: string, body: DossierCommentCreate): Observable<DossierComment> {
    return this.http.post<DossierComment>(apiUrl('/dossiers/meetings/{id}/comments', { id }), body);
  }

  /** "Sinh biên bản": điểm danh và biểu quyết; trả 202 kèm `Location` của job (cần phiên AAL2). */
  minutes(m: DossierMeeting, body: DossierMinutesRequest): Observable<{ location: string }> {
    return this.http
      .post<Job | null>(apiUrl('/dossiers/meetings/{id}/minutes', { id: m.id }), body, {
        headers: ifMatch(m),
        observe: 'response',
      })
      .pipe(
        map((res) => {
          const loc =
            res.headers.get('Location') ?? (res.body?.id ? `/api/v1/jobs/${res.body.id}` : null);
          if (!loc) throw new Error('Phản hồi 202 thiếu Location');
          return { location: loc };
        }),
      );
  }

  /** "Kết thúc hội nghị" (chủ trì): khoá góp ý, ghi hạn chế cần khắc phục, yêu cầu ký biên bản. */
  close(m: DossierMeeting): Observable<DossierMeeting> {
    return this.http.post<DossierMeeting>(
      apiUrl('/dossiers/meetings/{id}/close', { id: m.id }),
      null,
      { headers: ifMatch(m) },
    );
  }

  remediationItems(
    id: string,
    origin?: 'KY_TRUOC' | 'HOI_NGHI_NAY',
  ): Observable<DossierRemediationItemPage> {
    const params: Record<string, string | number> = { limit: MAX_PAGE_LIMIT };
    if (origin) params['origin'] = origin;
    return this.http.get<DossierRemediationItemPage>(
      apiUrl('/dossiers/meetings/{id}/remediation-items', { id }),
      { params },
    );
  }

  /** Thư ký cập nhật trạng thái khắc phục một hạn chế kỳ trước. */
  updateRemediation(
    item: DossierRemediationItem,
    body: DossierRemediationItemUpdate,
  ): Observable<DossierRemediationItem> {
    return this.http.patch<DossierRemediationItem>(
      apiUrl('/dossiers/remediation-items/{id}', { id: item.id }),
      body,
      { headers: ifMatch(item) },
    );
  }

  /** Trường hợp đặc thù phát hiện trong kỳ của đơn vị (BR-11-05). */
  specialCases(filter: { periodId: string; unitId?: string }): Observable<DossierSpecialCasePage> {
    const params: Record<string, string | number> = {
      limit: MAX_PAGE_LIMIT,
      period: filter.periodId,
    };
    if (filter.unitId) params['unit'] = filter.unitId;
    return this.http.get<DossierSpecialCasePage>(apiUrl('/dossiers/special-cases'), { params });
  }

  /** Chứng thư ký số đã đăng ký của người dùng, kèm hiệu lực (SCR-0904 trường 7). */
  myCertificates(): Observable<EsignCertificate[]> {
    return this.http
      .get<{ items: EsignCertificate[] }>('/api/v1/esign/certificates/mine')
      .pipe(map((r) => r.items));
  }
}

/** Mã biểu mẫu ở cuối `resultLocation` của job sinh biểu mẫu (`/api/v1/dossiers/forms/{id}`). */
export function formIdOf(resultLocation: string): string {
  return decodeURIComponent(
    resultLocation
      .replace(/\/file$/, '')
      .split('/')
      .pop() ?? '',
  );
}
