import { InjectionToken, Injector, inject } from '@angular/core';
import { PermissionKey, PermissionService, SessionService } from '@vt/auth';

/**
 * Hàm hỏi "vai trò của phân công đang chọn có thể có quyền này không", nối vào `can()` của `@vt/auth`
 * (bảng quyền sinh từ ma trận wiki/15a). Chỉ để ẩn hiện nhanh; máy chủ (OPA, securityContext của lớp ngữ
 * nghĩa) quyết định cuối cùng, thao tác bị chặn trả 403 và màn hình hiện thông báo. Kiểm thử thay hàm giả.
 */
export const DASHBOARD_CAN = new InjectionToken<(key: PermissionKey) => boolean>(
  'vt.fe-dashboard.can',
  {
    providedIn: 'root',
    factory: () => {
      const permissions = inject(PermissionService);
      return (key) => permissions.can(key);
    },
  },
);

/** Mã người dùng hiện tại (`sub`, `dev-user` khi tắt đăng nhập), để nhận ra dashboard của chính mình. */
export const DASHBOARD_USER_ID = new InjectionToken<() => string | null>(
  'vt.fe-dashboard.user-id',
  {
    providedIn: 'root',
    factory: () => {
      const injector = inject(Injector);
      return () => {
        try {
          return injector.get(SessionService).user()?.id ?? null;
        } catch {
          return null;
        }
      };
    },
  },
);
