import { InjectionToken, inject } from '@angular/core';
import { PermissionService } from '@vt/auth';

/**
 * Khoá chức năng của fe-appraisal theo ma trận phân quyền (wiki/15a, `docs/phan-quyen/ma-tran.json`),
 * dạng `SCR-qqnn.<khoá>`. Chỉ liệt kê khoá mà remote đang dùng.
 */
export type AppraisalPermissionKey =
  | 'SCR-0901.xem'
  | 'SCR-0901.luu'
  | 'SCR-0901.ky-nop'
  | 'SCR-0902.xem'
  | 'SCR-0902.tra-lai'
  | 'SCR-0902.ky'
  | 'SCR-0902.xuat';

/**
 * Hàm hỏi "vai trò hiện tại có thể có quyền này không", nối vào `can()` của `@vt/auth` (WEB-054).
 * Màn hình kết hợp thêm điều kiện theo dữ liệu (chủ hồ sơ, hồ sơ ở `TU_DANH_GIA` hay `CHO_THAM_DINH`); máy chủ là nơi
 * quyết định cuối cùng và trả 403 khi chặn. Kiểm thử đơn vị thay token này bằng hàm giả.
 */
export const APPRAISAL_CAN = new InjectionToken<(key: AppraisalPermissionKey) => boolean>(
  'vt.fe-appraisal.can',
  {
    providedIn: 'root',
    factory: () => {
      const permissions = inject(PermissionService);
      return (key) => permissions.can(key);
    },
  },
);
