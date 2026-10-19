/*
 * @vt/auth: OIDC Authorization Code + PKCE với Keycloak (ADR-08), phiên, vai trò, chọn phân công,
 * xác thực bước lên (QT-00, WEB-006) và bảng quyền phía giao diện sinh từ ma trận phân quyền
 * (wiki/15a, WEB-054).
 */
export * from './lib/session.service';
export * from './lib/permission.service';
export * from './lib/can.directive';
export * from './lib/auth.guard';
export * from './lib/auth.interceptor';
export * from './lib/provide-auth';
export * from './lib/token-storage';
export * from './lib/messages';
export * from './lib/ui/context-picker.component';
export * from './lib/ui/step-up-dialog.component';
export * from './lib/ui/session-notice.component';
