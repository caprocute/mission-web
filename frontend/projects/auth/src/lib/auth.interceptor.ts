import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { SessionService } from './session.service';

/** Gắn access token cho request tới API của cell, không gắn cho tài nguyên tĩnh hay CDN. */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const token = inject(SessionService).accessToken();
  if (token && (req.url.startsWith('/api/') || req.url.includes('/api/v1/'))) {
    return next(req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }));
  }
  return next(req);
};
