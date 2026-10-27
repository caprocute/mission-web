import { TestBed } from '@angular/core/testing';
import { OAuthService, OAuthStorage } from 'angular-oauth2-oidc';
import { VtTokenStorage } from './token-storage';
import { Subject, of, throwError } from 'rxjs';
import { HttpErrorResponse } from '@angular/common/http';
import { Assignment, DEFAULT_RUNTIME_CONFIG, OrgApi, RUNTIME_CONFIG } from '@vt/api-client';
import { MSG_00_06, MSG_00_07, TEXT_SESSION_EXPIRED } from './messages';
import {
  AAL2_TTL_MS,
  DEV_ROLES_KEY,
  hoTen,
  SessionService,
  isLoopbackHttp,
  requiresAal2,
} from './session.service';

/** WEB-006: phiên OIDC, chọn phân công khi kiêm nhiệm, bước lên AAL2 (QT-00, BR-00-01, BR-00-02). */
const A = (id: string, roles: string[], kind: Assignment['kind'] = 'KIEM_NHIEM'): Assignment =>
  ({
    id,
    orgUnitId: `dv-${id}`,
    orgUnitName: `Đơn vị ${id}`,
    jobTitleName: 'Chuyên viên',
    kind,
    branch: 'CHINH_QUYEN',
    roles,
    validFrom: '2026-01-01',
    validTo: null,
  }) as Assignment;

const CHINH = A('chinh', ['member', 'manager'], 'CHINH');
const CHI_BO = A('chi-bo', ['member', 'leader']);
const THAM_DINH = A('tham-dinh', ['member', 'appraiser']);

function fakeOAuth() {
  return {
    events: new Subject<{ type: string }>(),
    state: '',
    customQueryParams: {} as Record<string, string>,
    configure: vi.fn(),
    loadDiscoveryDocument: vi.fn().mockResolvedValue({}),
    tryLoginCodeFlow: vi.fn().mockResolvedValue(undefined),
    hasValidAccessToken: vi.fn().mockReturnValue(true),
    setupAutomaticSilentRefresh: vi.fn(),
    getIdentityClaims: vi.fn().mockReturnValue(null),
    getAccessToken: vi.fn().mockReturnValue('tok'),
    getRefreshToken: vi.fn().mockReturnValue('rt'),
    initCodeFlow: vi.fn(),
    initLoginFlowInPopup: vi.fn().mockResolvedValue(true),
    refreshToken: vi.fn().mockResolvedValue({}),
    logOut: vi.fn(),
  };
}

function setup(
  opts: {
    assignments?: Assignment[];
    authEnabled?: boolean;
    meError?: unknown;
    redirectUri?: string;
    devRoles?: string;
  } = {},
) {
  sessionStorage.clear();
  if (opts.devRoles) sessionStorage.setItem(DEV_ROLES_KEY, opts.devRoles);
  const oauth = fakeOAuth();
  const org = {
    me: vi.fn(() =>
      opts.meError
        ? throwError(() => opts.meError)
        : of({ personId: 'p', fullName: 'Thử', assignments: opts.assignments ?? [CHINH] }),
    ),
    setContext: vi.fn((id: string) => of({ assignmentId: id, orgUnitId: 'x', roles: [] })),
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: OAuthService, useValue: oauth },
      { provide: OAuthStorage, useClass: VtTokenStorage },
      { provide: OrgApi, useValue: org },
      {
        provide: RUNTIME_CONFIG,
        useValue: {
          ...DEFAULT_RUNTIME_CONFIG,
          tenant: 'so-tu-phap',
          auth: {
            ...DEFAULT_RUNTIME_CONFIG.auth,
            enabled: !!opts.authEnabled,
            redirectUri: opts.redirectUri,
          },
        },
      },
    ],
  });
  return { session: TestBed.inject(SessionService), oauth, org };
}

const flush = () => new Promise((r) => setTimeout(r, 0));

describe('SessionService: chọn phân công (QT-00 bước 7)', () => {
  it('một phân công: dùng ngay, vai trò và đơn vị lấy theo phân công', async () => {
    const { session } = setup();
    await session.init();
    await flush();
    expect(session.contextStatus()).toBe('SAN_SANG');
    expect(session.user()?.roles).toEqual(['member', 'manager']);
    expect(session.user()?.unitName).toBe('Đơn vị chinh');
    expect(session.canSwitchContext()).toBe(false);
  });

  it('kiêm nhiệm: hỏi chọn, chọn sẵn phân công chính, ghi ngữ cảnh rồi nhớ trong phiên', async () => {
    const { session, org } = setup({ assignments: [CHI_BO, CHINH, THAM_DINH] });
    await session.init();
    await flush();
    expect(session.contextStatus()).toBe('CAN_CHON');
    expect(session.canSwitchContext()).toBe(true);
    expect(session.preselectedAssignmentId()).toBe('chinh');
    await session.selectAssignment('chi-bo');
    expect(org.setContext).toHaveBeenCalledWith('chi-bo');
    expect(session.contextStatus()).toBe('SAN_SANG');
    expect(session.user()?.roles).toEqual(['member', 'leader']);
    expect(sessionStorage.getItem('vt.assignment')).toBe('chi-bo');
  });

  it('đã chọn trong phiên thì không hỏi lại khi tải lại', async () => {
    const { session } = setup({ assignments: [CHINH, CHI_BO] });
    sessionStorage.setItem('vt.assignment', 'chi-bo');
    await session.init();
    await flush();
    expect(session.contextStatus()).toBe('SAN_SANG');
    expect(session.assignment()?.id).toBe('chi-bo');
  });

  it('"Đổi phân công" mở lại hộp chọn, huỷ thì giữ phân công đang dùng', async () => {
    const { session } = setup({ assignments: [CHINH, CHI_BO] });
    await session.init();
    await flush();
    await session.selectAssignment('chinh');
    session.switchContext();
    expect(session.contextStatus()).toBe('CAN_CHON');
    session.cancelSwitch();
    expect(session.contextStatus()).toBe('SAN_SANG');
    expect(session.assignment()?.id).toBe('chinh');
  });

  it('không có phân công: KHONG_PHAN_CONG và MSG-00-06', async () => {
    const { session } = setup({ assignments: [] });
    await session.init();
    await flush();
    expect(session.contextStatus()).toBe('KHONG_PHAN_CONG');
    expect(session.notice()?.text).toBe(MSG_00_06);
  });

  it('quản trị nền tảng không có phân công vẫn vào làm việc bằng vai trò trong token', async () => {
    const { session } = setup({ assignments: [], devRoles: 'platform_admin' });
    await session.init();
    await flush();
    expect(session.contextStatus()).toBe('SAN_SANG');
    expect(session.assignment()).toBeNull();
    expect(session.user()?.roles).toEqual(['platform_admin']);
    expect(session.notice()).toBeNull();
  });

  it('tenant tạm ngừng: MSG-00-07; org-service lỗi khác không chặn người dùng', async () => {
    const err = new HttpErrorResponse({
      status: 403,
      error: { title: 'Bị chặn', status: 403, code: 'TENANT_SUSPENDED' },
    });
    const { session } = setup({ meError: err });
    await session.init();
    await flush();
    expect(session.contextStatus()).toBe('LOI');
    expect(session.notice()?.text).toBe(MSG_00_07('so-tu-phap'));
  });
});

describe('SessionService: AAL2 và bước lên (BR-00-01, BR-00-02)', () => {
  it('vai trò AAL2 cần MFA: chưa dùng được ở AAL1, có lý do theo MSG-00-05', async () => {
    const { session } = setup({ assignments: [CHINH, THAM_DINH] });
    await session.init();
    await flush();
    expect(requiresAal2(THAM_DINH)).toBe(true);
    expect(requiresAal2(CHINH)).toBe(false);
    expect(session.canUse(THAM_DINH)).toBe(false);
    expect(session.aal2Reason(THAM_DINH)).toContain(
      'Vai trò Cán bộ thẩm định yêu cầu xác thực nhiều lớp',
    );
  });

  it('chọn phân công AAL2: mở hộp bước lên, xác thực xong mới ghi ngữ cảnh', async () => {
    const { session, org } = setup({ assignments: [CHINH, THAM_DINH] });
    await session.init();
    await flush();
    const done = session.selectAssignment('tham-dinh');
    await flush();
    expect(session.stepUpRequest()?.acr).toBe('aal2');
    expect(org.setContext).not.toHaveBeenCalled();
    await session.confirmStepUp();
    await done;
    expect(session.state()).toBe('AAL2');
    expect(org.setContext).toHaveBeenCalledWith('tham-dinh');
    expect(session.user()?.roles).toContain('appraiser');
  });

  it('huỷ bước lên thì thao tác bị từ chối, giữ trạng thái chọn', async () => {
    const { session, org } = setup({ assignments: [CHINH, THAM_DINH] });
    await session.init();
    await flush();
    const done = session.selectAssignment('tham-dinh');
    await flush();
    session.cancelStepUp();
    await expect(done).rejects.toThrow('step-up-cancelled');
    expect(org.setContext).not.toHaveBeenCalled();
    expect(session.contextStatus()).toBe('CAN_CHON');
  });

  it('AAL2 hạ về AAL1 sau 15 phút', async () => {
    vi.useFakeTimers();
    try {
      const { session } = setup();
      await session.init();
      const p = session.requireStepUp('aal2', 900);
      await session.confirmStepUp();
      await p;
      expect(session.satisfies('aal2', 900)).toBe(true);
      vi.advanceTimersByTime(AAL2_TTL_MS + 1);
      expect(session.state()).toBe('AAL1');
      expect(session.satisfies('aal2')).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('SessionService: OIDC (auth.enabled = true, Keycloak giả lập)', () => {
  const nowSec = () => Math.floor(Date.now() / 1000);

  it('chưa có token thì chuyển sang Keycloak, giữ đường dẫn đang mở trong state', async () => {
    const { session, oauth } = setup({ authEnabled: true });
    oauth.hasValidAccessToken.mockReturnValue(false);
    void session.init();
    await flush();
    expect(oauth.configure).toHaveBeenCalledWith(
      expect.objectContaining({
        responseType: 'code',
        silentRefreshRedirectUri: expect.any(String),
      }),
    );
    expect(oauth.initCodeFlow).toHaveBeenCalledWith(location.pathname + location.search);
  });

  it('mặc định Keycloak trả về gốc trang; chế độ nhúng (WEB-013) trả về trang của cổng', async () => {
    const plain = setup({ authEnabled: true });
    plain.oauth.hasValidAccessToken.mockReturnValue(false);
    void plain.session.init();
    await flush();
    expect(plain.oauth.configure).toHaveBeenCalledWith(
      expect.objectContaining({
        redirectUri: location.origin + '/',
        postLogoutRedirectUri: location.origin + '/',
      }),
    );
    TestBed.resetTestingModule();
    const embed = setup({
      authEnabled: true,
      redirectUri: 'https://cong.example.vn/van-phong/okr',
    });
    embed.oauth.hasValidAccessToken.mockReturnValue(false);
    void embed.session.init();
    await flush();
    expect(embed.oauth.configure).toHaveBeenCalledWith(
      expect.objectContaining({
        redirectUri: 'https://cong.example.vn/van-phong/okr',
        postLogoutRedirectUri: 'https://cong.example.vn/van-phong/okr',
      }),
    );
  });

  it('sau callback quay lại đường dẫn trong state, bỏ tham số iss; không nhận đường dẫn ngoài nguồn', async () => {
    const { session, oauth } = setup({ authEnabled: true });
    history.replaceState(null, '', '/?iss=http%3A%2F%2Fidp');
    oauth.state = encodeURIComponent('/tasks?mock-org=kiem-nhiem');
    await session.init();
    expect(location.pathname + location.search).toBe('/tasks?mock-org=kiem-nhiem');

    TestBed.resetTestingModule();
    const again = setup({ authEnabled: true });
    history.replaceState(null, '', '/?iss=x');
    again.oauth.state = encodeURIComponent('//evil.example/x');
    await again.session.init();
    expect(location.pathname + location.search).toBe('/');
  });

  it('đọc claim: vai trò realm, acr aal2 và auth_time', async () => {
    const { session, oauth } = setup({ authEnabled: true, assignments: [CHINH, THAM_DINH] });
    oauth.getIdentityClaims.mockReturnValue({
      sub: 'u1',
      name: 'Nguyễn Văn A',
      acr: 'aal2',
      auth_time: nowSec(),
      realm_access: { roles: ['member', 'appraiser'] },
    });
    sessionStorage.setItem('PKCE_verifier', 'v');
    sessionStorage.setItem('nonce', 'n');
    await session.init();
    expect(sessionStorage.getItem('PKCE_verifier')).toBeNull();
    expect(sessionStorage.getItem('nonce')).toBeNull();
    expect(session.user()?.name).toBe('Nguyễn Văn A');
    expect(session.state()).toBe('AAL2');
    expect(session.accessToken()).toBe('tok');
    await flush();
    expect(session.canUse(THAM_DINH)).toBe(true);
  });

  it('MFA đã quá max_age thì vẫn phải bước lên', async () => {
    const { session, oauth } = setup({ authEnabled: true });
    oauth.getIdentityClaims.mockReturnValue({ sub: 'u1', acr: 'aal2', auth_time: nowSec() - 600 });
    await session.init();
    expect(session.satisfies('aal2')).toBe(true);
    expect(session.satisfies('aal2', 300)).toBe(false);
  });

  it('bước lên mở popup với acr_values, max_age rồi xoá tham số', async () => {
    const { session, oauth } = setup({ authEnabled: true });
    oauth.getIdentityClaims.mockReturnValue({ sub: 'u1', acr: 'aal1', auth_time: nowSec() });
    await session.init();
    const p = session.requireStepUp('aal2', 300);
    oauth.initLoginFlowInPopup.mockImplementation(async () => {
      expect(oauth.customQueryParams).toEqual({ acr_values: 'aal2', max_age: '300' });
      oauth.getIdentityClaims.mockReturnValue({ sub: 'u1', acr: 'aal2', auth_time: nowSec() });
      return true;
    });
    await session.confirmStepUp();
    await p;
    expect(oauth.customQueryParams).toEqual({});
    expect(session.stepUpRequest()).toBeNull();
  });

  it('popup về mà vẫn AAL1 thì báo lỗi, hộp bước lên còn mở', async () => {
    const { session, oauth } = setup({ authEnabled: true });
    oauth.getIdentityClaims.mockReturnValue({ sub: 'u1', acr: 'aal1', auth_time: nowSec() });
    await session.init();
    void session.requireStepUp('aal2').catch(() => undefined);
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    await expect(session.confirmStepUp()).rejects.toThrow('step-up-not-satisfied');
    expect(session.stepUpRequest()).not.toBeNull();
  });

  it('401 song song chỉ làm mới token một lần; làm mới lỗi thì HET_HAN, giữ màn hình', async () => {
    const { session, oauth } = setup({ authEnabled: true });
    oauth.getIdentityClaims.mockReturnValue({ sub: 'u1' });
    await session.init();
    oauth.refreshToken.mockRejectedValue(new Error('invalid_grant'));
    const [a, b] = await Promise.all([session.handleUnauthorized(), session.handleUnauthorized()]);
    expect([a, b]).toEqual([false, false]);
    expect(oauth.refreshToken).toHaveBeenCalledTimes(1);
    expect(session.state()).toBe('HET_HAN');
    expect(session.notice()).toEqual(
      expect.objectContaining({ text: TEXT_SESSION_EXPIRED, relogin: true }),
    );
  });

  it('401 khi chưa có refresh token: không gọi làm mới, không báo hết phiên', async () => {
    const { session, oauth } = setup({ authEnabled: true });
    oauth.getIdentityClaims.mockReturnValue({ sub: 'u1' });
    await session.init();
    oauth.getRefreshToken.mockReturnValue(null);
    await expect(session.handleUnauthorized()).resolves.toBe(false);
    expect(oauth.refreshToken).not.toHaveBeenCalled();
    expect(session.state()).not.toBe('HET_HAN');
  });

  it('Keycloak không phản hồi: báo lỗi, không vòng lặp chuyển hướng', async () => {
    const { session, oauth } = setup({ authEnabled: true });
    oauth.loadDiscoveryDocument.mockRejectedValue(new Error('network'));
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    await session.init();
    expect(oauth.initCodeFlow).not.toHaveBeenCalled();
    expect(session.notice()?.severity).toBe('error');
  });

  it('đăng xuất gọi end_session của Keycloak và xoá ngữ cảnh đã nhớ', async () => {
    const { session, oauth } = setup({ authEnabled: true });
    await session.init();
    sessionStorage.setItem('vt.assignment', 'x');
    session.logout();
    expect(oauth.logOut).toHaveBeenCalled();
    expect(sessionStorage.getItem('vt.assignment')).toBeNull();
  });
});

describe('isLoopbackHttp: issuer HTTP chỉ được miễn TLS trên máy cục bộ', () => {
  it('miễn cho localhost, 127.x, ::1 (Keycloak dev qua hầm SSH), không miễn địa chỉ khác', () => {
    expect(isLoopbackHttp('http://127.0.0.1:35080/realms/mission')).toBe(true);
    expect(isLoopbackHttp('http://localhost:35080/realms/mission')).toBe(true);
    expect(isLoopbackHttp('http://[::1]:8080/realms/mission')).toBe(true);
    expect(isLoopbackHttp('http://10.0.0.5:35080/realms/mission')).toBe(false);
    expect(isLoopbackHttp('http://127.0.0.1.example.vn/realms/mission')).toBe(false);
    expect(isLoopbackHttp('https://sso.example.vn/realms/mission')).toBe(false);
    expect(isLoopbackHttp('')).toBe(false);
  });
});

describe('hoTen', () => {
  it('ghép họ, đệm rồi tên theo thứ tự tiếng Việt từ family_name và given_name', () => {
    expect(
      hoTen({ name: 'Bộ Nguyễn Văn Cán', given_name: 'Bộ', family_name: 'Nguyễn Văn Cán' }),
    ).toBe('Nguyễn Văn Cán Bộ');
  });

  it('thiếu họ hoặc tên thì dùng claim name, rồi preferred_username', () => {
    expect(hoTen({ name: 'Nguyễn Văn Minh', given_name: 'Minh' })).toBe('Nguyễn Văn Minh');
    expect(hoTen({ preferred_username: 'canbo' })).toBe('canbo');
  });
});
