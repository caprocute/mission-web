import { describe, expect, it } from 'vitest';
import { sanitizeCriteria } from '@vt/api-client';
import { MY_TASKS_CODEC } from './my-tasks.list';

describe('MY_TASKS_CODEC (CNH-04)', () => {
  it('đi và về giữ nguyên trạng thái lọc, không có văn bản tự do', () => {
    const c = MY_TASKS_CODEC.toCriteria({ filter: 'CHAM', role: 'CHU_TRI', group: 'THEN_CHOT' });
    expect(sanitizeCriteria(c)).toEqual(c);
    expect(MY_TASKS_CODEC.fromCriteria(c)).toEqual({
      state: { filter: 'CHAM', role: 'CHU_TRI', group: 'THEN_CHOT' },
      dropped: 0,
    });
    expect(MY_TASKS_CODEC.toCriteria({ filter: 'TAT_CA', role: null, group: null })).toEqual({});
  });

  it('điều kiện không còn hợp lệ bị bỏ qua và được đếm', () => {
    const r = MY_TASKS_CODEC.fromCriteria({ flags: { quick_KHONG_CO: true, role_CHU_TRI: true }, periodIds: [] });
    expect(r.state.role).toBe('CHU_TRI');
    expect(r.state.filter).toBe('TAT_CA');
    expect(r.dropped).toBe(2);
  });
});
