import { inject } from '@angular/core';
import { CanActivateFn, CanMatchFn } from '@angular/router';
import { PermissionKey, PermissionService } from './permission.service';
import { SessionService, VtRole } from './session.service';

export const authGuard: CanActivateFn = () => inject(SessionService).isAuthenticated();

/**
 * Cho vào tuyến khi vai trò hiện tại có ít nhất một chức năng trong `keys` theo ma trận (wiki/15a).
 * Dùng được cho cả `canActivate` và `canMatch`; quyết định cuối cùng vẫn ở máy chủ (OPA, ABAC).
 */
export const permissionGuard =
  (...keys: PermissionKey[]): CanActivateFn & CanMatchFn =>
  () =>
    inject(PermissionService).canAny(...keys);

/**
 * Chặn tuyến theo vai trò; quyết định cuối cùng vẫn ở máy chủ (OPA, ABAC).
 * @deprecated Dùng `permissionGuard(...)` với khoá chức năng của ma trận (WEB-054); không tự đặt
 * danh sách vai trò trong mã (AGENTS.md quy tắc 6).
 */
export const roleGuard =
  (...roles: VtRole[]): CanActivateFn =>
  () =>
    inject(SessionService).hasRole(...roles);
