import { HttpClient, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { DossierDraftFormsRequest, EsignCertificate } from './models/appraisal';
import { Job } from './models/job';

/**
 * Client cho `/api/v1/dossiers` của MOD-06 và `/api/v1/esign` của SVC-10 (QT-09, QT-11) theo hợp đồng
 * `contracts/openapi/modules/dossiers.yaml`: tải tệp biểu mẫu đã sinh (SCR-0903 "Tải Mẫu 1B", WEB-037),
 * sinh Mẫu 3 nháp và đọc chứng thư của người ký (SCR-0904, WEB-038); các màn hình QT-11 bổ sung khi làm.
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
