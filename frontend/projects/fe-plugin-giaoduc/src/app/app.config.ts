import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { provideDesignSystem } from '@vt/design-system';
import { ROUTES } from './plugin-giaoduc.routes';

/** Chạy độc lập với cấu hình mặc định và mock API; trong shell, cấu hình do shell cung cấp. */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(ROUTES, withComponentInputBinding()),
    // mockApiInterceptor đứng cuối chuỗi interceptor (WEB-008)
    provideHttpClient(withFetch(), withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
    provideDesignSystem(),
    { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
  ],
};
