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
    /**
     * Cho phép issuer chạy HTTP ở máy chủ khác `localhost` (chỉ dev nội bộ, như server dev truy cập bằng
     * địa chỉ IP); mặc định thư viện OIDC chỉ nhận HTTP cho `localhost`. Production luôn để trống.
     */
    allowHttp?: boolean;
  };
  /** Bật dữ liệu giả lập cho mọi module khi mission-core chưa sẵn sàng. */
  useMockApi: boolean;
  /**
   * Module API vẫn dùng giả lập khi `useMockApi` tắt (WEB-070): mỗi mục là tiền tố đường dẫn sau
   * `/api/v1/`, ví dụ `dossiers` (cả module) hay `tasks/source-docs` (một nhánh). Dùng khi nối dần
   * từng module với máy chủ thật; `useMockApi` bật thì mọi module đều giả lập như trước.
   */
  mockApiModules?: string[];
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

/**
 * Request tới `path` (`/api/v1/...`) có trả bằng giả lập không: `useMockApi` bật thì mọi module,
 * tắt thì chỉ các tiền tố trong `mockApiModules`. Tiền tố khớp trọn từng đoạn (`tasks` không khớp
 * `tasks-x`), đoạn chỉ gồm dấu sao khớp một đoạn bất kỳ (ví dụ ở wiki/06).
 */
export function isMockedApiPath(
  cfg: Pick<RuntimeConfig, 'useMockApi' | 'mockApiModules'>,
  path: string,
): boolean {
  if (cfg.useMockApi) return true;
  const segs = path.replace(/^\/api\/v1\//, '').split('/');
  return (cfg.mockApiModules ?? []).some((m) => {
    const prefix = m
      .trim()
      .split('/')
      .filter((x) => x);
    return (
      prefix.length > 0 &&
      prefix.length <= segs.length &&
      prefix.every((p, i) => p === '*' || p === segs[i])
    );
  });
}

/** Có module nào đang dùng giả lập không (để báo máy chủ giả lập là phiên vừa MFA, v.v.). */
export function mockApiActive(cfg: Pick<RuntimeConfig, 'useMockApi' | 'mockApiModules'>): boolean {
  return cfg.useMockApi || !!cfg.mockApiModules?.length;
}
