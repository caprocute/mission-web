import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ThemeService } from './theme.service';

describe('ThemeService chế độ theo người dùng (CNH-05)', () => {
  beforeEach(() => localStorage.clear());

  it('dùng bản đệm cục bộ ngay khi khởi tạo để không nháy', () => {
    localStorage.setItem('vt.displayMode', 'elderly');
    const theme = TestBed.inject(ThemeService);
    expect(theme.mode()).toBe('elderly');
    expect(document.documentElement.getAttribute('data-mode')).toBe('elderly');
  });

  it('tuỳ chọn máy chủ thắng mặc định UI Pack; xoá tuỳ chọn thì về mặc định', () => {
    const theme = TestBed.inject(ThemeService);
    theme.reducedSteps.set(true); // mặc định UI Pack
    theme.applyUserPreference({ mode: 'elderly', reducedSteps: false });
    expect(theme.mode()).toBe('elderly');
    expect(theme.reducedSteps()).toBe(false);
    theme.reducedSteps.set(true); // UI Pack nạp sau vẫn không đè tuỳ chọn người dùng
    expect(theme.reducedSteps()).toBe(false);
    theme.applyUserPreference(null);
    expect(theme.mode()).toBe('default');
    expect(theme.reducedSteps()).toBe(true);
  });

  it('lựa chọn trong phiên được giữ khi máy chủ chưa có tuỳ chọn', () => {
    const theme = TestBed.inject(ThemeService);
    theme.toggleElderly();
    theme.applyUserPreference(null);
    expect(theme.mode()).toBe('elderly');
    expect(theme.currentPreference()).toEqual({ mode: 'elderly', reducedSteps: false });
  });
});
