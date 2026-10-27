import {
  CustomRole,
  CustomRoleCreate,
  CustomRoleUpdate,
  PermissionCatalog,
  PermissionScope,
  scopeWithin,
} from '../../lib/models/custom-role';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { mockSession } from '../mock-session';
import catalogJson from './permission-catalog.json';

/**
 * Giả lập vai trò tuỳ chỉnh và danh mục quyền của MOD-08 (`/api/v1/config/permission-catalog`,
 * `/config/custom-roles`, `/config/me/custom-roles`; SCR-0110, BS-04). Danh mục là bản sao do
 * `docs/phan-quyen/gen_matrix.py --rego` sinh cùng bản của mission-core. Kiểm như máy chủ: chỉ thu hẹp
 * vai trò gốc (`CONFIG-422-CUSTOM-ROLE-WIDER`), tên không trùng, vai trò đã ngừng không sửa được.
 */

const CATALOG = catalogJson as unknown as PermissionCatalog;

/** Phạm vi của vai trò gốc với từng chức năng. */
const GRANTS = (() => {
  const m = new Map<string, Record<string, PermissionScope>>();
  for (const g of CATALOG.groups)
    for (const s of g.screens)
      for (const f of s.functions) {
        const byRole: Record<string, PermissionScope> = {};
        for (const [role, grant] of Object.entries(f.grants)) byRole[role] = grant.scope;
        m.set(f.key, byRole);
      }
  return m;
})();

let roles: CustomRole[] = [];
const nowIso = () => new Date().toISOString();
const etag = (v: number) => ({ ETag: `"${v}"` });

function reset() {
  roles = [
    {
      id: 'cr-xem-don-vi',
      code: 'tc_mock0001_can_bo_theo_doi',
      name: 'Cán bộ theo dõi tiến độ',
      description: 'Chỉ xem mục tiêu và nhiệm vụ của đơn vị, không giao việc.',
      baseRole: 'manager',
      permissions: [
        { key: 'SCR-0201.xem', scope: 'Dv' },
        { key: 'SCR-0401.xem', scope: 'Dv' },
      ],
      status: 'HIEU_LUC',
      holderCount: 2,
      createdAt: '2026-10-20T02:00:00Z',
      updatedAt: '2026-10-20T02:00:00Z',
      rowVersion: 0,
    },
  ];
}
reset();

const stepUpRequired = (): MockResponse => {
  const r = problem(401, 'Cần xác thực nhiều lớp', {
    type: 'https://datatracker.ietf.org/doc/html/rfc9470',
    code: 'insufficient_user_authentication',
  });
  r.headers = {
    ...r.headers,
    'WWW-Authenticate':
      // ui-text-ok: tiêu đề HTTP theo RFC 9470, không hiện lên màn hình
      'Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=900',
  };
  return r;
};

function validate(base: string, perms: { key: string; scope: PermissionScope }[] | undefined) {
  if (!perms?.length)
    return problem(422, 'Vai trò cần ít nhất một quyền', {
      code: 'COMMON-422-VALIDATION',
      errors: [{ field: 'permissions', message: 'Chọn ít nhất một quyền.' }],
    } as never);
  const errors = perms
    .filter((p) => {
      const limit = GRANTS.get(p.key)?.[base];
      return !limit || !scopeWithin(p.scope, limit);
    })
    .map((p) => ({
      field: `permissions.${p.key}`,
      message: 'Phạm vi rộng hơn vai trò gốc.',
      rule: 'BR-01-23',
    }));
  return errors.length
    ? problem(422, 'Chỉ được thu hẹp quyền của vai trò gốc', {
        code: 'CONFIG-422-CUSTOM-ROLE-WIDER',
        errors,
      } as never)
    : null;
}

function find(r: MockRequest): CustomRole | MockResponse {
  return (
    roles.find((x) => x.id === r.pathParams['id']) ??
    problem(404, 'Không tìm thấy vai trò', { code: 'CONFIG-404-NOT-FOUND' })
  );
}
const isRes = (x: unknown): x is MockResponse =>
  !!x && typeof (x as MockResponse).status === 'number';

function checkIfMatch(r: MockRequest, v: number): MockResponse | null {
  const h = r.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu If-Match', { code: 'CONFIG-428-PRECONDITION-REQUIRED' });
  return h.replace(/"/g, '') === String(v)
    ? null
    : problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
        code: 'CONFIG-409-VERSION-CONFLICT',
      });
}

const nameTaken = (name: string, except?: string) =>
  roles.some((x) => x.id !== except && x.name.toLowerCase() === name.trim().toLowerCase());

function create(r: MockRequest): MockResponse {
  if (!mockSession.recentMfa()) return stepUpRequired();
  const b = (r.body ?? {}) as Partial<CustomRoleCreate>;
  if (!b.name?.trim())
    return problem(422, 'Nhập tên vai trò', {
      code: 'COMMON-422-VALIDATION',
      errors: [{ field: 'name', message: 'Nhập tên vai trò.' }],
    } as never);
  if (!b.baseRole || !CATALOG.roles.some((x) => x.code === b.baseRole && x.customizable))
    return problem(422, 'Vai trò gốc không hợp lệ', {
      code: 'COMMON-422-VALIDATION',
      errors: [{ field: 'baseRole', message: 'Chọn vai trò gốc.' }],
    } as never);
  if (nameTaken(b.name))
    return problem(409, 'Trùng tên vai trò', { code: 'CONFIG-409-CUSTOM-ROLE-NAME' });
  const bad = validate(b.baseRole, b.permissions);
  if (bad) return bad;
  const row: CustomRole = {
    id: `cr-${crypto.randomUUID()}`,
    code: `tc_mock0001_${roles.length + 1}`,
    name: b.name.trim(),
    description: b.description ?? null,
    baseRole: b.baseRole,
    permissions: structuredClone(b.permissions!),
    status: 'HIEU_LUC',
    holderCount: 0,
    createdAt: nowIso(),
    updatedAt: nowIso(),
    rowVersion: 0,
  };
  roles.push(row);
  return { status: 201, body: structuredClone(row), headers: etag(0) };
}

function update(r: MockRequest): MockResponse {
  if (!mockSession.recentMfa()) return stepUpRequired();
  const row = find(r);
  if (isRes(row)) return row;
  const pre = checkIfMatch(r, row.rowVersion);
  if (pre) return pre;
  if (row.status === 'NGUNG')
    return problem(409, 'Vai trò đã ngừng', { code: 'CONFIG-409-CUSTOM-ROLE-RETIRED' });
  const b = (r.body ?? {}) as Partial<CustomRoleUpdate>;
  if (!b.name?.trim())
    return problem(422, 'Nhập tên vai trò', {
      code: 'COMMON-422-VALIDATION',
      errors: [{ field: 'name', message: 'Nhập tên vai trò.' }],
    } as never);
  if (nameTaken(b.name, row.id))
    return problem(409, 'Trùng tên vai trò', { code: 'CONFIG-409-CUSTOM-ROLE-NAME' });
  const bad = validate(row.baseRole, b.permissions);
  if (bad) return bad;
  row.name = b.name.trim();
  row.description = b.description ?? null;
  row.permissions = structuredClone(b.permissions!);
  row.updatedAt = nowIso();
  row.rowVersion++;
  return ok(structuredClone(row), etag(row.rowVersion));
}

function retire(r: MockRequest): MockResponse {
  if (!mockSession.recentMfa()) return stepUpRequired();
  const row = find(r);
  if (isRes(row)) return row;
  const pre = checkIfMatch(r, row.rowVersion);
  if (pre) return pre;
  if (row.status === 'NGUNG')
    return problem(409, 'Vai trò đã ngừng', { code: 'CONFIG-409-CUSTOM-ROLE-RETIRED' });
  const reason = ((r.body ?? {}) as { reason?: string }).reason?.trim() ?? '';
  if (reason.length < 5)
    return problem(422, 'Nhập lý do', {
      code: 'COMMON-422-VALIDATION',
      errors: [{ field: 'reason', message: 'Nhập lý do (ít nhất 5 ký tự).' }],
    } as never);
  row.status = 'NGUNG';
  row.holderCount = 0;
  row.updatedAt = nowIso();
  row.rowVersion++;
  return ok(structuredClone(row), etag(row.rowVersion));
}

export const CUSTOM_ROLES_MOCK: MockModule = {
  name: 'custom-roles',
  reset,
  routes: [
    { method: 'GET', pattern: '/api/v1/config/permission-catalog', handle: () => ok(CATALOG) },
    {
      method: 'GET',
      pattern: '/api/v1/config/custom-roles',
      handle: (r) => {
        const status = r.params.get('status');
        return ok({
          items: roles.filter((x) => !status || x.status === status).map((x) => structuredClone(x)),
        });
      },
    },
    { method: 'POST', pattern: '/api/v1/config/custom-roles', handle: create },
    {
      method: 'GET',
      pattern: '/api/v1/config/custom-roles/:id',
      handle: (r) => {
        const row = find(r);
        return isRes(row) ? row : ok(structuredClone(row), etag(row.rowVersion));
      },
    },
    { method: 'PUT', pattern: '/api/v1/config/custom-roles/:id', handle: update },
    { method: 'POST', pattern: '/api/v1/config/custom-roles/:id/retire', handle: retire },
    { method: 'GET', pattern: '/api/v1/config/me/custom-roles', handle: () => ok({ items: [] }) },
  ],
};
