import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from './api-path';
import {
  HomeLayout,
  PrefKey,
  SavedFilter,
  SavedFilterInput,
  UserPreferenceItem,
  UserPreferenceMap,
} from './models/preference';

/**
 * Client tuỳ chọn cá nhân của người dùng đang đăng nhập (CNH-02, `config.yaml`):
 * `/api/v1/config/me/preferences` và bố cục trang chủ đã ghép lớp phủ `/api/v1/ui/layouts/home`.
 * Ghi kèm `Idempotency-Key` (interceptor tự thêm); khoá đã có thì gửi `If-Match` theo
 * `rowVersion`, khoá chưa lưu thì bỏ trống để máy chủ tạo mới (201).
 */
@Injectable({ providedIn: 'root' })
export class PreferenceApi {
  private readonly http = inject(HttpClient);

  all(): Observable<UserPreferenceMap> {
    return this.http.get<UserPreferenceMap>(apiUrl('/config/me/preferences'));
  }

  /** Lưu một khoá; `rowVersion` là phiên bản đã đọc, `null` khi khoá chưa từng lưu. */
  put<T extends object>(
    key: PrefKey,
    value: T,
    rowVersion: number | null,
  ): Observable<UserPreferenceItem> {
    return this.http.put<UserPreferenceItem>(
      apiUrl('/config/me/preferences/{prefKey}', { prefKey: key }),
      { value },
      rowVersion === null ? {} : { headers: { 'If-Match': `"${rowVersion}"` } },
    );
  }

  /** Xoá lớp phủ cá nhân của khoá, trở về mặc định của đơn vị. */
  remove(key: PrefKey, rowVersion: number): Observable<void> {
    return this.http.delete<void>(apiUrl('/config/me/preferences/{prefKey}', { prefKey: key }), {
      headers: { 'If-Match': `"${rowVersion}"` },
    });
  }

  /** Bố cục trang chủ máy chủ đã lọc theo vai trò và áp lớp phủ cá nhân. */
  homeLayout(): Observable<HomeLayout> {
    return this.http.get<HomeLayout>(apiUrl('/ui/layouts/home'));
  }

  /** Bộ lọc đã lưu của người dùng trên một màn hình (`SCR-xxxx`). */
  filters(screenCode: string): Observable<SavedFilter[]> {
    return this.http
      .get<{ items: SavedFilter[] }>(apiUrl('/config/me/saved-filters'), { params: { screenCode } })
      .pipe(map((r) => r.items));
  }

  createFilter(input: SavedFilterInput): Observable<SavedFilter> {
    return this.http.post<SavedFilter>(apiUrl('/config/me/saved-filters'), input);
  }

  updateFilter(id: string, input: SavedFilterInput, rowVersion: number): Observable<SavedFilter> {
    return this.http.put<SavedFilter>(apiUrl('/config/me/saved-filters/{id}', { id }), input, {
      headers: { 'If-Match': `"${rowVersion}"` },
    });
  }

  removeFilter(id: string, rowVersion: number): Observable<void> {
    return this.http.delete<void>(apiUrl('/config/me/saved-filters/{id}', { id }), {
      headers: { 'If-Match': `"${rowVersion}"` },
    });
  }
}
