/** Thời điểm tương đối bằng tiếng Việt cho hộp thông báo ("5 phút trước", "Hôm qua", "12/10/2026"). */
export function relativeTime(iso: string, now = new Date()): string {
  const t = new Date(iso);
  if (Number.isNaN(t.getTime())) return '';
  const sec = Math.max(0, Math.round((now.getTime() - t.getTime()) / 1000));
  if (sec < 60) return 'Vừa xong';
  if (sec < 3600) return `${Math.floor(sec / 60)} phút trước`;
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  if (t.getTime() >= startOfToday) return `${Math.floor(sec / 3600)} giờ trước`;
  if (t.getTime() >= startOfToday - 86_400_000) return 'Hôm qua';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(t.getDate())}/${p(t.getMonth() + 1)}/${t.getFullYear()}`;
}

/** Chỉ điều hướng tới đường dẫn nội bộ của ứng dụng; liên kết ngoài hoặc lạ bị bỏ. */
export function safeInternalLink(link: string | null | undefined): string | null {
  return link && /^\/(?!\/)[^\s\\]*$/.test(link) ? link : null;
}
