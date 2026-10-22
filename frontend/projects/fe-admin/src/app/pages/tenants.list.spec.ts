import { describe, expect, it } from 'vitest';
import { sanitizeCriteria } from '@vt/api-client';
import { TENANTS_CODEC } from './tenants.list';

describe('TENANTS_CODEC (CNH-04)', () => {
  it('cụm máy chủ, mức cô lập, trạng thái đi và về đúng', () => {
    const s = { cell: 'CELL-HN', model: 'pool' as const, status: 'DANG_VAN_HANH' as const };
    const c = sanitizeCriteria(TENANTS_CODEC.toCriteria(s));
    expect(c).toEqual({
      statusCodes: ['DANG_VAN_HANH'],
      flags: { model_pool: true, cell_CELL_HN: true },
    });
    expect(TENANTS_CODEC.fromCriteria(c)).toEqual({ state: s, dropped: 0 });
  });

  it('bỏ trạng thái và mức cô lập không còn tồn tại', () => {
    const r = TENANTS_CODEC.fromCriteria({
      statusCodes: ['DA_XOA_HAN'],
      flags: { model_cu: true },
    });
    expect(r.state).toEqual({ cell: null, model: null, status: null });
    expect(r.dropped).toBe(2);
  });
});
