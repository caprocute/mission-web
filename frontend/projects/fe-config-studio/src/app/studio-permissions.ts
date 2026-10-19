import { InjectionToken, inject } from '@angular/core';
import { PermissionKey, PermissionService, SessionService, rolesCan } from '@vt/auth';

/** Quyền của Config Studio: `can` theo ma trận wiki/15a, `author` chỉ tính ô chắc chắn (không `*`). */
export interface StudioAccess {
  can(key: PermissionKey): boolean;
  /**
   * Được soạn thật (không chỉ xem): ô `X` của ma trận, bỏ ô suy luận `R/Tn*` của quản trị tenant ở
   * SCR-1501. Chỉ để ẩn hiện; máy chủ vẫn trả 403 khi không có quyền.
   */
  author(key: PermissionKey): boolean;
}

export const STUDIO_ACCESS = new InjectionToken<StudioAccess>('vt.fe-config-studio.access', {
  providedIn: 'root',
  factory: () => {
    const permissions = inject(PermissionService);
    const session = inject(SessionService);
    return {
      can: (key) => permissions.can(key),
      author: (key) => rolesCan(session.user()?.roles ?? [], key, false),
    };
  },
});
