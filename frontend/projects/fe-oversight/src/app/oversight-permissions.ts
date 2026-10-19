import { InjectionToken, Injector, inject } from '@angular/core';
import { PermissionKey, PermissionService, SessionService } from '@vt/auth';

/**
 * Hàm hỏi "vai trò của phân công đang chọn có thể có quyền này không", nối vào `can()` của `@vt/auth`
 * (bảng quyền sinh từ ma trận wiki/15a). Chỉ để ẩn hiện nhanh; máy chủ (OPA) quyết định cuối cùng, thao
 * tác bị chặn trả 403 và màn hình hiện thông báo tương ứng. Kiểm thử thay bằng hàm giả.
 */
export const OVERSIGHT_CAN = new InjectionToken<(key: PermissionKey) => boolean>(
  'vt.fe-oversight.can',
  {
    providedIn: 'root',
    factory: () => {
      const permissions = inject(PermissionService);
      return (key) => permissions.can(key);
    },
  },
);

/**
 * Mã người dùng hiện tại (`sub` của token, `dev-user` khi tắt đăng nhập), để nhận ra khiếu nại của chính
 * mình (SCR-1201) và yêu cầu giải trình gửi tới mình (SCR-1202 chế độ bên liên quan). Máy chủ vẫn lọc theo
 * phạm vi; giá trị này chỉ dùng để chọn cách hiển thị.
 */
export const OVERSIGHT_USER_ID = new InjectionToken<() => string | null>(
  'vt.fe-oversight.user-id',
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
