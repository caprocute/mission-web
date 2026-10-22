import { AppNotification } from '../../lib/models/notification';
import {
  MockModule,
  MockRequest,
  MockResponse,
  ok,
  problem,
  resolveRelativeDates,
} from '../mock-router';

/**
 * Giả lập hộp thông báo SVC-12 (WEB-012, `platform.yaml`): danh sách, đánh dấu đã đọc và luồng SSE.
 * Luồng giả lập trả một lượt ngắn (sự kiện `unread` hiện hành) với `retry` dài, vì ô giả lập trả
 * cả thân một lần; trình duyệt nối lại theo `retry` như với máy chủ thật.
 */
const SEED: AppNotification[] = [
  {
    id: '0192f6a8-0001-7000-8000-000000000001',
    templateCode: 'NV_DUOC_GIAO',
    title: 'Đồng chí được giao nhiệm vụ mới',
    body: 'Xây dựng báo cáo tổng kết công tác chuyển đổi số quý IV, hạn trong 2 ngày tới.',
    // ui-text-ok: đường dẫn điều hướng tới nhiệm vụ trong fixture, không hiện lên màn hình
    deepLink: '/tasks/0192a0f0-0001-7000-8000-000000000001',
    priority: 'CAO',
    createdAt: '@today-0T08:05:00+07:00',
    readAt: null,
  },
  {
    id: '0192f6a8-0002-7000-8000-000000000002',
    templateCode: 'VB_DEN_MOI',
    title: 'Có văn bản chỉ đạo mới cần xử lý',
    body: 'Văn bản 512-CV/ĐUK về việc triển khai kiểm điểm, đánh giá, xếp loại năm 2026.',
    deepLink: '/tasks/documents/sd-512',
    priority: 'THUONG',
    createdAt: '@today-1T15:03:00+07:00',
    readAt: null,
  },
  {
    id: '0192f6a8-0003-7000-8000-000000000003',
    templateCode: 'KR_CAN_CHECKIN',
    title: 'Đến hạn cập nhật tiến độ kết quả then chốt',
    body: 'Có 2 kết quả then chốt của quý IV cần cập nhật tiến độ trong tuần này.',
    deepLink: '/okr',
    priority: 'THUONG',
    createdAt: '@today-2T09:00:00+07:00',
    readAt: null,
  },
  {
    id: '0192f6a8-0004-7000-8000-000000000004',
    templateCode: 'MC_DA_NGHIEM_THU',
    title: 'Minh chứng đã được nghiệm thu',
    body: 'Minh chứng cho nhiệm vụ rà soát quy chế làm việc đã được nghiệm thu.',
    deepLink: '/evidence',
    priority: 'THAP',
    createdAt: '@today-4T10:20:00+07:00',
    readAt: '@today-4T11:00:00+07:00',
  },
];

let items: AppNotification[] = [];
let seq = 0;

function reset(): void {
  items = resolveRelativeDates(structuredClone(SEED));
  seq = 0;
}
reset();

const unreadCount = () => items.filter((n) => !n.readAt).length;

function list(req: MockRequest): MockResponse {
  const unread = req.params.get('unread') === 'true';
  const limit = Math.min(200, Math.max(1, Number(req.params.get('limit') ?? 20) || 20));
  const sorted = [...items]
    .filter((n) => !unread || !n.readAt)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  return ok({ items: sorted.slice(0, limit), nextCursor: null, unreadCount: unreadCount() });
}

function markRead(req: MockRequest): MockResponse {
  const body = (req.body ?? {}) as { ids?: string[]; all?: boolean };
  if ((body.all === true) === Array.isArray(body.ids)) {
    return problem(422, 'Cần chọn thông báo hoặc chọn đánh dấu tất cả', {
      code: 'NOTIFY-422-BODY',
    });
  }
  const now = new Date().toISOString();
  let updated = 0;
  for (const n of items) {
    if (!n.readAt && (body.all || body.ids?.includes(n.id))) {
      n.readAt = now;
      updated++;
    }
  }
  return ok({ updated, unreadCount: unreadCount() });
}

function stream(): MockResponse {
  seq++;
  const id = `${Date.now()}-${seq}`;
  const text = `retry: 60000\n\n: ping\n\nid: ${id}\nevent: unread\ndata: ${JSON.stringify({ unreadCount: unreadCount() })}\n\n`;
  return { status: 200, body: text, headers: { 'Content-Type': 'text/event-stream' } };
}

export const NOTIFICATIONS_MOCK: MockModule = {
  name: 'notifications',
  reset,
  routes: [
    { method: 'GET', pattern: '/api/v1/notifications', handle: list },
    { method: 'POST', pattern: '/api/v1/notifications/read', handle: markRead },
    { method: 'GET', pattern: '/api/v1/notifications/stream', handle: stream },
  ],
};
