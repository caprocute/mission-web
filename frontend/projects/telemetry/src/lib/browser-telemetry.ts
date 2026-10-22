/**
 * Đo lường phía trình duyệt bằng Grafana Faro (WEB-011, ADR-12, TL-01 "Quan sát").
 *
 * SDK Faro và OpenTelemetry web (khoảng 90 KB nén) chỉ nạp lười sau khi trang đã hiện và chỉ khi
 * `config.json` có `telemetry.faroUrl`, nên không tính vào ngân sách tải đầu của shell. Trước khi SDK
 * sẵn sàng, sự kiện được giữ tạm trong hàng đợi có giới hạn.
 */

/** Trường `telemetry` của `config.json` mà WEB-011 dùng (bổ sung cho `RuntimeConfig.telemetry`). */
export interface BrowserTelemetryConfig {
  /** Địa chỉ nhận Faro, nên cùng nguồn (ví dụ `/faro/collect`) để không phải mở CSP. */
  faroUrl?: string;
  /** Tỷ lệ phiên được lấy mẫu, 0..1; mặc định 0,1 theo TL-01. */
  sampleRatio?: number;
}

export interface BrowserTelemetryOptions extends BrowserTelemetryConfig {
  /** Mã cell, dùng làm `environment` của ứng dụng. */
  environment: string;
  tenant?: string;
  version?: string;
}

/** Phần API của Faro mà shell dùng, tách ra để kiểm thử không cần SDK thật. */
export interface TelemetryApi {
  pushEvent(name: string, attributes?: Record<string, string>): void;
}

const MAX_PENDING = 50;
let api: TelemetryApi | null = null;
let tracing = false;
let pending: [string, Record<string, string>][] = [];

/** Có đang dùng instrumentation fetch của OpenTelemetry để gắn `traceparent` không. */
export function browserTracingActive(): boolean {
  return tracing;
}

/** Gửi một sự kiện nghiệp vụ hoặc an toàn (chỉ tên và mã, không bao giờ chứa nội dung người dùng nhập). */
export function pushTelemetryEvent(
  name: string,
  attributes: Record<string, string | number> = {},
): void {
  const attrs = Object.fromEntries(Object.entries(attributes).map(([k, v]) => [k, String(v)]));
  if (api) {
    api.pushEvent(name, attrs);
  } else if (pending.length < MAX_PENDING) {
    pending.push([name, attrs]);
  }
}

/** Gắn API đã khởi tạo, xả hàng đợi. Dùng nội bộ và trong kiểm thử. */
export function attachTelemetryApi(next: TelemetryApi | null, tracingActive = false): void {
  api = next;
  tracing = !!next && tracingActive;
  if (!next) return;
  const queued = pending;
  pending = [];
  for (const [name, attrs] of queued) next.pushEvent(name, attrs);
}

/** Tỷ lệ lấy mẫu hợp lệ trong 0..1, mặc định 0,1. */
export function sampleRatioOf(cfg?: BrowserTelemetryConfig): number {
  const r = cfg?.sampleRatio;
  return typeof r === 'number' && Number.isFinite(r) ? Math.min(1, Math.max(0, r)) : 0.1;
}

/**
 * Khởi động Faro khi có `faroUrl`; gọi một lần sau bootstrap. Không có `faroUrl` (dev, dữ liệu giả
 * lập) thì không tải gì và `traceparentInterceptor` tiếp tục tự sinh `traceparent`.
 */
export function startBrowserTelemetry(opts: BrowserTelemetryOptions): Promise<void> {
  if (!opts.faroUrl || api) return Promise.resolve();
  return new Promise<void>((resolve) => {
    const run = () =>
      import('./faro-init')
        .then((m) => attachTelemetryApi(m.initFaro(opts), true))
        .catch((err) => console.warn('[telemetry] Không khởi động được Faro', err))
        .finally(resolve);
    const idle = (globalThis as { requestIdleCallback?: (cb: () => void, o?: object) => void })
      .requestIdleCallback;
    if (idle) idle(run, { timeout: 3000 });
    else setTimeout(run, 0);
  });
}
