import { RegistryEntry } from '@vt/sdui-renderer';
import {
  AnnouncementSlot,
  MyTasksTodaySlot,
  OkrProgressSlot,
  PendingApprovalsSlot,
  loadAnnouncementWidget,
  loadMyTasksTodayWidget,
} from './widget-slots';

/**
 * Danh mục widget SDUI mà shell cho phép UI Pack tham chiếu (component registry).
 * Widget của miền nghiệp vụ được nạp từ remote tương ứng qua Native Federation.
 * Mỗi mục trả về ô giữ chỗ gói sẵn trong shell (widget-slots.ts), ô này nạp widget thật
 * bên trong để các widget xuất hiện cùng lúc với chiều cao giữ trước (CLS, WEB-059).
 */
export const SHELL_WIDGETS: RegistryEntry[] = [
  {
    name: 'vt.widget.myTasksToday',
    since: '2.0.0',
    load: () => Promise.resolve(MyTasksTodaySlot),
    // tải trước widget thật trong remote ngay khi có bố cục (WEB-052); lỗi do ô giữ chỗ xử lý
    prefetch: () => void loadMyTasksTodayWidget().catch(() => undefined),
    propsSchema: {
      type: 'object',
      additionalProperties: false,
      properties: { limit: { type: 'integer', minimum: 1, maximum: 20 } },
    },
  },
  {
    name: 'vt.widget.okrProgress',
    since: '2.0.0',
    load: () => Promise.resolve(OkrProgressSlot),
    propsSchema: {
      type: 'object',
      additionalProperties: false,
      properties: { scope: { type: 'string', enum: ['me', 'unit'] } },
    },
  },
  {
    name: 'vt.widget.pendingApprovals',
    since: '2.0.0',
    load: () => Promise.resolve(PendingApprovalsSlot),
    propsSchema: { type: 'object', additionalProperties: false, properties: {} },
  },
  {
    name: 'vt.widget.announcement',
    since: '2.1.0',
    load: () => Promise.resolve(AnnouncementSlot),
    prefetch: () => void loadAnnouncementWidget().catch(() => undefined),
    propsSchema: {
      type: 'object',
      additionalProperties: false,
      properties: { i18nKey: { type: 'string', pattern: '^[a-z0-9.]+$' } },
    },
  },
];
