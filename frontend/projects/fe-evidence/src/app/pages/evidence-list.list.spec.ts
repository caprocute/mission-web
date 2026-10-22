import { describe, expect, it } from 'vitest';
import { EVIDENCE_LIST_CODEC } from './evidence-list.list';

describe('EVIDENCE_LIST_CODEC (CNH-04)', () => {
  it('tình trạng lưu thành mã, mã lạ bị bỏ qua', () => {
    expect(EVIDENCE_LIST_CODEC.toCriteria({ statuses: ['DA_NOP', 'NHAP'] })).toEqual({
      statusCodes: ['DA_NOP', 'NHAP'],
    });
    expect(EVIDENCE_LIST_CODEC.toCriteria({ statuses: [] })).toEqual({});
    expect(
      EVIDENCE_LIST_CODEC.fromCriteria({ statusCodes: ['DA_NOP', 'CU'], assigneeScope: 'ME' }),
    ).toEqual({
      state: { statuses: ['DA_NOP'] },
      dropped: 2,
    });
  });
});
