/**
 * Ngày làm việc dùng chung cho các bộ giả lập nhiệm vụ và kế hoạch. Tách khỏi `tasks.mock.ts` để
 * `assign.mock.ts` và `plans.mock.ts` không import vòng qua `tasks.mock.ts` (vòng import làm
 * `ASSIGN_MOCK` chưa khởi tạo khi bản dựng webpack của Storybook nạp bộ giả lập, WEB-005).
 */
export const utcDay = (iso: string) =>
  Date.UTC(+iso.slice(0, 4), +iso.slice(5, 7) - 1, +iso.slice(8, 10));

/** Số ngày làm việc từ hôm nay tới `due` (âm nếu đã qua), bỏ thứ Bảy và Chủ nhật. */
export function workingDaysBetween(todayIso: string, dueIso: string): number {
  const a = utcDay(todayIso);
  const b = utcDay(dueIso);
  const sign = b >= a ? 1 : -1;
  let n = 0;
  for (let d = a; d !== b; d += sign * 86_400_000) {
    const next = d + sign * 86_400_000;
    const wd = new Date(sign > 0 ? next : d).getUTCDay();
    if (wd !== 0 && wd !== 6) n++;
  }
  return sign * n;
}
