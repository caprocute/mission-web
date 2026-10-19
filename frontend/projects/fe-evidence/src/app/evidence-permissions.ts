import { InjectionToken, inject } from '@angular/core';
import { PermissionService } from '@vt/auth';

/**
 * Khoá chức năng của fe-evidence theo ma trận phân quyền (wiki/15a, `docs/phan-quyen/ma-tran.json`),
 * dạng `SCR-qqnn.<khoá>`. Chỉ liệt kê khoá mà remote đang dùng.
 */
export type EvidencePermissionKey =
  | 'SCR-0701.xem'
  | 'SCR-0701.soan'
  | 'SCR-0701.nop'
  | 'SCR-0703.mo'
  | 'SCR-0703.xac-nhan'
  | 'SCR-0703.bo-sung-tu-choi';

/**
 * Hàm hỏi "vai trò hiện tại có thể có quyền này không", nối vào `can()` của `@vt/auth` (WEB-054).
 * Màn hình kết hợp thêm điều kiện theo dữ liệu (người nộp là chủ trì nhiệm vụ, người nghiệm thu được chỉ định, trạng thái phiên bản);
 * máy chủ là nơi quyết định cuối cùng và trả 403 khi chặn. Kiểm thử đơn vị thay token này bằng hàm giả.
 */
export const EVIDENCE_CAN = new InjectionToken<(key: EvidencePermissionKey) => boolean>(
  'vt.fe-evidence.can',
  {
    providedIn: 'root',
    factory: () => {
      const permissions = inject(PermissionService);
      return (key) => permissions.can(key);
    },
  },
);
