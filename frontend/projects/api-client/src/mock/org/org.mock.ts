import { Assignment, MyOrgProfile, OrgPerson, OrgUnit } from '../../lib/models/org';
import { MockModule, MockRequest, ok, problem, resolveRelativeDates } from '../mock-router';
import fixture from './org.json';

/** Cán bộ của đơn vị kèm khối lượng việc (chỉ máy chủ giả lập dùng cho BR-05-11). */
export type StoredPerson = OrgPerson & {
  workload: { leadCount: number; coCount: number; weeks: number[] };
};

export const ORG_PERSONS = fixture.persons as StoredPerson[];
export const ORG_UNITS = fixture.units as (OrgUnit & { scope: string })[];
/** Người dùng hiện tại trong dữ liệu giả lập (người giao trên SCR-0501). */
export const ORG_ME = fixture.me;
export const WEEK_LABELS = fixture.weekLabels;
export const WEEKLY_THRESHOLD = fixture.weeklyThreshold;

const toPerson = ({ id, name, title, unitId, unitName }: StoredPerson): OrgPerson => ({
  id,
  name,
  title,
  unitId,
  unitName,
});

/**
 * Giả lập `/api/v1/org/me` và `PUT /api/v1/org/me/context` (SVC-04) cho QT-00 (WEB-006).
 * Biến thể chọn bằng `?mock-org=` trên URL trang: mặc định một phân công, `kiem-nhiem` ba phân
 * công, `khong-phan-cong` không có phân công nào (MSG-00-06). Nếu request mang access token,
 * phân công chính lấy vai trò và đơn vị từ token để khớp tài khoản thử của Keycloak dev.
 */
let lastAssignmentId: string | null = null;

function variant(): string {
  return new URLSearchParams(globalThis.location?.search ?? '').get('mock-org') ?? '';
}

function tokenClaims(req: MockRequest): Record<string, unknown> | null {
  const auth = req.headers.get('Authorization');
  const jwt = auth?.startsWith('Bearer ') ? auth.slice(7) : null;
  const payload = jwt?.split('.')[1];
  if (!payload) return null;
  try {
    const bin = atob(payload.replace(/-/g, '+').replace(/_/g, '/'));
    const json = new TextDecoder().decode(Uint8Array.from(bin, (c) => c.charCodeAt(0)));
    return JSON.parse(json) as Record<string, unknown>;
  } catch {
    return null;
  }
}

function assignments(req: MockRequest): Assignment[] {
  const all = resolveRelativeDates(structuredClone(fixture.assignments)) as Assignment[];
  const v = variant();
  if (v === 'khong-phan-cong') return [];
  const claims = tokenClaims(req);
  const primary = { ...all[0] };
  if (claims) {
    const roles = (claims['realm_access'] as { roles?: string[] } | undefined)?.roles ?? [];
    primary.roles = roles.filter((r) => !r.startsWith('default-roles') && !r.includes('_access'));
    if (typeof claims['unit_id'] === 'string') primary.orgUnitId = claims['unit_id'];
  }
  return v === 'kiem-nhiem' ? [primary, ...all.slice(1)] : [primary];
}

function me(req: MockRequest) {
  const claims = tokenClaims(req);
  const list = assignments(req);
  const body: MyOrgProfile = {
    ...fixture.profile,
    fullName: String(claims?.['name'] ?? fixture.profile.fullName),
    assignments: list,
    lastAssignmentId: list.some((a) => a.id === lastAssignmentId)
      ? lastAssignmentId
      : fixture.profile.lastAssignmentId,
  };
  return ok(body);
}

function setContext(req: MockRequest) {
  const id = (req.body as { assignmentId?: string } | null)?.assignmentId;
  const a = assignments(req).find((x) => x.id === id);
  if (!a) {
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'assignmentId', message: 'Phân công không còn hiệu lực' }],
    });
  }
  lastAssignmentId = a.id;
  return ok({ assignmentId: a.id, orgUnitId: a.orgUnitId, roles: a.roles });
}

/**
 * Giả lập `/api/v1/org`: cán bộ và đơn vị trong phạm vi đơn vị người dùng (WEB-028); hồ sơ và
 * phân công của người dùng (WEB-006).
 */
export const ORG_MOCK: MockModule = {
  name: 'org',
  reset: () => (lastAssignmentId = null),
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/org/persons',
      handle: (r) => {
        const q = r.params.get('q')?.toLowerCase();
        const items = ORG_PERSONS.filter((p) => !q || p.name.toLowerCase().includes(q)).map(
          toPerson,
        );
        return ok({ items, nextCursor: null });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/org/units',
      handle: (r) => {
        const scope = r.params.get('scope') ?? 'unit';
        const items = ORG_UNITS.filter((u) => u.scope === scope).map(({ id, name, parentId }) => ({
          id,
          name,
          parentId,
        }));
        return ok({ items, nextCursor: null });
      },
    },
    { method: 'GET', pattern: '/api/v1/org/me', handle: me },
    { method: 'PUT', pattern: '/api/v1/org/me/context', handle: setContext },
  ],
};
