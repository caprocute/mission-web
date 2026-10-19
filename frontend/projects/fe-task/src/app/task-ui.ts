import { TaskStatus } from '@vt/api-client';

/** Màu thẻ trạng thái theo bảng trạng thái của TL-02 QT-05, QT-06. */
export function statusSeverity(s: TaskStatus, overdue = false): 'success' | 'info' | 'warn' | 'danger' | 'secondary' | 'contrast' {
  if (overdue && s !== 'HOAN_THANH') return 'danger';
  switch (s) {
    case 'HOAN_THANH': return 'success';
    case 'CHO_NGHIEM_THU': return 'warn';
    case 'DANG_THUC_HIEN': return 'info';
    case 'HUY': case 'TAM_DUNG': return 'secondary';
    default: return 'contrast';
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
