/*
 * @vt/auth: OIDC Authorization Code + PKCE với Keycloak (ADR-08), phiên, vai trò và bảng quyền
 * phía giao diện sinh từ ma trận phân quyền (wiki/15a, WEB-054).
 */
export * from './lib/session.service';
export * from './lib/permission.service';
export * from './lib/can.directive';
export * from './lib/auth.guard';
export * from './lib/auth.interceptor';
export * from './lib/provide-auth';
