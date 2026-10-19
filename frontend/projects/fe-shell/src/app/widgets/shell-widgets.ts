import { loadRemoteModule } from '@angular-architects/native-federation';
import { RegistryEntry } from '@vt/sdui-renderer';

/**
 * Danh mục widget SDUI mà shell cho phép UI Pack tham chiếu (component registry).
 * Widget của miền nghiệp vụ được nạp từ remote tương ứng qua Native Federation.
 */
export const SHELL_WIDGETS: RegistryEntry[] = [
  {
    name: 'vt.widget.myTasksToday', since: '2.0.0',
    load: () => loadRemoteModule('feTask', './widgets').then((m) => m.MyTasksTodayWidget),
    propsSchema: { type: 'object', additionalProperties: false, properties: { limit: { type: 'integer', minimum: 1, maximum: 20 } } },
  },
  {
    name: 'vt.widget.okrProgress', since: '2.0.0',
    load: () => import('./okr-progress.widget').then((m) => m.OkrProgressWidget),
    propsSchema: { type: 'object', additionalProperties: false, properties: { scope: { type: 'string', enum: ['me', 'unit'] } } },
  },
  {
    name: 'vt.widget.pendingApprovals', since: '2.0.0',
    load: () => import('./pending-approvals.widget').then((m) => m.PendingApprovalsWidget),
    propsSchema: { type: 'object', additionalProperties: false, properties: {} },
  },
  {
    name: 'vt.widget.announcement', since: '2.1.0',
    load: () => import('./announcement.widget').then((m) => m.AnnouncementWidget),
    propsSchema: { type: 'object', additionalProperties: false, properties: { i18nKey: { type: 'string', pattern: '^[a-z0-9.]+$' } } },
  },
];
