import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  inject,
  provideAppInitializer,
} from '@angular/core';
import { LocationStrategy } from '@angular/common';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  provideRouter,
  withComponentInputBinding,
  withDisabledInitialNavigation,
} from '@angular/router';
import { MessageService } from 'primeng/api';
import {
  RUNTIME_CONFIG,
  RuntimeConfig,
  cellBaseUrlInterceptor,
  idempotencyInterceptor,
  loadSheddingInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { VT_FORBIDDEN_URL, authInterceptor, provideVtAuth } from '@vt/auth';
import { provideDesignSystem } from '@vt/design-system';
import { UiPackLoader } from '@vt/sdui-renderer';
import { TRACE_OPTIONS, traceparentInterceptor } from '@vt/telemetry';
import { EMBED_ROUTES } from './embed-routes';
import { EMBED_APP_ROOT, MemoryLocationStrategy } from './memory-location';

/**
 * Cấu hình của phần nhúng (WEB-013): cùng chuỗi interceptor và đăng nhập như shell, nhưng định
 * tuyến trong bộ nhớ để không đụng thanh địa chỉ của cổng đang chứa.
 */
export function buildEmbedConfig(cfg: RuntimeConfig, appRoot: string): ApplicationConfig {
  return {
    providers: [
      provideBrowserGlobalErrorListeners(),
      { provide: RUNTIME_CONFIG, useValue: cfg },
      { provide: EMBED_APP_ROOT, useValue: appRoot },
      { provide: LocationStrategy, useClass: MemoryLocationStrategy },
      { provide: VT_FORBIDDEN_URL, useValue: '/khong-co-quyen' },
      {
        provide: TRACE_OPTIONS,
        useValue: {
          sampleRatio: cfg.telemetry?.sampleRatio ?? 1,
          tenant: cfg.tenant,
          debugTrace: false,
          random: Math.random,
        },
      },
      MessageService,
      provideRouter(EMBED_ROUTES, withComponentInputBinding(), withDisabledInitialNavigation()),
      provideHttpClient(
        withFetch(),
        withInterceptors([
          cellBaseUrlInterceptor,
          authInterceptor,
          idempotencyInterceptor,
          loadSheddingInterceptor,
          traceparentInterceptor,
          mockApiInterceptor,
        ]),
      ),
      provideDesignSystem(),
      provideVtAuth(),
      // token giao diện của tenant trước khi phần tử hiển thị
      provideAppInitializer(() => inject(UiPackLoader).load()),
    ],
  };
}
