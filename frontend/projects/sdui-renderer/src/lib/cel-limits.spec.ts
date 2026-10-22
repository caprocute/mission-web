import { celLimitViolation } from './cel-context';

describe('Giới hạn chi phí CEL (WEB-009)', () => {
  it('cho qua biểu thức thường gặp', () => {
    expect(celLimitViolation('\'AC-04\' in user.roles && user.tenant == "tinh-mau"')).toBeNull();
    expect(celLimitViolation('user.roles.exists(r, r == "AC-01")')).toBeNull();
  });

  it('chặn biểu thức quá dài, lồng quá sâu hoặc quá nhiều macro lặp', () => {
    expect(celLimitViolation('true || '.repeat(80) + 'true')).toBe('length');
    expect(celLimitViolation('('.repeat(13) + 'true' + ')'.repeat(13))).toBe('depth');
    expect(celLimitViolation('[1,2].all(a, [1,2].all(b, [1,2].exists(c, c == a + b)))')).toBe(
      'comprehensions',
    );
  });

  it('không đếm ngoặc nằm trong chuỗi', () => {
    expect(celLimitViolation(`user.id == "${'('.repeat(20)}"`)).toBeNull();
  });
});
