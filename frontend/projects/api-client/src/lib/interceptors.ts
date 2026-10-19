import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { RUNTIME_CONFIG } from './runtime-config';

const WRITE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

/** Ghép gốc API của cell vào đường dẫn tương đối bắt đầu bằng `/api/v1/`. */
export const apiBaseUrlInterceptor: HttpInterceptorFn = (req, next) => {
  const cfg = inject(RUNTIME_CONFIG);
  if (req.url.startsWith('/api/v1/') && cfg.apiBaseUrl !== '/api') {
    return next(req.clone({ url: cfg.apiBaseUrl.replace(/\/$/, '') + req.url.slice(4) }));
  }
  return next(req);
};

/**
 * Mọi request ghi tới API của cell mang `Idempotency-Key` (UUID) để máy chủ chống xử lý trùng
 * khi người dùng bấm lại hoặc mạng di động gửi lại. Không gắn cho request ra ngoài API, ví dụ lệnh
 * đổi mã lấy token của Keycloak: header lạ làm preflight CORS bị từ chối và hỏng đăng nhập (WEB-057).
 */
export const idempotencyInterceptor: HttpInterceptorFn = (req, next) => {
  const isApi = req.url.startsWith('/api/') || req.url.includes('/api/v1/');
  if (isApi && WRITE_METHODS.has(req.method) && !req.headers.has('Idempotency-Key')) {
    return next(req.clone({ setHeaders: { 'Idempotency-Key': crypto.randomUUID() } }));
  }
  return next(req);
};
