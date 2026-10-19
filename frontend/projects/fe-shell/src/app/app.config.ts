import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import {
  RUNTIME_CONFIG,
  RuntimeConfig,
  apiBaseUrlInterceptor,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { authInterceptor, provideVtAuth } from '@vt/auth';
import { provideDesignSystem } from '@vt/design-system';
import {
  BUILTIN_SDUI_COMPONENTS,
  UI_PACK_PREFETCH,
  UiPackLoader,
  UiPackPrefetch,
  provideSduiComponents,
} from '@vt/sdui-renderer';
import { traceparentInterceptor } from '@vt/telemetry';
import { routes } from './app.routes';
import { SHELL_WIDGETS } from './widgets/shell-widgets';

/** `uiPackPrefetch`: UI Pack main.ts đã tải sớm (WEB-052), `UiPackLoader` dùng lại nếu khớp cấu hình. */
export function buildAppConfig(
  cfg: RuntimeConfig,
  uiPackPrefetch?: Promise<UiPackPrefetch | null>,
): ApplicationConfig {
  return {
    providers: [
      provideBrowserGlobalErrorListeners(),
      { provide: RUNTIME_CONFIG, useValue: cfg },
      provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({ scrollPositionRestoration: 'top' })),
      provideHttpClient(
        withFetch(),
        // mockApiInterceptor đứng cuối: chỉ trả fixture khi useMockApi bật (WEB-008)
        withInterceptors([
          apiBaseUrlInterceptor,
          authInterceptor,
          idempotencyInterceptor,
          traceparentInterceptor,
          mockApiInterceptor,
        ]),
      ),
      provideDesignSystem(),
      provideVtAuth(),
      provideSduiComponents(BUILTIN_SDUI_COMPONENTS),
      provideSduiComponents(SHELL_WIDGETS),
      ...(uiPackPrefetch ? [{ provide: UI_PACK_PREFETCH, useValue: uiPackPrefetch }] : []),
      // nạp token của tenant trước khi render để không nháy giao diện
      provideAppInitializer(() => inject(UiPackLoader).load()),
    ],
  };
}
