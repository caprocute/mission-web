import { EnvironmentProviders, makeEnvironmentProviders } from '@angular/core';
import { providePrimeNG } from 'primeng/config';
import { VtPreset } from './vt-preset';

/** Đăng ký PrimeNG với preset của nền tảng; dùng chung cho shell và mọi remote. */
export function provideDesignSystem(): EnvironmentProviders {
  return makeEnvironmentProviders([
    providePrimeNG({
      theme: { preset: VtPreset, options: { cssLayer: { name: 'primeng', order: 'base, primeng, app' }, darkModeSelector: false } },
      ripple: false,
    }),
  ]);
}
