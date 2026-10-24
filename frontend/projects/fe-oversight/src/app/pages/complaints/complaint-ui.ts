import { ComplaintStatus, ComplaintTarget } from '@vt/api-client/domains';

/** Màu nhãn trạng thái khiếu nại (PrimeNG `p-tag`); nháp (`null`) là xám. */
export function complaintSeverity(
  s: ComplaintStatus | null | undefined,
): 'info' | 'warn' | 'success' | 'danger' | 'secondary' {
  switch (s) {
    case 'MOI':
    case 'DA_TIEP_NHAN':
      return 'info';
    case 'DANG_GIAI_TRINH':
    case 'CHO_KET_LUAN':
    case 'DANG_THI_HANH':
      return 'warn';
    case 'DA_TRA_LOI':
      return 'success';
    case 'KHONG_THU_LY':
      return 'danger';
    default:
      return 'secondary';
  }
}

/** "còn 7 ngày làm việc", "quá hạn 2 ngày làm việc" hoặc rỗng khi không áp dụng. */
export function daysLeftText(n?: number | null): string {
  if (n === null || n === undefined) return '';
  if (n < 0) return `quá hạn ${-n} ngày làm việc`;
  if (n === 0) return 'hết hạn hôm nay';
  return `còn ${n} ngày làm việc`;
}

/**
 * Giá trị hiện tại của cấu phần đọc từ nhãn snapshot (ví dụ "Nhiệm vụ then chốt 3 · C = 0,85" cho 0,85).
 * Hợp đồng `ComplaintTarget` chưa có trường giá trị nên đây là suy luận; không đọc được thì trả `null`.
 */
export function targetValue(t: ComplaintTarget): number | null {
  const m = /=\s*([0-9]+(?:,[0-9]+)?)\s*$/.exec(t.label ?? '');
  return m ? Number(m[1].replace(',', '.')) : null;
}

/** Cấu phần hệ số (B, C) chỉ nhận giá trị 0..1; khoá dạng `NV-THEN-CHOT-3.C`. */
export const componentOf = (key: string) => key.split('.').pop() ?? key;

export const fmtNum = (n: number | string | null | undefined, digits = 2) =>
  n === null || n === undefined || n === ''
    ? '—'
    : typeof n === 'number'
      ? n.toLocaleString('vi-VN', { minimumFractionDigits: digits, maximumFractionDigits: digits })
      : n;

/** Các bước xử lý và thời hạn hiển thị ở SCR-1201 vùng 4 (QT-12 bảng các bước). */
export const COMPLAINT_STEPS: { title: string; due: string }[] = [
  { title: 'Ký số và gửi khiếu nại', due: 'Trong thời hạn khiếu nại' },
  { title: 'Tiếp nhận, phân loại', due: '2 ngày làm việc' },
  { title: 'Yêu cầu và nhận giải trình các bên', due: '3 ngày làm việc mỗi lượt' },
  { title: 'Kết luận, ký văn bản trả lời', due: 'Tổng không quá 15 ngày làm việc' },
  { title: 'Nhận trả lời, xác nhận hoặc bảo lưu ý kiến', due: '5 ngày làm việc' },
];
