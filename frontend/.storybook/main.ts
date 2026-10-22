import type { StorybookConfig } from '@storybook/angular';

/**
 * Storybook tầng 1 của chiến lược kiểm thử giao diện theo tenant (TL-01 "Kiểm thử hồi quy giao diện
 * theo tenant", WEB-005): mỗi component trong registry SDUI có story, chụp ảnh với bốn bộ token.
 * UI Pack thử của shell được phục vụ ở `/ui-packs` để `UiPackLoader` áp token như ứng dụng thật.
 */
const config: StorybookConfig = {
  stories: ['../projects/**/*.stories.ts'],
  addons: [],
  framework: { name: '@storybook/angular', options: {} },
  staticDirs: [{ from: '../projects/fe-shell/public/ui-packs', to: '/ui-packs' }],
  core: { disableTelemetry: true },
};

export default config;
