import { InjectionToken, inject } from '@angular/core';
import { PermissionService } from '@vt/auth';

/**
 * Khoá chức năng của fe-okr theo ma trận phân quyền (wiki/15a, `docs/phan-quyen/ma-tran.json`),
 * dạng `SCR-qqnn.<khoá>`. Chỉ liệt kê khoá mà remote đang dùng.
 */
export type OkrPermissionKey =
  | 'SCR-0201.xem'
  | 'SCR-0201.them'
  | 'SCR-0201.check-in'
  | 'SCR-0202.soan-don-vi'
  | 'SCR-0202.soan-ca-nhan'
  | 'SCR-0202.ai-soat'
  | 'SCR-0202.trinh'
  | 'SCR-0203.xem'
  | 'SCR-0203.duyet'
  | 'SCR-0203.tra-lai'
  | 'SCR-0203.duyet-lo'
  | 'SCR-0301.check-in'
  | 'SCR-0301.lich-su'
  | 'SCR-1701.hoi'
  | 'SCR-1701.dung-goi-y'
  | 'SCR-1701.trinh';

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
