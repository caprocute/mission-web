import { TaskStatus, TaskSummary } from '@vt/api-client';

export type TagSeverity = 'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast';

/** Màu thẻ trạng thái theo bảng trạng thái của TL-02 QT-05, QT-06. */
export function statusSeverity(s: TaskStatus, overdue = false): TagSeverity {
  if (overdue && s !== 'HOAN_THANH') return 'danger';
  switch (s) {
    case 'HOAN_THANH':
      return 'success';
    case 'CHO_NGHIEM_THU':
      return 'warn';
    case 'DANG_THUC_HIEN':
      return 'info';
    case 'HUY':
    case 'TAM_DUNG':
      return 'secondary';
    default:
      return 'contrast';
  }
}

export function daysLeft(dueDate: string): number {
  // so sánh theo ngày lịch địa phương, không theo số giờ còn lại
  const [y, m, d] = dueDate.split('-').map(Number);
  const due = new Date(y, m - 1, d);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  return Math.round((due.getTime() - today.getTime()) / 86_400_000);
}

/** Số ngày làm việc còn lại tối đa để hiện "Còn n ngày" thay cho "Đúng tiến độ" (giả định, khớp bộ lọc Sắp hạn). */
export const SAP_HAN_WORKING_DAYS = 3;

/**
 * SCR-0503 trường 6 "Tình trạng": "Trễ n ngày", "Còn n ngày", "Hôm nay", "Đúng tiến độ",
 * cùng các nhãn "Mới giao", "Chờ nghiệm thu", "Hoàn thành" như mockup Hình 8.5.
 * Số ngày là ngày làm việc do máy chủ tính theo lịch nghỉ (`workingDaysLeft`).
 */
export function situation(t: Pick<TaskSummary, 'status' | 'overdue' | 'workingDaysLeft'>): {
  label: string;
  severity: TagSeverity;
} {
  switch (t.status) {
    case 'HOAN_THANH':
      return { label: 'Hoàn thành', severity: 'success' };
    case 'CHO_NGHIEM_THU':
      return { label: 'Chờ nghiệm thu', severity: 'success' };
    case 'TAM_DUNG':
      return { label: 'Tạm dừng', severity: 'secondary' };
    case 'HUY':
      return { label: 'Đã huỷ', severity: 'secondary' };
    case 'NHAP':
      return { label: 'Chờ duyệt đăng ký', severity: 'secondary' };
  }
  const n = t.workingDaysLeft;
  if (t.overdue) return { label: `Trễ ${Math.max(1, -n)} ngày`, severity: 'danger' };
  if (n <= 0) return { label: 'Hôm nay', severity: 'success' };
  if (n <= SAP_HAN_WORKING_DAYS) return { label: `Còn ${n} ngày`, severity: 'warn' };
  if (t.status === 'DA_GIAO') return { label: 'Mới giao', severity: 'secondary' };
  return { label: 'Đúng tiến độ', severity: 'success' };
}

/** Ngày ISO `yyyy-mm-dd` → `dd/mm` (SCR-0503 trường 4) hoặc `dd/mm/yyyy`. */
export function formatDate(iso: string, withYear = false): string {
  const [y, m, d] = iso.slice(0, 10).split('-');
  return withYear ? `${d}/${m}/${y}` : `${d}/${m}`;
}

/** Màu thanh tiến độ theo tình trạng, như mockup: đỏ khi trễ, vàng khi sắp hạn, xanh khi ổn. */
export function progressTone(
  t: Pick<TaskSummary, 'status' | 'overdue' | 'workingDaysLeft'>,
): 'danger' | 'warn' | 'ok' {
  const s = situation(t).severity;
  return s === 'danger' ? 'danger' : s === 'warn' ? 'warn' : 'ok';
}
