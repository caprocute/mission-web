import { InjectionToken, inject } from '@angular/core';
import { PermissionKey, PermissionService } from '@vt/auth';

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
