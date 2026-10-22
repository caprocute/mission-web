import { Injectable, computed, inject, signal } from '@angular/core';
import { DashboardDefaultPreference, PreferenceApi, UserPreferenceItem } from '@vt/api-client';
import { Observable, map, tap } from 'rxjs';

const KEY = 'ui.dashboard.default';

/**
 * Dashboard mặc định của người dùng (CNH-03, SCR-1602), lưu ở khoá tuỳ chọn cá nhân
 * `ui.dashboard.default` (CNH-02) nên theo người dùng sang mọi thiết bị. Ghi kèm `If-Match` theo
 * `rowVersion` đã đọc; giữ nguyên các trường khác của khoá (chỉ số ghim, loại kỳ).
 */
@Injectable({ providedIn: 'root' })
export class DefaultDashboardStore {
  private readonly api = inject(PreferenceApi);
  private readonly item = signal<UserPreferenceItem | null>(null);
  private readonly loaded = signal(false);

  readonly defaultId = computed(
    () =>
      (this.item()?.value as DashboardDefaultPreference | undefined)?.defaultDashboardId ?? null,
  );
  readonly ready = this.loaded.asReadonly();

  load(): Observable<string | null> {
    return this.api.all().pipe(
      tap((all) => {
        this.item.set(all.items[KEY] ?? null);
        this.loaded.set(true);
      }),
      map(() => this.defaultId()),
    );
  }

  /** Đặt (`id`) hoặc bỏ (`null`) dashboard mặc định. */
  set(id: string | null): Observable<unknown> {
    const current = this.item();
    const { defaultDashboardId: _old, ...rest } = (current?.value ??
      {}) as DashboardDefaultPreference;
    const value: DashboardDefaultPreference = id ? { ...rest, defaultDashboardId: id } : rest;
    if (!id && current && Object.keys(rest).length === 0) {
      return this.api.remove(KEY, current.rowVersion).pipe(tap(() => this.item.set(null)));
    }
    return this.api
      .put(KEY, value, current?.rowVersion ?? null)
      .pipe(tap((saved) => this.item.set(saved)));
  }
}
