import {
  HttpContextToken,
  HttpErrorResponse,
  HttpInterceptorFn,
  HttpRequest,
} from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, from, switchMap, throwError } from 'rxjs';
import { toProblem } from '@vt/api-client';
import { MSG_00_06, MSG_00_07, TEXT_FORBIDDEN } from './messages';
import { Acr, SessionService } from './session.service';

/**
 * Đặt `true` cho request mà màn hình tự hiện thông báo 403 theo MSG riêng (ví dụ MSG-09-08),
 * để interceptor không hiện thêm thông báo chung.
 */
export const SKIP_FORBIDDEN_NOTICE = new HttpContextToken<boolean>(() => false);

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

const isApi = (url: string) => url.startsWith('/api/') || url.includes('/api/v1/');

/** Đọc yêu cầu bước lên RFC 9470 từ `WWW-Authenticate`; `null` nếu không phải. */
export function parseStepUpChallenge(header: string | null): { acr: Acr; maxAge?: number } | null {
  if (!header || !/error="?insufficient_user_authentication"?/i.test(header)) return null;
  const acr = /acr_values="([^"]+)"/i.exec(header)?.[1]?.split(/\s+/)[0]?.toLowerCase();
  const maxAge = /max_age="?(\d+)"?/i.exec(header)?.[1];
  return {
    acr: acr === 'aal1' ? 'aal1' : 'aal2',
    maxAge: maxAge !== undefined ? Number(maxAge) : undefined,
  };
}

/**
 * Gắn access token cho request tới API của cell (không gắn cho tài nguyên tĩnh hay CDN) và
 * xử lý lỗi xác thực theo QT-00:
 * - 401 có `insufficient_user_authentication` (RFC 9470, BR-00-02): mở hộp MFA bước lên rồi gửi lại.
 * - 401 khác: làm mới token một lần rồi gửi lại; không được thì báo hết phiên, giữ màn hình.
 * - 403: hiện thông báo (MSG-00-06, MSG-00-07 hoặc thông báo chung), trừ khi màn hình tự xử lý.
 * Request ghi được gắn `Idempotency-Key` trước khi gửi để lần gửi lại dùng cùng khoá.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isApi(req.url)) return next(req);
  const session = inject(SessionService);
  const base =
    WRITE_METHODS.has(req.method) && !req.headers.has('Idempotency-Key')
      ? req.clone({ setHeaders: { 'Idempotency-Key': crypto.randomUUID() } })
      : req;
  const withToken = (r: HttpRequest<unknown>) => {
    const token = session.accessToken();
    return token ? r.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : r;
  };

  return next(withToken(base)).pipe(
    catchError((err: unknown) => {
      if (!(err instanceof HttpErrorResponse)) return throwError(() => err);
      if (err.status === 401) {
        const challenge = parseStepUpChallenge(err.headers.get('WWW-Authenticate'));
        const retry$ = challenge
          ? from(
              session
                .requireStepUp(challenge.acr, challenge.maxAge)
                .then(() => true)
                .catch(() => false),
            )
          : from(session.handleUnauthorized());
        return retry$.pipe(switchMap((ok) => (ok ? next(withToken(base)) : throwError(() => err))));
      }
      if (err.status === 403 && !req.context.get(SKIP_FORBIDDEN_NOTICE)) {
        const p = toProblem(err);
        const code = p.code ?? '';
        const text = /NO-ASSIGNMENT|KHONG_PHAN_CONG/.test(code)
          ? MSG_00_06
          : /TENANT.*SUSPENDED|TAM_NGUNG/.test(code)
            ? MSG_00_07(session.user()?.tenant ?? '')
            : TEXT_FORBIDDEN;
        session.showNotice({ severity: 'error', text });
      }
      return throwError(() => err);
    }),
  );
};
