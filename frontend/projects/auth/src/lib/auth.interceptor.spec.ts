import { HttpClient, HttpContext, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MSG_00_06, MSG_00_07, TEXT_FORBIDDEN } from './messages';
import { SKIP_FORBIDDEN_NOTICE, authInterceptor, parseStepUpChallenge } from './auth.interceptor';
import { SessionService } from './session.service';

/** WEB-006: gắn token, 401 bước lên (RFC 9470), 401 làm mới token, 403 theo MSG (QT-00). */
function setup() {
  const session = {
    user: signal({ tenant: 'so-tu-phap' }),
    accessToken: vi.fn(() => 'tok-1'),
    requireStepUp: vi.fn(() => Promise.resolve()),
    handleUnauthorized: vi.fn(() => Promise.resolve(true)),
    showNotice: vi.fn(),
  };
  TestBed.configureTestingModule({
    providers: [
      provideHttpClient(withInterceptors([authInterceptor])),
      provideHttpClientTesting(),
      { provide: SessionService, useValue: session },
    ],
  });
  return { session, http: TestBed.inject(HttpClient), ctrl: TestBed.inject(HttpTestingController) };
}

const flush = () => new Promise((r) => setTimeout(r, 0));
const STEP_UP =
  'Bearer error="insufficient_user_authentication", error_description="Cần MFA", acr_values="aal2", max_age=300';

describe('parseStepUpChallenge', () => {
  it('đọc acr_values và max_age; header khác trả null', () => {
    expect(parseStepUpChallenge(STEP_UP)).toEqual({ acr: 'aal2', maxAge: 300 });
    expect(parseStepUpChallenge('Bearer error="invalid_token"')).toBeNull();
    expect(parseStepUpChallenge(null)).toBeNull();
  });
});

describe('authInterceptor', () => {
  it('chỉ gắn token cho API, không gắn cho tài nguyên tĩnh', () => {
    const { http, ctrl } = setup();
    http.get('/api/v1/tasks/mine').subscribe();
    http.get('/assets/logo.svg').subscribe();
    expect(ctrl.expectOne('/api/v1/tasks/mine').request.headers.get('Authorization')).toBe(
      'Bearer tok-1',
    );
    expect(ctrl.expectOne('/assets/logo.svg').request.headers.has('Authorization')).toBe(false);
  });

  it('401 bước lên: mở MFA rồi gửi lại cùng Idempotency-Key', async () => {
    const { http, ctrl, session } = setup();
    let body: unknown;
    http.post('/api/v1/tasks/1/progress', { p: 1 }).subscribe((b) => (body = b));
    const first = ctrl.expectOne('/api/v1/tasks/1/progress');
    const key = first.request.headers.get('Idempotency-Key');
    expect(key).toBeTruthy();
    first.flush(null, {
      status: 401,
      statusText: 'Unauthorized',
      headers: { 'WWW-Authenticate': STEP_UP },
    });
    await flush();
    expect(session.requireStepUp).toHaveBeenCalledWith('aal2', 300);
    const retry = ctrl.expectOne('/api/v1/tasks/1/progress');
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    retry.flush({ ok: true });
    expect(body).toEqual({ ok: true });
  });

  it('huỷ bước lên: trả lỗi 401 gốc, không gửi lại', async () => {
    const { http, ctrl, session } = setup();
    session.requireStepUp.mockReturnValue(Promise.reject(new Error('step-up-cancelled')));
    let status = 0;
    http.post('/api/v1/x', {}).subscribe({ error: (e) => (status = e.status) });
    ctrl.expectOne('/api/v1/x').flush(null, {
      status: 401,
      statusText: 'Unauthorized',
      headers: { 'WWW-Authenticate': STEP_UP },
    });
    await flush();
    ctrl.expectNone('/api/v1/x');
    expect(status).toBe(401);
  });

  it('401 thường: làm mới token rồi gửi lại với token mới', async () => {
    const { http, ctrl, session } = setup();
    http.get('/api/v1/tasks/mine').subscribe();
    ctrl.expectOne('/api/v1/tasks/mine').flush(null, { status: 401, statusText: 'Unauthorized' });
    session.accessToken.mockReturnValue('tok-2');
    await flush();
    expect(session.handleUnauthorized).toHaveBeenCalledTimes(1);
    const retry = ctrl.expectOne('/api/v1/tasks/mine');
    expect(retry.request.headers.get('Authorization')).toBe('Bearer tok-2');
  });

  it('403: thông báo chung chỉ cho thao tác ghi, MSG-00-06 cho mọi request; màn hình tự xử lý thì bỏ qua', () => {
    const { http, ctrl, session } = setup();
    const noop = { error: () => undefined };
    // YC-PT-21: lời gọi đọc bị từ chối không bật thông báo nổi, màn hình hiện lỗi tại chỗ
    http.get('/api/v1/doc').subscribe(noop);
    ctrl.expectOne('/api/v1/doc').flush({ status: 403 }, { status: 403, statusText: 'Forbidden' });
    expect(session.showNotice).not.toHaveBeenCalled();

    http.post('/api/v1/a', {}).subscribe(noop);
    ctrl.expectOne('/api/v1/a').flush({ status: 403 }, { status: 403, statusText: 'Forbidden' });
    expect(session.showNotice).toHaveBeenLastCalledWith({
      severity: 'error',
      text: TEXT_FORBIDDEN,
    });

    http.get('/api/v1/b').subscribe(noop);
    ctrl
      .expectOne('/api/v1/b')
      .flush(
        { title: 'Bị chặn', status: 403, code: 'ORG-NO-ASSIGNMENT' },
        { status: 403, statusText: 'Forbidden' },
      );
    expect(session.showNotice).toHaveBeenLastCalledWith({ severity: 'error', text: MSG_00_06 });

    session.showNotice.mockClear();
    http
      .get('/api/v1/c', { context: new HttpContext().set(SKIP_FORBIDDEN_NOTICE, true) })
      .subscribe(noop);
    ctrl.expectOne('/api/v1/c').flush({ status: 403 }, { status: 403, statusText: 'Forbidden' });
    expect(session.showNotice).not.toHaveBeenCalled();
  });

  it('403 do tenant tạm ngừng chặn lệnh ghi (tenantStatus TAM_NGUNG): hiện MSG-00-07', () => {
    const { http, ctrl, session } = setup();
    http.post('/api/v1/tasks', {}).subscribe({ error: () => undefined });
    ctrl.expectOne('/api/v1/tasks').flush(
      {
        title: 'Không có quyền thực hiện thao tác này',
        status: 403,
        code: 'COMMON-403-FORBIDDEN',
        tenantStatus: 'TAM_NGUNG',
      },
      { status: 403, statusText: 'Forbidden' },
    );
    expect(session.showNotice).toHaveBeenLastCalledWith({
      severity: 'error',
      text: MSG_00_07('so-tu-phap'),
    });
  });
});
