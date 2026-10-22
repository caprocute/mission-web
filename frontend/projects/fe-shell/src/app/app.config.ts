import { firstValueFrom } from 'rxjs';
import {
  ApplicationConfig,
  effect,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideEnvironmentInitializer,
} from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import {
  RUNTIME_CONFIG,
  RuntimeConfig,
  CellRouter,
  cellBaseUrlInterceptor,
  idempotencyInterceptor,
  loadSheddingInterceptor,
  mockApiInterceptor,
  FeatureFlagService,
} from '@vt/api-client';
import { SessionService, authInterceptor, provideVtAuth } from '@vt/auth';
import { provideDesignSystem } from '@vt/design-system';
import {
  BUILTIN_SDUI_COMPONENTS,
  SDUI_TELEMETRY_SINK,
  UI_PACK_PREFETCH,
  UiPackLoader,
  UiPackPrefetch,
  provideSduiComponents,
} from '@vt/sdui-renderer';
import {
  BrowserTelemetryConfig,
  TRACE_OPTIONS,
  browserTracingActive,
  debugTraceRequested,
  pushTelemetryEvent,
  startBrowserTelemetry,
  traceparentInterceptor,
} from '@vt/telemetry';
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
      {
        // WEB-074: lấy mẫu theo cấu hình, Baggage tenant, X-Debug-Trace theo ?debug-trace=1
        provide: TRACE_OPTIONS,
        useValue: {
          sampleRatio: cfg.telemetry?.sampleRatio ?? 1,
          tenant: cfg.tenant,
          debugTrace: debugTraceRequested(),
          // WEB-011: khi Faro đã bật, instrumentation fetch của OpenTelemetry gắn traceparent
          delegated: browserTracingActive,
          random: Math.random,
        },
      },
      // WEB-011: sự kiện an toàn của SDUI (WEB-009) đi vào Faro; chưa bật Faro thì chỉ ghi console
      {
        provide: SDUI_TELEMETRY_SINK,
        useValue: (r: { event: string; attributes: Record<string, string | number> }) =>
          pushTelemetryEvent(r.event, r.attributes),
      },
      // Faro nạp lười khi trình duyệt rảnh, không chặn khởi động; chỉ khi config.json có faroUrl
      provideEnvironmentInitializer(() => {
        const t = cfg.telemetry as BrowserTelemetryConfig | undefined;
        void startBrowserTelemetry({ ...t, environment: cfg.cell, tenant: cfg.tenant });
      }),
      provideRouter(
        routes,
        withComponentInputBinding(),
        withInMemoryScrolling({ scrollPositionRestoration: 'top' }),
      ),
      provideHttpClient(
        withFetch(),
        // mockApiInterceptor đứng cuối: chỉ trả fixture khi useMockApi bật (WEB-008)
        withInterceptors([
          // Gốc API theo cell của tenant, đổi cell khi gặp 421 (WEB-075)
          cellBaseUrlInterceptor,
          authInterceptor,
          idempotencyInterceptor,
          // Thử lại 429/503 theo Retry-After (WEB-073); sau Idempotency-Key để giữ nguyên khoá
          loadSheddingInterceptor,
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
      // Khám phá cell sau đăng nhập (WEB-075); dữ liệu giả lập thì giữ gốc API của config.json
      provideAppInitializer(() => {
        if (cfg.useMockApi) return;
        const tenant = inject(SessionService).user()?.tenant ?? cfg.tenant;
        return firstValueFrom(inject(CellRouter).resolve(tenant)).then(() => undefined);
      }),
      // Cờ tính năng (WEB-076): nạp khi khởi động và nạp lại khi đổi phân công; lỗi thì mọi cờ tắt
      provideAppInitializer(() => {
        const flags = inject(FeatureFlagService);
        const session = inject(SessionService);
        let loaded = session.assignment()?.id;
        effect(() => {
          const id = session.assignment()?.id;
          if (id !== loaded) {
            loaded = id;
            void flags.load();
          }
        });
        return flags.load();
      }),
    ],
  };
}
