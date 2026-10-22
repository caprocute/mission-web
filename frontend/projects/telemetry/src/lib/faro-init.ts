import {
  CSPInstrumentation,
  ErrorsInstrumentation,
  NavigationInstrumentation,
  PerformanceInstrumentation,
  SessionInstrumentation,
  TransportItem,
  ViewInstrumentation,
  WebVitalsInstrumentation,
  initializeFaro,
} from '@grafana/faro-web-sdk';
import { TracingInstrumentation } from '@grafana/faro-web-tracing';
import { BrowserTelemetryOptions, TelemetryApi, sampleRatioOf } from './browser-telemetry';

/** Bỏ chuỗi truy vấn và phần sau `#`: có thể chứa từ khoá người dùng gõ vào ô tìm kiếm. */
export function scrubUrl(url: string): string {
  return url.replace(/[?#].*$/, '');
}

const URL_WITH_QUERY = /^(https?:\/\/|\/)[^\s]*[?#]/;

/**
 * Bản sao của giá trị với truy vấn bị bỏ ở mọi chuỗi có dạng địa chỉ (giới hạn độ sâu). Không sửa
 * tại chỗ vì Faro đóng băng một số mảng của bản tin.
 */
function scrubDeep(value: unknown, depth: number): unknown {
  if (typeof value === 'string') return URL_WITH_QUERY.test(value) ? scrubUrl(value) : value;
  if (depth > 12 || value === null || typeof value !== 'object') return value;
  if (Array.isArray(value)) return value.map((v) => scrubDeep(v, depth + 1));
  const out: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(value)) out[k] = scrubDeep(v, depth + 1);
  return out;
}

/**
 * Làm sạch mọi bản tin trước khi gửi: bỏ truy vấn khỏi mọi địa chỉ (trang, điều hướng, span fetch),
 * vì truy vấn có thể chứa từ khoá người dùng gõ. Không thu console, thao tác nhấp chuột hay nội dung
 * trường nhập (không bật UserAction, Frustration, Console instrumentation).
 */
export function scrubItem<T extends TransportItem>(item: T): T {
  return { ...item, meta: scrubDeep(item.meta, 0), payload: scrubDeep(item.payload, 0) } as T;
}

/**
 * Khởi tạo Faro với các instrumentation đã chọn và OpenTelemetry fetch. `traceparent` chỉ gắn cho
 * request API (`/api/`), cùng phạm vi với `traceparentInterceptor`; phiên không được lấy mẫu thì không
 * gắn để APISIX tự quyết định lấy mẫu (hai tầng lấy mẫu của TL-01).
 */
export function initFaro(opts: BrowserTelemetryOptions): TelemetryApi {
  const faro = initializeFaro({
    url: opts.faroUrl,
    app: { name: 'mission-web', version: opts.version ?? 'web', environment: opts.environment },
    sessionTracking: {
      enabled: true,
      persistent: false,
      samplingRate: sampleRatioOf(opts),
      session: opts.tenant ? { attributes: { tenant: opts.tenant } } : undefined,
    },
    trackResources: false,
    beforeSend: scrubItem,
    instrumentations: [
      new PerformanceInstrumentation(),
      new ErrorsInstrumentation(),
      new WebVitalsInstrumentation(),
      new SessionInstrumentation(),
      new ViewInstrumentation(),
      new NavigationInstrumentation(),
      new CSPInstrumentation(),
      new TracingInstrumentation({
        omitTraceContextForUnsampledSessions: true,
        instrumentationOptions: { propagateTraceHeaderCorsUrls: [/\/api\//] },
      }),
    ],
  });
  return { pushEvent: (name, attributes) => faro.api.pushEvent(name, attributes) };
}
