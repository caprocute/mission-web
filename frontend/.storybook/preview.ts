import type { Decorator, Preview } from '@storybook/angular';
import { applicationConfig } from '@storybook/angular';
import { inject, provideAppInitializer } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withDisabledInitialNavigation } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  MOCK_API_OPTIONS,
  RUNTIME_CONFIG,
  RuntimeConfig,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { provideVtAuth } from '@vt/auth';
import { ThemeService, provideDesignSystem } from '@vt/design-system';
import { UiPackLoader } from '@vt/sdui-renderer';

/** Bốn bộ token của kiểm thử hồi quy, mỗi bộ là một UI Pack thử trong `fe-shell/public/ui-packs`. */
export const TOKEN_SETS = ['default', 'cao-tuoi', 'thuong-hieu-toi', 'tuong-phan-cao'] as const;

/**
 * Mỗi story dựng một ứng dụng Angular riêng với cấu hình như shell ở chế độ giả lập: đăng nhập tắt,
 * API giả lập không trễ, UI Pack của bộ token đang chọn được nạp trước khi vẽ.
 */
const withTokenSet: Decorator = (story, ctx) => {
  const tenant = String(ctx.globals['tokenSet'] ?? 'default');
  const elderly = ctx.globals['cheDo'] === 'elderly';
  const cfg: RuntimeConfig = { ...DEFAULT_RUNTIME_CONFIG, tenant, useMockApi: true };
  return applicationConfig({
    providers: [
      { provide: RUNTIME_CONFIG, useValue: cfg },
      {
        provide: MOCK_API_OPTIONS,
        useValue: { minDelayMs: 0, maxDelayMs: 0, pageQuery: () => '' },
      },
      provideHttpClient(withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
      provideRouter([], withDisabledInitialNavigation()),
      provideDesignSystem(),
      provideVtAuth(),
      provideAppInitializer(() => {
        // chế độ "Đảng viên cao tuổi" (NFR-11) chồng lên bộ token như người dùng bật trên thiết bị
        if (elderly)
          inject(ThemeService).applyUserPreference({ mode: 'elderly', reducedSteps: true });
        return inject(UiPackLoader).load(tenant);
      }),
    ],
  })(story, ctx);
};

const preview: Preview = {
  decorators: [withTokenSet],
  globalTypes: {
    tokenSet: {
      description: 'Bộ token (UI Pack thử)',
      toolbar: {
        title: 'Bộ token',
        icon: 'paintbrush',
        items: TOKEN_SETS.map((t) => ({ value: t, title: t })),
        dynamicTitle: true,
      },
    },
    cheDo: {
      description: 'Chế độ hiển thị',
      toolbar: {
        title: 'Chế độ',
        icon: 'accessibility',
        items: [
          { value: 'default', title: 'Thường' },
          { value: 'elderly', title: 'Đảng viên cao tuổi' },
        ],
        dynamicTitle: true,
      },
    },
  },
  initialGlobals: { tokenSet: 'default', cheDo: 'default' },
  parameters: { layout: 'padded' },
};

export default preview;
