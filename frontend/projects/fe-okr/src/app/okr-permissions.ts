import { InjectionToken, inject } from '@angular/core';
import { PermissionService } from '@vt/auth';

/**
 * Khoá chức năng của fe-okr theo ma trận phân quyền (wiki/15a, `docs/phan-quyen/ma-tran.json`),
 * dạng `SCR-qqnn.<khoá>`. Chỉ liệt kê khoá mà remote đang dùng.
 */
export type OkrPermissionKey = 'SCR-0201.xem' | 'SCR-0201.them' | 'SCR-0201.check-in';

/**
 * Hàm hỏi "vai trò hiện tại có thể có quyền này không", nối vào `can()` của `@vt/auth` (WEB-054).
 * Màn hình kết hợp thêm điều kiện theo dữ liệu (kỳ chưa khoá, đơn vị của ngữ cảnh, chủ sở hữu KR,
 * mục tiêu `DA_DUYET`); máy chủ là nơi quyết định cuối cùng và trả 403 khi chặn.
 * Kiểm thử đơn vị thay token này bằng hàm giả.
 */
export const OKR_CAN = new InjectionToken<(key: OkrPermissionKey) => boolean>('vt.fe-okr.can', {
  providedIn: 'root',
  factory: () => {
    const permissions = inject(PermissionService);
    return (key) => permissions.can(key);
  },
});
