import { formatDate, progressTone, situation } from './task-ui';

/** SCR-0503 trường 4 (hạn dd/mm) và trường 6 (tình trạng theo ngày làm việc). */
describe('situation', () => {
  const t = (
    status: Parameters<typeof situation>[0]['status'],
    workingDaysLeft: number,
    overdue = false,
  ) => ({ status, workingDaysLeft, overdue });

  it('trễ hạn hiện "Trễ n ngày" màu đỏ', () => {
    expect(situation(t('DANG_THUC_HIEN', -2, true))).toEqual({
      label: 'Trễ 2 ngày',
      severity: 'danger',
    });
    expect(progressTone(t('DANG_THUC_HIEN', -2, true))).toBe('danger');
  });

  it('hôm nay, còn n ngày, đúng tiến độ, mới giao', () => {
    expect(situation(t('DANG_THUC_HIEN', 0)).label).toBe('Hôm nay');
    expect(situation(t('DANG_THUC_HIEN', 2))).toEqual({ label: 'Còn 2 ngày', severity: 'warn' });
    expect(situation(t('DANG_THUC_HIEN', 9)).label).toBe('Đúng tiến độ');
    expect(situation(t('DA_GIAO', 20)).label).toBe('Mới giao');
    expect(situation(t('DA_GIAO', 1)).label).toBe('Còn 1 ngày');
  });

  it('trạng thái kết thúc không tính ngày', () => {
    expect(situation(t('CHO_NGHIEM_THU', -5)).label).toBe('Chờ nghiệm thu');
    expect(situation(t('HOAN_THANH', -5)).label).toBe('Hoàn thành');
    expect(situation(t('NHAP', 10)).label).toBe('Chờ duyệt đăng ký');
  });
});

describe('formatDate', () => {
  it('đổi yyyy-mm-dd sang dd/mm và dd/mm/yyyy', () => {
    expect(formatDate('2026-10-05')).toBe('05/10');
    expect(formatDate('2026-10-05T08:00:00Z', true)).toBe('05/10/2026');
  });
});
