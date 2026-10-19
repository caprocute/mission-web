import { HttpInterceptorFn } from '@angular/common/http';

function hex(bytes: number): string {
  const a = crypto.getRandomValues(new Uint8Array(bytes));
  return Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Sinh header W3C `traceparent` cho mỗi request API để trace nối từ trình duyệt
 * qua APISIX tới mission-core (NFR-10, ADR-12). Khi tích hợp OTel web SDK/Faro,
 * interceptor này được thay bằng instrumentation của SDK.
 */
export const traceparentInterceptor: HttpInterceptorFn = (req, next) => {
  if (!req.url.includes('/api/') || req.headers.has('traceparent')) return next(req);
  return next(req.clone({ setHeaders: { traceparent: `00-${hex(16)}-${hex(8)}-01` } }));
};
