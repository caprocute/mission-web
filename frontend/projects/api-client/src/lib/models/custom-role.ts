import type { components } from '../generated/config';

/**
 * Vai trò tuỳ chỉnh của tenant và danh mục quyền (QT-01, SCR-0110, BS-04): bí danh của lược đồ trong
 * `contracts/openapi/modules/config.yaml`. Nguồn dữ liệu là bảng bổ sung `config.custom_role`.
 */
type S = components['schemas'];

export type RoleRef = S['RoleRef'];
export type PermissionScope = S['PermissionScope'];
export type PermissionCatalog = S['PermissionCatalog'];
export type PermissionGroup = S['PermissionGroup'];
export type PermissionScreen = S['PermissionScreen'];
export type PermissionFunction = S['PermissionFunction'];
export type CustomRoleStatus = S['CustomRoleStatus'];
export type CustomRolePermission = S['CustomRolePermission'];
export type CustomRole = S['CustomRole'];
export type CustomRoleCreate = S['CustomRoleCreate'];
export type CustomRoleUpdate = S['CustomRoleUpdate'];

/** Thứ tự phạm vi từ hẹp đến rộng; "được giao" đứng riêng (ma trận wiki/15a). */
export const SCOPE_ORDER: readonly PermissionScope[] = ['B', 'Dv', 'Cd', 'Tn', 'N'];

/** Phạm vi `scope` không rộng hơn `limit` (cùng quy tắc với máy chủ, BR-01-23). */
export function scopeWithin(scope: PermissionScope, limit: PermissionScope): boolean {
  if (scope === 'Gn' || limit === 'Gn') return scope === limit;
  return SCOPE_ORDER.indexOf(scope) <= SCOPE_ORDER.indexOf(limit);
}

/** Các phạm vi chọn được khi vai trò gốc có phạm vi `limit`. */
export function scopesUpTo(limit: PermissionScope): PermissionScope[] {
  if (limit === 'Gn') return ['Gn'];
  return SCOPE_ORDER.filter((s) => scopeWithin(s, limit));
}

export const PERMISSION_SCOPE_LABEL: Record<PermissionScope, string> = {
  B: 'Bản thân',
  Dv: 'Đơn vị',
  Cd: 'Đơn vị và cấp dưới',
  Tn: 'Toàn đơn vị triển khai',
  N: 'Toàn hệ thống',
  Gn: 'Việc được giao',
};

export const CUSTOM_ROLE_STATUS_LABEL: Record<CustomRoleStatus, string> = {
  HIEU_LUC: 'Đang sử dụng',
  NGUNG: 'Ngừng sử dụng',
};

/** Mã lỗi nghiệp vụ của vai trò tuỳ chỉnh → văn bản hiển thị (BS-04). */
export const CUSTOM_ROLE_MESSAGES: Record<string, string> = {
  'CONFIG-409-CUSTOM-ROLE-NAME': 'Đã có vai trò cùng tên. Vui lòng đặt tên khác.',
  'CONFIG-409-CUSTOM-ROLE-RETIRED': 'Vai trò này đã ngừng sử dụng nên không sửa được nữa.',
  'CONFIG-422-CUSTOM-ROLE-WIDER':
    'Vai trò tuỳ chỉnh chỉ được thu hẹp quyền của vai trò gốc. Vui lòng kiểm tra lại các quyền đã chọn.',
};
