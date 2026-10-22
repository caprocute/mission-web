import { HttpErrorResponse } from '@angular/common/http';
import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { PreferenceApi } from '@vt/api-client';
import { SessionService } from '@vt/auth';
import { ThemeService } from '@vt/design-system';
import { of, throwError } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { DisplayPreferenceSync } from './display-preference.sync';

const item = (mode: string, rowVersion: number, extra = {}) => ({
  prefKey: 'ui.display',
  value: { mode, reducedSteps: true, ...extra },
  schemaVersion: 1,
  rowVersion,
});

function setup(items: Record<string, unknown>) {
  const user = signal<{ id: string } | null>(null);
  const api = {
    all: vi.fn(() => of({ schemaVersion: 1, items })),
    put: vi.fn((_k: string, value: object, rv: number | null) =>
      of({ prefKey: 'ui.display', value, schemaVersion: 1, rowVersion: (rv ?? 0) + 1 }),
    ),
  };
  TestBed.configureTestingModule({
    providers: [
      { provide: PreferenceApi, useValue: api },
      { provide: SessionService, useValue: { user } },
    ],
  });
  const sync = TestBed.inject(DisplayPreferenceSync);
  const theme = TestBed.inject(ThemeService);
  return { sync, theme, api, user };
}

describe('DisplayPreferenceSync (CNH-05)', () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.useRealTimers());

  it('đăng nhập ở thiết bị khác thì áp chế độ đã lưu trên máy chủ', () => {
    const { theme, user } = setup({ 'ui.display': item('elderly', 3) });
    expect(theme.mode()).toBe('default');
    user.set({ id: 'u1' });
    TestBed.tick();
    expect(theme.mode()).toBe('elderly');
    expect(theme.reducedSteps()).toBe(true);
  });

  it('nút chuyển nhanh ghi ngay kèm phiên bản, giữ các trường khác', () => {
    const { sync, api, user } = setup({ 'ui.display': item('elderly', 3, { fontScale: 1.2 }) });
    user.set({ id: 'u1' });
    TestBed.tick();
    sync.toggle();
    expect(api.put).toHaveBeenCalledWith(
      'ui.display',
      { mode: 'default', reducedSteps: true, fontScale: 1.2 },
      3,
    );
  });

  it('xung đột thì đọc bản mới rồi ghi lại lựa chọn vừa bấm', () => {
    const { sync, api, user } = setup({});
    user.set({ id: 'u1' });
    TestBed.tick();
    api.put.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 409 })));
    api.all.mockReturnValueOnce(
      of({ schemaVersion: 1, items: { 'ui.display': item('default', 5) } }),
    );
    sync.toggle();
    expect(api.put).toHaveBeenLastCalledWith(
      'ui.display',
      expect.objectContaining({ mode: 'elderly' }),
      5,
    );
  });

  it('mất mạng thì giữ chế độ trong phiên và thử lại', () => {
    vi.useFakeTimers();
    const { sync, theme, api, user } = setup({});
    user.set({ id: 'u1' });
    TestBed.tick();
    api.put.mockReturnValueOnce(throwError(() => new HttpErrorResponse({ status: 0 })));
    sync.toggle();
    expect(theme.mode()).toBe('elderly');
    expect(api.put).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(2000);
    expect(api.put).toHaveBeenCalledTimes(2);
  });
});
