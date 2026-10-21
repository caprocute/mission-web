import { AnnualRatingRow, Period } from '@vt/api-client';
import {
  annualPeriodOf,
  hasAwaitingAppraisal,
  linkageOf,
  reassignProblemMessage,
} from './annual-rules';

/** WEB-039: cách trình bày cột "Quy tắc liên thông" và chọn kỳ năm của SCR-1001. */
const row = (p: Partial<AnnualRatingRow>): AnnualRatingRow =>
  ({
    id: 'a',
    profileId: 'p',
    person: { id: 'x', name: 'A' },
    flow: 'DANG',
    quarterRatings: [],
    hasKhtnvQuarter: false,
    consecutiveKhtnv: false,
    reassignFlag: false,
    relocationProposed: false,
    rowVersion: 1,
    ...p,
  }) as AnnualRatingRow;

describe('annual-rules (WEB-039)', () => {
  it('cờ bố trí lại đứng trước lý do chặn và không hiện mã', () => {
    const both = linkageOf(row({ reassignFlag: true, blockReason: 'CO_QUY_KHTNV' }));
    expect(both.tone).toBe('bad');
    expect(both.text).toContain('QĐ khoản 15.6');
    const block = linkageOf(row({ blockReason: 'CO_QUY_KHTNV', linkageNote: 'x' }));
    expect([block.tone, block.text]).toEqual([
      'warn',
      'Có quý không hoàn thành nhiệm vụ: không xếp loại xuất sắc năm (QĐ khoản 15.5)',
    ]);
    expect(block.text).not.toMatch(/KHTNV|HTXSNV/);
    expect(linkageOf(row({ linkageNote: 'Chưa đạt điều kiện 30%' })).tone).toBe('warn');
    expect(linkageOf(row({}))).toEqual({ text: '', tone: '' });
  });

  it('kỳ năm mặc định là kỳ năm đầu danh sách; kỳ ghi trên đường dẫn được ưu tiên', () => {
    const ps = [
      { id: 'q', kind: 'QUY' },
      { id: 'y', kind: 'NAM' },
    ] as Period[];
    expect(annualPeriodOf(ps)?.id).toBe('y');
    expect(annualPeriodOf(ps, 'q')?.id).toBe('q');
    expect(annualPeriodOf(ps, 'khac')).toBeNull();
  });

  it('nút chuyển thẩm định năm và lỗi máy chủ', () => {
    expect(hasAwaitingAppraisal([row({ profileStatus: 'DA_THAM_DINH' })])).toBe(false);
    expect(hasAwaitingAppraisal([row({ profileStatus: 'CHO_THAM_DINH' })])).toBe(true);
    expect(
      reassignProblemMessage({
        status: 409,
        title: '',
        code: 'APPRAISAL-409-REASSIGN-EXISTS',
      } as never),
    ).toBe('Đề xuất bố trí lại của hồ sơ này đã được lập.');
    expect(reassignProblemMessage({ status: 403, title: 'Forbidden' } as never)).toContain(
      'không có quyền',
    );
  });
});
