import { InjectionToken, inject } from '@angular/core';
import { PermissionService } from '@vt/auth';

/**
 * Khoá chức năng của fe-task theo ma trận phân quyền (wiki/15a, `docs/phan-quyen/ma-tran.json`),
 * dạng `SCR-qqnn.<khoá>`. Chỉ liệt kê khoá mà remote đang dùng.
 */
export type TaskPermissionKey =
  | 'SCR-0401.xem'
  | 'SCR-0401.sua'
  | 'SCR-0402.xem'
  | 'SCR-0402.lap'
  | 'SCR-0402.gui-duyet'
  | 'SCR-0402.dieu-chinh'
  | 'SCR-0403.so-sanh'
  | 'SCR-0403.tra-lai'
  | 'SCR-0403.duyet-ky'
  | 'SCR-0501.soan'
  | 'SCR-0501.giao'
  | 'SCR-0501.giao-dong-loat'
  | 'SCR-0501.trung'
  | 'SCR-0502.nhan-van-ban'
  | 'SCR-0502.xac-nhan'
  | 'SCR-0502.xac-nhan-giao'
  | 'SCR-0503.xem'
  | 'SCR-0503.nhan-viec'
  | 'SCR-0503.tien-do'
  | 'SCR-0503.dang-ky'
  | 'SCR-0601.xem'
  | 'SCR-0601.tien-do'
  | 'SCR-0601.bao-nghen'
  | 'SCR-0601.nop-sp'
  | 'SCR-0601.xu-ly-nghen'
  | 'SCR-0601.duyet-de-nghi'
  | 'SCR-0602.gui'
  | 'SCR-0602.rut';

/**
 * Hàm hỏi "vai trò hiện tại có thể có quyền này không", nối vào `can()` của `@vt/auth` (WEB-055),
 * tức bảng quyền sinh từ ma trận. Màn hình kết hợp thêm điều kiện theo dữ liệu nhiệm vụ (người chủ
 * trì, trạng thái); máy chủ là nơi quyết định cuối cùng: thao tác bị chặn trả 403 và giao diện hiện
 * đúng thông báo của máy chủ. Kiểm thử đơn vị thay token này bằng hàm giả.
 */
export const TASK_CAN = new InjectionToken<(key: TaskPermissionKey) => boolean>('vt.fe-task.can', {
  providedIn: 'root',
  factory: () => {
    const permissions = inject(PermissionService);
    return (key) => permissions.can(key);
  },
});
