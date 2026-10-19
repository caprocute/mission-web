import { InjectionToken, inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, Router } from '@angular/router';
import { PermissionKey, PermissionService } from './permission.service';
import { SessionService, VtRole } from './session.service';

/** Chưa đăng nhập thì chuyển sang Keycloak (khi bật đăng nhập), giữ đường dẫn đang mở. */
export const authGuard: CanActivateFn = () => {
  const session = inject(SessionService);
  if (session.isAuthenticated()) return true;
  session.login();
  return false;
};

/**
 * Đường dẫn trang "không có quyền" mà `permissionGuard` chuyển tới khi chặn. Shell khai báo tuyến
 * này; `null` thì guard chỉ trả `false` (ví dụ remote chạy riêng không có trang đó).
 */
export const VT_FORBIDDEN_URL = new InjectionToken<string | null>('vt.forbiddenUrl', {
  providedIn: 'root',
  factory: () => '/khong-co-quyen',
});

/**
 * Cho vào tuyến khi vai trò hiện tại có ít nhất một chức năng trong `keys` theo ma trận (wiki/15a);
 * nếu không, chuyển tới trang "không có quyền" (`VT_FORBIDDEN_URL`) thay vì để màn hình trống.
 * Dùng được cho cả `canActivate` và `canMatch` (tuyến remote nên dùng `canMatch` để không tải
 * remote khi bị chặn); quyết định cuối cùng vẫn ở máy chủ (OPA, ABAC).
 */
export const permissionGuard =
  (...keys: PermissionKey[]): CanActivateFn & CanMatchFn =>
  () => {
    if (inject(PermissionService).canAny(...keys)) return true;
    const url = inject(VT_FORBIDDEN_URL);
    return url ? inject(Router).parseUrl(url) : false;
  };

/**
 * Chặn tuyến theo vai trò; quyết định cuối cùng vẫn ở máy chủ (OPA, ABAC).
 * @deprecated Dùng `permissionGuard(...)` với khoá chức năng của ma trận (WEB-054); không tự đặt
 * danh sách vai trò trong mã (AGENTS.md quy tắc 6).
 */
export const roleGuard =
  (...roles: VtRole[]): CanActivateFn =>
  () =>
    inject(SessionService).hasRole(...roles);
