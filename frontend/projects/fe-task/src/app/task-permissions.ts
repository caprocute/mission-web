import { InjectionToken } from '@angular/core';

/**
 * Khoá chức năng của fe-task theo ma trận phân quyền (wiki/15a, `docs/phan-quyen/ma-tran.json`),
 * dạng `SCR-qqnn.<khoá>`. Chỉ liệt kê khoá mà remote đang dùng.
 */
export type TaskPermissionKey =
  'SCR-0503.xem' | 'SCR-0503.nhan-viec' | 'SCR-0503.tien-do' | 'SCR-0503.dang-ky';

/**
 * Hàm hỏi "vai trò hiện tại có thể có quyền này không". Bảng quyền dùng chung `can()` của `@vt/auth`
 * do WEB-054 làm và WEB-055 gắn vào đây; trong lúc chờ, mặc định cho phép vì mọi chức năng SCR-0503
 * đều là `T/B` hoặc `R/B` của AC-06 (`member`), tức mọi người đã đăng nhập vào được tuyến `/tasks`.
 * Điều kiện theo đối tượng (người chủ trì, trạng thái) vẫn xét theo dữ liệu nhiệm vụ, và máy chủ là
 * nơi quyết định cuối cùng: thao tác bị chặn trả 403 và giao diện hiện đúng thông báo của máy chủ.
 */
export const TASK_CAN = new InjectionToken<(key: TaskPermissionKey) => boolean>('vt.fe-task.can', {
  providedIn: 'root',
  factory: () => () => true,
});
