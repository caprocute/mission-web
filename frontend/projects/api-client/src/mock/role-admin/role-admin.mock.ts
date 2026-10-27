import {
  AccessEvent,
  Delegation,
  DelegationStatus,
  RoleBinding,
  RoleBindingCreate,
  RoleBindingStatus,
  DelegationCreate,
} from '../../lib/models/role-binding';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { MOCK_ME, mockSession } from '../mock-session';
import { seedOrgTree } from '../org-tree/org-tree.fixture';
import {
  SENSITIVE,
  StoredBinding,
  StoredDelegation,
  ref,
  seedBindings,
  seedDelegations,
  titleOf,
} from './role-admin.fixture';

/**
 * Giả lập quản trị vai trò và uỷ quyền của MOD-08 (`/api/v1/config/role-bindings`,
 * `/config/access-history`, `/config/delegations`; SCR-0105 đến SCR-0107, WEB-058). Người gọi là
 * `MOCK_ME`. Thao tác ghi cần MFA gần đây (BR-01-15) và trả 401 `insufficient_user_authentication` nếu
 * chưa có; kiểm tự cấp, tự duyệt (BR-01-13, `CONFIG-403-SELF-GRANT`), vai trò nhạy cảm chờ duyệt thứ
 * hai (BR-01-14), thời hạn bắt buộc (BR-01-16), không uỷ quyền lại (BR-01-17), `If-Match`. Lịch sử quyền
 * suy ra từ chính các bản ghi, như máy chủ đọc từ nhật ký kiểm toán.
 */

let bindings: StoredBinding[] = [];
let delegations: StoredDelegation[] = [];

/** Tên đơn vị và quan hệ cha con lấy từ cây tổ chức giả lập (phiên bản mới nhất của mỗi đơn vị). */
const UNITS = (() => {
  const m = new Map<string, { name: string; parentId: string | null }>();
  for (const u of seedOrgTree().units)
    m.set(u.id, { name: u.shortName ?? u.name, parentId: u.parentId });
  return m;
})();

function reset() {
  bindings = seedBindings();
  delegations = seedDelegations();
}
reset();

const now = () => Date.now();
const nowIso = () => new Date().toISOString();
const etag = (rowVersion: number) => ({ ETag: `"${rowVersion}"` });

function bindingStatus(b: StoredBinding): RoleBindingStatus {
  if (b.endKind === 'TU_CHOI') return 'TU_CHOI';
  if (b.endKind === 'THU_HOI') return 'DA_THU_HOI';
  if (b.sensitive && !b.approvedBy) return 'CHO_DUYET';
  if (Date.parse(b.validTo) <= now()) return 'HET_HAN';
  if (Date.parse(b.validFrom) > now()) return 'SAP_HIEU_LUC';
  return 'HIEU_LUC';
}
function delegationStatus(d: StoredDelegation): DelegationStatus {
  if (d.endedAt) return 'DA_THU_HOI';
  if (Date.parse(d.validTo) <= now()) return 'HET_HAN';
  if (Date.parse(d.validFrom) > now()) return 'SAP_HIEU_LUC';
  return 'HIEU_LUC';
}
const outB = (b: StoredBinding): RoleBinding => structuredClone({ ...b, status: bindingStatus(b) });
const outD = (d: StoredDelegation): Delegation =>
  structuredClone({ ...d, status: delegationStatus(d) });

/** Đơn vị và mọi đơn vị cấp dưới (theo `parentId` của phiên bản mới nhất). */
function subtree(id: string): Set<string> {
  const out = new Set([id]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const [uid, u] of UNITS) {
      if (u.parentId && out.has(u.parentId) && !out.has(uid)) {
        out.add(uid);
        grew = true;
      }
    }
  }
  return out;
}

function stepUpRequired(): MockResponse {
  const r = problem(401, 'Cần xác thực nhiều lớp trước khi thay đổi quyền', {
    type: 'https://datatracker.ietf.org/doc/html/rfc9470',
    code: 'insufficient_user_authentication',
  });
  r.headers = {
    ...r.headers,
    // ui-text-ok: tiêu đề HTTP, không hiện lên màn hình
    'WWW-Authenticate':
      'Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=900',
  };
  return r;
}

const selfGrant = () =>
  problem(403, 'Không được tự cấp, tự duyệt hoặc tự thu hồi quyền của chính mình', {
    type: 'https://okrkpi.vn/problems/forbidden',
    code: 'CONFIG-403-SELF-GRANT',
  });

function checkIfMatch(r: MockRequest, rowVersion: number): MockResponse | null {
  const h = r.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu If-Match', { code: 'CONFIG-428-PRECONDITION-REQUIRED' });
  if (h.replace(/"/g, '') !== String(rowVersion))
    return problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      type: 'https://okrkpi.vn/problems/concurrent-modification',
      code: 'CONFIG-409-VERSION-CONFLICT',
    });
  return null;
}

function invalid(field: string, message: string, code = 'CONFIG-422-VALIDITY'): MockResponse {
  return problem(422, message, { code, errors: [{ field, message }] } as never);
}

/** Kiểm thời hạn và lý do chung cho cấp vai trò và uỷ quyền (BR-01-16). */
function checkTerm(body: { validFrom?: string; validTo?: string; reason?: string }) {
  if (!body.validFrom || !body.validTo || Date.parse(body.validTo) <= Date.parse(body.validFrom))
    return invalid('validTo', 'Phải có ngày kết thúc sau ngày bắt đầu.');
  if (!body.reason || body.reason.trim().length < 5)
    return invalid('reason', 'Nhập căn cứ hoặc lý do (ít nhất 5 ký tự).', 'CONFIG-422-REASON');
  return null;
}

const ACTIVE: RoleBindingStatus[] = ['CHO_DUYET', 'SAP_HIEU_LUC', 'HIEU_LUC'];
const awaitingMe = (b: StoredBinding) =>
  bindingStatus(b) === 'CHO_DUYET' && b.grantedBy.id !== MOCK_ME.id && b.person.id !== MOCK_ME.id;

function listBindings(r: MockRequest): MockResponse {
  const unit = r.params.get('orgUnitId');
  const person = r.params.get('personId');
  const units = unit
    ? r.params.get('includeSubunits') === 'true'
      ? subtree(unit)
      : new Set([unit])
    : null;
  const base = bindings.filter(
    (b) => (!units || units.has(b.orgUnit.id)) && (!person || b.person.id === person),
  );
  const role = r.params.get('role');
  const status = r.params.get('status');
  const mine = r.params.get('awaitingMe') === 'true';
  const items = base
    .filter((b) => !role || b.roleCode === role)
    .filter((b) =>
      status ? bindingStatus(b) === status : bindingStatus(b) !== 'TU_CHOI' || !!person,
    )
    .filter((b) => !mine || awaitingMe(b))
    .sort((a, b) => b.grantedAt.localeCompare(a.grantedAt))
    .map(outB);
  const soon = now() + 30 * 86_400_000;
  const counts = {
    active: base.filter((b) => bindingStatus(b) === 'HIEU_LUC').length,
    pending: base.filter((b) => bindingStatus(b) === 'CHO_DUYET').length,
    awaitingMe: base.filter(awaitingMe).length,
    expiringSoon: base.filter(
      (b) => bindingStatus(b) === 'HIEU_LUC' && Date.parse(b.validTo) <= soon,
    ).length,
  };
  return ok({ items, nextCursor: null, counts });
}

function grant(r: MockRequest): MockResponse {
  if (!mockSession.recentMfa()) return stepUpRequired();
  const body = (r.body ?? {}) as Partial<RoleBindingCreate>;
  if (!body.personId || !body.roleCode || !body.orgUnitId || !body.scope)
    return invalid('personId', 'Chọn người, vai trò, đơn vị và phạm vi.', 'CONFIG-422-REQUIRED');
  if (body.personId === MOCK_ME.id) return selfGrant();
  const term = checkTerm(body);
  if (term) return term;
  const dup = bindings.find(
    (b) =>
      b.person.id === body.personId &&
      b.roleCode === body.roleCode &&
      b.orgUnit.id === body.orgUnitId &&
      b.scope === body.scope &&
      ACTIVE.includes(bindingStatus(b)) &&
      Date.parse(b.validFrom) < Date.parse(body.validTo!) &&
      Date.parse(body.validFrom!) < Date.parse(b.validTo),
  );
  if (dup)
    return problem(409, 'Đã có vai trò trùng còn hiệu lực', {
      code: 'CONFIG-409-DUPLICATE-BINDING',
    });
  const row: StoredBinding = {
    id: `rb-${crypto.randomUUID()}`,
    person: ref(body.personId),
    personTitle: titleOf(body.personId),
    roleCode: body.roleCode,
    orgUnit: { id: body.orgUnitId, name: UNITS.get(body.orgUnitId)?.name ?? body.orgUnitId },
    scope: body.scope,
    flow: body.flow ?? null,
    validFrom: body.validFrom!,
    validTo: body.validTo!,
    sensitive: (SENSITIVE as readonly string[]).includes(body.roleCode),
    grantedBy: ref(MOCK_ME.id),
    grantedAt: nowIso(),
    reason: body.reason!.trim(),
    approvedBy: null,
    approvedAt: null,
    endedBy: null,
    endedAt: null,
    endKind: null,
    endReason: null,
    rowVersion: 1,
  };
  bindings.push(row);
  return {
    status: 201,
    body: outB(row),
    headers: { ...etag(1), Location: `/api/v1/config/role-bindings/${row.id}` },
  };
}

function findBinding(r: MockRequest): StoredBinding | MockResponse {
  return (
    bindings.find((b) => b.id === r.pathParams['id']) ??
    problem(404, 'Không tìm thấy vai trò đã cấp', { code: 'CONFIG-404-NOT-FOUND' })
  );
}
const isRes = (x: object): x is MockResponse => 'status' in x && typeof x.status === 'number';

/** Duyệt, từ chối hoặc thu hồi một vai trò đã cấp. */
function endOrApprove(action: 'approve' | 'reject' | 'revoke') {
  return (r: MockRequest): MockResponse => {
    const b = findBinding(r);
    if (isRes(b)) return b;
    if (!mockSession.recentMfa()) return stepUpRequired();
    const pre = checkIfMatch(r, b.rowVersion);
    if (pre) return pre;
    const st = bindingStatus(b);
    if (action === 'revoke') {
      if (b.person.id === MOCK_ME.id) return selfGrant();
      if (st !== 'HIEU_LUC' && st !== 'SAP_HIEU_LUC')
        return problem(409, 'Vai trò đã kết thúc', { code: 'CONFIG-409-ALREADY-ENDED' });
    } else {
      if (b.grantedBy.id === MOCK_ME.id || b.person.id === MOCK_ME.id) return selfGrant();
      if (st !== 'CHO_DUYET')
        return problem(409, 'Đề nghị không còn chờ duyệt', { code: 'CONFIG-409-NOT-PENDING' });
    }
    const body = (r.body ?? {}) as { reason?: string };
    if (action !== 'approve' && (!body.reason || body.reason.trim().length < 5))
      return invalid('reason', 'Nhập lý do (ít nhất 5 ký tự).', 'CONFIG-422-REASON');
    if (action === 'approve') {
      b.approvedBy = ref(MOCK_ME.id);
      b.approvedAt = nowIso();
    } else {
      b.endedBy = ref(MOCK_ME.id);
      b.endedAt = nowIso();
      b.endKind = action === 'reject' ? 'TU_CHOI' : 'THU_HOI';
      b.endReason = body.reason!.trim();
      if (action === 'revoke' && Date.parse(b.validTo) > now()) b.validTo = nowIso();
    }
    b.rowVersion++;
    return ok(outB(b), etag(b.rowVersion));
  };
}

/** Sự kiện quyền suy ra từ bản ghi (máy chủ thật đọc từ `sys.audit.v1`). */
type UnitEvent = AccessEvent & { orgUnitId: string };
function events(): UnitEvent[] {
  const out: UnitEvent[] = [];
  const role = (b: StoredBinding) => `vai trò ${b.roleCode} tại ${b.orgUnit.name}`;
  for (const b of bindings) {
    const base = { person: b.person, refId: b.id, orgUnitId: b.orgUnit.id };
    out.push({
      ...base,
      id: `${b.id}-cap`,
      at: b.grantedAt,
      kind: 'CAP_VAI_TRO',
      actor: b.grantedBy,
      summary: `Cấp ${role(b)}${b.sensitive ? ', chờ duyệt thứ hai' : ''}. Căn cứ: ${b.reason}`,
    });
    if (b.approvedBy)
      out.push({
        ...base,
        id: `${b.id}-duyet`,
        at: b.approvedAt!,
        kind: 'DUYET_VAI_TRO',
        actor: b.approvedBy,
        summary: `Duyệt thứ hai ${role(b)}`,
      });
    if (b.endKind)
      out.push({
        ...base,
        id: `${b.id}-ket-thuc`,
        at: b.endedAt!,
        kind: b.endKind === 'TU_CHOI' ? 'TU_CHOI_VAI_TRO' : 'THU_HOI_VAI_TRO',
        actor: b.endedBy,
        summary: `${b.endKind === 'TU_CHOI' ? 'Từ chối cấp' : 'Thu hồi'} ${role(b)}. Lý do: ${b.endReason}`,
      });
    else if (bindingStatus(b) === 'HET_HAN')
      out.push({
        ...base,
        id: `${b.id}-het-han`,
        at: b.validTo,
        kind: 'HET_HAN_VAI_TRO',
        actor: null,
        summary: `Hết hạn ${role(b)}`,
      });
  }
  for (const d of delegations) {
    const what = `${d.kind === 'DUYET_THAY' ? 'duyệt thay' : d.kind === 'NGHIEM_THU_THAY' ? 'nghiệm thu thay' : 'check-in thay'} ${d.delegator.name} → ${d.delegate.name}`;
    for (const person of [d.delegator, d.delegate]) {
      const base = { person, refId: d.id, orgUnitId: d.orgUnit.id };
      out.push({
        ...base,
        id: `${d.id}-${person.id}-tao`,
        at: d.createdAt,
        kind: 'TAO_UY_QUYEN',
        actor: d.createdBy,
        summary: `Uỷ quyền ${what}. Lý do: ${d.reason}`,
      });
      if (d.endedAt)
        out.push({
          ...base,
          id: `${d.id}-${person.id}-thu-hoi`,
          at: d.endedAt,
          kind: 'THU_HOI_UY_QUYEN',
          actor: d.endedBy,
          summary: `Thu hồi uỷ quyền ${what}. Lý do: ${d.endReason}`,
        });
      else if (delegationStatus(d) === 'HET_HAN')
        out.push({
          ...base,
          id: `${d.id}-${person.id}-het-han`,
          at: d.validTo,
          kind: 'HET_HAN_UY_QUYEN',
          actor: null,
          summary: `Hết hạn uỷ quyền ${what}`,
        });
    }
  }
  return out.sort((a, b) => b.at.localeCompare(a.at));
}

function history(r: MockRequest): MockResponse {
  const person = r.params.get('personId');
  const unit = r.params.get('orgUnitId');
  if (!person && !unit)
    return invalid('personId', 'Chọn người hoặc đơn vị.', 'CONFIG-422-REQUIRED');
  const items = events()
    .filter((e) => (!person || e.person.id === person) && (!unit || e.orgUnitId === unit))
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    .map(({ orgUnitId, ...e }): AccessEvent => e);
  return ok({ items, nextCursor: null });
}

function exportHistory(r: MockRequest): MockResponse {
  if (!mockSession.recentMfa()) return stepUpRequired();
  const person = r.params.get('personId');
  const esc = (s: string) => `"${s.replace(/"/g, '""')}"`;
  const rows = events()
    .filter((e) => e.person.id === person)
    .map((e) => [e.at, e.kind, e.actor?.name ?? 'Hệ thống', e.summary].map(esc).join(','));
  return ok('﻿' + ['Thời điểm,Loại,Người thực hiện,Nội dung', ...rows].join('\r\n'), {
    'Content-Type': 'text/csv; charset=utf-8',
  });
}

function listDelegations(r: MockRequest): MockResponse {
  const unit = r.params.get('orgUnitId');
  const person = r.params.get('personId');
  const kind = r.params.get('kind');
  const status = r.params.get('status');
  const items = delegations
    .filter((d) => !unit || d.orgUnit.id === unit)
    .filter((d) => !person || d.delegator.id === person || d.delegate.id === person)
    .filter((d) => !kind || d.kind === kind)
    .filter((d) => !status || delegationStatus(d) === status)
    .sort((a, b) => b.validFrom.localeCompare(a.validFrom))
    .map(outD);
  return ok({ items, nextCursor: null });
}

function createDelegation(r: MockRequest): MockResponse {
  if (!mockSession.recentMfa()) return stepUpRequired();
  const body = (r.body ?? {}) as Partial<DelegationCreate>;
  if (!body.kind || !body.delegatorId || !body.delegateId || !body.orgUnitId)
    return invalid(
      'delegateId',
      'Chọn loại, người uỷ quyền, người nhận và đơn vị.',
      'CONFIG-422-REQUIRED',
    );
  if (body.delegatorId === body.delegateId) return selfGrant();
  if (body.kind === 'CHECK_IN_THAY' && !body.subjectId)
    return invalid('subjectId', 'Chọn KR được check-in thay.', 'CONFIG-422-REQUIRED');
  const term = checkTerm(body);
  if (term) return term;
  const received = delegations.some(
    (d) =>
      d.delegate.id === body.delegatorId &&
      d.kind === body.kind &&
      ['HIEU_LUC', 'SAP_HIEU_LUC'].includes(delegationStatus(d)),
  );
  if (received)
    return problem(422, 'Người nhận uỷ quyền không được uỷ quyền lại', {
      code: 'CONFIG-422-REDELEGATION',
    });
  const row: StoredDelegation = {
    id: `dl-${crypto.randomUUID()}`,
    kind: body.kind,
    delegator: ref(body.delegatorId),
    delegate: ref(body.delegateId),
    orgUnit: { id: body.orgUnitId, name: UNITS.get(body.orgUnitId)?.name ?? body.orgUnitId },
    subject: body.subjectId
      ? { id: body.subjectId, name: KR_NAMES[body.subjectId] ?? body.subjectId }
      : null,
    validFrom: body.validFrom!,
    validTo: body.validTo!,
    reason: body.reason!.trim(),
    createdBy: ref(MOCK_ME.id),
    createdAt: nowIso(),
    endedBy: null,
    endedAt: null,
    endReason: null,
    rowVersion: 1,
  };
  delegations.push(row);
  return { status: 201, body: outD(row), headers: etag(1) };
}

/** KR mẫu để chọn khi giao check-in thay (máy chủ thật tra MOD-01). */
export const KR_NAMES: Record<string, string> = {
  'kr-btc-01': 'KR1: Hoàn thành rà soát quy hoạch cán bộ nhiệm kỳ 2025–2030',
  'kr-btc-02': 'KR2: 100% hồ sơ đánh giá quý được số hoá',
  'kr-btc-03': 'KR3: Tổ chức 4 lớp bồi dưỡng cấp uỷ cơ sở',
};

function revokeDelegation(r: MockRequest): MockResponse {
  const d = delegations.find((x) => x.id === r.pathParams['id']);
  if (!d) return problem(404, 'Không tìm thấy uỷ quyền', { code: 'CONFIG-404-NOT-FOUND' });
  if (!mockSession.recentMfa()) return stepUpRequired();
  const pre = checkIfMatch(r, d.rowVersion);
  if (pre) return pre;
  if (!['HIEU_LUC', 'SAP_HIEU_LUC'].includes(delegationStatus(d)))
    return problem(409, 'Uỷ quyền đã kết thúc', { code: 'CONFIG-409-ALREADY-ENDED' });
  const body = (r.body ?? {}) as { reason?: string };
  if (!body.reason || body.reason.trim().length < 5)
    return invalid('reason', 'Nhập lý do (ít nhất 5 ký tự).', 'CONFIG-422-REASON');
  d.endedBy = ref(MOCK_ME.id);
  d.endedAt = nowIso();
  d.endReason = body.reason.trim();
  if (Date.parse(d.validTo) > now()) d.validTo = d.endedAt;
  d.rowVersion++;
  return ok(outD(d), etag(d.rowVersion));
}

export const ROLE_ADMIN_MOCK: MockModule = {
  name: 'role-admin',
  reset,
  routes: [
    { method: 'GET', pattern: '/api/v1/config/role-bindings', handle: listBindings },
    { method: 'POST', pattern: '/api/v1/config/role-bindings', handle: grant },
    {
      method: 'GET',
      pattern: '/api/v1/config/role-bindings/:id',
      handle: (r) => {
        const b = findBinding(r);
        return isRes(b) ? b : ok(outB(b), etag(b.rowVersion));
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/config/role-bindings/:id/approve',
      handle: endOrApprove('approve'),
    },
    {
      method: 'POST',
      pattern: '/api/v1/config/role-bindings/:id/reject',
      handle: endOrApprove('reject'),
    },
    {
      method: 'POST',
      pattern: '/api/v1/config/role-bindings/:id/revoke',
      handle: endOrApprove('revoke'),
    },
    { method: 'GET', pattern: '/api/v1/config/access-history', handle: history },
    { method: 'GET', pattern: '/api/v1/config/access-history/export', handle: exportHistory },
    { method: 'GET', pattern: '/api/v1/config/delegations', handle: listDelegations },
    { method: 'POST', pattern: '/api/v1/config/delegations', handle: createDelegation },
    { method: 'POST', pattern: '/api/v1/config/delegations/:id/revoke', handle: revokeDelegation },
  ],
};
