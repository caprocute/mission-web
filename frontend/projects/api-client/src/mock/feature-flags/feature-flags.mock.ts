import { FeatureFlag, flagActive } from '../../lib/feature-flags';
import { MockModule, ok } from '../mock-router';

/**
 * Giả lập cờ tính năng (WEB-076) theo `config.yaml`: danh sách quản trị `GET /config/feature-flags`
 * và bản đồ cờ hiệu lực `GET /ui/feature-flags` mà shell đọc (WEB-072). Tham số trang `?ff=khoa1,khoa2`
 * bật thêm cờ khi chạy giả lập để thử giao diện của tính năng chưa phát hành. Không kiểm vai trò.
 */
const fixture = (): FeatureFlag[] => [
  {
    flagKey: 'ai.weeklySummary',
    description: 'Tóm tắt tuần bằng AI',
    enabled: false,
    rowVersion: 1,
  },
  {
    flagKey: 'okr.alignmentGraph',
    description: 'Đồ thị liên kết OKR',
    enabled: true,
    rowVersion: 1,
  },
];

let store = fixture();

function pageOverrides(): Set<string> {
  const v = new URLSearchParams(globalThis.location?.search ?? '').get('ff') ?? '';
  return new Set(
    v
      .split(',')
      .map((s) => s.trim())
      .filter(Boolean),
  );
}

export const FEATURE_FLAGS_MOCK: MockModule = {
  name: 'feature-flags',
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/config/feature-flags',
      handle: () => {
        const on = pageOverrides();
        return ok({
          items: store.map((f) => (on.has(f.flagKey) ? { ...f, enabled: true } : f)),
          nextCursor: null,
        });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/ui/feature-flags',
      handle: () => {
        const on = pageOverrides();
        const now = Date.now();
        return ok({
          flags: Object.fromEntries(
            store.map((f) => [f.flagKey, on.has(f.flagKey) || flagActive(f, now)]),
          ),
          evaluatedAt: new Date(now).toISOString(),
        });
      },
    },
  ],
  reset: () => {
    store = fixture();
  },
};
