import { toProblem } from '@vt/api-client';
import { forbiddenText } from '@vt/auth';

/** Ngày giờ kiểu Việt Nam: 20/01/2027 10:14. */
export function fmtDateTime(iso?: string | null): string {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  const p = (x: number) => String(x).padStart(2, '0');
  return `${p(d.getDate())}/${p(d.getMonth() + 1)}/${d.getFullYear()} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

export function fmtDate(iso?: string | null): string {
  return fmtDateTime(iso).slice(0, 10);
}

export const fmtInt = (n?: number | null) => (n ?? 0).toLocaleString('vi-VN');

/**
 * Văn bản lỗi cho người dùng (YC-PT-26: hiện lời giải thích tiếng Việt của máy chủ): ưu tiên `detail` khi
 * máy chủ gửi thông điệp TL-02 (`msg`, ví dụ MSG-13-01); 403 nêu lý do máy chủ trả về trong `detail` (không
 * có thì câu không có quyền chung); lỗi theo trường thì nối các thông điệp; còn lại `detail` khi có (ví dụ
 * 409 `OVERSIGHT-409-INVALID-STATE` mang "Đã có yêu cầu mở lại đang xử lý…" dưới tiêu đề chung "Xung đột
 * dữ liệu"), sau cùng mới đến `title` của RFC 9457.
 */
export function errorText(err: unknown, fallback = 'Không thực hiện được thao tác.'): string {
  const p = toProblem(err);
  const detail = p.detail?.trim();
  if (p.msg && detail) return detail;
  if (p.status === 403) return forbiddenText(p);
  if (p.errors?.length) return p.errors.map((e) => e.message).join(' ');
  // `detail` mang mã kỹ thuật (ví dụ OVERSIGHT-409-…, tên hằng) thì không đưa lên giao diện, như forbiddenText
  if (detail && !/[A-Z]{2,}-\d{3}|_/.test(detail)) return detail;
  return p.title || fallback;
}
