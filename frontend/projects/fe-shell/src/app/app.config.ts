import { MessageService } from 'primeng/api';
import { firstValueFrom } from 'rxjs';
import {
  ApplicationConfig,
  ErrorHandler,
  effect,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideEnvironmentInitializer,
} from '@angular/core';
import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  RedirectCommand,
  Router,
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withNavigationErrorHandler,
} from '@angular/router';
import {
  RUNTIME_CONFIG,
  RuntimeConfig,
  CellRouter,
  cellBaseUrlInterceptor,
  idempotencyInterceptor,
  loadSheddingInterceptor,
  mockApiInterceptor,
  FeatureFlagService,
  mockApiActive,
  setMockHomeBase,
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
import { PluginHost } from './plugins/plugin-host';
import { GlobalErrorHandler } from './layout/app-status';
import { SHELL_WIDGETS } from './widgets/shell-widgets';

/** `uiPackPrefetch`: UI Pack main.ts đã tải sớm (WEB-052), `UiPackLoader` dùng lại nếu khớp cấu hình. */
export function buildAppConfig(
  cfg: RuntimeConfig,
  uiPackPrefetch?: Promise<UiPackPrefetch | null>,
): ApplicationConfig {
  return {
    providers: [
      provideBrowserGlobalErrorListeners(),
      // WEB-012: lỗi chưa xử lý vào telemetry và dải thông báo; lỗi điều hướng sang trang lỗi thân thiện
      { provide: ErrorHandler, useClass: GlobalErrorHandler },
      // WEB-012: MessageService dùng chung để ProblemPresenter hiện toast qua <p-toast> của shell
      MessageService,
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
        withNavigationErrorHandler((e) => {
          inject(ErrorHandler).handleError(e.error);
          const router = inject(Router);
          return new RedirectCommand(router.parseUrl('/loi'), {
            skipLocationChange: true,
            state: { from: e.url },
          });
        }),
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
      // nạp token của tenant trước khi render để không nháy giao diện; sau đó nạp plugin chuyên ngành
      // có trong UI Pack (WEB-051) để menu và widget của plugin có ngay ở lần vẽ đầu
      provideAppInitializer(() => {
        const plugins = inject(PluginHost);
        const loader = inject(UiPackLoader);
        return loader
          .load()
          .then(() => plugins.init())
          .then(() => {
            // giả lập không biết tenant: bố cục trang chủ gốc lấy theo UI Pack đã nạp, như máy chủ
            if (mockApiActive(cfg)) setMockHomeBase(loader.layout('home'));
          });
      }),
      // Khám phá cell sau đăng nhập (WEB-075); dữ liệu giả lập thì giữ gốc API của config.json
      // Chờ `init()` của phiên có token rồi mới gọi registry, tránh 401 do chạy song song
      provideAppInitializer(() => {
        if (cfg.useMockApi) return;
        const session = inject(SessionService);
        const cells = inject(CellRouter);
        return session.ready.then(() => {
          if (cfg.auth.enabled && !session.isAuthenticated()) return;
          const tenant = session.user()?.tenant ?? cfg.tenant;
          return firstValueFrom(cells.resolve(tenant)).then(() => undefined);
        });
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
        // Chờ phiên sẵn sàng: gọi trước khi có token chỉ nhận 401 (trình duyệt ghi lỗi tải tài nguyên); chưa
        // đăng nhập thì trình duyệt đang chuyển sang Keycloak, cờ nạp ở lần tải trang sau đăng nhập
        return session.ready.then(() =>
          cfg.auth.enabled && !session.isAuthenticated() ? undefined : flags.load(),
        );
      }),
    ],
  };
}
