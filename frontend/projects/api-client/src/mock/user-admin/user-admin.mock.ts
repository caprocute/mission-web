import {
  JobTitle,
  OrgAssignmentCreateInput,
  PersonAssignment,
  UserAccount,
  UserAccountCreate,
  UserAccountUpdate,
} from '../../lib/models/user-account';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { MOCK_ME, mockSession } from '../mock-session';
import { ORG_PERSONS } from '../org/org.mock';
import { seedOrgTree } from '../org-tree/org-tree.fixture';

/**
 * Giả lập quản trị tài khoản người dùng của org-service (`/api/v1/org/users`, `/org/job-titles`,
 * `/org/persons/:id/assignments`, `/org/assignments`; SCR-0108, SCR-0109, BS-04). Người gọi là
 * `MOCK_ME`. Tạo, khoá, mở khoá, đặt lại mật khẩu cần MFA gần đây; không tự khoá chính mình
 * (`ORG-422-SELF-LOCK`); tên đăng nhập trùng trả `ORG-409-USERNAME-TAKEN`; ghi có `If-Match`.
 */

type Stored = Omit<UserAccount, 'permittedActions'>;
type StoredAssignment = PersonAssignment & { personId: string; rowVersion: number };

const UNITS = (() => {
  const m = new Map<string, { name: string; parentId: string | null }>();
  for (const u of seedOrgTree().units)
    m.set(u.id, { name: u.shortName ?? u.name, parentId: u.parentId });
  return m;
})();

const JOB_TITLES: JobTitle[] = [
  { id: 'jt-truong-ban', code: 'TRUONG_BAN', name: 'Trưởng ban', level: 1 },
  { id: 'jt-pho-ban', code: 'PHO_TRUONG_BAN', name: 'Phó Trưởng ban', level: 2 },
  { id: 'jt-truong-phong', code: 'TRUONG_PHONG', name: 'Trưởng phòng', level: 3 },
  { id: 'jt-pho-phong', code: 'PHO_TRUONG_PHONG', name: 'Phó Trưởng phòng', level: 4 },
  { id: 'jt-chuyen-vien', code: 'CHUYEN_VIEN', name: 'Chuyên viên', level: 5 },
];

let accounts: Stored[] = [];
let assignments: StoredAssignment[] = [];

const nowIso = () => new Date().toISOString();
const today = () => nowIso().slice(0, 10);
const etag = (v: number) => ({ ETag: `"${v}"` });

function slug(name: string): string {
  const parts = name
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/đ/g, 'd')
    .replace(/Đ/g, 'D')
    .toLowerCase()
    .split(/\s+/)
    .filter(Boolean);
  const last = parts.pop() ?? 'nguoidung';
  return last + parts.map((p) => p[0]).join('');
}

function reset() {
  accounts = ORG_PERSONS.map((p, i) => ({
    id: p.id,
    username: slug(p.name),
    fullName: p.name,
    email: `${slug(p.name)}@vidu.gov.vn`,
    status: i === 5 ? 'KHOA' : 'HOAT_DONG',
    primaryUnitId: p.unitId,
    primaryUnitName: p.unitName,
    jobTitleName: p.title,
    concurrentCount: i === 1 ? 1 : 0,
    lockedAt: i === 5 ? nowIso() : null,
    lockReason: i === 5 ? 'Nghỉ phép dài hạn theo quyết định của đơn vị' : null,
    passwordResetAt: null,
    createdAt: '2026-09-01T02:00:00Z',
    source: 'NAP_SAN',
    rowVersion: 0,
  }));
  assignments = ORG_PERSONS.map((p) => ({
    id: `as-${p.id}`,
    personId: p.id,
    orgUnitId: p.unitId,
    orgUnitName: p.unitName,
    jobTitleName: p.title,
    kind: 'CHINH',
    branch: 'DANG',
    roles: [],
    validFrom: '2025-01-01',
    validTo: null,
    rowVersion: 0,
  }));
  const second = ORG_PERSONS[1];
  if (second)
    assignments.push({
      id: `as-${second.id}-kn`,
      personId: second.id,
      orgUnitId: 'u-vptu',
      orgUnitName: UNITS.get('u-vptu')?.name ?? 'Văn phòng Tỉnh uỷ',
      jobTitleName: 'Phó Chánh Văn phòng',
      kind: 'KIEM_NHIEM',
      branch: 'DANG',
      roles: [],
      validFrom: '2026-01-01',
      validTo: null,
      rowVersion: 0,
    });
}
reset();

function subtree(id: string): Set<string> {
  const out = new Set([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const [uid, u] of UNITS)
      if (u.parentId && out.has(u.parentId) && !out.has(uid)) {
        out.add(uid);
        grew = true;
      }
  }
  return out;
}

const stepUpRequired = (): MockResponse => {
  const r = problem(401, 'Cần xác thực nhiều lớp', {
    type: 'https://datatracker.ietf.org/doc/html/rfc9470',
    code: 'insufficient_user_authentication',
  });
  // ui-text-ok: tiêu đề HTTP, không hiện lên màn hình
  r.headers = {
    ...r.headers,
    'WWW-Authenticate':
      'Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=900',
  };
  return r;
};

function checkIfMatch(r: MockRequest, rowVersion: number): MockResponse | null {
  const h = r.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu If-Match', { code: 'ORG-428-PRECONDITION-REQUIRED' });
  if (h.replace(/"/g, '') !== String(rowVersion))
    return problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      type: 'https://okrkpi.vn/problems/concurrent-modification',
      code: 'ORG-409-VERSION-CONFLICT',
    });
  return null;
}

const invalid = (field: string, message: string): MockResponse =>
  problem(422, message, { code: 'ORG-422-VALIDATION', errors: [{ field, message }] } as never);

const actions = (a: Stored): string[] => [
  'SCR-0109.sua',
  'SCR-0109.phan-cong',
  'SCR-0109.dat-lai-mat-khau',
  ...(a.id === MOCK_ME.id ? [] : ['SCR-0108.khoa']),
];
const out = (a: Stored): UserAccount => structuredClone({ ...a, permittedActions: actions(a) });

function find(r: MockRequest): Stored | MockResponse {
  const a = accounts.find((x) => x.id === r.pathParams['id']);
  return a ?? problem(404, 'Không tìm thấy người dùng', { code: 'ORG-404-NOT-FOUND' });
}
const isRes = (x: unknown): x is MockResponse =>
  !!x && typeof (x as MockResponse).status === 'number';

function list(r: MockRequest): MockResponse {
  const unit = r.params.get('unit');
  const units = unit
    ? r.params.get('includeSubunits') === 'true'
      ? subtree(unit)
      : new Set([unit])
    : null;
  const q = r.params.get('q')?.toLowerCase().trim();
  const status = r.params.get('status');
  const base = accounts.filter((a) => !units || (!!a.primaryUnitId && units.has(a.primaryUnitId)));
  const matched = base
    .filter((a) => !q || a.fullName.toLowerCase().includes(q) || a.username.includes(q))
    .filter((a) => !status || a.status === status)
    .sort((a, b) => a.fullName.localeCompare(b.fullName, 'vi'));
  return ok({
    items: matched.map(out),
    nextCursor: null,
    counts: {
      total: base.length,
      active: base.filter((a) => a.status === 'HOAT_DONG').length,
      locked: base.filter((a) => a.status === 'KHOA').length,
    },
  });
}

function create(r: MockRequest): MockResponse {
  if (!mockSession.recentMfa()) return stepUpRequired();
  const b = (r.body ?? {}) as Partial<UserAccountCreate>;
  if (!b.fullName?.trim()) return invalid('fullName', 'Nhập họ và tên.');
  if (!b.username || !/^[a-z0-9][a-z0-9._-]{2,49}$/.test(b.username))
    return invalid(
      'username',
      'Tên đăng nhập gồm chữ thường không dấu, số, dấu chấm, gạch; từ 3 ký tự.',
    );
  if (!b.orgUnitId) return invalid('orgUnitId', 'Chọn đơn vị công tác.');
  if (!b.jobTitleId) return invalid('jobTitleId', 'Chọn chức danh.');
  if (accounts.some((a) => a.username === b.username))
    return problem(409, 'Tên đăng nhập đã có', {
      code: 'ORG-409-USERNAME-TAKEN',
      msg: 'MSG-01-20',
    });
  const id = `p-${crypto.randomUUID()}`;
  const unitName = UNITS.get(b.orgUnitId)?.name ?? b.orgUnitId;
  const title = JOB_TITLES.find((j) => j.id === b.jobTitleId)?.name ?? 'Chuyên viên';
  const row: Stored = {
    id,
    username: b.username,
    fullName: b.fullName.trim(),
    email: b.email ?? null,
    status: 'HOAT_DONG',
    primaryUnitId: b.orgUnitId,
    primaryUnitName: unitName,
    jobTitleName: title,
    concurrentCount: 0,
    lockedAt: null,
    lockReason: null,
    passwordResetAt: null,
    createdAt: nowIso(),
    source: 'QUAN_TRI',
    rowVersion: 0,
  };
  accounts.push(row);
  assignments.push({
    id: `as-${id}`,
    personId: id,
    orgUnitId: b.orgUnitId,
    orgUnitName: unitName,
    jobTitleName: title,
    kind: 'CHINH',
    branch: 'DANG',
    roles: [],
    validFrom: b.validFrom ?? today(),
    validTo: null,
    rowVersion: 0,
  });
  return {
    status: 201,
    body: { ...out(row), temporaryPassword: 'Tam-8kQ2-vX7p-M4z' },
    headers: etag(0),
  };
}

function update(r: MockRequest): MockResponse {
  const a = find(r);
  if (isRes(a)) return a;
  const pre = checkIfMatch(r, a.rowVersion);
  if (pre) return pre;
  const b = (r.body ?? {}) as UserAccountUpdate;
  if (b.fullName !== undefined && !b.fullName.trim()) return invalid('fullName', 'Nhập họ và tên.');
  if (b.fullName) a.fullName = b.fullName.trim();
  if (b.email !== undefined) a.email = b.email;
  a.rowVersion++;
  return ok(out(a), etag(a.rowVersion));
}

function lockOrUnlock(lock: boolean) {
  return (r: MockRequest): MockResponse => {
    if (!mockSession.recentMfa()) return stepUpRequired();
    const a = find(r);
    if (isRes(a)) return a;
    const pre = checkIfMatch(r, a.rowVersion);
    if (pre) return pre;
    const reason = ((r.body ?? {}) as { reason?: string }).reason?.trim() ?? '';
    if (reason.length < 5) return invalid('reason', 'Nhập lý do (ít nhất 5 ký tự).');
    if (lock && a.id === MOCK_ME.id)
      return problem(422, 'Không tự khoá', { code: 'ORG-422-SELF-LOCK' });
    a.status = lock ? 'KHOA' : 'HOAT_DONG';
    a.lockedAt = lock ? nowIso() : null;
    a.lockReason = lock ? reason : null;
    a.rowVersion++;
    return ok(out(a), etag(a.rowVersion));
  };
}

function resetPassword(r: MockRequest): MockResponse {
  if (!mockSession.recentMfa()) return stepUpRequired();
  const a = find(r);
  if (isRes(a)) return a;
  a.passwordResetAt = nowIso();
  a.rowVersion++;
  return ok({ temporaryPassword: 'Tam-3nW9-pL6d-R2q' });
}

function personAssignments(r: MockRequest): MockResponse {
  const id = r.pathParams['id'];
  const items = assignments
    .filter((x) => x.personId === id && (!x.validTo || x.validTo > today()))
    .sort((a, b) => (a.kind === 'CHINH' ? -1 : b.kind === 'CHINH' ? 1 : 0))
    .map(({ personId: _p, ...rest }) => structuredClone(rest));
  return ok({ items });
}

function addAssignment(r: MockRequest): MockResponse {
  if (!mockSession.recentMfa()) return stepUpRequired();
  const b = (r.body ?? {}) as Partial<OrgAssignmentCreateInput>;
  if (!b.personId || !b.orgUnitId || !b.jobTitleId || !b.validFrom)
    return invalid('orgUnitId', 'Chọn đơn vị, chức danh và ngày bắt đầu.');
  const overlap = assignments.some(
    (x) =>
      x.personId === b.personId &&
      x.orgUnitId === b.orgUnitId &&
      (!x.validTo || x.validTo > b.validFrom!),
  );
  if (overlap) return problem(409, 'Trùng phân công', { code: 'ORG-409-ASSIGNMENT-OVERLAP' });
  const row: StoredAssignment = {
    id: `as-${crypto.randomUUID()}`,
    personId: b.personId,
    orgUnitId: b.orgUnitId,
    orgUnitName: UNITS.get(b.orgUnitId)?.name ?? b.orgUnitId,
    jobTitleName: JOB_TITLES.find((j) => j.id === b.jobTitleId)?.name ?? 'Chuyên viên',
    kind: b.assignmentKind ?? 'KIEM_NHIEM',
    branch: 'DANG',
    roles: [],
    validFrom: b.validFrom,
    validTo: b.validTo ?? null,
    rowVersion: 0,
  };
  assignments.push(row);
  const acc = accounts.find((a) => a.id === b.personId);
  if (acc && row.kind !== 'CHINH') acc.concurrentCount++;
  const person = accounts.find((a) => a.id === row.personId);
  return {
    status: 201,
    body: {
      id: row.id,
      personId: row.personId,
      personName: person?.fullName ?? '',
      jobTitleName: row.jobTitleName,
      kind: row.kind,
      branch: row.branch,
      validFrom: row.validFrom,
      validTo: row.validTo,
    },
    headers: etag(0),
  };
}

function endAssignment(r: MockRequest): MockResponse {
  if (!mockSession.recentMfa()) return stepUpRequired();
  const row = assignments.find((x) => x.id === r.pathParams['id']);
  if (!row) return problem(404, 'Không tìm thấy phân công', { code: 'ORG-404-NOT-FOUND' });
  const pre = checkIfMatch(r, row.rowVersion);
  if (pre) return pre;
  const b = (r.body ?? {}) as { validTo?: string; reason?: string };
  if (!b.validTo || b.validTo <= row.validFrom)
    return invalid('validTo', 'Ngày kết thúc phải sau ngày bắt đầu phân công.');
  if (!b.reason || b.reason.trim().length < 5)
    return invalid('reason', 'Nhập lý do (ít nhất 5 ký tự).');
  row.validTo = b.validTo;
  row.rowVersion++;
  const acc = accounts.find((a) => a.id === row.personId);
  if (acc && row.kind !== 'CHINH') acc.concurrentCount = Math.max(0, acc.concurrentCount - 1);
  return ok(
    {
      id: row.id,
      personId: row.personId,
      personName: acc?.fullName ?? '',
      jobTitleName: row.jobTitleName,
      kind: row.kind,
      branch: row.branch,
      validFrom: row.validFrom,
      validTo: row.validTo,
    },
    etag(row.rowVersion),
  );
}

export const USER_ADMIN_MOCK: MockModule = {
  name: 'user-admin',
  reset,
  routes: [
    { method: 'GET', pattern: '/api/v1/org/users', handle: list },
    { method: 'POST', pattern: '/api/v1/org/users', handle: create },
    {
      method: 'GET',
      pattern: '/api/v1/org/users/:id',
      handle: (r) => {
        const a = find(r);
        return isRes(a) ? a : ok(out(a), etag(a.rowVersion));
      },
    },
    { method: 'PATCH', pattern: '/api/v1/org/users/:id', handle: update },
    { method: 'POST', pattern: '/api/v1/org/users/:id/lock', handle: lockOrUnlock(true) },
    { method: 'POST', pattern: '/api/v1/org/users/:id/unlock', handle: lockOrUnlock(false) },
    { method: 'POST', pattern: '/api/v1/org/users/:id/reset-password', handle: resetPassword },
    { method: 'GET', pattern: '/api/v1/org/job-titles', handle: () => ok({ items: JOB_TITLES }) },
    { method: 'GET', pattern: '/api/v1/org/persons/:id/assignments', handle: personAssignments },
    { method: 'POST', pattern: '/api/v1/org/assignments', handle: addAssignment },
    { method: 'POST', pattern: '/api/v1/org/assignments/:id/end', handle: endAssignment },
  ],
};
