import { InjectionToken } from '@angular/core';

/**
 * Cấu hình môi trường nạp lúc chạy từ `/config.json` (TL-01 SVC-17, đáp ứng K6, C9).
 * nginx sinh tệp này từ biến môi trường, nên một bản build dùng được cho mọi cell.
 */
export interface RuntimeConfig {
  /** Mã cell phục vụ tenant, ví dụ CELL-G4 hoặc DEV. */
  cell: string;
  /** Mã tenant mặc định khi chưa phân giải được từ tên miền. */
  tenant: string;
  /** Gốc API của cell, ví dụ https://api.g4.okr.viettel.vn hoặc /api. */
  apiBaseUrl: string;
  /** Gốc phục vụ UI Pack (CDN), ví dụ /ui-packs. */
  uiPackBaseUrl: string;
  auth: {
    enabled: boolean;
    issuer: string;
    clientId: string;
    scope: string;
    /**
     * Địa chỉ Keycloak trả về sau đăng nhập và đăng xuất; mặc định gốc của trang. Chế độ nhúng
     * `<vt-okr-app>` (WEB-013) đặt bằng địa chỉ trang của cổng đang nhúng.
     */
    redirectUri?: string;
  };
  /** Bật dữ liệu giả lập khi mission-core chưa sẵn sàng. */
  useMockApi: boolean;
  telemetry?: { otlpEndpoint?: string; sampleRatio?: number };
}

export const RUNTIME_CONFIG = new InjectionToken<RuntimeConfig>('vt.runtimeConfig');

export const DEFAULT_RUNTIME_CONFIG: RuntimeConfig = {
  cell: 'DEV',
  tenant: 'default',
  apiBaseUrl: '/api',
  uiPackBaseUrl: '/ui-packs',
  auth: { enabled: false, issuer: '', clientId: 'mission-web', scope: 'openid profile email' },
  useMockApi: true,
};

/**
 * Đọc `/config.json` trước khi bootstrap. Gọi trong main.ts để cấu hình có mặt
 * trước khi bất kỳ provider nào cần đến nó.
 *
 * `source` có thể là phản hồi đã được gửi từ trước (WEB-052): shell bắt đầu tải `config.json`
 * ngay trong main.ts, song song với `initFederation`, thay vì chờ tới khi nạp xong bootstrap.
 */
export async function loadRuntimeConfig(
  source: string | Promise<Response> = '/config.json',
): Promise<RuntimeConfig> {
  try {
    const res = await (typeof source === 'string' ? fetch(source, { cache: 'no-cache' }) : source);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const cfg = (await res.json()) as Partial<RuntimeConfig>;
    return {
      ...DEFAULT_RUNTIME_CONFIG,
      ...cfg,
      auth: { ...DEFAULT_RUNTIME_CONFIG.auth, ...cfg.auth },
    };
  } catch (err) {
    console.warn('[vt] Không đọc được config.json, dùng cấu hình mặc định', err);
    return DEFAULT_RUNTIME_CONFIG;
  }
}
