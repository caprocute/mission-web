import { InjectionToken } from '@angular/core';

/**
 * Khoá chức năng của fe-task theo ma trận phân quyền (wiki/15a, `docs/phan-quyen/ma-tran.json`),
 * dạng `SCR-qqnn.<khoá>`. Chỉ liệt kê khoá mà remote đang dùng.
 */
export type TaskPermissionKey =
  | 'SCR-0401.xem'
  | 'SCR-0401.sua'
  | 'SCR-0402.lap'
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
  | 'SCR-0602.gui'
  | 'SCR-0602.rut';

/**
 * Hàm hỏi "vai trò hiện tại có thể có quyền này không". Bảng quyền dùng chung `can()` của `@vt/auth`
 * do WEB-054 làm và WEB-055 gắn vào đây; trong lúc chờ, mặc định cho phép vì mọi chức năng SCR-0503
 * và SCR-0602 mà fe-task dùng đều là `T/B` hoặc `R/B` của AC-06 (`member`), tức mọi người đã đăng nhập vào được tuyến `/tasks`.
 * Điều kiện theo đối tượng (người chủ trì, trạng thái) vẫn xét theo dữ liệu nhiệm vụ, và máy chủ là
 * nơi quyết định cuối cùng: thao tác bị chặn trả 403 và giao diện hiện đúng thông báo của máy chủ.
 * Riêng khoá `SCR-0401.sua` (AC-03, AC-05 được giao), `SCR-0402.lap`, `SCR-0501.*` và `SCR-0502.*` (AC-05) theo wiki/15a; mặc định cho phép ở đây chỉ để chạy
 * với dữ liệu giả lập khi chưa đăng nhập, WEB-055 phải thay bằng `can()` thật trước khi bật đăng nhập.
 */
export const TASK_CAN = new InjectionToken<(key: TaskPermissionKey) => boolean>('vt.fe-task.can', {
  providedIn: 'root',
  factory: () => () => true,
});
