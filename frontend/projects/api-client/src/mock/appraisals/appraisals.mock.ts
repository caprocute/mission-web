import {
  AppraisalProfile,
  AppraisalProfileRow,
  AppraisalScoreTraceStep,
  AppraisalScoreView,
  AppraisalSelfAssessmentInput,
  AppraisalSignRequestInput,
  GeneralCriteriaLevel,
  RATING_LEVEL_LABEL,
  RatingLevel,
} from '../../lib/models/appraisal';
import { Job } from '../../lib/models/job';
import { mockSession } from '../mock-session';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import fixture from './appraisals.json';

/**
 * Giả lập MOD-04 `appraisal` cho phiếu tự đánh giá SCR-0901 (QT-09; WEB-035) theo
 * `contracts/openapi/modules/appraisals.yaml`. Quy tắc máy chủ: chỉ chủ hồ sơ ghi khi hồ sơ ở
 * `TU_DANH_GIA` (403, 409), `If-Match` (409 MSG-09-07), BR-09-05 quy đổi tiêu chí chung, BR-09-12
 * giải trình khi khác mức tham khảo (422 MSG-09-02), giải pháp đột phá kèm minh chứng khi HTXSNV
 * (422 MSG-09-03), MFA trong 15 phút trước khi ký (401 step-up), ký số qua job: lần hỏi thứ hai coi như
 * đã xác nhận trên MySign và nhận EVT-18, hồ sơ sang `CHO_THAM_DINH` (BR-09-15). Ký lỗi (MSG-09-05)
 * khi `setMockEsignOutage(true)` hoặc URL trang có `?mock-esign=loi`.
 */

const ME = fixture.mine;
const MSG_09_02 =
  'Mức tự đề xuất khác mức hệ thống tham khảo. Đồng chí cần ghi giải trình trước khi nộp (QĐ Điều 16).';
const MSG_09_03 =
  'Đề xuất mức Hoàn thành xuất sắc cần mô tả giải pháp đột phá và gắn ít nhất một minh chứng đã được xác nhận.';

interface SignJob {
  job: Job;
  profileId: string;
  polls: number;
  fail: boolean;
  provider: NonNullable<AppraisalSignRequestInput['signProvider']>;
}

let profiles: AppraisalProfile[] | null = null;
const jobs = new Map<string, SignJob>();

const store = () =>
  (profiles ??= structuredClone(fixture.profiles) as unknown as AppraisalProfile[]);
const views = () => fixture.scores as unknown as Record<string, AppraisalScoreView>;
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
      detail: `Trạng thái hiện tại: ${p.status}.`,
    });
  const match = req.headers.get('If-Match');
  if (!match) return problem(428, 'Thiếu If-Match');
  if (match.replace(/"/g, '') !== String(p.rowVersion))
    return problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      code: 'APPRAISAL-409-VERSION-CONFLICT',
    });
  return null;
}

function row(p: AppraisalProfile): AppraisalProfileRow {
  const v = views()[p.id]?.snapshot;
  const self = p.ratingTrail.find((r) => r.levelKind === 'TU_DE_XUAT' && r.isCurrent);
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
    totalScore: v?.totalScore ?? null,
    autoRatingCode: v?.autoRatingCode ?? null,
    levels: {
      selfProposed: self?.ratingCode ?? null,
      appraised: null,
      proposed: null,
      decided: null,
    },
    dueDate: p.selfAssessDue,
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
  const r = problem(401, 'Cần xác thực bước lên trước khi ký số', {
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
  if (!s) return problem(404, 'Không tìm thấy tác vụ ký');
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
    if (!other) return problem(404, 'Luồng này chưa bật cho cán bộ trong kỳ');
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
        legalRef: 'PL 3 mục 1, 2, 3',
        taskId: t.taskId,
        inputs: {
          slDuyet: t.approvedQuantity ?? null,
          slKeHoach: t.plannedQuantity ?? null,
          a,
          b,
          c,
          nguon: 'Minh chứng DA_XAC_NHAN (QT-07), nghiệm thu (SCR-0703)',
        },
        output: { kpi: t.kpi },
        explanation: `KPI = (A + B + C) / 3 = (${num(a)} + ${num(b)} + ${num(c)}) / 3 = ${num(t.kpi)}.`,
      });
    }
    steps.push({
      stepNo: ++n,
      ruleCode: 'kpi_nhom',
      legalRef: 'PL A3; Bảng chấm điểm',
      inputs: { nhom: g.code, soNhiemVu: g.tasks?.length ?? 0, diemToiDa: g.maxScore },
      output: { kpi: g.kpi, diem: g.score },
      explanation: `${g.label}: KPI nhóm ${num(g.kpi)} × ${g.maxScore} = ${num(g.score)} điểm.`,
    });
  }
  steps.push({
    stepNo: ++n,
    ruleCode: 'diem_ket_qua',
    legalRef: 'BR-09-03',
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
    jobs.clear();
  },
  routes: [
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
