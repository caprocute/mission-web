import { definePreset } from '@primeuix/themes';
import { AuraCore } from './aura-core';

/**
 * Màu dùng cho chữ và cho nền có chữ trắng (WCAG 2.1 AA, WEB-059). Màu nhận diện
 * `--vt-color-primary` (#EE0033) chỉ đạt 4,48:1 với chữ trắng, nên nút, liên kết và tab
 * lấy `--vt-color-primary-strong`. Có giá trị dự phòng cho remote chạy độc lập khi
 * UI Pack chưa khai báo token mới.
 */
const STRONG = 'var(--vt-color-primary-strong, #d1002d)';
/** Chữ phụ: #64748b mặc định của Aura chỉ đạt 4,4:1 trên nền #F5F6F8. */
const MUTED = 'var(--vt-color-text-muted, #566275)';

/**
 * Preset gốc của nền tảng (ADR-05, lớp 1 Design Token).
 * Các token ngữ nghĩa của PrimeNG trỏ tới CSS variables của tenant,
 * nên đổi tenant chỉ cần đổi tệp CSS token, không phải dựng lại preset.
 * Thang màu (50..950) vẫn pha từ màu nhận diện để giữ sắc hồng quen thuộc cho nền nhạt,
 * viền và thanh tiến độ; chỉ màu chữ và nền nút chuyển sang bản đậm.
 */
export const VtPreset = definePreset(AuraCore, {
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
        primary: {
          color: STRONG,
          contrastColor: 'var(--vt-color-on-primary-strong, var(--vt-color-on-primary, #ffffff))',
          hoverColor: `color-mix(in srgb, ${STRONG} 88%, black)`,
          activeColor: `color-mix(in srgb, ${STRONG} 76%, black)`,
        },
        text: {
          color: 'var(--vt-color-text)',
          mutedColor: MUTED,
          hoverMutedColor: `color-mix(in srgb, ${MUTED} 80%, black)`,
        },
      },
    },
  },
  components: {
    card: { root: { borderRadius: 'var(--vt-radius-card)' } },
    // Nút phụ viền và nút chữ của Aura dùng {surface.500} (#64748b), chỉ 4,4:1 trên nền trang;
    // lấy màu chữ phụ của UI Pack, đã qua kiểm tra tương phản của bộ biên dịch token (WEB-004).
    button: {
      colorScheme: {
        light: {
          outlined: { secondary: { color: MUTED } },
          text: { secondary: { color: MUTED } },
        },
      },
    },
  },
});
