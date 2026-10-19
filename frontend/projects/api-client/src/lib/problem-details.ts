import { HttpErrorResponse } from '@angular/common/http';

/** Lỗi theo RFC 9457 (quy ước API mục 13 của baseline). */
export interface ProblemDetails {
  type: string;
  title: string;
  status: number;
  detail?: string;
  instance?: string;
  /** Mã thông báo nghiệp vụ, ví dụ MSG-06-03. */
  code?: string;
  errors?: { field: string; message: string }[];
  traceId?: string;
}

export function toProblem(err: unknown): ProblemDetails {
  if (err instanceof HttpErrorResponse) {
    const body = err.error;
    if (body && typeof body === 'object' && 'title' in body) {
      return body as ProblemDetails;
    }
    if (err.status === 409) {
      return { type: 'about:blank', title: 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', status: 409 };
    }
    return { type: 'about:blank', title: err.statusText || 'Lỗi kết nối', status: err.status };
  }
  return { type: 'about:blank', title: 'Lỗi không xác định', status: 0, detail: String(err) };
}
