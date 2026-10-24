import { ledgerAggregateTypeLabel } from './ledger';

/** Máy chủ sổ cái trả loại đối tượng viết hoa; màn hình SCR-1302 không bao giờ hiện mã thô (YC-PT-10). */
describe('ledgerAggregateTypeLabel', () => {
  it('tra không phân biệt hoa thường, loại chưa có tên thì hiện tên chung', () => {
    expect(ledgerAggregateTypeLabel('TASK')).toBe('Nhiệm vụ');
    expect(ledgerAggregateTypeLabel('task')).toBe('Nhiệm vụ');
    expect(ledgerAggregateTypeLabel('KIEM_TRA_SO_CAI')).toBe('Kiểm tra sổ cái');
    expect(ledgerAggregateTypeLabel('LOAI_MOI')).toBe('Đối tượng khác');
  });
});
