import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { apiUrl } from './api-path';
import type { components } from './generated/config';

type CustomRole = components['schemas']['CustomRole'];

/** Định nghĩa gọn của một vai trò tuỳ chỉnh để `PermissionService` của `@vt/auth` mở chức năng. */
export interface MyCustomRoleGrant {
  code: string;
  baseRole: string;
  keys: string[];
}

/**
 * Nạp vai trò tuỳ chỉnh người dùng đang giữ (`GET /config/me/custom-roles`, BS-04) khi shell khởi động
 * và khi đổi phân công. Lỗi thì coi như không có vai trò tuỳ chỉnh nào: giao diện chỉ ẩn bớt chức năng,
 * máy chủ vẫn quyết định cuối cùng.
 */
@Injectable({ providedIn: 'root' })
export class MyCustomRolesLoader {
  private readonly http = inject(HttpClient);

  async load(): Promise<MyCustomRoleGrant[]> {
    try {
      const r = await firstValueFrom(
        this.http.get<{ items: CustomRole[] }>(apiUrl('/config/me/custom-roles')),
      );
      return (r.items ?? []).map((c) => ({
        code: c.code,
        baseRole: c.baseRole,
        keys: c.permissions.map((p) => p.key),
      }));
    } catch {
      return [];
    }
  }
}
