import {
  isOverride,
  largeChange,
  largeChangeMessage,
  parseValue,
  valueText,
  weekdayDate,
} from './checkin-rules';

describe('checkin-rules (SCR-0301)', () => {
  it('đọc số kiểu Việt Nam, tối đa 4 chữ số lẻ', () => {
    expect(parseValue('2,6')).toBe(2.6);
    expect(parseValue(' 13 ')).toBe(13);
    expect(parseValue('2.6125')).toBe(2.6125);
    expect(parseValue('2,61255')).toBeNull();
    expect(parseValue('')).toBeNull();
    expect(parseValue('abc')).toBeNull();
    expect(valueText(2.4)).toBe('2,4');
    expect(valueText(null)).toBe('');
  });

  it('BR-03-05: chênh quá 50% khoảng chỉ tiêu mới cảnh báo', () => {
    expect(largeChange(2.4, 2.6, 0, 3)).toBeNull();
    expect(largeChange(2.4, 4, 0, 3)).toBeCloseTo(0.5333, 3);
    expect(largeChangeMessage(0.5333)).toBe(
      'Giá trị mới chênh 53% so với lần trước. Đồng chí có chắc chắn không?',
    );
    expect(largeChange(null, 4, 0, 3)).toBeNull();
    expect(largeChange(1, 9, 5, 5)).toBeNull();
  });

  it('BR-03-04: chỉ coi là ghi đè khi có số liệu nguồn và giá trị khác', () => {
    expect(isOverride(2.6, 2.6)).toBe(false);
    expect(isOverride(2.6, 2.5)).toBe(true);
    expect(isOverride(undefined, 2.5)).toBe(false);
    expect(isOverride(2.6, null)).toBe(false);
  });

  it('hạn check-in kèm thứ', () => {
    expect(weekdayDate('2026-10-23')).toBe('thứ Sáu 23/10/2026');
  });
});
