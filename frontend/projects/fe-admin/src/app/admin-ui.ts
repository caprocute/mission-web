import { HttpErrorResponse } from '@angular/common/http';
import { toProblem } from '@vt/api-client';
import { forbiddenText } from '@vt/auth';

/** Số theo cách viết Việt Nam: 1.184.200. */
export const fmtInt = (n: number) => n.toLocaleString('vi-VN');

/** Số tài khoản rút gọn theo mockup: "4,12 tr", "312.450". */
export function fmtAccounts(n: number): string {
  if (n >= 1_000_000) {
    return `${(n / 1_000_000).toLocaleString('vi-VN', { maximumFractionDigits: 2, minimumFractionDigits: 2 })} tr`;
  }
  return fmtInt(n);
}

export const fmtPercent = (ratio: number) => `${Math.round(ratio * 100)}%`;

export function fmtDateTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (x: number) => String(x).padStart(2, '0');
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function fmtDate(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (x: number) => String(x).padStart(2, '0');
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()}`;
}

/** Văn bản lỗi cho người dùng: 403 nêu lý do máy chủ trả về (hoặc câu chung), còn lại `title` của RFC 9457. */
export function errorText(err: unknown, fallback: string): string {
  const p = toProblem(err);
  if (p.status === 403 || (err instanceof HttpErrorResponse && err.status === 403)) return forbiddenText(p);
  return p.title || fallback;
}
