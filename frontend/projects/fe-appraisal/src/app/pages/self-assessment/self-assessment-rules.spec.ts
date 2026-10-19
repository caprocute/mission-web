import { UiFormVersion } from '@vt/api-client';
import fixture from '../../../../../api-client/src/mock/ui-forms/ui-forms.json';
import {
  checkSelfForm,
  compareRating,
  criteriaLevel,
  formRules,
  stepOf,
  vn,
} from './self-assessment-rules';

/** Quy tắc CEL của Mẫu 1B như máy chủ trả (dữ liệu giả lập). */
const RULES = formRules(fixture.forms['mau-1b'] as unknown as UiFormVersion);
const CTX = { mucThamKhao: 'HTTNV' as const, soMinhChung: 0 };

describe('self-assessment-rules (SCR-0901)', () => {
  const base = {
    selfRatingCode: 'HTTNV' as const,
    breakthrough: '',
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
    expect(checkSelfForm(RULES, base, CTX)).toEqual({});
    expect(checkSelfForm(RULES, { ...base, content: '  ' }, CTX)).toEqual({
      content: 'Vui lòng nhập ưu điểm, hạn chế, hướng khắc phục.',
    });
  });

  it('BR-09-12 MSG-09-02: khác mức tham khảo phải giải trình 20 đến 2.000 ký tự', () => {
    const f = { ...base, selfRatingCode: 'HTNV' as const, reason: 'quá ngắn' };
    expect(Object.keys(checkSelfForm(RULES, f, CTX))).toEqual(['reason']);
    expect(checkSelfForm(RULES, { ...f, reason: 'x'.repeat(20) }, CTX)).toEqual({});
  });

  it('MSG-09-03: HTXSNV cần giải pháp đột phá và minh chứng', () => {
    const f = {
      ...base,
      selfRatingCode: 'HTXSNV' as const,
      reason: 'Giải pháp số hoá đã có quyết định ghi nhận.',
    };
    expect(Object.keys(checkSelfForm(RULES, f, CTX))).toEqual(['breakthrough']);
    expect(
      checkSelfForm(RULES, { ...f, breakthrough: 'Giải pháp' }, { ...CTX, soMinhChung: 1 }),
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
