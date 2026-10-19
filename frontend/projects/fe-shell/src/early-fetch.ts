import type { UiPackPrefetch } from '@vt/sdui-renderer';

/** Các yêu cầu khởi động main.ts gửi trước khi nạp thư viện dùng chung (WEB-052). */
export interface EarlyFetches {
  /** Phản hồi `config.json`, chuyển cho `loadRuntimeConfig`. */
  config: Promise<Response>;
  /** `current.json` và `pack.json` của tenant trong `config.json`; lỗi thì `null`. */
  uiPack: Promise<UiPackPrefetch | null>;
}

/**
 * Gửi sớm các yêu cầu mà bootstrap chắc chắn cần, song song với `initFederation` và việc nạp
 * thư viện dùng chung, thay vì chờ tới khi Angular khởi động mới bắt đầu (mỗi lượt mạng trên 4G
 * tốn khoảng 150 ms). Tệp này chạy trước khi có import map nên chỉ được dùng API của trình duyệt,
 * không import giá trị từ thư viện dùng chung (chỉ import kiểu). Giá trị mặc định của tenant và
 * gốc UI Pack trùng `DEFAULT_RUNTIME_CONFIG`; nếu khác cấu hình thật, `UiPackLoader` bỏ qua bản
 * tải sớm và tự tải lại.
 */
export function startEarlyFetches(): EarlyFetches {
  const config = fetch('/config.json', { cache: 'no-cache' });
  // lỗi mạng do loadRuntimeConfig xử lý (dùng cấu hình mặc định); bắt ở đây để không báo
  // "unhandled rejection" trước khi bootstrap kịp chờ promise này
  config.catch(() => undefined);

  const uiPack = config
    .then((res) => (res.ok ? res.clone().json() : {}))
    .then(async (cfg: { tenant?: string; uiPackBaseUrl?: string }) => {
      const base = `${cfg.uiPackBaseUrl ?? '/ui-packs'}/${cfg.tenant ?? 'default'}`;
      const ptrRes = await fetch(`${base}/current.json`, { cache: 'no-cache' });
      if (!ptrRes.ok) return null;
      const pointer = (await ptrRes.json()) as UiPackPrefetch['pointer'];
      const packRes = await fetch(`${base}/${pointer.version}/pack.json`);
      const pack = packRes.ok ? ((await packRes.json()) as UiPackPrefetch['pack']) : null;
      return { base, pointer, pack };
    })
    .catch(() => null);

  return { config, uiPack };
}
