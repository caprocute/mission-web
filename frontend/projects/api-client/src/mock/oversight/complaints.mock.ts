import {
  AppraisalSimulationInput,
  COMPLAINT_LIMITS,
  COMPLAINT_OPEN_STATUSES,
  ComplainableProfile,
  Complaint,
  ComplaintConclusionInput,
  ComplaintInput,
  ComplaintResponseRecord,
  ComplaintStatus,
  ExplanationParty,
} from '../../lib/models/complaint';
import { Job } from '../../lib/models/job';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { mockSession } from '../mock-session';
import { mockPeriodInfo } from '../periods/periods.mock';
import {
  BAN_TO_CHUC,
  COMPLAINT_PEOPLE,
  PROFILE_FACTS,
  complaintFixtures,
  eligibleFixtures,
  responseFixtures,
  workingDate,
} from './complaints.fixture';

/**
 * Giả lập khiếu nại, giải trình của MOD-07 (QT-12, SCR-1201, SCR-1202, WEB-042) theo
 * `contracts/openapi/modules/oversight.yaml`, cùng tính thử `POST /appraisals/profiles/{id}/simulate` của
 * SVC-07. Kiểm: `If-Match` (428, 412), xác thực bước lên khi ký gửi và ký trả lời (401), độ dài và trường
 * bắt buộc (422, `OVERSIGHT-422-NO-TARGET` MSG-12-03, `OVERSIGHT-422-DEADLINE-PASSED` MSG-12-01,
 * `OVERSIGHT-422-CONCLUSION-MISSING`), trạng thái (409), tách vai trò BR-12-04 (403
 * `OVERSIGHT-403-SEPARATION-OF-DUTIES`, MSG-12-05: người dùng thử nghiệm đã tham gia hồ sơ). Người dùng thử
 * nghiệm được coi là người xử lý và người ký của mọi khiếu nại (OPA thật chọn đúng người).
 */

const ME = COMPLAINT_PEOPLE.me;

let store: Complaint[] = complaintFixtures();
let records: ComplaintResponseRecord[] = responseFixtures();
let eligible: ComplainableProfile[] = eligibleFixtures();

const etag = (rowVersion: number) => ({ ETag: `"${rowVersion}"` });

function find(req: MockRequest): Complaint | MockResponse {
  return (
    store.find((c) => c.id === req.pathParams['id']) ?? problem(404, 'Không tìm thấy khiếu nại')
  );
}
// khiếu nại cũng có trường `status` nên phân biệt bằng `rowVersion`
const isResponse = (x: Complaint | MockResponse): x is MockResponse => !('rowVersion' in x);

function ifMatch(c: Complaint, req: MockRequest): MockResponse | null {
  const h = req.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu thông tin phiên bản dữ liệu; vui lòng tải lại trang.');
  if (h.replace(/"/g, '') !== String(c.rowVersion))
    return problem(412, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      code: 'PRECONDITION_FAILED',
    });
  return null;
}

function stepUpRequired(): MockResponse {
  const r = problem(401, 'Cần xác thực bổ sung để ký số.', {
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

const invalid = (field: string, message: string, extra: Record<string, string> = {}) =>
  problem(422, 'Dữ liệu không hợp lệ', {
    code: 'COMMON-422-VALIDATION',
    ...extra,
    errors: [{ field, message }],
  });

const invalidState = (title: string) =>
  problem(409, title, { code: 'OVERSIGHT-409-INVALID-STATE' });

/** Tách vai trò PL 4 V.1 (BR-12-04): người đã tham gia hồ sơ không được xử lý, ký trả lời. */
function separation(c: Complaint): MockResponse | null {
  if (!PROFILE_FACTS[c.profileId]?.participants.includes(ME.id)) return null;
  return problem(403, 'Không thể xử lý khiếu nại này', {
    type: 'https://okrkpi.vn/problems/separation-of-duties',
    code: 'OVERSIGHT-403-SEPARATION-OF-DUTIES',
    msg: 'MSG-12-05',
    detail: 'Đồng chí không thể xử lý khiếu nại này vì đã tham gia thẩm định hồ sơ liên quan.',
  });
}

const out = (c: Complaint) => ok(structuredClone(c), etag(c.rowVersion));

function touch(c: Complaint, action: string, status?: ComplaintStatus) {
  if (status !== undefined) c.status = status;
  c.rowVersion++;
  c.timeline = [...(c.timeline ?? []), { at: new Date().toISOString(), action, actor: ME }];
}

function daysLeft(date: string): number {
  const end = new Date(`${date}T00:00:00`);
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  let n = 0;
  const step = end >= d ? 1 : -1;
  while (d.getTime() !== end.getTime()) {
    d.setDate(d.getDate() + step);
    if (d.getDay() !== 0 && d.getDay() !== 6) n += step;
  }
  return n;
}

function nextCode(): string {
  const year = new Date().getFullYear();
  const nums = store
    .filter((c) => c.code.startsWith(`KN-${year}-`))
    .map((c) => Number(c.code.slice(-4)));
  return `KN-${year}-${String(Math.max(0, ...nums) + 1).padStart(4, '0')}`;
}

const accepted = (job: Job): MockResponse => ({
  status: 202,
  body: job,
  headers: { Location: `/api/v1/jobs/${job.id}`, 'Retry-After': '1' },
});

const doneJob = (type: string, c: Complaint, message: string): Job => ({
  id: crypto.randomUUID(),
  type,
  status: 'HOAN_TAT',
  progress: 100,
  message,
  resultLocation: `/api/v1/oversight/complaints/${c.id}`,
  updatedAt: new Date().toISOString(),
});

// ───────────── Người khiếu nại (SCR-1201) ─────────────

function listEligible(): MockResponse {
  for (const e of eligible) e.workingDaysLeft = daysLeft(e.complaintDeadline);
  return ok({ items: structuredClone(eligible) });
}

function list(req: MockRequest): MockResponse {
  const periodId = req.params.get('period');
  const status = req.params.get('status')?.split(',').filter(Boolean);
  const kind = req.params.get('kind');
  const mine = req.params.get('assignedToMe') === 'true';
  const q = req.params.get('q')?.toLowerCase();
  const items = store
    .filter((c) => !periodId || c.period.id === periodId)
    .filter((c) => !status?.length || (c.status && status.includes(c.status)))
    .filter((c) => !kind || c.kind === kind)
    .filter(
      (c) =>
        !mine ||
        (c.status &&
          !['DONG', 'RUT', 'KHONG_THU_LY'].includes(c.status) &&
          (c.handler?.id === ME.id || assignedParty(c))),
    )
    .filter(
      (c) =>
        !q || c.code.toLowerCase().includes(q) || c.complainant?.name.toLowerCase().includes(q),
    )
    // khiếu nại chưa gửi chỉ chủ hồ sơ thấy
    .filter((c) => c.status !== null || c.complainant?.id === ME.id)
    .map((c) => ({
      ...c,
      workingDaysLeft:
        c.responseDeadline &&
        c.status &&
        !['DONG', 'RUT', 'KHONG_THU_LY', 'DA_TRA_LOI', 'DANG_THI_HANH'].includes(c.status)
          ? daysLeft(c.responseDeadline)
          : null,
    }))
    .sort((a, b) => (a.responseDeadline ?? '9999').localeCompare(b.responseDeadline ?? '9999'));
  return ok({
    items: items.map((c) => ({
      id: c.id,
      code: c.code,
      complainant: c.complainant,
      subjectLine: c.subjectLine,
      kind: c.kind,
      targetStage: c.targetStage,
      status: c.status,
      filedAt: c.filedAt,
      responseDeadline: c.responseDeadline,
      workingDaysLeft: c.workingDaysLeft,
    })),
    nextCursor: null,
  });
}

/** Người dùng thử nghiệm là bên được yêu cầu giải trình còn chưa trả lời. */
const assignedParty = (c: Complaint) =>
  records.some(
    (r) =>
      r.complaintId === c.id &&
      r.recordKind === 'YEU_CAU' &&
      r.party?.id === ME.id &&
      !records.some((g) => g.requestId === r.id),
  );

function get(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  return out({
    ...c,
    workingDaysLeft: c.responseDeadline && c.status ? daysLeft(c.responseDeadline) : null,
  });
}

function validateInput(b: Partial<ComplaintInput>): MockResponse | ComplainableProfile {
  const e = eligible.find((x) => x.profileId === b.profileId);
  if (!e) return invalid('profileId', 'Chọn kết quả còn trong thời hạn khiếu nại.');
  if (!b.kind) return invalid('kind', 'Chọn hình thức.');
  if (!e.allowedKinds.includes(b.kind))
    return problem(422, 'Đã quá thời hạn khiếu nại', {
      code: 'OVERSIGHT-422-DEADLINE-PASSED',
      msg: 'MSG-12-01',
      detail: `Đã quá thời hạn khiếu nại (${e.complaintDeadline}). Đồng chí vẫn có thể gửi ý kiến dưới hình thức bảo lưu ý kiến.`,
      errors: [{ field: 'kind', message: 'Quá hạn chỉ còn hình thức bảo lưu ý kiến.' }],
    });
  if ((b.content ?? '').length > COMPLAINT_LIMITS.contentMax)
    return invalid('content', 'Lý do, căn cứ tối đa 8.000 ký tự.');
  if ((b.requestText ?? '').length > COMPLAINT_LIMITS.requestMax)
    return invalid('requestText', 'Đề nghị cụ thể tối đa 500 ký tự.');
  return e;
}

function applyInput(c: Complaint, b: ComplaintInput, e: ComplainableProfile) {
  c.profileId = b.profileId;
  c.period = e.period;
  c.kind = b.kind;
  c.targetStage = b.targetStage;
  c.targets = structuredClone(b.targets ?? []);
  c.subjectLine = b.subjectLine || `Khiếu nại kết quả xếp loại ${e.period.label}`;
  c.content = b.content ?? '';
  c.requestText = b.requestText ?? '';
  c.attachmentObjectIds = [...(b.attachmentObjectIds ?? [])];
  c.reserveOpinion = !!b.reserveOpinion;
}

function create(req: MockRequest): MockResponse {
  const b = (req.body ?? {}) as ComplaintInput;
  const e = validateInput(b);
  if ('status' in e) return e;
  const c: Complaint = {
    id: crypto.randomUUID(),
    code: nextCode(),
    complainant: ME,
    subjectLine: '',
    kind: b.kind,
    targetStage: b.targetStage,
    status: null,
    filedAt: null,
    responseDeadline: null,
    workingDaysLeft: null,
    profileId: b.profileId,
    period: e.period,
    outcome: null,
    conclusion: null,
    reopenRequestId: null,
    timeline: [],
    rowVersion: 1,
  };
  applyInput(c, b, e);
  store.unshift(c);
  return {
    status: 201,
    body: structuredClone(c),
    headers: { ...etag(1), Location: `/api/v1/oversight/complaints/${c.id}` },
  };
}

function update(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  const pre = ifMatch(c, req);
  if (pre) return pre;
  if (c.status !== null) return invalidState('Khiếu nại đã gửi, không sửa được.');
  const b = (req.body ?? {}) as ComplaintInput;
  const e = validateInput(b);
  if ('status' in e) return e;
  applyInput(c, b, e);
  c.rowVersion++;
  return out(c);
}

function submit(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  const pre = ifMatch(c, req);
  if (pre) return pre;
  if (!mockSession.recentMfa()) return stepUpRequired();
  if (c.status !== null) return invalidState('Khiếu nại đã được gửi.');
  const e = eligible.find((x) => x.profileId === c.profileId);
  if (!e || !e.allowedKinds.includes(c.kind))
    return problem(422, 'Đã quá thời hạn khiếu nại', {
      code: 'OVERSIGHT-422-DEADLINE-PASSED',
      msg: 'MSG-12-01',
      detail: `Đã quá thời hạn khiếu nại (${e?.complaintDeadline ?? ''}). Đồng chí vẫn có thể gửi ý kiến dưới hình thức bảo lưu ý kiến.`,
    });
  if (!c.targets?.length)
    return problem(422, 'Dữ liệu chưa hợp lệ', {
      code: 'OVERSIGHT-422-NO-TARGET',
      msg: 'MSG-12-03',
      errors: [
        {
          field: 'targets',
          message: 'Vui lòng chọn ít nhất một cấu phần bị khiếu nại.',
          rule: 'BR-12-02',
        },
      ],
    });
  const len = (c.content ?? '').trim().length;
  if (len < COMPLAINT_LIMITS.contentMin)
    return invalid('content', 'Lý do, căn cứ cần từ 50 đến 8.000 ký tự.');
  if (!(c.requestText ?? '').trim()) return invalid('requestText', 'Nêu đề nghị cụ thể.');
  c.filedAt = new Date().toISOString();
  c.responseDeadline = workingDate(15);
  c.handlerUnit = BAN_TO_CHUC;
  c.handler = COMPLAINT_PEOPLE.ha;
  touch(c, 'Gửi khiếu nại', 'MOI');
  return accepted(doneJob('COMPLAINT_SIGN', c, 'Đã ký số và gửi khiếu nại'));
}

function withdraw(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  const pre = ifMatch(c, req);
  if (pre) return pre;
  if (c.complainant?.id !== ME.id) return problem(403, 'Chỉ người khiếu nại được rút khiếu nại.');
  if (!c.status || !COMPLAINT_OPEN_STATUSES.includes(c.status))
    return invalidState('Chỉ rút được khiếu nại đã gửi và chưa có kết luận.');
  const reason = String((req.body as { reason?: string })?.reason ?? '').trim();
  if (reason.length < COMPLAINT_LIMITS.withdrawReasonMin)
    return invalid('reason', 'Lý do rút cần từ 10 đến 2.000 ký tự.');
  touch(c, `Rút khiếu nại: ${reason}`, 'RUT');
  return out(c);
}

function reserve(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  const pre = ifMatch(c, req);
  if (pre) return pre;
  if (c.complainant?.id !== ME.id) return problem(403, 'Chỉ người khiếu nại được bảo lưu ý kiến.');
  if (c.status !== 'DA_TRA_LOI' || c.reservedOpinion)
    return invalidState('Chỉ bảo lưu ý kiến một lần, sau khi có văn bản trả lời.');
  const opinion = String((req.body as { opinion?: string })?.opinion ?? '').trim();
  if (!opinion || opinion.length > COMPLAINT_LIMITS.contentMax)
    return invalid('opinion', 'Nêu ý kiến bảo lưu, tối đa 8.000 ký tự.');
  c.reservedOpinion = opinion;
  c.outcome = 'BAO_LUU';
  records.push({
    id: crypto.randomUUID(),
    complaintId: c.id,
    recordKind: 'BAO_LUU',
    responder: ME,
    content: opinion,
    respondedAt: new Date().toISOString(),
  });
  touch(c, 'Xác nhận bảo lưu ý kiến', 'DONG');
  return out(c);
}

// ───────────── Người xử lý, bên liên quan, người ký (SCR-1202) ─────────────

function handlerGuard(
  c: Complaint,
  req: MockRequest,
  allowed: ComplaintStatus[],
): MockResponse | null {
  return (
    ifMatch(c, req) ??
    separation(c) ??
    (c.status && allowed.includes(c.status)
      ? null
      : invalidState('Khiếu nại không ở trạng thái cho phép thao tác này.'))
  );
}

function accept(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  const g = handlerGuard(c, req, ['MOI']);
  if (g) return g;
  const note = String((req.body as { note?: string })?.note ?? '').trim();
  touch(c, note ? `Tiếp nhận: ${note}` : 'Tiếp nhận', 'DA_TIEP_NHAN');
  return out(c);
}

function reject(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  const g = handlerGuard(c, req, ['MOI']);
  if (g) return g;
  const b = (req.body ?? {}) as { reasonCode?: string; reason?: string };
  if (!b.reasonCode) return invalid('reasonCode', 'Chọn lý do không thụ lý.');
  const reason = (b.reason ?? '').trim();
  if (reason.length < COMPLAINT_LIMITS.rejectReasonMin)
    return invalid('reason', 'Lý do không thụ lý cần từ 20 đến 2.000 ký tự.');
  touch(c, `Không thụ lý: ${reason}`, 'KHONG_THU_LY');
  return out(c);
}

function requestExplanations(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  const g = handlerGuard(c, req, ['DA_TIEP_NHAN', 'DANG_GIAI_TRINH']);
  if (g) return g;
  const b = (req.body ?? {}) as {
    parties?: ExplanationParty[];
    content?: string;
    dueDate?: string;
  };
  if (!b.parties?.length)
    return invalid('parties', 'Chọn ít nhất một bên được yêu cầu giải trình.');
  const content = (b.content ?? '').trim();
  if (!content) return invalid('content', 'Nêu nội dung yêu cầu giải trình.');
  const due = b.dueDate || workingDate(3);
  for (const party of b.parties)
    records.push({
      id: crypto.randomUUID(),
      complaintId: c.id,
      recordKind: 'YEU_CAU',
      responder: ME,
      party: structuredClone(party),
      dueDate: due,
      content,
      respondedAt: new Date().toISOString(),
    });
  touch(c, 'Yêu cầu giải trình', 'DANG_GIAI_TRINH');
  return out(c);
}

function submitExplanation(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  const pre = ifMatch(c, req);
  if (pre) return pre;
  if (c.status !== 'DANG_GIAI_TRINH') return invalidState('Khiếu nại không còn chờ giải trình.');
  const b = (req.body ?? {}) as {
    requestId?: string;
    content?: string;
    attachmentObjectIds?: string[];
  };
  const ask = records.find(
    (r) => r.id === b.requestId && r.complaintId === c.id && r.recordKind === 'YEU_CAU',
  );
  if (!ask) return invalid('requestId', 'Không tìm thấy yêu cầu giải trình.');
  if (records.some((r) => r.requestId === ask.id))
    return invalidState('Yêu cầu này đã được giải trình.');
  const content = (b.content ?? '').trim();
  if (!content) return invalid('content', 'Nêu nội dung giải trình.');
  const rec: ComplaintResponseRecord = {
    id: crypto.randomUUID(),
    complaintId: c.id,
    recordKind: 'GIAI_TRINH',
    responder: ME,
    party: ask.party,
    requestId: ask.id,
    content,
    attachmentObjectIds: [...(b.attachmentObjectIds ?? [])],
    respondedAt: new Date().toISOString(),
  };
  records.push(rec);
  const open = records.filter(
    (r) =>
      r.complaintId === c.id &&
      r.recordKind === 'YEU_CAU' &&
      !records.some((g) => g.requestId === r.id),
  );
  touch(c, 'Đã nhận giải trình', open.length ? undefined : 'CHO_KET_LUAN');
  return { status: 201, body: structuredClone(rec) };
}

function listResponses(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  const kind = req.params.get('recordKind');
  const items = records
    .filter((r) => r.complaintId === c.id && (!kind || r.recordKind === kind))
    .sort((a, b) => a.respondedAt.localeCompare(b.respondedAt));
  return ok({ items: structuredClone(items), nextCursor: null });
}

function saveConclusion(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  const g = handlerGuard(c, req, ['DA_TIEP_NHAN', 'DANG_GIAI_TRINH', 'CHO_KET_LUAN']);
  if (g) return g;
  const b = (req.body ?? {}) as Partial<ComplaintConclusionInput>;
  if (!b.outcome) return invalid('outcome', 'Chọn kết quả xử lý.');
  if (b.outcome !== 'GIU_NGUYEN' && !b.adjustments?.length)
    return invalid('adjustments', 'Kết luận chấp nhận cần ít nhất một điều chỉnh đề nghị.');
  for (const a of b.adjustments ?? []) {
    const v = Number(a.newValue);
    if (/\.(B|C)$/.test(a.targetKey) && (!Number.isFinite(v) || v < 0 || v > 1))
      return invalid('adjustments', `Giá trị mới của ${a.targetKey} phải trong khoảng 0 đến 1.`);
    if (!(a.reason ?? '').trim())
      return invalid('adjustments', `Nêu lý do điều chỉnh ${a.targetKey}.`);
  }
  if (!(b.responseText ?? '').trim())
    return invalid('responseText', 'Nêu nội dung văn bản trả lời.');
  if (c.status === 'DA_TIEP_NHAN' && !(b.noExplanationNote ?? '').trim())
    return invalid('noExplanationNote', 'Kết luận không qua giải trình cần ghi chú lý do.');
  const locked = mockPeriodInfo(c.period.id)?.status === 'DA_KHOA';
  c.conclusion = {
    outcome: b.outcome,
    adjustments: b.outcome === 'GIU_NGUYEN' ? [] : structuredClone(b.adjustments ?? []),
    responseDocRef: b.responseDocRef ?? null,
    signRequestId: null,
    periodLocked: locked,
  };
  c.outcome = b.outcome === 'GIU_NGUYEN' ? 'GIU_NGUYEN' : 'DIEU_CHINH';
  const existing = records.find((r) => r.complaintId === c.id && r.recordKind === 'KET_LUAN');
  const rec: ComplaintResponseRecord = {
    id: existing?.id ?? crypto.randomUUID(),
    complaintId: c.id,
    recordKind: 'KET_LUAN',
    responder: ME,
    outcome: b.outcome,
    adjustments: c.conclusion.adjustments,
    content: b.responseText!.trim(),
    responseDocRef: b.responseDocRef ?? null,
    respondedAt: new Date().toISOString(),
  };
  records = [...records.filter((r) => r.id !== rec.id), rec];
  touch(c, existing ? 'Sửa kết luận' : 'Ghi kết luận', 'CHO_KET_LUAN');
  return out(c);
}

function respond(req: MockRequest): MockResponse {
  const c = find(req);
  if (isResponse(c)) return c;
  const pre = ifMatch(c, req);
  if (pre) return pre;
  if (!mockSession.recentMfa()) return stepUpRequired();
  const sep = separation(c);
  if (sep) return sep;
  if (c.status !== 'CHO_KET_LUAN') return invalidState('Khiếu nại chưa ở bước chờ ký trả lời.');
  if (!c.conclusion?.outcome || !c.conclusion.responseDocRef)
    return problem(422, 'Chưa có kết luận hoặc văn bản trả lời', {
      code: 'OVERSIGHT-422-CONCLUSION-MISSING',
    });
  c.conclusion.signRequestId = crypto.randomUUID();
  const adjusts = c.conclusion.outcome !== 'GIU_NGUYEN';
  touch(
    c,
    `Ký văn bản trả lời ${c.conclusion.responseDocRef}`,
    adjusts && c.reopenRequestId ? 'DANG_THI_HANH' : 'DA_TRA_LOI',
  );
  return accepted(doneJob('COMPLAINT_RESPOND', c, 'Đã ký số và gửi văn bản trả lời'));
}

/** SVC-07 chế độ mô phỏng: mỗi 0,01 hệ số B hoặc C đổi 0,06 điểm (giả lập, không phải công thức thật). */
function simulate(req: MockRequest): MockResponse {
  const id = req.pathParams['id'];
  const facts = PROFILE_FACTS[id];
  if (!facts) return problem(404, 'Không tìm thấy hồ sơ đánh giá');
  const b = (req.body ?? {}) as Partial<AppraisalSimulationInput>;
  if (!b.adjustments?.length)
    return invalid('adjustments', 'Nêu ít nhất một điều chỉnh để tính thử.');
  const delta = b.adjustments.reduce(
    (s, a) => s + (Number(a.newValue) - Number(a.oldValue ?? a.newValue)) * 6,
    0,
  );
  const total = Math.round((facts.score + delta) * 10) / 10;
  const rating = total >= 95 ? 'HTXSNV' : total >= 80 ? 'HTTNV' : total >= 50 ? 'HTNV' : 'KHTNV';
  return ok({
    policyVersion: 'Đảng v2.0',
    before: { totalScore: facts.score, ratingCode: facts.rating },
    after: { totalScore: total, ratingCode: rating },
    explanation:
      rating === facts.rating
        ? 'Sau điều chỉnh, mức xếp loại không đổi.'
        : 'Sau điều chỉnh, mức xếp loại thay đổi; kiểm tra lại tỷ lệ mức trong đơn vị.',
  });
}

/** Giả lập QT-13 gọi khi tạo yêu cầu mở lại kỳ có căn cứ là khiếu nại (bước 9). */
export function linkComplaintReopen(complaintId: string, reopenRequestId: string) {
  const c = store.find((x) => x.id === complaintId);
  if (!c) return;
  c.reopenRequestId = reopenRequestId;
  if (c.status === 'DA_TRA_LOI' && c.outcome === 'DIEU_CHINH') c.status = 'DANG_THI_HANH';
  c.rowVersion++;
  c.timeline = [
    ...(c.timeline ?? []),
    { at: new Date().toISOString(), action: 'Tạo yêu cầu mở lại kỳ', actor: ME },
  ];
}

const B = '/api/v1/oversight/complaints';

export const COMPLAINTS_MOCK: MockModule = {
  name: 'oversight-complaints',
  routes: [
    { method: 'GET', pattern: `${B}/eligible-profiles`, handle: listEligible },
    { method: 'GET', pattern: B, handle: list },
    { method: 'POST', pattern: B, handle: create },
    { method: 'GET', pattern: `${B}/:id`, handle: get },
    { method: 'PUT', pattern: `${B}/:id`, handle: update },
    { method: 'POST', pattern: `${B}/:id/submit`, handle: submit },
    { method: 'POST', pattern: `${B}/:id/withdraw`, handle: withdraw },
    { method: 'POST', pattern: `${B}/:id/reserve`, handle: reserve },
    { method: 'POST', pattern: `${B}/:id/accept`, handle: accept },
    { method: 'POST', pattern: `${B}/:id/reject`, handle: reject },
    { method: 'POST', pattern: `${B}/:id/explanation-requests`, handle: requestExplanations },
    { method: 'POST', pattern: `${B}/:id/explanations`, handle: submitExplanation },
    { method: 'GET', pattern: `${B}/:id/responses`, handle: listResponses },
    { method: 'PUT', pattern: `${B}/:id/conclusion`, handle: saveConclusion },
    { method: 'POST', pattern: `${B}/:id/respond`, handle: respond },
    { method: 'POST', pattern: '/api/v1/appraisals/profiles/:id/simulate', handle: simulate },
  ],
  reset: () => {
    store = complaintFixtures();
    records = responseFixtures();
    eligible = eligibleFixtures();
  },
};
