import {
  AppraisalProfile,
  AppraisalProfileRow,
  AppraisalProfileStatus,
  AppraisalProposalInput,
  AppraisalReviewInput,
  AppraisalScoreTraceStep,
  AppraisalScoreView,
  AppraisalSelfAssessmentInput,
  AppraisalSignRequestInput,
  GeneralCriteriaLevel,
  APPRAISAL_PROFILE_STATUS_LABEL,
  RATING_LEVEL_LABEL,
  RatingLevel,
} from '../../lib/models/appraisal';
import { Job } from '../../lib/models/job';
import { mockSession } from '../mock-session';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import fixture from './appraisals.json';
import {
  DECISION_SCORES,
  batchDecisionRoutes,
  decisionQueue,
  resetBatchDecisions,
} from './batch-decision';
import { MOCK_MANAGER, PROPOSAL_BLOCKED, RESULT_MINE, ratingSheets } from './rating-sheet';
import {
  ACCEPTED_BY_VIEWER,
  MOCK_APPRAISER,
  patchFixtureProfile,
  reviewQueue,
} from './review-queue';

/**
 * Giả lập MOD-04 `appraisal` cho phiếu tự đánh giá SCR-0901 (QT-09; WEB-035) theo
 * `contracts/openapi/modules/appraisals.yaml`. Quy tắc máy chủ: chỉ chủ hồ sơ ghi khi hồ sơ ở
 * `TU_DANH_GIA` (403, 409), `If-Match` (409 MSG-09-07), BR-09-05 quy đổi tiêu chí chung, BR-09-12
 * giải trình khi khác mức tham khảo (422 MSG-09-02), giải pháp đột phá kèm minh chứng khi HTXSNV
 * (422 MSG-09-03), MFA trong 15 phút trước khi ký (401 step-up), ký số qua job: lần hỏi thứ hai coi như
 * đã xác nhận trên MySign và nhận EVT-18, hồ sơ sang `CHO_THAM_DINH` (BR-09-15). Ký lỗi (MSG-09-05)
 * khi `setMockEsignOutage(true)` hoặc URL trang có `?mock-esign=loi`.
 *
 * Thẩm định SCR-0902 (WEB-036): hàng đợi `GET /appraisals/profiles` theo trạng thái, sắp theo hạn;
 * trả lại bổ sung (lý do 20 đến 2.000 ký tự, tối đa `returnLimit` lần, 409 `APPRAISAL-409-RETURN-LIMIT`,
 * MSG-09-09); ký thẩm định (nhận xét bắt buộc tối đa 4.000 ký tự, lý do khi khác mức tự đề xuất 422
 * MSG-09-06, tách vai trò 403 MSG-09-08 với hồ sơ người thẩm định đã nghiệm thu, MFA, job ký như trên,
 * hồ sơ sang `DA_THAM_DINH`); xuất danh sách XLSX qua job (tệp giả lập).
 *
 * Phiếu xếp loại SCR-0903 (WEB-037): hồ sơ ở `rating-sheet.ts`; "Ghi mức đề xuất" chỉ khi hồ sơ ở
 * `DA_THAM_DINH` (409), `If-Match`, lý do 20 đến 2.000 ký tự khi khác mức thẩm định (422 MSG-09-12),
 * tách vai trò với hồ sơ quản lý giả lập đã nghiệm thu (403); ghi mức `DE_XUAT`, hồ sơ sang
 * `CHO_QUYET_DINH`. Hồ sơ đã có quyết định của tôi nằm trong `profiles/mine`.
 *
 * Quyết định hàng loạt SCR-0904 (WEB-038): 48 hồ sơ chờ quyết định và quy tắc ký lô ở `batch-decision.ts`.
 */

const ME = [...fixture.mine, ...RESULT_MINE];
/** Hạn thẩm định của kỳ trong bản giả lập (3 ngày làm việc sau hạn nộp). */
const APPRAISAL_DUE = '2027-01-08';
const MSG_09_02 =
  'Mức tự đề xuất khác mức hệ thống tham khảo. Đồng chí cần ghi giải trình trước khi nộp (Điều 16 Quy định 366).';
const MSG_09_06 =
  'Mức thẩm định khác mức tự đề xuất. Theo Điều 16 Quy định 366, đồng chí cần ghi lý do trước khi ký.';
const MSG_09_08 =
  'Đồng chí không thể thẩm định hồ sơ này vì đã tham gia nghiệm thu một nhiệm vụ của hồ sơ (Phụ lục 4, mục V.1).';
const MSG_09_12 =
  'Mức đề xuất khác mức thẩm định. Vui lòng ghi lý do (khoản 16.3 Điều 16 Quy định 366).';
const MSG_PROPOSAL_SOD =
  'Đồng chí không thể đề xuất mức cho hồ sơ này vì đã tham gia nghiệm thu một nhiệm vụ của hồ sơ (Phụ lục 4, mục V.1).';
const MSG_09_03 =
  'Đề xuất mức Hoàn thành xuất sắc nhiệm vụ cần mô tả giải pháp đột phá và gắn ít nhất một minh chứng đã được xác nhận.';

interface SignJob {
  job: Job;
  profileId: string;
  polls: number;
  fail: boolean;
  provider: NonNullable<AppraisalSignRequestInput['signProvider']>;
  /** Có khi job ký kết quả thẩm định (SCR-0902), vắng khi ký phiếu tự đánh giá. */
  review?: AppraisalReviewInput;
}

let profiles: AppraisalProfile[] | null = null;
let scoreViews: Record<string, AppraisalScoreView> | null = null;
const jobs = new Map<string, SignJob>();
const exportJobs = new Map<string, Job>();

function load(): void {
  const queue = reviewQueue();
  const sheets = ratingSheets();
  const base = structuredClone(fixture.profiles) as unknown as AppraisalProfile[];
  base.forEach(patchFixtureProfile);
  profiles = [...base, ...queue.profiles, ...sheets.profiles, ...decisionQueue()];
  scoreViews = {
    ...(fixture.scores as unknown as Record<string, AppraisalScoreView>),
    ...queue.views,
    ...sheets.views,
  };
}
const store = (): AppraisalProfile[] => {
  if (!profiles) load();
  return profiles!;
};
const views = (): Record<string, AppraisalScoreView> => {
  if (!scoreViews) load();
  return scoreViews!;
};
const etag = (p: AppraisalProfile) => ({ ETag: `"${p.rowVersion}"` });
const pageQuery = () => new URLSearchParams(globalThis.location?.search ?? '');

/** BR-09-05: quy đổi tỷ lệ đáp ứng ra mức tiêu chí chung. */
export function criteriaLevel(ratio: number): GeneralCriteriaLevel {
  if (ratio >= 1) return 'THUC_HIEN_TOT';
  if (ratio >= 0.9) return 'DAM_BAO';
  if (ratio >= 0.6) return 'CO_BAN_DAM_BAO';
  return 'KHONG_DAM_BAO';
}

function find(req: MockRequest): AppraisalProfile | MockResponse {
  const p = store().find((x) => x.id === req.pathParams['id']);
  return p ?? problem(404, 'Không tìm thấy hồ sơ đánh giá', { code: 'APPRAISAL-404' });
}

/** Kiểm quyền ghi của chủ hồ sơ, trạng thái và `If-Match`; `null` khi được ghi. */
function guardWrite(p: AppraisalProfile, req: MockRequest): MockResponse | null {
  if (!ME.includes(p.id))
    return problem(403, 'Chỉ chủ hồ sơ được sửa phiếu tự đánh giá', {
      code: 'APPRAISAL-403-NOT-OWNER',
    });
  if (p.status !== 'TU_DANH_GIA')
    return problem(409, 'Hồ sơ không còn ở bước tự đánh giá', {
      code: 'APPRAISAL-409-INVALID-STATUS',
      detail: `Trạng thái hiện tại: ${APPRAISAL_PROFILE_STATUS_LABEL[p.status]}.`,
    });
  const match = req.headers.get('If-Match');
  if (!match)
    return problem(
      428,
      'Dữ liệu trên màn hình đã cũ. Vui lòng tải lại rồi thực hiện lại thao tác.',
    );
  if (match.replace(/"/g, '') !== String(p.rowVersion))
    return problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      code: 'APPRAISAL-409-VERSION-CONFLICT',
    });
  return null;
}

/** Kiểm thao tác của người thẩm định: trạng thái `CHO_THAM_DINH` và `If-Match`. */
function guardReview(p: AppraisalProfile, req: MockRequest): MockResponse | null {
  if (p.status !== 'CHO_THAM_DINH')
    return problem(409, 'Hồ sơ không còn ở bước thẩm định', {
      code: 'APPRAISAL-409-INVALID-STATUS',
      detail: `Trạng thái hiện tại: ${APPRAISAL_PROFILE_STATUS_LABEL[p.status]}.`,
    });
  const match = req.headers.get('If-Match');
  if (!match)
    return problem(
      428,
      'Dữ liệu trên màn hình đã cũ. Vui lòng tải lại rồi thực hiện lại thao tác.',
    );
  if (match.replace(/"/g, '') !== String(p.rowVersion))
    return problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      code: 'APPRAISAL-409-VERSION-CONFLICT',
    });
  return null;
}

function returnProfile(req: MockRequest): MockResponse {
  const p = find(req);
  if (!('person' in p)) return p;
  const blocked = guardReview(p, req);
  if (blocked) return blocked;
  const reason = String((req.body as { reason?: string } | null)?.reason ?? '').trim();
  if (reason.length < 20 || reason.length > 2000)
    return problem(422, 'Lý do trả lại chưa hợp lệ', {
      errors: [{ field: 'reason', message: 'Lý do trả lại từ 20 đến 2.000 ký tự.' }],
    });
  const limit = p.returnLimit ?? 2;
  if ((p.returnCount ?? 0) >= limit)
    return problem(409, 'Đã trả lại hồ sơ đủ số lần tối đa', {
      code: 'APPRAISAL-409-RETURN-LIMIT',
      detail: `Hồ sơ đã được trả lại ${limit}/${limit} lần, không trả lại thêm được.`,
    });
  p.status = 'TU_DANH_GIA';
  p.returnCount = (p.returnCount ?? 0) + 1;
  p.rowVersion++;
  return ok(structuredClone(p), etag(p));
}

/** "Ghi mức đề xuất" (SCR-0903): mức `DE_XUAT` của quản lý trực tiếp, hồ sơ sang `CHO_QUYET_DINH`. */
function propose(req: MockRequest): MockResponse {
  const p = find(req);
  if (!('person' in p)) return p;
  if (PROPOSAL_BLOCKED.has(p.id))
    return problem(403, MSG_PROPOSAL_SOD, { code: 'APPRAISAL-403-SEPARATION-OF-DUTIES' });
  if (p.status !== 'DA_THAM_DINH')
    return problem(409, 'Hồ sơ không còn ở bước đề xuất mức', {
      code: 'APPRAISAL-409-INVALID-STATUS',
      detail: 'Hồ sơ đã được đề xuất mức hoặc đã chuyển bước khác. Vui lòng tải lại trang.',
    });
  const match = req.headers.get('If-Match');
  if (!match) return problem(428, 'Thiếu If-Match');
  if (match.replace(/"/g, '') !== String(p.rowVersion))
    return problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      code: 'APPRAISAL-409-VERSION-CONFLICT',
    });
  const body = (req.body ?? {}) as AppraisalProposalInput;
  if (!body.ratingCode || !(body.ratingCode in RATING_LEVEL_LABEL))
    return problem(422, 'Chưa chọn mức đề xuất', {
      errors: [{ field: 'ratingCode', message: 'Chọn mức đề xuất.' }],
    });
  if ((body.comment?.length ?? 0) > 4000)
    return problem(422, 'Nhận xét quá dài', {
      errors: [{ field: 'comment', message: 'Tối đa 4.000 ký tự.' }],
    });
  const appraised = p.ratingTrail.find((r) => r.levelKind === 'THAM_DINH' && r.isCurrent);
  const differs = !!appraised && body.ratingCode !== appraised.ratingCode;
  const reason = body.reason?.trim() ?? '';
  if (differs && (reason.length < 20 || reason.length > 2000))
    return problem(422, MSG_09_12, {
      code: 'APPRAISAL-422-REASON-REQUIRED',
      errors: [{ field: 'reason', message: MSG_09_12 }],
    });
  // dữ liệu mẫu đặt ở tháng 01/2027: mức mới ghi sau mức cuối để vết giữ đúng thứ tự thời gian
  const last = Math.max(0, ...p.ratingTrail.map((x) => Date.parse(x.proposedAt)));
  const now = new Date(Math.max(Date.now(), last + 3_600_000)).toISOString();
  p.ratingTrail = [
    ...p.ratingTrail.map((x) => (x.levelKind === 'DE_XUAT' ? { ...x, isCurrent: false } : x)),
    {
      id: crypto.randomUUID(),
      levelKind: 'DE_XUAT',
      ratingCode: body.ratingCode,
      actor: MOCK_MANAGER,
      actorRole: 'manager',
      isCurrent: true,
      differsFromPrev: differs,
      explanationRequired: differs,
      isBulkDecision: false,
      ...(differs ? { reason } : {}),
      ...(body.comment?.trim() ? { comment: body.comment.trim() } : {}),
      proposedAt: now,
    },
  ];
  p.status = 'CHO_QUYET_DINH';
  p.rowVersion++;
  return ok(structuredClone(p), etag(p));
}

function appraise(req: MockRequest): MockResponse {
  const p = find(req);
  if (!('person' in p)) return p;
  if (ACCEPTED_BY_VIEWER.has(p.id))
    return problem(403, MSG_09_08, {
      code: 'APPRAISAL-403-SEPARATION-OF-DUTIES',
      msg: 'MSG-09-08',
    });
  const blocked = guardReview(p, req);
  if (blocked) return blocked;
  const body = (req.body ?? {}) as AppraisalReviewInput;
  const errors: { field: string; message: string }[] = [];
  if (!body.ratingCode) errors.push({ field: 'ratingCode', message: 'Chọn mức thẩm định.' });
  const comment = body.comment?.trim() ?? '';
  if (!comment) errors.push({ field: 'comment', message: 'Nhập nhận xét thẩm định.' });
  if (comment.length > 4000) errors.push({ field: 'comment', message: 'Tối đa 4.000 ký tự.' });
  if (errors.length) return problem(422, 'Kết luận thẩm định chưa đủ thông tin', { errors });
  const self = p.ratingTrail.find((r) => r.levelKind === 'TU_DE_XUAT' && r.isCurrent)?.ratingCode;
  const reason = body.reason?.trim() ?? '';
  if (self && body.ratingCode !== self && (reason.length < 20 || reason.length > 2000))
    return problem(422, MSG_09_06, {
      code: 'APPRAISAL-422-REASON-REQUIRED',
      errors: [{ field: 'reason', message: MSG_09_06 }],
    });
  if (!mockSession.recentMfa()) return stepUpRequired();
  const id = crypto.randomUUID();
  const job: Job = {
    id,
    type: 'ESIGN',
    status: 'CHO',
    progress: 0,
    message: 'Đã gửi yêu cầu ký kết quả thẩm định tới ứng dụng ký số',
  };
  jobs.set(id, {
    job,
    profileId: p.id,
    polls: 0,
    fail: mockSession.esignDown() || pageQuery().get('mock-esign') === 'loi',
    provider: body.signProvider ?? 'MYSIGN',
    review: body,
  });
  return { status: 202, body: job, headers: { Location: `/api/v1/appraisals/sign-jobs/${id}` } };
}

/** EVT-18 của ký thẩm định: ghi mức `THAM_DINH` (EVT-11), hồ sơ sang `DA_THAM_DINH`. */
function completeReview(p: AppraisalProfile, s: SignJob, now: string): void {
  const r = s.review!;
  const self = p.ratingTrail.find((x) => x.levelKind === 'TU_DE_XUAT' && x.isCurrent)?.ratingCode;
  p.ratingTrail = [
    ...p.ratingTrail.map((x) => (x.levelKind === 'THAM_DINH' ? { ...x, isCurrent: false } : x)),
    {
      id: crypto.randomUUID(),
      levelKind: 'THAM_DINH',
      ratingCode: r.ratingCode,
      actor: MOCK_APPRAISER,
      actorRole: 'appraiser',
      isCurrent: true,
      differsFromPrev: !!self && r.ratingCode !== self,
      explanationRequired: !!self && r.ratingCode !== self,
      isBulkDecision: false,
      comment: r.comment,
      ...(r.reason ? { reason: r.reason } : {}),
      proposedAt: now,
      signedAt: now,
    },
  ];
  p.status = 'DA_THAM_DINH';
  p.appraiser = MOCK_APPRAISER;
  p.rowVersion++;
}

function list(req: MockRequest): MockResponse {
  const status = (req.params.getAll('status') ?? []) as AppraisalProfileStatus[];
  const wanted: AppraisalProfileStatus[] = status.length ? status : ['CHO_THAM_DINH'];
  const period = req.params.get('periodId');
  const flow = req.params.get('flow');
  const q = req.params.get('q')?.toLocaleLowerCase('vi');
  const rows = store()
    .filter((p) => !ME.includes(p.id))
    .filter((p) => wanted.includes(p.status))
    .filter((p) => !period || p.period.id === period)
    .filter((p) => !flow || p.flow === flow)
    .filter((p) => !q || p.person.name.toLocaleLowerCase('vi').includes(q))
    .map(row)
    .sort((a, b) => (a.dueDate ?? '').localeCompare(b.dueDate ?? ''));
  const limit = Math.min(Number(req.params.get('limit') ?? 50), 200);
  const start = Number(req.params.get('cursor') ?? 0);
  const items = rows.slice(start, start + limit);
  const next = start + limit < rows.length ? String(start + limit) : null;
  return ok({ items, nextCursor: next });
}

function exportProfiles(req: MockRequest): MockResponse {
  const body = (req.body ?? {}) as { periodId?: string; format?: string };
  if (!body.periodId || body.format !== 'XLSX')
    return problem(422, 'Thiếu kỳ hoặc định dạng xuất', {
      errors: [{ field: 'periodId', message: 'Chọn kỳ cần xuất.' }],
    });
  const id = crypto.randomUUID();
  exportJobs.set(id, {
    id,
    type: 'EXPORT',
    status: 'HOAN_TAT',
    progress: 100,
    message: 'Đã xuất danh sách hàng đợi thẩm định (không gồm nội dung được mã hoá khi lưu).',
    resultLocation: `/api/v1/appraisals/export-jobs/${id}/file`,
  });
  return { status: 202, body: null, headers: { Location: `/api/v1/appraisals/export-jobs/${id}` } };
}

function row(p: AppraisalProfile): AppraisalProfileRow {
  const v = views()[p.id]?.snapshot;
  const d = DECISION_SCORES.get(p.id);
  const level = (kind: string) =>
    p.ratingTrail.find((r) => r.levelKind === kind && r.isCurrent)?.ratingCode ?? null;
  return {
    id: p.id,
    person: p.person,
    jobTitle: p.jobTitle,
    orgUnit: p.orgUnit,
    period: p.period,
    periodKind: p.periodKind,
    flow: p.flow,
    status: p.status,
    isHead: p.isHead,
    totalScore: v?.totalScore ?? d?.total ?? null,
    autoRatingCode: v?.autoRatingCode ?? d?.auto ?? null,
    levels: {
      selfProposed: level('TU_DE_XUAT'),
      appraised: level('THAM_DINH'),
      proposed: level('DE_XUAT'),
      decided: level('QUYET_DINH'),
    },
    differsExplained: p.ratingTrail.some((r) => r.isCurrent && r.explanationRequired && !!r.reason),
    noSelfAssessment: p.noSelfAssessment,
    dueDate: p.status === 'TU_DANH_GIA' ? p.selfAssessDue : APPRAISAL_DUE,
    anomalyHints: p.anomalyHints,
    rowVersion: p.rowVersion,
  };
}

function save(p: AppraisalProfile, body: AppraisalSelfAssessmentInput): void {
  const prev = p.selfAssessment ?? { versionNo: 1, formCode: 'MAU_1B', formVersion: '1.0' };
  const { criteria, ...rest } = body;
  p.selfAssessment = { ...prev, ...rest, savedAt: new Date().toISOString() };
  if (criteria?.length) {
    const stage = p.generalCriteria?.find((g) => g.stage === 'TU_DANH_GIA');
    if (stage) {
      for (const c of criteria) {
        const item = stage.items.find((i) => i.criteriaCode === c.criteriaCode);
        if (item) item.met = c.met;
      }
      stage.complianceRatio = stage.items.filter((i) => i.met).length / stage.items.length;
      stage.level = criteriaLevel(stage.complianceRatio);
    }
  }
  p.rowVersion++;
}

/** Kiểm đủ trường khi nộp (kiểm tra đầy đủ chỉ chạy khi nộp, lưu nháp không kiểm). */
function validate(p: AppraisalProfile): MockResponse | null {
  const sa = p.selfAssessment;
  const auto = views()[p.id]?.snapshot.autoRatingCode;
  const errors: { field: string; message: string }[] = [];
  if (!sa?.selfRatingCode)
    errors.push({ field: 'selfRatingCode', message: 'Chọn mức tự đề xuất.' });
  if (!sa?.content?.trim())
    errors.push({ field: 'content', message: 'Nhập ưu điểm, hạn chế, hướng khắc phục.' });
  if (errors.length) return problem(422, 'Phiếu chưa đủ thông tin', { errors });
  if (
    sa!.selfRatingCode === 'HTXSNV' &&
    (!sa!.breakthrough?.trim() || !sa!.breakthroughEvidenceIds?.length)
  )
    return problem(422, MSG_09_03, {
      code: 'APPRAISAL-422-BREAKTHROUGH-REQUIRED',
      errors: [{ field: 'breakthrough', message: MSG_09_03 }],
    });
  const reason = sa!.reason?.trim() ?? '';
  if (sa!.selfRatingCode !== auto && (reason.length < 20 || reason.length > 2000))
    return problem(422, MSG_09_02, {
      code: 'APPRAISAL-422-REASON-REQUIRED',
      errors: [{ field: 'reason', message: MSG_09_02 }],
    });
  return null;
}

function stepUpRequired(): MockResponse {
  const r = problem(401, 'Cần xác thực nhiều lớp trước khi ký số', {
    type: 'https://datatracker.ietf.org/doc/html/rfc9470',
    code: 'insufficient_user_authentication',
  });
  r.headers = {
    ...r.headers,
    'WWW-Authenticate':
      // ui-text-ok: giá trị header HTTP cho thư viện xác thực, không hiện trên màn hình
      'Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=900',
  };
  return r;
}

function submit(req: MockRequest): MockResponse {
  const p = find(req);
  if (!('person' in p)) return p;
  const prof = p;
  const blocked = guardWrite(prof, req) ?? validate(prof);
  if (blocked) return blocked;
  if (!mockSession.recentMfa()) return stepUpRequired();
  const body = (req.body ?? {}) as AppraisalSignRequestInput;
  const id = crypto.randomUUID();
  const job: Job = {
    id,
    type: 'ESIGN',
    status: 'CHO',
    progress: 0,
    message: 'Đã gửi yêu cầu ký tới ứng dụng ký số, chờ đồng chí xác nhận',
  };
  jobs.set(id, {
    job,
    profileId: prof.id,
    polls: 0,
    fail: mockSession.esignDown() || pageQuery().get('mock-esign') === 'loi',
    provider: body.signProvider ?? 'MYSIGN',
  });
  return { status: 202, body: job, headers: { Location: `/api/v1/appraisals/sign-jobs/${id}` } };
}

/** Lần hỏi thứ hai: EVT-18 về, hồ sơ chuyển `CHO_THAM_DINH` và ghi mức `TU_DE_XUAT` (EVT-11). */
function signJob(req: MockRequest): MockResponse {
  const s = jobs.get(req.pathParams['id']);
  if (!s) return problem(404, 'Không tìm thấy yêu cầu ký số');
  if (s.job.status !== 'CHO' && s.job.status !== 'DANG_CHAY') return ok(s.job);
  if (++s.polls < 2) {
    s.job = { ...s.job, status: 'DANG_CHAY', progress: 50 };
    return ok(s.job);
  }
  const now = new Date().toISOString();
  if (s.fail) {
    s.job = {
      ...s.job,
      status: 'THAT_BAI',
      progress: 100,
      problem: {
        type: 'about:blank',
        title: 'Dịch vụ ký số từ xa không phản hồi',
        status: 503,
        code: 'ESIGN-503-UNAVAILABLE',
      },
      updatedAt: now,
    };
    return ok(s.job);
  }
  const p = store().find((x) => x.id === s.profileId)!;
  if (s.review) {
    completeReview(p, s, now);
    s.job = {
      ...s.job,
      status: 'HOAN_TAT',
      progress: 100,
      message: 'Đã ký số kết quả thẩm định',
      resultLocation: `/api/v1/appraisals/profiles/${p.id}`,
      updatedAt: now,
    };
    return ok(s.job);
  }
  const sa = p.selfAssessment!;
  const auto = views()[p.id]?.snapshot.autoRatingCode;
  const hash = Array.from(crypto.getRandomValues(new Uint8Array(32)), (b) =>
    b.toString(16).padStart(2, '0'),
  ).join('');
  Object.assign(sa, { submittedAt: now, signedAt: now, contentHash: hash });
  p.status = 'CHO_THAM_DINH';
  p.submittedAt = now;
  p.submissionSignature = { signedAt: now, provider: s.provider, contentHash: hash, valid: true };
  p.ratingTrail = [
    ...p.ratingTrail.map((r) => (r.levelKind === 'TU_DE_XUAT' ? { ...r, isCurrent: false } : r)),
    {
      id: crypto.randomUUID(),
      levelKind: 'TU_DE_XUAT',
      ratingCode: sa.selfRatingCode as RatingLevel,
      actor: p.person,
      actorRole: 'staff',
      isCurrent: true,
      differsFromPrev: sa.selfRatingCode !== auto,
      explanationRequired: sa.selfRatingCode !== auto,
      isBulkDecision: false,
      reason: sa.selfRatingCode !== auto ? sa.reason : undefined,
      proposedAt: now,
      signedAt: now,
    },
  ];
  p.rowVersion++;
  s.job = {
    ...s.job,
    status: 'HOAN_TAT',
    progress: 100,
    message: 'Đã ký số phiếu tự đánh giá',
    resultLocation: `/api/v1/appraisals/profiles/${p.id}`,
    updatedAt: now,
  };
  return ok(s.job);
}

/** Ảnh chụp của hồ sơ, hoặc của hồ sơ cùng người, cùng kỳ ở luồng kia (AP-04). */
function viewFor(p: AppraisalProfile, flow: string | null): AppraisalScoreView | MockResponse {
  let target = p;
  if (flow && flow !== p.flow) {
    const other = store().find(
      (x) => x.person.id === p.person.id && x.period.id === p.period.id && x.flow === flow,
    );
    if (!other) return problem(404, 'Luồng đánh giá này chưa được áp dụng cho đồng chí trong kỳ.');
    target = other;
  }
  if (target.status === 'CHUA_LAP' || !views()[target.id])
    return problem(409, 'Kết quả quý đang được tính', {
      code: 'APPRAISAL-409-NOT-COMPUTED',
      detail: 'Kết quả quý của đồng chí đang được tính, vui lòng quay lại sau.',
    });
  return structuredClone(views()[target.id]);
}

const num = (n: number) => n.toLocaleString('vi-VN', { maximumFractionDigits: 3 });

/** Vết tính sinh từ ảnh chụp: KPI từng nhiệm vụ (BR-09-01), KPI nhóm, tổng (BR-09-03). */
function trace(view: AppraisalScoreView, taskId: string | null): AppraisalScoreTraceStep[] {
  const s = view.snapshot;
  const steps: AppraisalScoreTraceStep[] = [];
  let n = 0;
  for (const g of s.groups) {
    for (const t of g.tasks ?? []) {
      const a = t.a ?? 0;
      const b = t.b ?? 0;
      const c = t.c ?? 0;
      steps.push({
        stepNo: ++n,
        ruleCode: 'kpi_nhiem_vu',
        legalRef: 'Phụ lục 3, mục 1, 2, 3',
        taskId: t.taskId,
        inputs: {
          slDuyet: t.approvedQuantity ?? null,
          slKeHoach: t.plannedQuantity ?? null,
          a,
          b,
          c,
          // Minh chứng DA_XAC_NHAN (QT-07), nghiệm thu (SCR-0703)
          nguon: 'Minh chứng đã được xác nhận và kết quả nghiệm thu sản phẩm',
        },
        output: { kpi: t.kpi },
        explanation: `KPI = (A + B + C) / 3 = (${num(a)} + ${num(b)} + ${num(c)}) / 3 = ${num(t.kpi)}.`,
      });
    }
    steps.push({
      stepNo: ++n,
      ruleCode: 'kpi_nhom',
      legalRef: 'Phụ lục A3; Bảng chấm điểm',
      inputs: { nhom: g.code, soNhiemVu: g.tasks?.length ?? 0, diemToiDa: g.maxScore },
      output: { kpi: g.kpi, diem: g.score },
      explanation: `${g.label}: KPI nhóm ${num(g.kpi)} × ${g.maxScore} = ${num(g.score)} điểm.`,
    });
  }
  steps.push({
    stepNo: ++n,
    ruleCode: 'diem_ket_qua',
    // BR-09-03
    legalRef: 'Phụ lục A3; Bảng chấm điểm',
    inputs: Object.fromEntries(s.groups.map((g) => [g.code, g.score])),
    output: { tong: s.totalScore, mucThamKhao: s.autoRatingCode },
    explanation: `Điểm kết quả nhiệm vụ ${num(s.totalScore)}; mức tham khảo ${RATING_LEVEL_LABEL[s.autoRatingCode]}.`,
  });
  return taskId ? steps.filter((x) => x.taskId === taskId) : steps;
}

export const APPRAISALS_MOCK: MockModule = {
  name: 'appraisals',
  reset: () => {
    profiles = null;
    scoreViews = null;
    jobs.clear();
    exportJobs.clear();
    resetBatchDecisions();
  },
  routes: [
    { method: 'GET', pattern: '/api/v1/appraisals/profiles', handle: list },
    { method: 'POST', pattern: '/api/v1/appraisals/profiles/:id/return', handle: returnProfile },
    { method: 'POST', pattern: '/api/v1/appraisals/profiles/:id/appraisal', handle: appraise },
    { method: 'POST', pattern: '/api/v1/appraisals/profiles/:id/proposal', handle: propose },
    { method: 'POST', pattern: '/api/v1/appraisals/exports', handle: exportProfiles },
    ...batchDecisionRoutes({ store, stepUpRequired, pageQuery }),
    {
      method: 'GET',
      pattern: '/api/v1/appraisals/export-jobs/:id',
      handle: (req) => {
        const job = exportJobs.get(req.pathParams['id']);
        return job ? ok(job) : problem(404, 'Không tìm thấy yêu cầu xuất danh sách');
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/appraisals/export-jobs/:id/file',
      handle: (req) => {
        if (!exportJobs.has(req.pathParams['id'])) return problem(404, 'Không tìm thấy tệp xuất');
        const rows = store()
          .filter((p) => p.status === 'CHO_THAM_DINH')
          .map(row)
          .map((r) =>
            [r.person.name, r.jobTitle ?? '', r.totalScore ?? '', r.levels.selfProposed ?? ''].join(
              '\t',
            ),
          );
        const text = ['Cán bộ\tChức vụ\tĐiểm hệ thống\tTự đề xuất', ...rows].join('\n');
        return {
          status: 200,
          body: new Blob([text], {
            type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
          }),
        };
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/appraisals/profiles/mine',
      handle: (req) => {
        const flow = req.params.get('flow');
        const period = req.params.get('period');
        const items = store()
          .filter((p) => ME.includes(p.id))
          .filter((p) => !flow || p.flow === flow)
          .filter((p) => !period || p.period.id === period)
          .map(row);
        return ok({ items, nextCursor: null });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/appraisals/profiles/:id',
      handle: (req) => {
        const p = find(req);
        return 'person' in p ? ok(structuredClone(p), etag(p)) : p;
      },
    },
    {
      method: 'PUT',
      pattern: '/api/v1/appraisals/profiles/:id/self-assessment',
      handle: (req) => {
        const p = find(req);
        if (!('person' in p)) return p;
        const blocked = guardWrite(p, req);
        if (blocked) return blocked;
        const body = (req.body ?? {}) as AppraisalSelfAssessmentInput;
        const long = (['breakthrough', 'reason', 'content'] as const).find(
          (k) => (body[k]?.length ?? 0) > 2000,
        );
        if (long)
          return problem(422, 'Dữ liệu không hợp lệ', {
            errors: [{ field: long, message: 'Tối đa 2.000 ký tự.' }],
          });
        save(p, body);
        return ok(structuredClone(p), etag(p));
      },
    },
    { method: 'POST', pattern: '/api/v1/appraisals/profiles/:id/submit', handle: submit },
    { method: 'GET', pattern: '/api/v1/appraisals/sign-jobs/:id', handle: signJob },
    {
      method: 'GET',
      pattern: '/api/v1/appraisals/profiles/:id/scores',
      handle: (req) => {
        const p = find(req);
        return 'person' in p ? resp(viewFor(p, req.params.get('flow'))) : p;
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/appraisals/profiles/:id/score-trace',
      handle: (req) => {
        const p = find(req);
        if (!('person' in p)) return p;
        const v = viewFor(p, req.params.get('flow'));
        if (!('snapshot' in v)) return v;
        return ok({
          profileId: v.profileId,
          scoreResultId: v.snapshot.id.replace(/.$/, '8'),
          snapshotId: v.snapshot.id,
          policyVersion: v.snapshot.policyVersion,
          engineVersion: 'scoring 1.8.2',
          steps: trace(v, req.params.get('taskId')),
        });
      },
    },
  ],
};

function resp(v: AppraisalScoreView | MockResponse): MockResponse {
  return 'snapshot' in v ? ok(v) : v;
}
