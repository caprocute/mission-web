import { TestBed } from '@angular/core/testing';
import { PreferenceApi } from '@vt/api-client';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { DefaultDashboardStore } from './default-dashboard';

const ITEM = {
  prefKey: 'ui.dashboard.default',
  value: { defaultDashboardId: 'd1', pinnedKpiCodes: ['K1'] },
  schemaVersion: 1,
  rowVersion: 3,
};

function setup(items: Record<string, unknown>) {
  const api = {
    all: vi.fn(() => of({ schemaVersion: 1, items })),
    put: vi.fn((_k: string, value: object) => of({ ...ITEM, value, rowVersion: 4 })),
    remove: vi.fn(() => of(undefined)),
  };
  TestBed.configureTestingModule({ providers: [{ provide: PreferenceApi, useValue: api }] });
  return { api, store: TestBed.inject(DefaultDashboardStore) };
}

describe('DefaultDashboardStore (CNH-03)', () => {
  it('đặt mặc định giữ trường khác và gửi phiên bản đã đọc', () => {
    const { api, store } = setup({ 'ui.dashboard.default': ITEM });
    store.load().subscribe();
    expect(store.defaultId()).toBe('d1');
    store.set('d2').subscribe();
    expect(api.put).toHaveBeenCalledWith(
      'ui.dashboard.default',
      { pinnedKpiCodes: ['K1'], defaultDashboardId: 'd2' },
      3,
    );
    expect(store.defaultId()).toBe('d2');
  });

  it('chưa từng lưu thì tạo mới không kèm phiên bản; bỏ mặc định khi không còn gì thì xoá khoá', () => {
    const { api, store } = setup({});
    store.load().subscribe();
    store.set('d1').subscribe();
    expect(api.put).toHaveBeenCalledWith('ui.dashboard.default', { defaultDashboardId: 'd1' }, null);
    store.set(null).subscribe();
    expect(api.remove).toHaveBeenCalledWith('ui.dashboard.default', 4);
    expect(store.defaultId()).toBeNull();
  });
});
