import { Injectable } from '@angular/core';
import { OAuthStorage } from 'angular-oauth2-oidc';

/** Khoá chỉ dùng một lần trong lúc chuyển hướng OIDC, phải sống qua lần tải lại trang. */
const REDIRECT_KEYS = new Set(['nonce', 'PKCE_verifier', 'session_state', 'requested_route']);

/**
 * Lưu token trong bộ nhớ (TL-01 mục bảo mật: token chỉ trong bộ nhớ của ứng dụng; SEC-07, SEC-08).
 * Riêng `nonce` và `PKCE_verifier` phải còn sau khi Keycloak chuyển hướng về, nên giữ trong
 * `sessionStorage` của tab; `SessionService` xoá hai khoá này ngay sau khi đổi mã lấy token.
 * Tải lại trang thì token mất, ứng dụng đăng nhập lại qua SSO của Keycloak (thường không phải
 * nhập mật khẩu).
 */
@Injectable()
export class VtTokenStorage implements OAuthStorage {
  private readonly memory = new Map<string, string>();

  getItem(key: string): string | null {
    if (REDIRECT_KEYS.has(key)) return safeSession()?.getItem(key) ?? null;
    return this.memory.get(key) ?? null;
  }

  setItem(key: string, data: string): void {
    if (REDIRECT_KEYS.has(key)) safeSession()?.setItem(key, data);
    else this.memory.set(key, data);
  }

  removeItem(key: string): void {
    if (REDIRECT_KEYS.has(key)) safeSession()?.removeItem(key);
    else this.memory.delete(key);
  }
}

function safeSession(): Storage | null {
  try {
    return globalThis.sessionStorage ?? null;
  } catch {
    return null;
  }
}
