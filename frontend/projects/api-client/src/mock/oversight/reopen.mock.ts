import {
  REOPEN_LIMITS,
  REOPEN_PENDING_STATUSES,
  PostAudit,
  REOPEN_BASIS_NEEDS_OBJECT,
  ReopenApproverSlot,
  ReopenImpact,
  ReopenRequest,
  ReopenRequestInput,
  ReopenRequestStatus,
} from '../../lib/models/reopen';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { mockSession } from '../mock-session';
import { mockPeriodInfo } from '../periods/periods.mock';
import { alertPeriodOf } from './alerts.mock';
import { complaintPeriodOf, linkComplaintReopen } from './complaints.mock';
import {
  IMPACT_007,
  PROFILE_NAMES,
  REOPEN_PEOPLE,
  postAuditFixtures,
  reopenFixtures,
} from './reopen.fixture';

/**
 * Giả lập yêu cầu mở lại kỳ của MOD-07 (QT-13, SCR-1301, WEB-043) theo `contracts/openapi/modules/oversight.yaml`.
 * Kiểm: căn cứ như `ReopenBasis.complete()` của mission-core (khiếu nại, cảnh báo, đợt hậu kiểm cần
 * `basis.id` thuộc kỳ đã chọn, quyết định hậu kiểm cần `basis.code`; thiếu thì 422 trường `basis`), kỳ phải `DA_KHOA` (409 `OVERSIGHT-409-PERIOD-NOT-LOCKED`), một yêu cầu đang chờ cho cùng hồ sơ
 * (409 `OVERSIGHT-409-INVALID-STATE`), độ dài lý do và ý kiến (422), xác thực bước lên khi tạo, duyệt, từ
 * chối (401, BR-13-04), `If-Match` (428, 412), nguyên tắc bốn mắt BR-13-02 (403 `OVERSIGHT-403-FOUR-EYES`,
 * MSG-13-01: người dùng thử nghiệm là người tạo hoặc đã duyệt lượt trước), chỉ người tạo được huỷ. Người
 * dùng thử nghiệm được coi là người được chỉ định ở mọi lượt (OPA thật chọn đúng người).
 */

const ME = REOPEN_PEOPLE.me;

let store: ReopenRequest[] = reopenFixtures();
let postAudits: PostAudit[] = postAuditFixtures();

const etag = (rowVersion: number) => ({ ETag: `"${rowVersion}"` });

function find(req: MockRequest): ReopenRequest | MockResponse {
  return (
    store.find((r) => r.id === req.pathParams['id']) ??
    problem(404, 'Không tìm thấy yêu cầu mở lại kỳ.')
  );
}
// yêu cầu cũng có trường `status` nên phân biệt bằng `rowVersion`
const isResponse = (x: ReopenRequest | MockResponse): x is MockResponse => !('rowVersion' in x);

function ifMatch(r: ReopenRequest, req: MockRequest): MockResponse | null {
  const h = req.headers.get('If-Match');
  if (!h)
    return problem(428, 'Không xác định được phiên bản dữ liệu; đồng chí vui lòng tải lại trang.');
  if (h.replace(/"/g, '') !== String(r.rowVersion))
    return problem(412, 'Dữ liệu đã được người khác cập nhật.', {
      code: 'PRECONDITION_FAILED',
    });
  return null;
}

function stepUpRequired(): MockResponse {
  const r = problem(401, 'Cần xác thực bổ sung để phê duyệt mở lại kỳ.', {
    type: 'https://datatracker.ietf.org/doc/html/rfc9470',
    code: 'insufficient_user_authentication',
    msg: 'MSG-13-02',
  });
  r.headers = {
    ...r.headers,
    'WWW-Authenticate':
      // ui-text-ok: header HTTP gửi cho trình duyệt, không hiện lên màn hình
      'Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=900',
  };
  return r;
}

const invalid = (field: string, message: string) =>
  problem(422, 'Dữ liệu không hợp lệ', { errors: [{ field, message }] });

const conflict = (code: string, title: string) => problem(409, title, { code });

const out = (r: ReopenRequest) => ok(structuredClone(r), etag(r.rowVersion));

/** Ngày làm việc kế tiếp sau `n` ngày (bỏ thứ Bảy, Chủ nhật; lịch tenant thật có ngày lễ). */
function addWorkingDays(n: number, from = new Date()): string {
  const d = new Date(from);
  let left = n;
  while (left > 0) {
    d.setDate(d.getDate() + 1);
    if (d.getDay() !== 0 && d.getDay() !== 6) left--;
  }
  d.setHours(17, 0, 0, 0);
  return d.toISOString();
}

function nextCode(): string {
  const year = new Date().getFullYear();
  const nums = store
    .filter((r) => r.code.startsWith(`MLK-${year}-`))
    .map((r) => Number(r.code.slice(-3)));
  return `MLK-${year}-${String(Math.max(0, ...nums) + 1).padStart(3, '0')}`;
}

function list(req: MockRequest): MockResponse {
  const periodId = req.params.get('period');
  const status = req.params.get('status')?.split(',').filter(Boolean) as
    ReopenRequestStatus[] | undefined;
  const mine = req.params.get('awaitingMyApproval') === 'true';
  const items = store
    .filter((r) => !periodId || r.period.id === periodId)
    .filter((r) => !status?.length || status.includes(r.status))
    .filter((r) => !mine || (REOPEN_PENDING_STATUSES.includes(r.status) && !involved(r)))
    .sort((a, b) => (b.createdAt ?? '').localeCompare(a.createdAt ?? ''));
  return ok({ items: structuredClone(items), nextCursor: null });
}

/** Người dùng thử nghiệm đã là người tạo hoặc người duyệt của yêu cầu (BR-13-02). */
const involved = (r: ReopenRequest) =>
  r.requestedBy.id === ME.id || (r.approvers ?? []).some((s) => s.approval?.approver.id === ME.id);

/** Dạng `Uuid` của `common.yaml` (mọi phiên bản UUID, chữ hoa hoặc thường). */
const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * Như mission-core: mã hồ sơ trong `scopeIds[]` (phạm vi `HO_SO`) và `scopeDetail.items[].profileId` khai
 * báo `Uuid`, nên chuỗi khác dạng UUID làm hỏng bước đọc thân yêu cầu và máy chủ trả 400 Problem chung
 * (không có `errors[]`) trước mọi kiểm tra nghiệp vụ. HT-34: trước đây ô tự gõ "mã hồ sơ" lọt qua giả lập.
 * Mã đơn vị (`DON_VI`) chưa kiểm vì giả lập cây tổ chức (`org.json`) còn dùng mã dạng chữ.
 */
function malformedProfileIds(b: Partial<ReopenRequestInput>): MockResponse | null {
  const ids: unknown[] = [
    ...(b.scope !== 'HO_SO' || b.scopeIds == null
      ? []
      : Array.isArray(b.scopeIds)
        ? b.scopeIds
        : [b.scopeIds]),
    ...(b.scopeDetail?.items ?? [])
      .map((it) => (it as { profileId?: unknown }).profileId)
      .filter((id) => id !== undefined && id !== null),
  ];
  if (ids.every((id) => typeof id === 'string' && UUID_RE.test(id))) return null;
  return problem(400, 'Yêu cầu không hợp lệ', {
    type: 'https://okrkpi.vn/problems/bad-request',
    detail: 'Yêu cầu không thực hiện được. Vui lòng kiểm tra lại.',
  });
}

function create(req: MockRequest): MockResponse {
  const b = (req.body ?? {}) as Partial<ReopenRequestInput>;
  const bad = malformedProfileIds(b);
  if (bad) return bad;
  if (!mockSession.recentMfa()) return stepUpRequired();
  const info = b.periodId ? mockPeriodInfo(b.periodId) : undefined;
  if (!info) return invalid('periodId', 'Chọn kỳ đánh giá.');
  const reason = (b.reason ?? '').trim();
  if (reason.length < REOPEN_LIMITS.reasonMin || reason.length > REOPEN_LIMITS.reasonMax)
    return invalid('reason', `Lý do mở lại cần từ ${REOPEN_LIMITS.reasonMin} đến 4.000 ký tự.`);
  const days = Number(b.openWorkingDays ?? REOPEN_LIMITS.daysDefault);
  if (!Number.isInteger(days) || days < REOPEN_LIMITS.daysMin || days > REOPEN_LIMITS.daysMax)
    return invalid('openWorkingDays', 'Thời hạn mở từ 1 đến 10 ngày làm việc.');
  if (!b.scope) return invalid('scope', 'Chọn phạm vi mở lại.');
  if (b.scope !== 'TOAN_KY' && !b.scopeIds?.length)
    return invalid('scopeIds', 'Phạm vi cần ít nhất một đối tượng.');
  if (b.scope === 'TOAN_KY' && b.basis?.kind !== 'QUYET_DINH')
    return invalid('scope', 'Chỉ chọn toàn kỳ khi có quyết định hậu kiểm toàn diện làm căn cứ.');
  if (!b.scopeDetail?.items?.length)
    return invalid('scopeDetail', 'Nêu ít nhất một nhóm dữ liệu được phép sửa.');
  const basis = b.basis;
  if (
    !basis?.kind ||
    !(REOPEN_BASIS_NEEDS_OBJECT[basis.kind] ? basis.id : (basis.code ?? '').trim())
  )
    return invalid('basis', 'Chọn căn cứ mở lại.');
  if (info.status !== 'DA_KHOA')
    return conflict(
      'OVERSIGHT-409-PERIOD-NOT-LOCKED',
      `Kỳ ${info.ref.label} chưa khoá nên không cần mở lại.`,
    );
  if (basis.kind !== 'QUYET_DINH' && basisPeriodOf(basis.kind, basis.id!) !== info.ref.id)
    return invalid('basis', 'Không tìm thấy căn cứ trong kỳ đã chọn.');
  const overlap = store.find(
    (r) =>
      r.period.id === info.ref.id &&
      REOPEN_PENDING_STATUSES.includes(r.status) &&
      (r.scope === 'TOAN_KY' ||
        b.scope === 'TOAN_KY' ||
        r.scopeIds?.some((id) => b.scopeIds!.includes(id))),
  );
  if (overlap)
    return conflict(
      'OVERSIGHT-409-INVALID-STATE',
      `Đã có yêu cầu ${overlap.code} đang chờ duyệt cho cùng phạm vi.`,
    );
  const P = REOPEN_PEOPLE;
  const r: ReopenRequest = {
    id: crypto.randomUUID(),
    code: nextCode(),
    period: info.ref,
    periodStatus: 'DA_KHOA',
    auditKind: b.auditKind,
    scope: b.scope,
    scopeIds: b.scope === 'TOAN_KY' ? [] : [...b.scopeIds!],
    scopeDetail: structuredClone(b.scopeDetail),
    openUntil: addWorkingDays(days),
    reason,
    basis: structuredClone(b.basis),
    requestedBy: ME,
    approvalsRequired: 2,
    status: 'CHO_DUYET_1',
    approvers: [
      { round: 1, role: 'decider', assignee: P.tam, approval: null },
      { round: 2, role: 'second_approver', assignee: P.quan, approval: null },
    ],
    executedAt: null,
    relockedAt: null,
    createdAt: new Date().toISOString(),
    rowVersion: 1,
  };
  store.unshift(r);
  if (r.basis?.kind === 'KHIEU_NAI' && r.basis.id) linkComplaintReopen(r.basis.id, r.id);
  return {
    status: 201,
    body: structuredClone(r),
    headers: { ...etag(1), Location: `/api/v1/oversight/reopen-requests/${r.id}` },
  };
}

/** Kỳ của đối tượng căn cứ trong MOD-07 (như `basisExists` của mission-core), `undefined` nếu không có. */
function basisPeriodOf(kind: 'KHIEU_NAI' | 'CANH_BAO' | 'HAU_KIEM', id: string) {
  if (kind === 'KHIEU_NAI') return complaintPeriodOf(id);
  if (kind === 'CANH_BAO') return alertPeriodOf(id);
  return postAudits.find((a) => a.id === id)?.periodId;
}

/** `listPostAudits`: đợt hậu kiểm theo kỳ và trạng thái mở, mới mở trước. */
function listPostAudits(req: MockRequest): MockResponse {
  const periodId = req.params.get('period');
  const open = req.params.get('open');
  const items = postAudits
    .filter((a) => !periodId || a.periodId === periodId)
    .filter((a) => open === null || (open === 'true') === !a.closedAt)
    .sort((a, b) => b.openedAt.localeCompare(a.openedAt));
  return ok({ items: structuredClone(items), nextCursor: null });
}

function simulate(req: MockRequest): MockResponse {
  const r = find(req);
  if (isResponse(r)) return r;
  if (r.id === 'rr-2026-007') return ok(structuredClone(IMPACT_007));
  const ids = r.scopeIds?.length ? r.scopeIds : ['toan-ky'];
  const impact: ReopenImpact = {
    policyVersion: 'khối Đảng, phiên bản 2.0',
    rows: ids.flatMap((id, i) => {
      const before = 84 + ((id.length * 7 + i * 3) % 12) + 0.4;
      return [
        {
          profileId: id,
          metric: PROFILE_NAMES[id] ? `Điểm · ${PROFILE_NAMES[id]}` : 'Điểm',
          before: before.toLocaleString('vi-VN', { maximumFractionDigits: 1 }),
          after: (before + 0.9).toLocaleString('vi-VN', { maximumFractionDigits: 1 }),
        },
        {
          profileId: id,
          metric: 'Mức xếp loại',
          before: 'Hoàn thành tốt nhiệm vụ',
          after: 'Hoàn thành tốt nhiệm vụ',
        },
      ];
    }),
    formsToRegenerate: ids.length * 2,
  };
  return ok(impact);
}

/** Lượt hiện tại của yêu cầu (1 hoặc 2); `null` khi không còn chờ duyệt. */
const currentSlot = (r: ReopenRequest): ReopenApproverSlot | null =>
  r.status === 'CHO_DUYET_1'
    ? (r.approvers?.find((s) => s.round === 1) ?? null)
    : r.status === 'CHO_DUYET_2'
      ? (r.approvers?.find((s) => s.round === 2) ?? null)
      : null;

function decide(req: MockRequest, decision: 'DONG_Y' | 'TU_CHOI'): MockResponse {
  const r = find(req);
  if (isResponse(r)) return r;
  const pre = ifMatch(r, req);
  if (pre) return pre;
  if (!mockSession.recentMfa()) return stepUpRequired();
  const comment = String((req.body as { comment?: string } | null)?.comment ?? '').trim();
  if (comment.length < REOPEN_LIMITS.commentMin || comment.length > REOPEN_LIMITS.commentMax)
    return invalid('comment', 'Ý kiến người duyệt cần từ 10 đến 2.000 ký tự.');
  const slot = currentSlot(r);
  if (!slot) return conflict('OVERSIGHT-409-INVALID-STATE', 'Yêu cầu không còn ở bước chờ duyệt.');
  if (involved(r))
    return problem(403, 'Không đáp ứng nguyên tắc hai người duyệt.', {
      type: 'https://okrkpi.vn/problems/four-eyes',
      code: 'OVERSIGHT-403-FOUR-EYES',
      msg: 'MSG-13-01',
      detail:
        'Hai người duyệt phải là hai người khác nhau, khác người tạo yêu cầu và chưa tham gia thẩm định, quyết định hồ sơ được mở lại.',
    });
  const now = new Date().toISOString();
  slot.assignee = ME;
  slot.approval = {
    id: crypto.randomUUID(),
    approver: ME,
    approverRole: slot.role,
    decision,
    mfaAcr: 'aal2',
    signRequestId: crypto.randomUUID(),
    comment,
    decidedAt: now,
  };
  if (decision === 'TU_CHOI') r.status = 'TU_CHOI';
  else if (slot.round === 1) r.status = 'CHO_DUYET_2';
  else {
    // đủ hai phê duyệt: MOD-08 đưa kỳ sang MO_LAI_HAU_KIEM trong phạm vi đã duyệt (EVT-22)
    r.status = 'DA_DUYET';
    r.periodStatus = 'MO_LAI_HAU_KIEM';
    r.executedAt = now;
  }
  r.rowVersion++;
  return out(r);
}

function cancel(req: MockRequest): MockResponse {
  const r = find(req);
  if (isResponse(r)) return r;
  const pre = ifMatch(r, req);
  if (pre) return pre;
  const reason = String((req.body as { reason?: string } | null)?.reason ?? '').trim();
  if (reason.length < 10 || reason.length > 2000)
    return invalid('reason', 'Lý do huỷ cần từ 10 đến 2.000 ký tự.');
  if (r.requestedBy.id !== ME.id)
    return problem(403, 'Chỉ người tạo yêu cầu mới được huỷ yêu cầu.', { code: 'FORBIDDEN' });
  if (!REOPEN_PENDING_STATUSES.includes(r.status))
    return conflict('OVERSIGHT-409-INVALID-STATE', 'Yêu cầu đã đủ phê duyệt hoặc đã đóng.');
  r.status = 'DA_DONG';
  r.rowVersion++;
  return out(r);
}

export const REOPEN_MOCK: MockModule = {
  name: 'oversight-reopen',
  routes: [
    { method: 'GET', pattern: '/api/v1/oversight/reopen-requests', handle: list },
    { method: 'GET', pattern: '/api/v1/oversight/post-audits', handle: listPostAudits },
    { method: 'POST', pattern: '/api/v1/oversight/reopen-requests', handle: create },
    {
      method: 'GET',
      pattern: '/api/v1/oversight/reopen-requests/:id',
      handle: (req) => {
        const r = find(req);
        return isResponse(r) ? r : out(r);
      },
    },
    { method: 'POST', pattern: '/api/v1/oversight/reopen-requests/:id/simulate', handle: simulate },
    {
      method: 'POST',
      pattern: '/api/v1/oversight/reopen-requests/:id/approvals',
      handle: (req) => decide(req, 'DONG_Y'),
    },
    {
      method: 'POST',
      pattern: '/api/v1/oversight/reopen-requests/:id/reject',
      handle: (req) => decide(req, 'TU_CHOI'),
    },
    { method: 'POST', pattern: '/api/v1/oversight/reopen-requests/:id/cancel', handle: cancel },
  ],
  reset: () => {
    store = reopenFixtures();
    postAudits = postAuditFixtures();
  },
};
