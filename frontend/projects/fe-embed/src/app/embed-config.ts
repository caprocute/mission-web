import type { RuntimeConfig } from '@vt/api-client';

/** Khoá remote (ví dụ `feTask`) → URL `remoteEntry.json`, như `federation.manifest.json` của shell. */
export type RemoteManifest = Record<string, string>;

/**
 * Gốc phục vụ ứng dụng web (nơi có `config.json`, `federation.manifest.json`, `/remotes/...`), suy
 * từ địa chỉ của chính gói nhúng: gói nằm ở `<gốc>/embed/`, nên gốc là thư mục cha.
 */
export function appRootOf(embedBase: string): string {
  return new URL('../', embedBase).href;
}

/**
 * Đường dẫn tương đối trong `config.json` (ví dụ `/api`) được viết cho trang của chính ứng dụng.
 * Khi nhúng, trang chứa là cổng của khách hàng, nên mọi địa chỉ phải đổi sang tuyệt đối theo gốc
 * ứng dụng, nếu không trình duyệt sẽ gọi nhầm sang máy chủ của cổng.
 */
export function absolutize(url: string, root: string): string {
  if (!url) return url;
  return new URL(url, root).href.replace(/\/$/, '');
}

/**
 * Địa chỉ Keycloak trả về sau đăng nhập, đăng xuất: trang của cổng đang nhúng, bỏ truy vấn và
 * phần sau dấu `#` (Keycloak chỉ nhận địa chỉ đã đăng ký trong danh sách chuyển hướng hợp lệ).
 */
export function pageRedirectUri(href: string): string {
  const u = new URL(href);
  return u.origin + u.pathname;
}

/** Mã tenant hợp lệ lấy từ thuộc tính `tenant` của phần tử; giá trị lạ thì bỏ qua. */
export function tenantAttr(value: string | null | undefined): string | undefined {
  const v = value?.trim();
  return v && /^[A-Za-z0-9._-]{1,64}$/.test(v) ? v : undefined;
}

/** Cấu hình chạy của chế độ nhúng: địa chỉ tuyệt đối, tenant theo thuộc tính, trả về trang của cổng. */
export function embedRuntimeConfig(
  cfg: RuntimeConfig,
  opts: { root: string; pageUrl: string; tenant?: string },
): RuntimeConfig {
  return {
    ...cfg,
    tenant: opts.tenant ?? cfg.tenant,
    apiBaseUrl: absolutize(cfg.apiBaseUrl, opts.root),
    uiPackBaseUrl: absolutize(cfg.uiPackBaseUrl, opts.root),
    auth: { ...cfg.auth, redirectUri: pageRedirectUri(opts.pageUrl) },
  };
}

/** URL `remoteEntry.json` của từng remote đổi sang tuyệt đối theo gốc ứng dụng. */
export function embedManifest(manifest: RemoteManifest, root: string): RemoteManifest {
  return Object.fromEntries(Object.entries(manifest).map(([k, v]) => [k, new URL(v, root).href]));
}

/** Tuyến nội bộ ứng dụng từ thuộc tính `route` (ví dụ `tasks`, `/tasks/123`); chỉ nhận đường dẫn nội bộ. */
export function normalizeRoute(route: string | null | undefined): string {
  const r = (route ?? '').trim().replace(/^\/+/, '');
  if (!r || /[\s\\]/.test(r) || /^[a-z][a-z0-9+.-]*:/i.test(r)) return '/tasks';
  return '/' + r;
}
