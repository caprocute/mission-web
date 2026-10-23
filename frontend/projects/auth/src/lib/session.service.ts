import { DestroyRef, Injectable, computed, inject, signal } from '@angular/core';
import { OAuthService, OAuthStorage } from 'angular-oauth2-oidc';
import { firstValueFrom } from 'rxjs';
import { Assignment, OrgApi, RUNTIME_CONFIG, toProblem } from '@vt/api-client';
import {
  MSG_00_05,
  MSG_00_06,
  MSG_00_07,
  ROLE_LABEL,
  TEXT_IDP_UNAVAILABLE,
  TEXT_SESSION_EXPIRED,
} from './messages';

/**
 * Vai trò hệ thống (baseline mục 10, AC-01…AC-12) và vai trò con khai báo ở cột keycloak của
 * ma trận phân quyền (`wf_template_author` của AC-01, BS-01 SCR-1505).
 */
export type VtRole =
  | 'platform_admin'
  | 'tenant_admin'
  | 'policy_author'
  | 'leader'
  | 'manager'
  | 'member'
  | 'appraiser'
  | 'decider'
  | 'second_approver'
  | 'inspector'
  | 'ai_readonly'
  | 'wf_template_author';

/** Mức bảo đảm xác thực của phiên (TL-02 QT-00 "Trạng thái đối tượng"). */
export type SessionState = 'CHUA_XAC_THUC' | 'AAL1' | 'AAL2' | 'HET_HAN' | 'BI_THU_HOI';
export type Acr = 'aal1' | 'aal2';

/** Trạng thái chọn ngữ cảnh làm việc (QT-00 bước 7). */
export type ContextStatus = 'DANG_TAI' | 'CAN_CHON' | 'SAN_SANG' | 'KHONG_PHAN_CONG' | 'LOI';

export interface VtUser {
  id: string;
  name: string;
  email?: string;
  tenant: string;
  /** Đơn vị đang làm việc (QT-00: chọn đơn vị khi kiêm nhiệm). */
  unitId?: string;
  unitName?: string;
  roles: VtRole[];
}

export interface SessionNotice {
  severity: 'info' | 'warn' | 'error';
  text: string;
  /** Hiện nút "Đăng nhập lại". */
  relogin?: boolean;
}

export interface StepUpRequest {
  acr: Acr;
  reason: string;
}

/** BR-00-01: vai trò chỉ dùng được trong phiên AAL2. */
export const AAL2_ROLES: readonly VtRole[] = [
  'appraiser',
  'decider',
  'second_approver',
  'tenant_admin',
  'platform_admin',
];
/** TL-02 QT-00: phiên AAL2 hạ về AAL1 sau 15 phút kể từ lần MFA gần nhất. */
export const AAL2_TTL_MS = 15 * 60_000;
const CONTEXT_KEY = 'vt.assignment';

const DEV_USER: VtUser = {
  id: 'dev-user',
  name: 'Nguyễn Văn Minh',
  email: 'dev@mission.local',
  tenant: 'default',
  unitId: 'phong-tong-hop',
  roles: ['member', 'manager'],
};

/** Khoá sessionStorage để thử giao diện theo từng vai trò khi chạy giả lập (WEB-055, WEB-057). */
export const DEV_ROLES_KEY = 'vt.devRoles';

/**
 * Vai trò giả lập đặt trong sessionStorage `vt.devRoles` (ví dụ `tenant_admin,policy_author`).
 * Chỉ dùng khi `auth.enabled = false`, thay cho vai trò của người dùng và phân công giả lập;
 * không có hoặc không đọc được thì giữ vai trò của dữ liệu giả lập.
 */
function devRoles(): VtRole[] | null {
  const raw = readSessionItem(DEV_ROLES_KEY) ?? '';
  const roles = raw
    .split(',')
    .map((r) => r.trim())
    .filter((r) => /^[a-z_]+$/.test(r));
  return roles.length ? (roles as VtRole[]) : null;
}

export function requiresAal2(a: Pick<Assignment, 'roles'>): boolean {
  return a.roles.some((r) => (AAL2_ROLES as readonly string[]).includes(r));
}

/**
 * Phiên đăng nhập OIDC Authorization Code + PKCE với Keycloak (ADR-08, QT-00, SCR-0001):
 * xử lý callback, làm mới token, hết phiên, chọn phân công khi kiêm nhiệm, xác thực bước lên
 * (RFC 9470, BR-00-01, BR-00-02). Khi `auth.enabled = false` (dev không có Keycloak), dùng người
 * dùng giả lập và giả lập bước lên ngay trên trình duyệt. Quyền thật vẫn do máy chủ quyết định.
 */
@Injectable({ providedIn: 'root' })
export class SessionService {
  private readonly oauth = inject(OAuthService);
  private readonly cfg = inject(RUNTIME_CONFIG);
  private readonly org = inject(OrgApi);
  private readonly storage = inject(OAuthStorage, { optional: true });

  private readonly _user = signal<VtUser | null>(null);
  private readonly _acr = signal<Acr>('aal1');
  private readonly _expired = signal(false);
  private readonly _assignments = signal<Assignment[]>([]);
  private readonly _assignment = signal<Assignment | null>(null);
  private readonly _contextStatus = signal<ContextStatus>('DANG_TAI');
  private readonly _notice = signal<SessionNotice | null>(null);
  private readonly _stepUp = signal<StepUpRequest | null>(null);
  private authTime = 0;
  private downgradeTimer: ReturnType<typeof setTimeout> | undefined;
  private pendingStepUp: { resolve: () => void; reject: (e: Error) => void } | null = null;
  private refreshing: Promise<boolean> | null = null;
  private stepUpMaxAge: number | undefined;
  private readonly devRoleOverride = this.cfg.auth.enabled ? null : devRoles();

  readonly user = this._user.asReadonly();
  readonly isAuthenticated = computed(() => this._user() !== null);
  readonly acr = this._acr.asReadonly();
  readonly state = computed<SessionState>(() => {
    if (this._expired()) return 'HET_HAN';
    if (!this._user()) return 'CHUA_XAC_THUC';
    return this._acr() === 'aal2' ? 'AAL2' : 'AAL1';
  });
  readonly assignments = this._assignments.asReadonly();
  readonly assignment = this._assignment.asReadonly();
  readonly contextStatus = this._contextStatus.asReadonly();
  /** Có từ hai phân công còn hiệu lực: hiện "Đổi phân công" trên thanh trên (SCR-0002). */
  readonly canSwitchContext = computed(() => this._assignments().length >= 2);
  readonly notice = this._notice.asReadonly();
  readonly stepUpRequest = this._stepUp.asReadonly();

  constructor() {
    inject(DestroyRef).onDestroy(() => clearTimeout(this.downgradeTimer));
  }

  private markReady!: () => void;
  /**
   * Xong khi `init()` đã nạp xong người dùng (đăng nhập OIDC hoặc người dùng giả lập). Lời gọi API
   * lúc khởi động cần token (ví dụ khám phá cell) chờ mốc này thay vì chạy song song với `init()`.
   */
  readonly ready = new Promise<void>((resolve) => (this.markReady = resolve));

  async init(): Promise<void> {
    try {
      await this.initSession();
    } finally {
      this.markReady();
    }
  }

  private async initSession(): Promise<void> {
    if (!this.cfg.auth.enabled) {
      this._user.set({
        ...DEV_USER,
        tenant: this.cfg.tenant,
        roles: this.devRoleOverride ?? DEV_USER.roles,
      });
      void this.loadContext();
      return;
    }
    // WEB-013: khi nhúng vào cổng khác, Keycloak trả về đúng trang của cổng đang chứa ứng dụng
    const back = this.cfg.auth.redirectUri ?? location.origin + '/';
    this.oauth.configure({
      issuer: this.cfg.auth.issuer,
      clientId: this.cfg.auth.clientId,
      responseType: 'code',
      scope: this.cfg.auth.scope,
      redirectUri: back,
      postLogoutRedirectUri: back,
      // Trang nhận kết quả của cửa sổ bước lên (MFA) để không rời màn hình đang nhập
      silentRefreshRedirectUri: location.origin + '/silent-refresh.html',
      useSilentRefresh: false,
      sessionChecksEnabled: false,
      showDebugInformation: false,
      clearHashAfterLogin: true,
      requireHttps: this.cfg.auth.allowHttp ? false : 'remoteOnly',
      // state + nonce + PKCE S256 do thư viện sinh và kiểm (SEC-17)
    });
    this.oauth.events.subscribe((e) => {
      if (e.type === 'token_refresh_error') this.expire();
      if (e.type === 'token_received' || e.type === 'token_refreshed') this.readClaims();
    });
    try {
      await this.oauth.loadDiscoveryDocument();
      await this.oauth.tryLoginCodeFlow();
    } catch (err) {
      console.error('[auth] Không hoàn tất đăng nhập OIDC', err);
      this._notice.set({ severity: 'error', text: TEXT_IDP_UNAVAILABLE });
      return;
    }
    if (!this.oauth.hasValidAccessToken()) {
      this.login();
      // trình duyệt đang chuyển sang Keycloak: không bootstrap tiếp
      await new Promise<never>(() => undefined);
    }
    this.restoreRoute();
    this.clearRedirectSecrets();
    this.oauth.setupAutomaticSilentRefresh();
    this.readClaims();
    void this.loadContext();
  }

  hasRole(...roles: VtRole[]): boolean {
    const u = this._user();
    return !!u && roles.some((r) => u.roles.includes(r));
  }

  accessToken(): string | null {
    return this.cfg.auth.enabled ? this.oauth.getAccessToken() : null;
  }

  /** Chuyển sang Keycloak, giữ đường dẫn hiện tại để quay về sau đăng nhập. */
  login(): void {
    if (!this.cfg.auth.enabled) return;
    // thư viện tự mã hoá phần state thêm vào (state = nonce;đường-dẫn)
    this.oauth.initCodeFlow(location.pathname + location.search);
  }

  logout(): void {
    clearSessionItem(CONTEXT_KEY);
    if (this.cfg.auth.enabled) {
      // gọi end_session_endpoint của Keycloak, không trỏ trang đăng nhập riêng của hệ cũ
      this.oauth.logOut();
    } else {
      this._user.set(null);
    }
  }

  /**
   * 401 không phải bước lên: thử làm mới token một lần (dùng chung cho các request song song).
   * Trả `true` khi đã có token mới để gửi lại request; ngược lại đánh dấu hết phiên.
   */
  handleUnauthorized(): Promise<boolean> {
    if (!this.cfg.auth.enabled) return Promise.resolve(false);
    this.refreshing ??= this.oauth
      .refreshToken()
      .then(() => true)
      .catch(() => {
        this.expire();
        return false;
      })
      .finally(() => (this.refreshing = null));
    return this.refreshing;
  }

  /** Hết phiên (HET_HAN): giữ màn hình, báo đăng nhập lại (TL-02: dữ liệu nháp được giữ). */
  expire(): void {
    this._expired.set(true);
    this._notice.set({ severity: 'warn', text: TEXT_SESSION_EXPIRED, relogin: true });
  }

  showNotice(n: SessionNotice): void {
    this._notice.set(n);
  }

  clearNotice(): void {
    this._notice.set(null);
  }

  /** Phiên hiện tại đạt mức `acr` và MFA không quá `maxAgeSec` giây (BR-00-02, RFC 9470). */
  satisfies(acr: Acr, maxAgeSec?: number): boolean {
    if (acr === 'aal1') return this.isAuthenticated();
    if (this._acr() !== 'aal2') return false;
    return maxAgeSec === undefined || Date.now() - this.authTime <= maxAgeSec * 1000;
  }

  /**
   * Móc xác thực bước lên cho thao tác quan trọng (ký, quyết định, nghiệm thu, khoá kỳ):
   * `await session.requireStepUp('aal2', 900)` trước khi gửi lệnh. Nếu phiên chưa đạt, mở hộp
   * MFA bước lên (`<vt-step-up-dialog>` trong shell); bị huỷ thì Promise bị từ chối.
   */
  requireStepUp(acr: Acr = 'aal2', maxAgeSec?: number, reason?: string): Promise<void> {
    if (this.satisfies(acr, maxAgeSec)) return Promise.resolve();
    this.pendingStepUp?.reject(new Error('step-up-superseded'));
    return new Promise<void>((resolve, reject) => {
      this.pendingStepUp = { resolve, reject };
      this.stepUpMaxAge = maxAgeSec;
      this._stepUp.set({
        acr,
        reason: reason ?? 'Thao tác này yêu cầu xác thực nhiều lớp (MFA) trước khi thực hiện.',
      });
    });
  }

  /** Gọi từ nút "Xác thực" của hộp bước lên (cần thao tác của người dùng để mở cửa sổ). */
  async confirmStepUp(): Promise<void> {
    const req = this._stepUp();
    if (!req) return;
    try {
      if (this.cfg.auth.enabled) {
        this.oauth.customQueryParams = {
          acr_values: req.acr,
          ...(this.stepUpMaxAge !== undefined ? { max_age: String(this.stepUpMaxAge) } : {}),
        };
        try {
          await this.oauth.initLoginFlowInPopup({ width: 480, height: 640 });
        } finally {
          this.oauth.customQueryParams = {};
        }
        this.clearRedirectSecrets();
        this.readClaims();
        if (!this.satisfies(req.acr)) throw new Error('step-up-not-satisfied');
      } else {
        // Dev không có Keycloak: giả lập MFA thành công
        this.setAcr('aal2', Date.now());
      }
      this._stepUp.set(null);
      this.pendingStepUp?.resolve();
      this.pendingStepUp = null;
    } catch (err) {
      console.warn('[auth] Bước lên chưa hoàn tất', err);
      throw err;
    }
  }

  cancelStepUp(): void {
    this._stepUp.set(null);
    this.pendingStepUp?.reject(new Error('step-up-cancelled'));
    this.pendingStepUp = null;
  }

  /** Tải phân công còn hiệu lực rồi chọn ngữ cảnh (QT-00 bước 7, BR-00-07). */
  async loadContext(): Promise<void> {
    this._contextStatus.set('DANG_TAI');
    try {
      const me = await firstValueFrom(this.org.me());
      const list = me.assignments;
      this._assignments.set(list);
      if (list.length === 0) {
        this._contextStatus.set('KHONG_PHAN_CONG');
        this._notice.set({ severity: 'error', text: MSG_00_06 });
        return;
      }
      if (list.length === 1) {
        this.apply(list[0]);
        return;
      }
      const stored = list.find((a) => a.id === readSessionItem(CONTEXT_KEY));
      if (stored && this.canUse(stored)) {
        this.apply(stored);
        return;
      }
      this._contextStatus.set('CAN_CHON');
    } catch (err) {
      const p = toProblem(err);
      if (p.status === 403 && /TENANT.*SUSPENDED|TAM_NGUNG/.test(p.code ?? '')) {
        this._notice.set({ severity: 'error', text: MSG_00_07(this.cfg.tenant) });
      }
      console.warn('[auth] Không tải được phân công', p.title);
      // Không chặn người dùng khi org-service lỗi: dùng vai trò trong token
      this._contextStatus.set('LOI');
    }
  }

  /** Phân công được chọn sẵn: lần trước trong phiên, rồi lần trước theo máy chủ, rồi phân công chính. */
  preselectedAssignmentId(lastFromServer?: string | null): string | null {
    const list = this._assignments();
    const ids = [
      readSessionItem(CONTEXT_KEY),
      lastFromServer,
      list.find((a) => a.kind === 'CHINH')?.id,
    ];
    return ids.find((id) => id && list.some((a) => a.id === id && this.canUse(a))) ?? null;
  }

  /** BR-00-01: phân công có vai trò AAL2 không chọn được khi phiên ở AAL1. */
  canUse(a: Assignment): boolean {
    return !requiresAal2(a) || this._acr() === 'aal2';
  }

  aal2Reason(a: Assignment): string {
    const role = a.roles.find((r) => (AAL2_ROLES as readonly string[]).includes(r)) ?? '';
    return MSG_00_05(ROLE_LABEL[role] ?? role);
  }

  /** Ghi ngữ cảnh phiên rồi làm mới token để nhận claim `org` (SCR-0001 "Chọn phân công"). */
  async selectAssignment(id: string): Promise<void> {
    const a = this._assignments().find((x) => x.id === id);
    if (!a) throw new Error(`Không có phân công ${id}`);
    if (!this.canUse(a)) await this.requireStepUp('aal2', undefined, this.aal2Reason(a));
    await firstValueFrom(this.org.setContext(id));
    if (this.cfg.auth.enabled) {
      await this.oauth.refreshToken().catch((e) => console.warn('[auth] Chưa làm mới token', e));
    }
    writeSessionItem(CONTEXT_KEY, id);
    this.apply(a);
  }

  /** Mở lại hộp chọn phân công (SCR-0002 "Đổi phân công"). */
  switchContext(): void {
    if (this.canSwitchContext()) this._contextStatus.set('CAN_CHON');
  }

  /** Đóng hộp đổi phân công, giữ phân công đang dùng. */
  cancelSwitch(): void {
    if (this._assignment()) this._contextStatus.set('SAN_SANG');
  }

  private apply(a: Assignment): void {
    this._assignment.set(a);
    this._user.update((u) =>
      u
        ? {
            ...u,
            unitId: a.orgUnitId,
            unitName: a.orgUnitName,
            roles: this.devRoleOverride ?? (a.roles as VtRole[]),
          }
        : u,
    );
    this._contextStatus.set('SAN_SANG');
  }

  /**
   * Sau callback: quay lại đường dẫn đã lưu trong `state` (chỉ đường dẫn cùng nguồn) và bỏ tham số
   * `iss` (RFC 9207) mà Keycloak gửi kèm, thư viện không tự xoá.
   */
  private restoreRoute(): void {
    let back = '';
    try {
      back = this.oauth.state ? decodeURIComponent(this.oauth.state) : '';
    } catch {
      back = '';
    }
    const sameOrigin = back.startsWith('/') && !back.startsWith('//') && !back.startsWith('/\\');
    const url = new URL(sameOrigin ? back : location.pathname + location.search, location.origin);
    if (!sameOrigin) url.searchParams.delete('iss');
    const target = url.pathname + url.search + location.hash;
    if (target !== location.pathname + location.search + location.hash) {
      history.replaceState(null, '', target);
    }
  }

  /**
   * `nonce` và `PKCE_verifier` chỉ dùng một lần khi đổi mã lấy token; thư viện không tự xoá nên
   * xoá ngay sau khi đổi mã xong (làm mới token không kiểm nonce).
   */
  private clearRedirectSecrets(): void {
    this.storage?.removeItem('PKCE_verifier');
    this.storage?.removeItem('nonce');
  }

  private setAcr(acr: Acr, authTimeMs: number): void {
    this._acr.set(acr);
    this.authTime = authTimeMs;
    clearTimeout(this.downgradeTimer);
    if (acr === 'aal2') {
      const left = AAL2_TTL_MS - (Date.now() - authTimeMs);
      if (left <= 0) this._acr.set('aal1');
      else this.downgradeTimer = setTimeout(() => this._acr.set('aal1'), left);
    }
  }

  private readClaims(): void {
    const c = this.oauth.getIdentityClaims() as Record<string, unknown> | null;
    if (!c) return;
    const realmRoles = ((c['realm_access'] as { roles?: string[] } | undefined)?.roles ??
      []) as VtRole[];
    const current = this._assignment();
    this._expired.set(false);
    this._user.set({
      id: String(c['sub']),
      name: String(c['name'] ?? c['preferred_username'] ?? ''),
      email: c['email'] as string | undefined,
      tenant: String(c['tenant'] ?? this.cfg.tenant),
      unitId: current?.orgUnitId ?? (c['unit_id'] as string | undefined),
      unitName: current?.orgUnitName,
      roles: current ? (current.roles as VtRole[]) : realmRoles,
    });
    const acr = String(c['acr'] ?? '').toLowerCase() === 'aal2' ? 'aal2' : 'aal1';
    this.setAcr(acr, Number(c['auth_time'] ?? 0) * 1000);
  }
}

function readSessionItem(key: string): string | null {
  try {
    return globalThis.sessionStorage?.getItem(key) ?? null;
  } catch {
    return null;
  }
}
function writeSessionItem(key: string, value: string): void {
  try {
    globalThis.sessionStorage?.setItem(key, value);
  } catch {
    /* trình duyệt chặn sessionStorage: chỉ mất phần nhớ lựa chọn */
  }
}
function clearSessionItem(key: string): void {
  try {
    globalThis.sessionStorage?.removeItem(key);
  } catch {
    /* bỏ qua */
  }
}
