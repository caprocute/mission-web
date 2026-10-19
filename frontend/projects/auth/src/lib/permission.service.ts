import { Injectable, InjectionToken, computed, inject } from '@angular/core';
import { PERMISSION_TABLE, PermissionKey } from './permissions.generated';
import { SessionService, VtRole } from './session.service';

export type { PermissionKey } from './permissions.generated';
export { PERMISSION_MATRIX_VERSION } from './permissions.generated';

export interface VtPermissionOptions {
  /**
   * Có tính ô suy luận (`*` trong ma trận, wiki/15a) là cho phép hay không. Mặc định `true`;
   * đặt `false` khi kiểm thử chặt để chỉ còn những ô có nguồn TL ghi trực tiếp.
   */
  allowInferred: boolean;
}

export const VT_PERMISSION_OPTIONS = new InjectionToken<VtPermissionOptions>(
  'vt.permissionOptions',
  { providedIn: 'root', factory: () => ({ allowInferred: true }) },
);

export interface PermissionRule {
  /** Vai trò có ô khác `-` (đã bỏ AC-11, AC-12). */
  roles: readonly VtRole[];
  /** Tập con của `roles` mà ô mang dấu suy luận `*`. */
  inferred: readonly VtRole[];
}

function parseRule(cell: string): PermissionRule {
  const roles: VtRole[] = [];
  const inferred: VtRole[] = [];
  for (const token of cell.split(' ').filter(Boolean)) {
    const role = token.replace(/\*$/, '') as VtRole;
    roles.push(role);
    if (token.endsWith('*')) inferred.push(role);
  }
  return { roles, inferred };
}

/** Bảng quyền đã phân tích từ `permissions.generated.ts`, dựng một lần khi nạp module. */
export const PERMISSIONS: ReadonlyMap<PermissionKey, PermissionRule> = new Map(
  (Object.entries(PERMISSION_TABLE) as [PermissionKey, string][]).map(([k, v]) => [
    k,
    parseRule(v),
  ]),
);

/** Danh sách mọi khoá chức năng web của ma trận. */
export const PERMISSION_KEYS: readonly PermissionKey[] = [...PERMISSIONS.keys()];

/** Vai trò có thể có quyền với chức năng `key`. */
export function rolesFor(key: PermissionKey, allowInferred = true): VtRole[] {
  const rule = PERMISSIONS.get(key);
  if (!rule) return [];
  return allowInferred ? [...rule.roles] : rule.roles.filter((r) => !rule.inferred.includes(r));
}

/** Hàm thuần dùng chung cho service, guard và kiểm thử: một trong các vai trò có quyền với `key`. */
export function rolesCan(
  roles: readonly VtRole[],
  key: PermissionKey,
  allowInferred = true,
): boolean {
  const allowed = rolesFor(key, allowInferred);
  return roles.some((r) => allowed.includes(r));
}

/**
 * Trả lời câu hỏi "vai trò của phân công đang chọn CÓ THỂ có quyền với chức năng này không",
 * theo ma trận ở wiki/15a (sinh từ `docs/phan-quyen/ma-tran.json`).
 *
 * Đây chỉ là phản hồi nhanh để không bày ra thao tác chắc chắn bị từ chối (AGENTS.md quy tắc 6).
 * Ràng buộc theo đối tượng (người khởi tạo, người được giao, trạng thái, phạm vi đơn vị,
 * tách vai trò, step-up) vẫn do máy chủ (OPA, SEC-18) quyết định; giao diện phải xử lý 403
 * theo MSG và 401 step-up kể cả khi `can()` trả `true`.
 */
@Injectable({ providedIn: 'root' })
export class PermissionService {
  private readonly session = inject(SessionService);
  private readonly options = inject(VT_PERMISSION_OPTIONS);

  /** Tập khoá được phép, tính lại khi người dùng hoặc phân công đang chọn thay đổi. */
  readonly granted = computed<ReadonlySet<PermissionKey>>(() => {
    const roles = this.session.user()?.roles ?? [];
    const set = new Set<PermissionKey>();
    if (roles.length === 0) return set;
    for (const key of PERMISSION_KEYS) {
      if (rolesCan(roles, key, this.options.allowInferred)) set.add(key);
    }
    return set;
  });

  can(key: PermissionKey): boolean {
    return this.granted().has(key);
  }

  canAny(...keys: PermissionKey[]): boolean {
    const granted = this.granted();
    return keys.some((k) => granted.has(k));
  }

  canAll(...keys: PermissionKey[]): boolean {
    const granted = this.granted();
    return keys.length > 0 && keys.every((k) => granted.has(k));
  }
}
