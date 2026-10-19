import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import {
  DEFAULT_RUNTIME_CONFIG,
  RUNTIME_CONFIG,
  idempotencyInterceptor,
  mockApiInterceptor,
} from '@vt/api-client';
import { VT_FORBIDDEN_URL, provideVtAuth } from '@vt/auth';
import { provideDesignSystem } from '@vt/design-system';
import { TASK_ROUTES } from './task.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(TASK_ROUTES, withComponentInputBinding()),
    provideHttpClient(withFetch(), withInterceptors([idempotencyInterceptor, mockApiInterceptor])),
    provideDesignSystem(),
    // Chạy riêng remote: người dùng giả lập (vai trò đặt được qua sessionStorage `vt.devRoles`),
    // không có trang "không có quyền" của shell nên guard chỉ chặn
    provideVtAuth(),
    { provide: VT_FORBIDDEN_URL, useValue: null },
    { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
  ],
};
