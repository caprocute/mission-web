import { contrastRatio } from './contrast';
import { BUTTON_TEXT, MESSAGE_TEXT } from './vt-preset';

/** Bảng màu Aura (Tailwind) dùng cho thông báo: nền sắc 50, chữ theo `MESSAGE_TEXT`. */
const PALETTE: Record<string, string> = {
  '{blue.50}': '#eff6ff',
  '{blue.700}': '#1d4ed8',
  '{green.50}': '#f0fdf4',
  '{green.800}': '#166534',
  '{yellow.50}': '#fefce8',
  '{yellow.800}': '#854d0e',
  '{red.50}': '#fef2f2',
  '{red.700}': '#b91c1c',
  '{sky.800}': '#075985',
  '{orange.800}': '#9a3412',
  '{purple.700}': '#7e22ce',
};
const NEN: Record<keyof typeof MESSAGE_TEXT, string> = {
  info: '{blue.50}',
  success: '{green.50}',
  warn: '{yellow.50}',
  error: '{red.50}',
};

describe('VtPreset · thông báo', () => {
  for (const k of Object.keys(MESSAGE_TEXT) as (keyof typeof MESSAGE_TEXT)[]) {
    it(`chữ thông báo mức ${k} đạt 4,5:1 trên nền thông báo và nền trắng`, () => {
      const chu = PALETTE[MESSAGE_TEXT[k]];
      expect(contrastRatio(chu, PALETTE[NEN[k]])).toBeGreaterThanOrEqual(4.5);
      expect(contrastRatio(chu, '#ffffff')).toBeGreaterThanOrEqual(4.5);
    });
  }
});

describe('VtPreset · nút viền và nút chữ', () => {
  for (const k of Object.keys(BUTTON_TEXT) as (keyof typeof BUTTON_TEXT)[]) {
    it(`chữ nút mức ${k} đạt 4,5:1 trên nền trắng`, () => {
      expect(contrastRatio(PALETTE[BUTTON_TEXT[k]], '#ffffff')).toBeGreaterThanOrEqual(4.5);
    });
  }
});
