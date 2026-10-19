import { inject } from '@angular/core';
import { CanActivateFn } from '@angular/router';
import { SessionService, VtRole } from './session.service';

export const authGuard: CanActivateFn = () => inject(SessionService).isAuthenticated();

/** Chặn tuyến theo vai trò; quyết định cuối cùng vẫn ở máy chủ (OPA, ABAC). */
export const roleGuard = (...roles: VtRole[]): CanActivateFn => () => inject(SessionService).hasRole(...roles);
