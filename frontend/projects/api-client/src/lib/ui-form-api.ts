import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { apiUrl } from './api-path';
import { UiFormVersion } from './models/ui-form';

/** Client cho biểu mẫu động `/api/v1/ui/forms` (MOD-08, QT-18) dùng ở màn hình nghiệp vụ. */
@Injectable({ providedIn: 'root' })
export class UiFormApi {
  private readonly http = inject(HttpClient);

  /**
   * Phiên bản đã phát hành đang áp dụng của biểu mẫu theo khoá (`GET /ui/forms/{key}/current`),
   * ví dụ `mau-1b` cho phiếu tự đánh giá.
   */
  current(key: string): Observable<UiFormVersion> {
    return this.http.get<UiFormVersion>(apiUrl('/ui/forms/{key}/current', { key }));
  }
}
