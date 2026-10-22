import type { components } from '../generated/platform';

type S = components['schemas'];

/** Thông báo trong ứng dụng của người dùng (`notify.notification`, SVC-12). */
export type AppNotification = S['Notification'];
export type NotificationPage = S['NotificationPage'];
export type NotificationReadRequest = S['NotificationReadRequest'];
export type NotificationReadResult = S['NotificationReadResult'];
/** Dữ liệu sự kiện SSE `unread`. */
export type NotificationUnreadEvent = S['NotificationUnreadEvent'];

/** Một kết quả tìm kiếm toàn văn (SVC-16), chỉ trường C0, C1. */
export type SearchResult = S['SearchResult'];
export type SearchResultType = S['SearchResultType'];
export type SearchResultPage = S['SearchResultPage'];
