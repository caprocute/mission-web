import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { apiUrl } from '@vt/api-client';
import {
  CustomRole,
  CustomRoleCreate,
  CustomRoleStatus,
  CustomRoleUpdate,
  PermissionCatalog,
} from './models/custom-role';

const ifMatch = (rowVersion: number) => ({ 'If-Match': `"${rowVersion}"` });

/**
 * Client vai trò tuỳ chỉnh và danh mục quyền `/api/v1/config/custom-roles`,
 * `/config/permission-catalog` của MOD-08 (SCR-0110, BS-04) theo `contracts/openapi/modules/config.yaml`.
 * Tạo, sửa, ngừng vai trò cần phiên `aal2`; máy chủ kiểm vai trò tuỳ chỉnh không rộng hơn vai trò gốc.
 */
@Injectable({ providedIn: 'root' })
export class CustomRoleApi {
  private readonly http = inject(HttpClient);

  catalog(): Observable<PermissionCatalog> {
    return this.http.get<PermissionCatalog>(apiUrl('/config/permission-catalog'));
  }

  list(status?: CustomRoleStatus): Observable<CustomRole[]> {
    return this.http
      .get<{ items: CustomRole[] }>(apiUrl('/config/custom-roles'), {
        params: status ? { status } : {},
      })
      .pipe(map((r) => r.items ?? []));
  }

  get(id: string): Observable<CustomRole> {
    return this.http.get<CustomRole>(apiUrl('/config/custom-roles/{id}', { id }));
  }

  create(body: CustomRoleCreate): Observable<CustomRole> {
    return this.http.post<CustomRole>(apiUrl('/config/custom-roles'), body);
  }

  update(r: CustomRole, body: CustomRoleUpdate): Observable<CustomRole> {
    return this.http.put<CustomRole>(apiUrl('/config/custom-roles/{id}', { id: r.id }), body, {
      headers: ifMatch(r.rowVersion),
    });
  }

  retire(r: CustomRole, reason: string): Observable<CustomRole> {
    return this.http.post<CustomRole>(
      apiUrl('/config/custom-roles/{id}/retire', { id: r.id }),
      { reason },
      { headers: ifMatch(r.rowVersion) },
    );
  }

  /** Vai trò tuỳ chỉnh mà người dùng hiện tại đang giữ (mọi vai trò, shell nạp khi khởi động). */
  mine(): Observable<CustomRole[]> {
    return this.http
      .get<{ items: CustomRole[] }>(apiUrl('/config/me/custom-roles'))
      .pipe(map((r) => r.items ?? []));
  }
}
