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
import { provideFormEngine } from '@vt/form-engine';
import { BUILTIN_SDUI_COMPONENTS, UiPackLoader, provideSduiComponents } from '@vt/sdui-renderer';
import { traceparentInterceptor } from '@vt/telemetry';
import { routes } from './app.routes';
import { SHELL_WIDGETS } from './widgets/shell-widgets';

export function buildAppConfig(cfg: RuntimeConfig): ApplicationConfig {
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
      provideFormEngine(),
      provideVtAuth(),
      provideSduiComponents(BUILTIN_SDUI_COMPONENTS),
      provideSduiComponents(SHELL_WIDGETS),
      // nạp token của tenant trước khi render để không nháy giao diện
      provideAppInitializer(() => inject(UiPackLoader).load()),
    ],
  };
}
