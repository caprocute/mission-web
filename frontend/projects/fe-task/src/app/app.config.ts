import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { DEFAULT_RUNTIME_CONFIG, RUNTIME_CONFIG, idempotencyInterceptor } from '@vt/api-client';
import { provideDesignSystem } from '@vt/design-system';
import { TASK_ROUTES } from './task.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(TASK_ROUTES, withComponentInputBinding()),
    provideHttpClient(withFetch(), withInterceptors([idempotencyInterceptor])),
    provideDesignSystem(),
    { provide: RUNTIME_CONFIG, useValue: DEFAULT_RUNTIME_CONFIG },
  ],
};
