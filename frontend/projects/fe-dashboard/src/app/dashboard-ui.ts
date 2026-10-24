import { toProblem } from '@vt/api-client';
import { AnalyticsFilter, Period } from '@vt/api-client/domains';
import { TEXT_FORBIDDEN } from '@vt/auth';

/** Ngày giờ kiểu Việt Nam: 16/11/2026 08:55. */
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

export const fmtNum = (n?: number | null, digits = 1) =>
  n === null || n === undefined
    ? '—'
    : n.toLocaleString('vi-VN', { maximumFractionDigits: digits });

/**
 * Văn bản lỗi cho người dùng: ưu tiên `detail` khi máy chủ gửi thông điệp TL-02 (`msg`, ví dụ MSG-16-04),
 * 403 không có thông điệp riêng dùng câu không có quyền chung, còn lại `title` của RFC 9457.
 */
export function errorText(err: unknown, fallback = 'Không thực hiện được thao tác.'): string {
  const p = toProblem(err);
  if (p.msg && p.detail) return p.detail;
  if (p.status === 403) return p.detail || TEXT_FORBIDDEN;
  if (p.errors?.length) return p.errors.map((e) => e.message).join(' ');
  return p.title || fallback;
}

/** Tuần hiện tại trong kỳ đang mở ("Tuần 6/13"); `null` khi kỳ đã qua hoặc chưa bắt đầu. */
export function weekOfPeriod(p: Period | undefined, now = new Date()): string | null {
  if (!p || p.status !== 'MO') return null;
  const start = Date.parse(p.startDate);
  const end = Date.parse(p.endDate);
  if (
    Number.isNaN(start) ||
    Number.isNaN(end) ||
    now.getTime() < start ||
    now.getTime() > end + 86_400_000
  )
    return null;
  const total = Math.max(1, Math.ceil((end - start + 86_400_000) / (7 * 86_400_000)));
  const cur = Math.min(total, Math.floor((now.getTime() - start) / (7 * 86_400_000)) + 1);
  return `Tuần ${cur}/${total}`;
}

/**
 * Tình trạng tiến độ của đơn vị. TL-02 SCR-1601 trường 6 ghi "ngưỡng màu theo tham số tenant" nhưng hợp
 * đồng chưa trả tham số này; tạm dùng 80% và 60% (suy luận, wiki/14).
 */
export function progressStatus(
  v: number | null,
): { label: string; severity: 'success' | 'warn' | 'danger' } | null {
  if (v === null) return null;
  if (v >= 80) return { label: 'Đạt tiến độ', severity: 'success' };
  if (v >= 60) return { label: 'Cần chú ý', severity: 'warn' };
  return { label: 'Chậm tiến độ', severity: 'danger' };
}

export const eq = (member: string, value: string): AnalyticsFilter => ({
  member,
  operator: 'equals',
  values: [value],
});
