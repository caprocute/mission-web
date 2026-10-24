import { Injectable, InjectionToken, computed, inject, signal } from '@angular/core';
import {
  API_PERMISSION_TABLE,
  ApiModule,
  NON_ENTRY_KEYS,
  PERMISSION_TABLE,
  PermissionKey,
} from './permissions.generated';
import { SessionService, VtRole } from './session.service';

export type { ApiModule, PermissionKey } from './permissions.generated';
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

/** Một vai trò tuỳ chỉnh của tenant: mã `tc_…`, vai trò gốc và các khoá chức năng được mở. */
export interface CustomRoleGrant {
  code: string;
  baseRole: string;
  keys: readonly string[];
}

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

/**
 * Mọi khoá chức năng của các màn hình `screens` (ví dụ `screenKeys('SCR-0101', 'SCR-0801')`), dùng
 * cho mục menu và tuyến cha gom nhiều SCR: cho qua khi có ít nhất một chức năng trong nhóm. Bỏ qua
 * khoá năng lực nền (`NON_ENTRY_KEYS`, ví dụ tra khoá công khai để mã hoá) vì có khoá đó không có
 * nghĩa là dùng được màn hình (YC-PT-21).
 */
export function screenKeys(...screens: string[]): PermissionKey[] {
  return PERMISSION_KEYS.filter(
    (k) => screens.includes(k.slice(0, k.indexOf('.'))) && !NON_ENTRY_KEYS.includes(k),
  );
}

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

/** Vai trò được gọi `method` của module API theo ma trận (wiki/15a, phần API). */
export function apiRolesFor(module: ApiModule, method: string, allowInferred = true): VtRole[] {
  const cell = API_PERMISSION_TABLE[module]?.[method.toUpperCase()];
  if (!cell) return [];
  const rule = parseRule(cell);
  return allowInferred ? [...rule.roles] : rule.roles.filter((r) => !rule.inferred.includes(r));
}

/**
 * Module API của đường dẫn `/api/v1/<module>/...` nếu module đó có trong ma trận; `null` khi đường
 * dẫn không phải API hoặc module chưa có dòng trong ma trận (không đủ thông tin để kết luận).
 */
export function apiModuleOf(url: string): ApiModule | null {
  const m = /\/api\/v1\/([a-z0-9-]+)/.exec(url);
  return m && m[1] in API_PERMISSION_TABLE ? (m[1] as ApiModule) : null;
}

/**
 * Đối tượng nghiệp vụ có thể mang danh sách thao tác mà máy chủ (OPA) cho phép người gọi làm trên
 * chính đối tượng đó (`permittedActions`, dạng khoá chức năng `SCR-qqnn.<khoá>`, YC-PT-21). Đây là
 * nơi giao diện biết các ràng buộc theo đối tượng (người giao việc, người soạn, phạm vi đơn vị, tách
 * vai trò) để ẩn nút trước thay vì để người dùng bấm rồi nhận 403.
 */
export interface WithPermittedActions {
  permittedActions?: readonly string[] | null;
}

/** Máy chủ không chặn `key` trên đối tượng: không có danh sách, hoặc danh sách có `key`. */
export function permittedOn(target: WithPermittedActions | null | undefined, key: string): boolean {
  const list = target?.permittedActions;
  return !Array.isArray(list) || list.includes(key);
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
  private readonly custom = signal<readonly CustomRoleGrant[]>([]);

  /** Tập khoá được phép, tính lại khi người dùng hoặc phân công đang chọn thay đổi. */
  readonly granted = computed<ReadonlySet<PermissionKey>>(() => {
    const roles = this.session.user()?.roles ?? [];
    const set = new Set<PermissionKey>();
    if (roles.length === 0) return set;
    for (const key of PERMISSION_KEYS) {
      if (rolesCan(roles, key, this.options.allowInferred)) set.add(key);
    }
    for (const def of this.custom()) {
      if (!roles.includes(def.code as VtRole)) continue;
      for (const key of def.keys)
        if (PERMISSIONS.has(key as PermissionKey)) set.add(key as PermissionKey);
    }
    return set;
  });

  /**
   * Định nghĩa vai trò tuỳ chỉnh mà người dùng đang giữ (`GET /config/me/custom-roles`, BS-04): mỗi vai
   * trò `tc_…` chỉ mở đúng các chức năng đã chọn, máy chủ đã kiểm không rộng hơn vai trò gốc.
   */
  setCustomRoles(defs: readonly CustomRoleGrant[]): void {
    this.custom.set(defs);
  }

  can(key: PermissionKey): boolean {
    return this.granted().has(key);
  }

  canAny(...keys: PermissionKey[]): boolean {
    const granted = this.granted();
    return keys.some((k) => granted.has(k));
  }

  /**
   * Quyền làm `key` trên một đối tượng cụ thể: vai trò phải có quyền theo ma trận, và nếu máy chủ trả
   * `permittedActions` cho đối tượng thì `key` phải có trong danh sách đó. Đối tượng chưa mang danh
   * sách (máy chủ chưa hỗ trợ) thì chỉ xét theo vai trò.
   */
  allows(key: PermissionKey, target?: WithPermittedActions | null): boolean {
    return this.can(key) && permittedOn(target, key);
  }

  /** Vai trò hiện tại được gọi `method` của module API theo ma trận (ví dụ ô tìm kiếm toàn cục). */
  canApi(module: ApiModule, method = 'GET'): boolean {
    const roles = this.session.user()?.roles ?? [];
    const allowed = apiRolesFor(module, method, this.options.allowInferred);
    // vai trò tuỳ chỉnh gọi API trong giới hạn vai trò gốc; OPA kiểm phạm vi chi tiết
    const bases = this.custom()
      .filter((d) => roles.includes(d.code as VtRole))
      .map((d) => d.baseRole as VtRole);
    return [...roles, ...bases].some((r) => allowed.includes(r));
  }

  canAll(...keys: PermissionKey[]): boolean {
    const granted = this.granted();
    return keys.length > 0 && keys.every((k) => granted.has(k));
  }
}
