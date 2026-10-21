import { HttpErrorResponse } from '@angular/common/http';
import { ProblemDetails, toProblem } from '@vt/api-client';
import { TEXT_FORBIDDEN } from '@vt/auth';

/** Số thập phân theo cách viết Việt Nam: 0,783; 91,2. */
export const fmtNum = (n: unknown, digits = 3) =>
  typeof n === 'number'
    ? n.toLocaleString('vi-VN', { maximumFractionDigits: digits })
    : String(n ?? '—');

export function fmtDate(iso: string | null | undefined): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (x: number) => String(x).padStart(2, '0');
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()}`;
}

/** Lời nhắn lỗi cho người dùng: ưu tiên `detail` (MSG của máy chủ), rồi `title`. */
export function errorText(err: unknown, fallback: string): string {
  if (err instanceof HttpErrorResponse && err.status === 403) return TEXT_FORBIDDEN;
  const p: ProblemDetails = toProblem(err);
  if (p.status === 403) return TEXT_FORBIDDEN;
  return p.detail || p.title || fallback;
}

/** Mã lỗi nghiệp vụ của RFC 9457 (`code`), nếu có. */
export const problemCode = (err: unknown) => toProblem(err).code;

/** Tên khối theo luồng của gói. */
export const FLOW_LABEL: Record<string, string | undefined> = {
  DANG: 'Khối Đảng',
  NHA_NUOC: 'Khối Nhà nước',
};
