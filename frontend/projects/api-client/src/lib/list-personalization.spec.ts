import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { HttpErrorResponse } from '@angular/common/http';
import {
  FilterCodec,
  createListPersonalization,
  decodeCriteria,
  encodeCriteria,
  mergeVisibleColumns,
  sanitizeCriteria,
} from './list-personalization';
import { PreferenceApi } from './preference-api';

const COLS = [
  { key: 'name', label: 'Tên', required: true },
  { key: 'a', label: 'A' },
  { key: 'b', label: 'B' },
];

describe('sanitizeCriteria (CNH-04)', () => {
  it('bỏ văn bản tìm kiếm và mọi khoá ngoài lược đồ, không để lọt dữ liệu C2', () => {
    const out = sanitizeCriteria({
      q: 'Nguyễn Văn A 0912345678',
      keyword: 'số CCCD 001203004567',
      statusCodes: ['DA_GIAO', 'Nguyễn Văn A', 'DA_GIAO'],
      orgUnitIds: ['khong-phai-uuid', '0b8f3c2a-1d4e-4a5b-8c6d-7e8f9a0b1c2d'],
      flags: { quick_CHAM: true, 'ten co dau cach': true, off: false },
      dateRanges: { dueDate: { from: '2026-10-01', to: 'hôm nay', note: 'x' } },
      sort: { field: 'dueDate', direction: 'DESC', extra: 1 },
    });
    expect(out).toEqual({
      statusCodes: ['DA_GIAO'],
      orgUnitIds: ['0b8f3c2a-1d4e-4a5b-8c6d-7e8f9a0b1c2d'],
      flags: { quick_CHAM: true },
      dateRanges: { dueDate: { from: '2026-10-01' } },
      sort: { field: 'dueDate', direction: 'DESC' },
    });
    expect(JSON.stringify(out)).not.toMatch(/Nguyễn|0912345678|CCCD/);
  });

  it('mã hoá lên URL và đọc lại an toàn', () => {
    expect(encodeCriteria({})).toBeNull();
    const raw = encodeCriteria({ statusCodes: ['DA_NOP'] });
    expect(decodeCriteria(raw)).toEqual({ statusCodes: ['DA_NOP'] });
    expect(decodeCriteria('{hỏng')).toBeNull();
    expect(decodeCriteria(null)).toBeNull();
  });

  it('cột bắt buộc luôn hiện, mã lạ bị bỏ, chưa lưu thì hiện hết', () => {
    expect(mergeVisibleColumns(COLS, undefined)).toEqual(['name', 'a', 'b']);
    expect(mergeVisibleColumns(COLS, ['b', 'zz'])).toEqual(['name', 'b']);
  });
});

interface S {
  status: string | null;
}
const CODEC: FilterCodec<S> = {
  toCriteria: (s) => (s.status ? { statusCodes: [s.status] } : {}),
  fromCriteria: (c) => ({
    state: { status: c.statusCodes?.[0] ?? null },
    dropped: c.flags ? 1 : 0,
  }),
};

function setup(filters: unknown[], table: unknown = null) {
  const api = {
    all: vi.fn(() => of({ schemaVersion: 1, items: table ? { 'ui.table.SCR-0101': table } : {} })),
    filters: vi.fn(() => of(filters)),
    put: vi.fn((_k: string, value: object) =>
      of({ prefKey: 'ui.table.SCR-0101', value, schemaVersion: 1, rowVersion: 2 }),
    ),
    createFilter: vi.fn((input: { name: string }) => of({ ...input, id: 'f9', rowVersion: 1 })),
    updateFilter: vi.fn(() => of({})),
    removeFilter: vi.fn(() => of(undefined)),
  };
  TestBed.configureTestingModule({ providers: [{ provide: PreferenceApi, useValue: api }] });
  const list = TestBed.runInInjectionContext(() =>
    createListPersonalization('SCR-0101', COLS, CODEC),
  );
  return { api, list };
}

const F1 = {
  id: 'f1',
  screenCode: 'SCR-0101',
  name: 'Đang vận hành',
  criteria: { statusCodes: ['DANG_VAN_HANH'], flags: { x: true } },
  isDefault: true,
  displayOrder: 0,
  rowVersion: 3,
};

describe('ListPersonalization (CNH-04)', () => {
  it('áp bộ lọc mặc định khi tải, báo điều kiện bị bỏ qua', () => {
    const { list } = setup([F1]);
    const onDefault = vi.fn();
    list.load(onDefault);
    expect(onDefault).toHaveBeenCalledWith({ status: 'DANG_VAN_HANH' });
    expect(list.activeId()).toBe('f1');
    expect(list.message()?.text).toContain('1 điều kiện không còn phù hợp');
  });

  it('lưu cột với If-Match theo phiên bản đã đọc và giữ cột bắt buộc', () => {
    const { api, list } = setup([], {
      prefKey: 'ui.table.SCR-0101',
      value: { pageSize: 50 },
      schemaVersion: 1,
      rowVersion: 7,
    });
    list.load();
    list.setVisible(['b']);
    expect(api.put).toHaveBeenCalledWith(
      'ui.table.SCR-0101',
      { pageSize: 50, visibleColumns: ['name', 'b'] },
      7,
    );
    expect(list.visible()).toEqual(['name', 'b']);
  });

  it('lưu bộ lọc chỉ gửi tiêu chí đã làm sạch; đổi tên, đặt mặc định và xoá kèm phiên bản', () => {
    const { api, list } = setup([F1]);
    list.load();
    list.saveFilter('  Bộ lọc mới ', { status: 'TAM_NGUNG' }, false);
    expect(api.createFilter).toHaveBeenCalledWith({
      screenCode: 'SCR-0101',
      name: 'Bộ lọc mới',
      criteria: { statusCodes: ['TAM_NGUNG'] },
      isDefault: false,
      displayOrder: 1,
    });
    list.rename('f1', 'Tên khác');
    expect(api.updateFilter).toHaveBeenCalledWith(
      'f1',
      expect.objectContaining({ name: 'Tên khác' }),
      3,
    );
    list.toggleDefault('f1');
    expect(api.updateFilter).toHaveBeenLastCalledWith(
      'f1',
      expect.objectContaining({ isDefault: false }),
      3,
    );
    list.remove('f1');
    expect(api.removeFilter).toHaveBeenCalledWith('f1', 3);
  });

  it('xung đột phiên bản thì báo và tải lại', () => {
    const { api, list } = setup([F1]);
    list.load();
    api.removeFilter.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409 })));
    list.remove('f1');
    expect(list.message()).toEqual({ ok: false, text: expect.stringContaining('thiết bị khác') });
    expect(api.filters).toHaveBeenCalledTimes(2);
  });
});
