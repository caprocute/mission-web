import { Job } from '../../lib/models/job';
import {
  Period,
  PeriodCreate,
  PeriodLockOrder,
  PeriodLockOrderCreate,
  PeriodLockProgress,
  PeriodLockStep,
  SAGA_ACTIVE_STATUSES,
} from '../../lib/models/period';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { MOCK_ME, mockSession } from '../mock-session';
import {
  PeriodFixture,
  SAGA_TEMPLATE,
  SECOND_APPROVER,
  STEP_ACTIVITY_IDS,
  STEP_DEFS,
  SagaFixture,
  periodFixtures,
} from './periods.fixture';

/**
 * Giả lập MOD-08 kỳ đánh giá và saga khoá kỳ `chot-ky` của SVC-06 (QT-08, SCR-0801, WEB-034) theo
 * `contracts/openapi/modules/config.yaml`. Saga tiến một nhịp mỗi lần đọc tiến trình (thay cho SSE):
 * bước 2 tính thêm khoảng một phần mười số hồ sơ mỗi nhịp và chỉ xong khi hàng đợi lỗi rỗng, các bước
 * 3–5 mỗi bước một nhịp; cờ tạm dừng làm saga dừng ở `CHO_SU_KIEN` khi bước hiện tại xong; bù trừ chạy
 * ngược mỗi nhịp một bước. Kiểm BR-08-01 (403 `PERIOD-403-SAME-PERSON`), BR-08-02 (401 step-up),
 * BR-08-03 ở bước 0 (job `THAT_BAI`, `PERIOD-422-PRECONDITION`), BR-08-08 (một saga mỗi kỳ), BR-08-09.
 */

const ME = { id: MOCK_ME.id, name: MOCK_ME.name };

let store: PeriodFixture[] = periodFixtures();

const ensure = () => store;

function find(req: MockRequest): PeriodFixture | MockResponse {
  const hit = ensure().find((p) => p.period.id === req.pathParams['id']);
  return hit ?? problem(404, 'Không tìm thấy kỳ đánh giá');
}

const isResponse = (x: PeriodFixture | MockResponse): x is MockResponse => 'status' in x;

const etag = (rowVersion: number) => ({ ETag: `"${rowVersion}"` });

const nowIso = () => new Date().toISOString();

function periodOut(f: PeriodFixture): Period {
  return structuredClone({ ...f.period, currentLockOrder: f.order });
}

function ifMatch(order: PeriodLockOrder, req: MockRequest): MockResponse | null {
  const h = req.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu If-Match');
  if (h.replace(/"/g, '') !== String(order.rowVersion))
    return problem(412, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      code: 'PRECONDITION_FAILED',
    });
  return null;
}

function stepUpRequired(action: string): MockResponse {
  const r = problem(401, `Cần xác thực bước lên trước khi ${action}`, {
    type: 'https://datatracker.ietf.org/doc/html/rfc9470',
    code: 'insufficient_user_authentication',
  });
  r.headers = {
    ...r.headers,
    'WWW-Authenticate':
      'Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=900',
  };
  return r;
}

const samePerson = (detail: string) =>
  problem(403, 'Không được thực hiện vì trùng người', { code: 'PERIOD-403-SAME-PERSON', detail });

function log(
  s: SagaFixture,
  event: string,
  evt: string | null = null,
  actor = null as null | typeof ME,
) {
  s.events.unshift({
    at: nowIso(),
    event,
    evt,
    actor,
    ledgerEntryId: crypto.randomUUID(),
  });
}

const sagaActive = (f: PeriodFixture) => !!f.saga && SAGA_ACTIVE_STATUSES.includes(f.saga.status);

function progressOut(f: PeriodFixture): PeriodLockProgress {
  const s = f.saga!;
  const step4Done = s.steps[4].status === 'DAT' || s.steps[4].status === 'DAT_CO_CANH_BAO';
  const slow =
    s.estimatedFinishAt && s.budgetFinishAt && s.estimatedFinishAt > s.budgetFinishAt
      ? [`Bước ${currentStep(s)?.stepNo ?? 2} đang chậm hơn ngân sách.`]
      : [];
  return structuredClone({
    periodId: f.period.id,
    lockOrder: f.order!,
    sagaStatus: s.status,
    templateKey: SAGA_TEMPLATE.key,
    templateVersion: SAGA_TEMPLATE.version,
    pauseRequested: s.pauseRequested,
    totals: s.totals,
    ratePerMinute: s.ratePerMinute,
    estimatedFinishAt: s.estimatedFinishAt,
    budgetFinishAt: s.budgetFinishAt,
    excludedEvidenceCount: s.excludedEvidenceCount,
    steps: s.steps,
    cancelRequest: s.cancelRequest
      ? { ...s.cancelRequest, approvedBy: null, approvedAt: null }
      : null,
    cancellable:
      SAGA_ACTIVE_STATUSES.includes(s.status) && s.status !== 'DANG_BU_TRU' && !step4Done,
    warnings: slow,
    updatedAt: nowIso(),
  });
}

const currentStep = (s: SagaFixture): PeriodLockStep | undefined =>
  s.steps.find((x) => x.status === 'DANG_CHAY');

/** Một nhịp của saga, gọi mỗi lần đọc tiến trình. */
function tick(f: PeriodFixture): void {
  const s = f.saga;
  if (!s) return;
  if (s.status === 'DANG_BU_TRU') return compensateOne(f);
  if (s.status !== 'DANG_CHAY') return;
  const cur = currentStep(s);
  if (!cur) return;
  const at = nowIso();
  if (cur.stepNo === 2) {
    const { profiles, failed } = s.totals;
    const left = profiles - failed - s.totals.scored;
    const chunk = Math.min(left, Math.ceil(profiles / 10));
    s.totals.scored += chunk;
    for (const b of s.batches
      .filter((x) => x.status === 'DANG_CHAY' || x.status === 'CHO')
      .slice(0, 4)) {
      b.status = 'XONG';
      b.attempts = Math.max(1, b.attempts);
      b.finishedAt = at;
    }
    if (s.totals.scored + failed < profiles) return;
    if (failed > 0) {
      cur.resultSummary = { note: `Chờ xử lý ${failed} hồ sơ lỗi trước khi hoàn tất bước 2` };
      return;
    }
    cur.resultSummary = {
      note: `${profiles.toLocaleString('vi-VN')} hồ sơ đã tính, không còn hồ sơ lỗi`,
    };
    log(s, 'Bước 2 hoàn tất, phát DiemDaTinh.', 'EVT-10');
  } else if (cur.stepNo === 3) {
    cur.resultSummary = { note: 'Đã sinh biểu mẫu và bảng chấm điểm theo đơn vị' };
    log(s, 'Bước 3 hoàn tất, phát HoSoSoDaSinh.', 'EVT-17');
  } else if (cur.stepNo === 4) {
    cur.resultSummary = { note: 'Đã ký số niêm phong các bảng tổng hợp bằng chứng thư tổ chức' };
    log(s, 'Bước 4 hoàn tất, phát KySoDaHoanTat.', 'EVT-18');
  } else if (cur.stepNo === 5) {
    cur.resultSummary = { note: 'Kỳ chuyển DA_KHOA, sổ cái đã niêm phong' };
  }
  cur.status = 'DAT';
  cur.finishedAt = at;
  if (cur.stepNo === 5) {
    s.status = 'HOAN_TAT';
    s.estimatedFinishAt = at;
    f.period.status = 'DA_KHOA';
    f.period.lockedAt = at;
    f.period.lockedBy = f.order!.startedBy ?? null;
    f.period.rowVersion++;
    f.order!.status = 'HOAN_TAT';
    f.order!.rowVersion++;
    log(s, `Kỳ ${f.period.name} chuyển DA_KHOA, sổ cái đã niêm phong.`, 'EVT-19');
    return;
  }
  if (s.pauseRequested) {
    s.status = 'CHO_SU_KIEN';
    log(s, `Bước ${cur.stepNo} hoàn tất; saga tạm dừng theo cờ tạm dừng.`);
    return;
  }
  startStep(s, cur.stepNo + 1);
}

function startStep(s: SagaFixture, stepNo: number) {
  const next = s.steps[stepNo];
  next.status = 'DANG_CHAY';
  next.startedAt = nowIso();
}

/** Bù trừ theo thứ tự ngược từ bước cao nhất đã chạy (TL-02 QT-08, bảng bù trừ). */
function compensateOne(f: PeriodFixture): void {
  const s = f.saga!;
  const at = nowIso();
  const target = [...s.steps]
    .reverse()
    .find(
      (x) =>
        STEP_DEFS[x.stepNo].compensation &&
        ['DANG_CHAY', 'DAT', 'DAT_CO_CANH_BAO'].includes(x.status),
    );
  if (target) {
    target.status = 'DA_BU_TRU';
    target.compensatedAt = at;
    target.finishedAt ??= at;
    log(s, `Bù trừ bước ${target.stepNo}: ${STEP_DEFS[target.stepNo].compensation}.`);
    if (target.stepNo !== 1) return;
  }
  s.status = 'DA_BU_TRU';
  f.period.status = 'MO';
  f.period.lockSagaId = null;
  f.period.rowVersion++;
  f.order!.status = 'DA_HUY';
  f.order!.rowVersion++;
  log(s, `Saga đã bù trừ toàn bộ. Kỳ ${f.period.name} trở về trạng thái mở.`);
}

function job(type: string, status: Job['status'], extra: Partial<Job> = {}): MockResponse {
  const id = crypto.randomUUID();
  const body: Job = { id, type, status, progress: 0, updatedAt: nowIso(), ...extra };
  return { status: 202, body, headers: { Location: `/api/v1/jobs/${id}`, 'Retry-After': '1' } };
}

function list(req: MockRequest): MockResponse {
  const kind = req.params.get('kind');
  const flow = req.params.get('flow');
  const status = req.params.get('status');
  const items = ensure()
    .filter(
      (f) =>
        (!kind || f.period.kind === kind) &&
        (!flow || f.period.flow === flow) &&
        (!status || f.period.status === status),
    )
    .sort(
      (a, b) =>
        b.period.endDate.localeCompare(a.period.endDate) ||
        b.period.startDate.localeCompare(a.period.startDate),
    )
    .map(periodOut);
  return ok({ items, nextCursor: null });
}

function create(req: MockRequest): MockResponse {
  const body = req.body as PeriodCreate;
  const errors: { field: string; message: string }[] = [];
  if (!body?.name?.trim()) errors.push({ field: 'name', message: 'Nhập tên kỳ' });
  if (!body?.code?.trim()) errors.push({ field: 'code', message: 'Nhập mã kỳ' });
  if (!body?.startDate) errors.push({ field: 'startDate', message: 'Chọn ngày bắt đầu' });
  if (!body?.endDate) errors.push({ field: 'endDate', message: 'Chọn ngày kết thúc' });
  if (body?.startDate && body?.endDate && body.endDate < body.startDate)
    errors.push({ field: 'endDate', message: 'Ngày kết thúc phải sau ngày bắt đầu' });
  if (errors.length) return problem(422, 'Dữ liệu không hợp lệ', { errors });
  const overlap = ensure().find(
    (f) =>
      f.period.kind === body.kind &&
      (f.period.flow === body.flow || f.period.flow === 'CA_HAI' || body.flow === 'CA_HAI') &&
      f.period.startDate <= body.endDate &&
      body.startDate <= f.period.endDate,
  );
  if (overlap)
    return problem(409, 'Kỳ chồng lấn với kỳ cùng loại đã có', {
      code: 'PERIOD-409-OVERLAP',
      detail: `Trùng thời gian với ${overlap.period.name}.`,
    });
  const lock = body.milestones?.plannedLockDate;
  const end = new Date(body.endDate);
  const limit = new Date(end.getFullYear(), end.getMonth() + 1, 10).toISOString().slice(0, 10);
  const period: Period = {
    id: crypto.randomUUID(),
    code: body.code.trim(),
    name: body.name.trim(),
    kind: body.kind,
    flow: body.flow,
    parentPeriodId: body.parentPeriodId ?? null,
    startDate: body.startDate,
    endDate: body.endDate,
    planDeadline: body.planDeadline ?? null,
    submissionDeadline: body.submissionDeadline ?? null,
    status: 'MO',
    notStarted: body.startDate > nowIso().slice(0, 10),
    reopenCount: 0,
    milestones: body.milestones ?? {},
    pinnedPolicyPacks: structuredClone(ensure()[0].period.pinnedPolicyPacks ?? []),
    warnings:
      lock && lock > limit
        ? ['Ngày khoá dự kiến muộn hơn tham số của Policy Pack (ngày 10 tháng sau).']
        : [],
    rowVersion: 1,
  };
  store.push({ period, order: null, saga: null });
  return { status: 201, body: periodOut(store[store.length - 1]), headers: etag(1) };
}

function createOrder(req: MockRequest): MockResponse {
  const f = find(req);
  if (isResponse(f)) return f;
  if (f.period.status !== 'MO' && f.period.status !== 'MO_LAI_HAU_KIEM')
    return problem(409, `Kỳ ${f.period.name} không ở trạng thái mở`, { code: 'PERIOD-409-STATUS' });
  if (f.order && f.order.status !== 'DA_HUY')
    return problem(409, 'Kỳ đã có lệnh khoá kỳ còn hiệu lực', { code: 'PERIOD-409-ORDER-EXISTS' });
  const body = req.body as PeriodLockOrderCreate;
  if (!body?.basisDoc?.trim())
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'basisDoc', message: 'Nhập văn bản căn cứ' }],
    });
  const year = f.period.code.slice(0, 4);
  f.order = {
    id: crypto.randomUUID(),
    periodId: f.period.id,
    status: 'CHO_DUYET',
    basisDoc: body.basisDoc.trim(),
    plannedLockAt: body.plannedLockAt ?? null,
    requestedBy: ME,
    requestedAt: nowIso(),
    approvedBy: null,
    approvedAt: null,
    startedBy: null,
    startedAt: null,
    sagaId: null,
    idempotencyKey: `CK-${year}${f.period.code.slice(5)}`,
    rowVersion: 1,
  };
  f.saga = null;
  return { status: 201, body: structuredClone(f.order), headers: etag(1) };
}

function approveOrder(req: MockRequest): MockResponse {
  const f = find(req);
  if (isResponse(f)) return f;
  const o = f.order;
  if (!o || o.id !== req.pathParams['oid']) return problem(404, 'Không tìm thấy lệnh khoá kỳ');
  if (o.status !== 'CHO_DUYET')
    return problem(409, 'Lệnh khoá kỳ không ở trạng thái chờ phê duyệt', {
      code: 'PERIOD-409-STATUS',
    });
  const conflict = ifMatch(o, req);
  if (conflict) return conflict;
  if (o.requestedBy.id === ME.id)
    return samePerson('BR-08-01: người phê duyệt lệnh phải khác người lập lệnh.');
  if (!mockSession.recentMfa()) return stepUpRequired('phê duyệt lệnh khoá kỳ');
  o.status = 'DA_DUYET';
  o.approvedBy = ME;
  o.approvedAt = nowIso();
  o.rowVersion++;
  return ok(structuredClone(o), etag(o.rowVersion));
}

function progress(req: MockRequest): MockResponse {
  const f = find(req);
  if (isResponse(f)) return f;
  if (!f.saga || !f.order) return problem(404, 'Kỳ chưa có saga khoá kỳ');
  tick(f);
  return ok(progressOut(f), etag(f.order.rowVersion));
}

function start(req: MockRequest): MockResponse {
  const f = find(req);
  if (isResponse(f)) return f;
  const o = f.order;
  const body = req.body as { lockOrderId?: string };
  if (!o || o.id !== body?.lockOrderId) return problem(404, 'Không tìm thấy lệnh khoá kỳ');
  if (sagaActive(f)) return job('PERIOD_LOCK', 'DANG_CHAY', { message: 'Saga đang chạy' });
  if (o.status !== 'DA_DUYET')
    return problem(409, 'Lệnh khoá kỳ chưa được phê duyệt', { code: 'PERIOD-409-STATUS' });
  const conflict = ifMatch(o, req);
  if (conflict) return conflict;
  if (o.approvedBy?.id === ME.id)
    return samePerson('BR-08-01: người khởi chạy phải khác người phê duyệt lệnh.');
  if (!mockSession.recentMfa()) return stepUpRequired('khởi chạy saga khoá kỳ');
  const backlog = f.period.milestones?.acceptanceBacklogDeadline;
  const today = nowIso().slice(0, 10);
  if (backlog && backlog >= today) {
    const [y, m, d] = backlog.split('-');
    return job('PERIOD_LOCK', 'THAT_BAI', {
      message: 'Bước 0 không đạt',
      problem: {
        type: 'about:blank',
        title: 'Chưa thể khởi chạy',
        status: 422,
        code: 'PERIOD-422-PRECONDITION',
        detail: `Chưa thể khởi chạy: chưa qua hạn nghiệm thu tồn đọng (${d}/${m}/${y}).`,
      },
    });
  }
  const at = nowIso();
  o.status = 'DANG_CHAY';
  o.startedBy = ME;
  o.startedAt = at;
  o.sagaId = crypto.randomUUID();
  o.rowVersion++;
  f.period.status = 'DANG_KHOA';
  f.period.lockSagaId = o.sagaId;
  f.period.rowVersion++;
  const profiles = 4812;
  const s: SagaFixture = {
    status: 'DANG_CHAY',
    pauseRequested: false,
    totals: { profiles, scored: 0, failed: 0 },
    ratePerMinute: 1200,
    estimatedFinishAt: new Date(Date.now() + 25 * 60_000).toISOString(),
    budgetFinishAt: new Date(Date.now() + 4 * 3600_000).toISOString(),
    excludedEvidenceCount: 9,
    steps: STEP_DEFS.map((d, i) => ({
      stepNo: i,
      name: d.name,
      status: 'CHO',
      startedAt: null,
      finishedAt: null,
      compensationAction: d.compensation,
      compensatedAt: null,
    })),
    cancelRequest: null,
    batches: Array.from({ length: 5 }, (_, i) => ({
      id: `${f.period.code}-lo-${String(i + 1).padStart(3, '0')}`,
      unit: { id: 'ou-van-phong', name: 'Văn phòng Đảng uỷ' },
      profileCount: i < 4 ? 1000 : profiles - 4000,
      status: 'CHO' as const,
      attempts: 0,
      finishedAt: null,
    })),
    failures: [],
    events: [],
  };
  s.steps[0] = {
    ...s.steps[0],
    status: 'DAT_CO_CANH_BAO',
    startedAt: at,
    finishedAt: at,
    resultSummary: { note: '9 minh chứng còn DA_NOP không được tính trong kỳ' },
  };
  s.steps[1] = {
    ...s.steps[1],
    status: 'DAT',
    startedAt: at,
    finishedAt: at,
    resultSummary: { note: 'Kỳ chuyển DANG_KHOA, đã phát EVT-09' },
  };
  startStep(s, 2);
  f.saga = s;
  log(
    s,
    `Khởi chạy saga ${SAGA_TEMPLATE.key} phiên bản ${SAGA_TEMPLATE.version}, khoá chống lặp ${o.idempotencyKey}.`,
    null,
    ME,
  );
  log(s, 'Bước 0 đạt có cảnh báo: 9 minh chứng còn DA_NOP không được tính trong kỳ.');
  log(s, `Kỳ ${f.period.name} chuyển DANG_KHOA, phát KyDaKhoa.`, 'EVT-09');
  return job('PERIOD_LOCK', 'DANG_CHAY', { message: 'Bước 2: tính điểm hàng loạt' });
}

function withSaga(req: MockRequest): { f: PeriodFixture; s: SagaFixture } | MockResponse {
  const f = find(req);
  if (isResponse(f)) return f;
  if (!f.saga || !f.order) return problem(404, 'Kỳ chưa có saga khoá kỳ');
  return { f, s: f.saga };
}

function pause(req: MockRequest): MockResponse {
  const hit = withSaga(req);
  if ('status' in hit) return hit;
  const { f, s } = hit;
  if (s.status !== 'DANG_CHAY')
    return problem(409, 'Saga không chạy', { code: 'PERIOD-409-SAGA-NOT-RUNNING' });
  const conflict = ifMatch(f.order!, req);
  if (conflict) return conflict;
  if (!mockSession.recentMfa()) return stepUpRequired('tạm dừng saga');
  s.pauseRequested = true;
  f.order!.rowVersion++;
  log(s, 'Đặt cờ tạm dừng sau bước hiện tại.', null, ME);
  return ok(progressOut(f), etag(f.order!.rowVersion));
}

function resume(req: MockRequest): MockResponse {
  const hit = withSaga(req);
  if ('status' in hit) return hit;
  const { f, s } = hit;
  if (!s.pauseRequested || !['DANG_CHAY', 'CHO_SU_KIEN'].includes(s.status))
    return problem(409, 'Saga không ở trạng thái tạm dừng', {
      code: 'PERIOD-409-SAGA-NOT-RUNNING',
    });
  if (s.cancelRequest)
    return problem(409, 'Đang có đề nghị huỷ chờ duyệt thứ hai', {
      code: 'PERIOD-409-CANCEL-PENDING',
    });
  const conflict = ifMatch(f.order!, req);
  if (conflict) return conflict;
  s.pauseRequested = false;
  if (s.status === 'CHO_SU_KIEN') {
    s.status = 'DANG_CHAY';
    const last = [...s.steps]
      .reverse()
      .find((x) => x.status === 'DAT' || x.status === 'DAT_CO_CANH_BAO');
    startStep(s, (last?.stepNo ?? -1) + 1);
  }
  f.order!.rowVersion++;
  log(s, 'Gỡ cờ tạm dừng, saga chạy tiếp.', null, ME);
  return ok(progressOut(f), etag(f.order!.rowVersion));
}

function retryFailed(req: MockRequest): MockResponse {
  const hit = withSaga(req);
  if ('status' in hit) return hit;
  const { s } = hit;
  if (!s.failures.length)
    return problem(409, 'Không có hồ sơ lỗi', { code: 'PERIOD-409-NO-FAILURE' });
  if (!mockSession.recentMfa()) return stepUpRequired('chạy lại hồ sơ lỗi');
  const ids = (req.body as { profileIds?: string[] })?.profileIds;
  const retried = ids?.length ? s.failures.filter((x) => ids.includes(x.profileId)) : s.failures;
  s.failures = s.failures.filter((x) => !retried.includes(x));
  s.totals.failed = s.failures.length;
  s.totals.scored += retried.length;
  for (const b of s.batches.filter((x) => x.status === 'LOI')) {
    b.status = 'XONG';
    b.attempts++;
    b.finishedAt = nowIso();
  }
  log(s, `Chạy lại ${retried.length} hồ sơ lỗi bằng lô mới.`, null, ME);
  return job('PERIOD_LOCK_RETRY', 'DANG_CHAY', {
    message: `Đang tính lại ${retried.length} hồ sơ`,
  });
}

function requestCancel(req: MockRequest): MockResponse {
  const hit = withSaga(req);
  if ('status' in hit) return hit;
  const { f, s } = hit;
  const p = progressOut(f);
  if (!p.cancellable)
    return problem(409, 'Đã quá điểm không quay lại, không thể huỷ và bù trừ', {
      code: 'PERIOD-409-POINT-OF-NO-RETURN',
      detail:
        'BR-08-09: chỉ huỷ và bù trừ được trước khi bước 4 hoàn tất; sau đó dùng mở lại hậu kiểm.',
    });
  if (s.cancelRequest)
    return problem(409, 'Đã có đề nghị huỷ chờ duyệt thứ hai', {
      code: 'PERIOD-409-CANCEL-PENDING',
    });
  const reason = (req.body as { reason?: string })?.reason?.trim() ?? '';
  if (reason.length < 10)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'reason', message: 'Lý do huỷ cần ít nhất 10 ký tự' }],
    });
  const conflict = ifMatch(f.order!, req);
  if (conflict) return conflict;
  if (!mockSession.recentMfa()) return stepUpRequired('đề nghị huỷ saga');
  s.cancelRequest = { requestedBy: ME, requestedAt: nowIso(), reason };
  s.pauseRequested = true;
  f.order!.rowVersion++;
  log(s, `Đề nghị huỷ và bù trừ toàn bộ, đã gửi tới ${SECOND_APPROVER.name}.`, 'EVT-21', ME);
  return ok(progressOut(f), etag(f.order!.rowVersion));
}

function approveCancel(req: MockRequest): MockResponse {
  const hit = withSaga(req);
  if ('status' in hit) return hit;
  const { f, s } = hit;
  if (!s.cancelRequest)
    return problem(409, 'Không có đề nghị huỷ chờ duyệt', { code: 'PERIOD-409-STATUS' });
  const conflict = ifMatch(f.order!, req);
  if (conflict) return conflict;
  if (s.cancelRequest.requestedBy.id === ME.id)
    return samePerson('BR-08-09: người duyệt thứ hai phải khác người đề nghị huỷ.');
  if (!mockSession.recentMfa()) return stepUpRequired('duyệt huỷ và bù trừ');
  s.cancelRequest = null;
  s.pauseRequested = false;
  s.status = 'DANG_BU_TRU';
  f.order!.rowVersion++;
  log(s, 'Duyệt thứ hai đề nghị huỷ; saga bù trừ theo thứ tự ngược.', null, ME);
  return job('PERIOD_UNLOCK', 'DANG_CHAY', { message: 'Đang bù trừ' });
}

function page<T>(req: MockRequest, pick: (s: SagaFixture) => T[]): MockResponse {
  const hit = withSaga(req);
  if ('status' in hit) return hit;
  return ok({ items: structuredClone(pick(hit.s)), nextCursor: null });
}

function diagram(req: MockRequest): MockResponse {
  const hit = withSaga(req);
  if ('status' in hit) return hit;
  const { s } = hit;
  const ids = (pred: (x: PeriodLockStep) => boolean) =>
    s.steps.filter(pred).map((x) => STEP_ACTIVITY_IDS[x.stepNo]);
  return ok({
    templateKey: SAGA_TEMPLATE.key,
    templateVersion: SAGA_TEMPLATE.version,
    bpmnXml:
      '<?xml version="1.0" encoding="UTF-8"?><definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"/>',
    activeActivityIds: ids((x) => x.status === 'DANG_CHAY'),
    completedActivityIds: ids((x) => x.status === 'DAT' || x.status === 'DAT_CO_CANH_BAO'),
    failedActivityIds: ids((x) => x.status === 'LOI'),
  });
}

export const PERIODS_MOCK: MockModule = {
  name: 'periods',
  reset: () => {
    store = periodFixtures();
  },
  routes: [
    { method: 'GET', pattern: '/api/v1/periods', handle: list },
    { method: 'POST', pattern: '/api/v1/periods', handle: create },
    {
      method: 'GET',
      pattern: '/api/v1/periods/:id',
      handle: (req) => {
        const f = find(req);
        return isResponse(f) ? f : ok(periodOut(f), etag(f.period.rowVersion));
      },
    },
    { method: 'POST', pattern: '/api/v1/periods/:id/lock-orders', handle: createOrder },
    {
      method: 'POST',
      pattern: '/api/v1/periods/:id/lock-orders/:oid/approve',
      handle: approveOrder,
    },
    { method: 'GET', pattern: '/api/v1/periods/:id/lock', handle: progress },
    { method: 'POST', pattern: '/api/v1/periods/:id/lock', handle: start },
    {
      method: 'GET',
      pattern: '/api/v1/periods/:id/lock/batches',
      handle: (r) => page(r, (s) => s.batches),
    },
    {
      method: 'GET',
      pattern: '/api/v1/periods/:id/lock/failures',
      handle: (r) => page(r, (s) => s.failures),
    },
    {
      method: 'GET',
      pattern: '/api/v1/periods/:id/lock/events',
      handle: (r) => page(r, (s) => s.events),
    },
    { method: 'GET', pattern: '/api/v1/periods/:id/lock/diagram', handle: diagram },
    { method: 'POST', pattern: '/api/v1/periods/:id/lock/pause', handle: pause },
    { method: 'POST', pattern: '/api/v1/periods/:id/lock/resume', handle: resume },
    { method: 'POST', pattern: '/api/v1/periods/:id/lock/retry-failed', handle: retryFailed },
    { method: 'POST', pattern: '/api/v1/periods/:id/lock/cancel', handle: requestCancel },
    { method: 'POST', pattern: '/api/v1/periods/:id/lock/cancel/approve', handle: approveCancel },
  ],
};
