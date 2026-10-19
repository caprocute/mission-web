import { HttpErrorResponse } from '@angular/common/http';
import type { components } from './generated/common';

/**
 * Lỗi theo RFC 9457 (quy ước API mục 13 của baseline), mở rộng `code`, `errors[]`, `traceId`
 * theo TL-01 mục "Định dạng lỗi chuẩn RFC 9457". Kiểu sinh từ OpenAPI (WEB-007).
 */
export type ProblemDetails = components['schemas']['ProblemDetails'];
export type FieldError = components['schemas']['FieldError'];

export const CONFLICT_TITLE = 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại';
const NETWORK_TITLE = 'Không kết nối được máy chủ, vui lòng kiểm tra mạng và thử lại';

/** Tiêu đề mặc định khi máy chủ không trả body Problem Details. */
const DEFAULT_TITLES: Record<number, string> = {
  400: 'Yêu cầu không hợp lệ',
  401: 'Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại',
  403: 'Đồng chí không có quyền thực hiện thao tác này',
  404: 'Không tìm thấy dữ liệu',
  409: CONFLICT_TITLE,
  412: CONFLICT_TITLE,
  422: 'Dữ liệu không hợp lệ',
  429: 'Thao tác quá nhanh, vui lòng thử lại sau ít phút',
  500: 'Lỗi máy chủ, vui lòng thử lại sau',
  502: 'Hệ thống tạm thời gián đoạn, vui lòng thử lại sau',
  503: 'Hệ thống đang quá tải, vui lòng thử lại sau',
  504: 'Máy chủ phản hồi quá lâu, vui lòng thử lại sau',
};

function isProblemBody(body: unknown): body is ProblemDetails {
  return !!body && typeof body === 'object' && 'title' in body && 'status' in body;
}

function parseBody(body: unknown): unknown {
  if (typeof body !== 'string') return body;
  try {
    return JSON.parse(body);
  } catch {
    return body;
  }
}

/** Chuyển mọi lỗi (HTTP, mạng, ngoại lệ) về `ProblemDetails`, kể cả khi body không đúng dạng. */
export function toProblem(err: unknown): ProblemDetails {
  if (err instanceof HttpErrorResponse) {
    const body = parseBody(err.error);
    if (isProblemBody(body)) {
      return { ...body, status: body.status || err.status, type: body.type || 'about:blank' };
    }
    if (err.status === 0) return { type: 'about:blank', title: NETWORK_TITLE, status: 0 };
    return {
      type: 'about:blank',
      title: DEFAULT_TITLES[err.status] ?? (err.statusText || 'Lỗi kết nối'),
      status: err.status,
    };
  }
  return { type: 'about:blank', title: 'Lỗi không xác định', status: 0, detail: String(err) };
}

/**
 * Xung đột ghi đồng thời (`If-Match` cũ). Baseline và TL-02 ghi 409, TL-01 ghi 412
 * `concurrent-modification`; coi cả hai là một tình huống (wiki/06). 409 mang `code` nghiệp vụ
 * khác (ví dụ `TASK-409-PERIOD-LOCKED`) không phải xung đột ghi.
 */
export function isConcurrencyConflict(p: ProblemDetails): boolean {
  if (p.status === 412) return true;
  if (p.status !== 409) return false;
  if (p.type.endsWith('concurrent-modification')) return true;
  return !p.code || /CONFLICT|CONCURRENT|VERSION|MOCK-409/.test(p.code);
}

/** Lỗi theo trường `errors[]` thành bảng `field → message` (gộp nhiều lỗi cùng trường). */
export function fieldErrors(p: ProblemDetails): Record<string, string> {
  const out: Record<string, string> = {};
  for (const e of p.errors ?? []) {
    out[e.field] = out[e.field] ? `${out[e.field]}; ${e.message}` : e.message;
  }
  return out;
}
