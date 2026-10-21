import { inject } from '@angular/core';
import { PermissionKey, PermissionService } from '@vt/auth';

/** Các thẻ của một phiên bản Policy Pack và quyền mở từng thẻ theo ma trận wiki/15a. */
export const POLICY_TABS: { path: string; label: string; keys: PermissionKey[] }[] = [
  { path: 'cong-thuc', label: 'Công thức', keys: ['SCR-1501.soan'] },
  { path: 'bang-quyet-dinh', label: 'Bảng quyết định', keys: ['SCR-1502.soan'] },
  { path: 'luong-duyet', label: 'Luồng duyệt', keys: ['SCR-1504.cau-hinh'] },
  { path: 'test-case', label: 'Test case', keys: ['SCR-1503.chay', 'SCR-1503.xem-duyet'] },
];

/** Thẻ mặc định khi mở phiên bản: thẻ đầu tiên người dùng được xem. */
export function firstPolicyTab(): string {
  const perms = inject(PermissionService);
  return POLICY_TABS.find((t) => perms.canAny(...t.keys))?.path ?? 'cong-thuc';
}
