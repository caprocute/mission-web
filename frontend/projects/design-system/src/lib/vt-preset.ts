import { definePreset } from '@primeuix/themes';
import Aura from '@primeuix/themes/aura';

/**
 * Preset gốc của nền tảng (ADR-05, lớp 1 Design Token).
 * Các token ngữ nghĩa của PrimeNG trỏ tới CSS variables của tenant,
 * nên đổi tenant chỉ cần đổi tệp CSS token, không phải dựng lại preset.
 */
export const VtPreset = definePreset(Aura, {
  semantic: {
    primary: {
      50: 'color-mix(in srgb, var(--vt-color-primary) 8%, white)',
      100: 'color-mix(in srgb, var(--vt-color-primary) 16%, white)',
      200: 'color-mix(in srgb, var(--vt-color-primary) 32%, white)',
      300: 'color-mix(in srgb, var(--vt-color-primary) 48%, white)',
      400: 'color-mix(in srgb, var(--vt-color-primary) 72%, white)',
      500: 'var(--vt-color-primary)',
      600: 'color-mix(in srgb, var(--vt-color-primary) 88%, black)',
      700: 'color-mix(in srgb, var(--vt-color-primary) 76%, black)',
      800: 'color-mix(in srgb, var(--vt-color-primary) 64%, black)',
      900: 'color-mix(in srgb, var(--vt-color-primary) 52%, black)',
      950: 'color-mix(in srgb, var(--vt-color-primary) 40%, black)',
    },
    colorScheme: {
      light: {
        surface: { 0: 'var(--vt-color-surface)' },
        text: { color: 'var(--vt-color-text)' },
      },
    },
  },
  components: {
    card: { root: { borderRadius: 'var(--vt-radius-card)' } },
  },
});
