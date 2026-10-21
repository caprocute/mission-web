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
  DossierFormPage,
  DossierFormStatus,
  DossierPackage,
  DossierPackageChecklist,
  DossierPackagePage,
  DossierPackageSignRequest,
  DossierPackageStatus,
  DossierSignJob,
  DossierTransferRequest,
  DossierRemediationItem,
  DossierRemediationItemPage,
  DossierRemediationItemUpdate,
  DossierSpecialCasePage,
} from './models/dossier';
import { Job } from './models/job';
import { MAX_PAGE_LIMIT } from './paging';

const ifMatch = (o: { rowVersion: number }) => ({ 'If-Match': `"${o.rowVersion}"` });

/** `Location` của phản hồi 202; máy chủ cũ chỉ trả thân job thì dựng từ mã job. */
function locationOf(res: HttpResponse<Job | null>): string {
  const loc = res.headers.get('Location') ?? (res.body?.id ? `/api/v1/jobs/${res.body.id}` : null);
  if (!loc) throw new Error('Phản hồi 202 thiếu Location');
  return loc;
}

/**
 * Client cho `/api/v1/dossiers` của MOD-06 và `/api/v1/esign` của SVC-10 (QT-09, QT-11) theo hợp đồng
 * `contracts/openapi/modules/dossiers.yaml`: tải tệp biểu mẫu đã sinh (SCR-0903 "Tải Mẫu 1B", WEB-037),
 * sinh Mẫu 3 nháp và đọc chứng thư của người ký (SCR-0904, WEB-038); hội nghị kiểm điểm, góp ý, biên bản,
 * khắc phục hạn chế và trường hợp đặc thù (SCR-1101, WEB-040); gói hồ sơ số, sinh lại mẫu lỗi, tải gói,
 * gửi gói con diện Trung ương và ký số gói (SCR-1102, WEB-041). Thao tác ghi lên hội nghị, hạn chế và gói
 * gửi `If-Match` bằng `rowVersion`.
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

  /** Gói hồ sơ số trong phạm vi người gọi (đường vào "Hồ sơ số › Gói hồ sơ số" của SCR-1102). */
  packages(
    filter: { periodId?: string; unitId?: string; status?: DossierPackageStatus } = {},
  ): Observable<DossierPackagePage> {
    const params: Record<string, string | number> = { limit: MAX_PAGE_LIMIT };
    if (filter.periodId) params['period'] = filter.periodId;
    if (filter.unitId) params['unit'] = filter.unitId;
    if (filter.status) params['status'] = filter.status;
    return this.http.get<DossierPackagePage>(apiUrl('/dossiers/packages'), { params });
  }

  package(id: string): Observable<DossierPackage> {
    return this.http.get<DossierPackage>(apiUrl('/dossiers/packages/{id}', { id }));
  }

  /** Vùng 1: biểu mẫu sinh từ dữ liệu của gói. */
  packageForms(id: string, status?: DossierFormStatus): Observable<DossierFormPage> {
    const params: Record<string, string | number> = { limit: MAX_PAGE_LIMIT };
    if (status) params['status'] = status;
    return this.http.get<DossierFormPage>(apiUrl('/dossiers/packages/{id}/forms', { id }), {
      params,
    });
  }

  /** Vùng 3: danh sách kiểm tra trước khi ký gói (BR-11-09), máy chủ tính tức thời. */
  packageChecklist(id: string): Observable<DossierPackageChecklist> {
    return this.http.get<DossierPackageChecklist>(
      apiUrl('/dossiers/packages/{id}/checklist', { id }),
    );
  }

  /** "Sinh lại mẫu lỗi": gửi lại các render job lỗi (202, EVT-17). */
  regenerate(p: DossierPackage, formIds?: string[]): Observable<{ location: string }> {
    return this.http
      .post<Job | null>(
        apiUrl('/dossiers/packages/{id}/regenerate', { id: p.id }),
        formIds?.length ? { formIds } : {},
        { headers: ifMatch(p), observe: 'response' },
      )
      .pipe(map((res) => ({ location: locationOf(res) })));
  }

  /** "Tải gói (ZIP và manifest)": tệp ở `resultLocation` của job khi hoàn tất. */
  exportPackage(id: string): Observable<{ location: string }> {
    return this.http
      .post<Job | null>(apiUrl('/dossiers/packages/{id}/export', { id }), null, {
        observe: 'response',
      })
      .pipe(map((res) => ({ location: locationOf(res) })));
  }

  /** "Gửi gói con" diện Trung ương qua integration-gateway (BR-11-11). */
  transfer(id: string, body: DossierTransferRequest): Observable<{ location: string }> {
    return this.http
      .post<Job | null>(apiUrl('/dossiers/packages/{id}/transfers', { id }), body, {
        observe: 'response',
      })
      .pipe(map((res) => ({ location: locationOf(res) })));
  }

  /**
   * "Ký số gói và nộp" (cần phiên AAL2): máy chủ kiểm lại danh sách kiểm tra, tạo yêu cầu ký ở SVC-10 và trả
   * 202 kèm `signRequestId`; khi EVT-18 đến gói chuyển `DA_NOP` và bị khoá (BR-11-10).
   */
  signPackage(
    p: DossierPackage,
    body: DossierPackageSignRequest = {},
  ): Observable<{ location: string; signRequestId: string | null }> {
    return this.http
      .post<DossierSignJob | null>(apiUrl('/dossiers/packages/{id}/sign', { id: p.id }), body, {
        headers: ifMatch(p),
        observe: 'response',
      })
      .pipe(
        map((res) => ({
          location: locationOf(res),
          signRequestId: res.body?.signRequestId ?? null,
        })),
      );
  }

  /** Tải tệp kết quả của một job (đường dẫn do máy chủ trả trong `resultLocation`). */
  download(resultLocation: string): Observable<HttpResponse<Blob>> {
    return this.http.get(resultLocation.replace(/^https?:\/\/[^/]+/, ''), {
      responseType: 'blob',
      observe: 'response',
    });
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
