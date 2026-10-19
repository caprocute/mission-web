import { Injectable, computed, inject, signal } from '@angular/core';
import { OAuthService } from 'angular-oauth2-oidc';
import { RUNTIME_CONFIG } from '@vt/api-client';

/** Vai trò hệ thống (baseline mục 10, AC-01…AC-12). */
export type VtRole =
  | 'platform_admin' | 'tenant_admin' | 'policy_author' | 'leader' | 'manager' | 'member'
  | 'appraiser' | 'decider' | 'second_approver' | 'inspector' | 'ai_readonly';

export interface VtUser {
  id: string;
  name: string;
  email?: string;
  tenant: string;
  /** Đơn vị đang làm việc (QT-00: chọn đơn vị khi kiêm nhiệm). */
  unitId?: string;
  roles: VtRole[];
}

const DEV_USER: VtUser = {
  id: 'dev-user', name: 'Cán bộ thử nghiệm', email: 'dev@mission.local', tenant: 'default',
  unitId: 'phong-tong-hop', roles: ['member', 'manager'],
};

/**
 * Phiên đăng nhập OIDC Authorization Code + PKCE với Keycloak (ADR-08, QT-00, SCR-0001).
 * Khi `auth.enabled = false` (dev không có Keycloak), dùng người dùng giả lập.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly oauth = inject(OAuthService);
  private readonly cfg = inject(RUNTIME_CONFIG);

  private readonly _user = signal<VtUser | null>(null);
  readonly user = this._user.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null);

  async init(): Promise<void> {
    if (!this.cfg.auth.enabled) {
      this._user.set({ ...DEV_USER, tenant: this.cfg.tenant });
      return;
    }
    this.oauth.configure({
      issuer: this.cfg.auth.issuer,
      clientId: this.cfg.auth.clientId,
      responseType: 'code',
      scope: this.cfg.auth.scope,
      redirectUri: window.location.origin + '/',
      postLogoutRedirectUri: window.location.origin + '/',
      useSilentRefresh: false,
      sessionChecksEnabled: false,
      showDebugInformation: false,
      // Keycloak 26 yêu cầu state + nonce; thư viện tự sinh và kiểm tra (SEC-15, SEC-54)
    });
    this.oauth.setupAutomaticSilentRefresh();
    await this.oauth.loadDiscoveryDocumentAndLogin();
    this.readClaims();
  }

  hasRole(...roles: VtRole[]): boolean {
    const u = this._user();
    return !!u && roles.some((r) => u.roles.includes(r));
  }

  accessToken(): string | null {
    return this.cfg.auth.enabled ? this.oauth.getAccessToken() : null;
  }

  logout(): void {
    if (this.cfg.auth.enabled) {
      // gọi end_session_endpoint của Keycloak, không trỏ trang đăng nhập riêng của hệ cũ
      this.oauth.logOut();
    } else {
      this._user.set(null);
    }
  }

  private readClaims(): void {
    const c = this.oauth.getIdentityClaims() as Record<string, unknown> | null;
    if (!c) return;
    const realmRoles = ((c['realm_access'] as { roles?: string[] } | undefined)?.roles ?? []) as VtRole[];
    this._user.set({
      id: String(c['sub']),
      name: String(c['name'] ?? c['preferred_username'] ?? ''),
      email: c['email'] as string | undefined,
      tenant: String(c['tenant'] ?? this.cfg.tenant),
      unitId: c['unit_id'] as string | undefined,
      roles: realmRoles,
    });
  }
}
