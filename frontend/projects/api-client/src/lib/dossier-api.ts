import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Client cho `/api/v1/dossiers` của MOD-06 (QT-09, QT-11) theo hợp đồng
 * `contracts/openapi/modules/dossiers.yaml`. Hiện có thao tác tải tệp biểu mẫu đã sinh, dùng ở SCR-0903
 * "Tải Mẫu 1B" (WEB-037); các màn hình QT-11 bổ sung khi làm.
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
}
