import { checkSelfForm, compareRating, criteriaLevel, stepOf, vn } from './self-assessment-rules';

describe('self-assessment-rules (SCR-0901)', () => {
  const base = {
    selfRating: null,
    breakthrough: '',
    evidenceIds: [],
    reason: '',
    content: 'Ưu điểm',
  };

  it('BR-09-05: quy đổi 9 tiêu chí ra mức tiêu chí chung', () => {
    expect(criteriaLevel(9, 9)).toBe('THUC_HIEN_TOT');
    expect(criteriaLevel(8, 9)).toBe('CO_BAN_DAM_BAO');
    expect(criteriaLevel(9, 10)).toBe('DAM_BAO');
    expect(criteriaLevel(5, 9)).toBe('KHONG_DAM_BAO');
  });

  it('so sánh mức: HTXSNV cao nhất', () => {
    expect(compareRating('HTXSNV', 'HTTNV')).toBe(-1);
    expect(compareRating('HTNV', 'HTTNV')).toBe(1);
    expect(compareRating('HTTNV', 'HTTNV')).toBe(0);
  });

  it('mặc định bằng mức tham khảo thì chỉ cần ưu điểm, hạn chế', () => {
    expect(checkSelfForm(base, 'HTTNV')).toEqual({});
    expect(checkSelfForm({ ...base, content: '  ' }, 'HTTNV')).toEqual({
      content: 'Vui lòng nhập ưu điểm, hạn chế, hướng khắc phục.',
    });
  });

  it('BR-09-12 MSG-09-02: khác mức tham khảo phải giải trình 20 đến 2.000 ký tự', () => {
    const f = { ...base, selfRating: 'HTNV' as const, reason: 'quá ngắn' };
    expect(Object.keys(checkSelfForm(f, 'HTTNV'))).toEqual(['reason']);
    expect(checkSelfForm({ ...f, reason: 'x'.repeat(20) }, 'HTTNV')).toEqual({});
  });

  it('MSG-09-03: HTXSNV cần giải pháp đột phá và minh chứng', () => {
    const f = {
      ...base,
      selfRating: 'HTXSNV' as const,
      reason: 'Giải pháp số hoá đã có quyết định ghi nhận.',
    };
    expect(Object.keys(checkSelfForm(f, 'HTTNV'))).toEqual(['breakthrough']);
    expect(
      checkSelfForm({ ...f, breakthrough: 'Giải pháp', evidenceIds: ['e1'] }, 'HTTNV'),
    ).toEqual({});
  });

  it('bước của hồ sơ và định dạng số', () => {
    expect(stepOf('TU_DANH_GIA')).toBe(0);
    expect(stepOf('CHO_THAM_DINH')).toBe(1);
    expect(stepOf('DA_KHOA')).toBe(4);
    expect(vn(91.2)).toBe('91,2');
    expect(vn(0.908, 3)).toBe('0,908');
  });
});
