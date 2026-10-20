import {
  AppraisalBatchDecision,
  AppraisalBatchDecisionInput,
  AppraisalProfile,
  AppraisalRatingProposal,
  RatingLevel,
} from '../../lib/models/appraisal';
import { Job } from '../../lib/models/job';
import { mockSession } from '../mock-session';
import { MockRequest, MockResponse, MockRoute, ok, problem } from '../mock-router';
import { MOCK_APPRAISER } from './review-queue';

/**
 * Hồ sơ chờ quyết định và lô ký số của SCR-0904 Quyết định xếp loại hàng loạt (WEB-038), theo mockup
 * TL-02b: 48 hồ sơ quý IV/2026 luồng Đảng của Ban Tổ chức Đảng uỷ khối, phân bố mức đề xuất 9 xuất sắc,
 * 30 tốt, 7 hoàn thành, 2 không hoàn thành (19% xuất sắc, dưới ngưỡng 20%); có hồ sơ khác mức đã giải
 * trình, hồ sơ dưới 60 điểm và hồ sơ người đứng đầu.
 *
 * Quy tắc máy chủ giả lập của `POST /appraisals/batch-decisions`: 1 đến 1.000 hồ sơ `CHO_QUYET_DINH`
 * cùng kỳ, cùng luồng; số quyết định không trùng trong năm (409); lý do 20 đến 2.000 ký tự khi khác mức
 * đề xuất (422); hồ sơ người đứng đầu chỉ quyết định khi không còn hồ sơ nào khác chờ quyết định ngoài lô
 * (422 MSG-09-14, BR-09-13); tỷ lệ xuất sắc vượt 20% mà chưa xác nhận phiên hiệu chỉnh (422 MSG-09-13,
 * BR-09-17); `rowVersion` lệch (409, `errors[]` chỉ rõ mục); MFA trong 15 phút (401 step-up). Mỗi lần hỏi
 * job ký thêm 12 hồ sơ; hồ sơ ký xong sang `DA_KY_SO`. `?mock-esign=loi` làm hai hồ sơ cuối lỗi ký.
 */

const ID = '01928f3e-7b1a-7c3e-9a51-3f2d9c1b';
const Q4 = {
  id: `${ID}0001`,
  label: 'Quý IV/2026',
  startDate: '2026-10-01',
  endDate: '2026-12-31',
};
const BAN = { id: `${ID}0101`, name: 'Ban Tổ chức Đảng uỷ khối' };
const TONG_HOP = { id: `${ID}0111`, name: 'Phòng Tổng hợp' };
const CO_SO = { id: `${ID}0112`, name: 'Phòng Cơ sở đảng' };
const CAN_BO = { id: `${ID}0113`, name: 'Phòng Cán bộ' };
const MANAGER = { id: `${ID}0205`, name: 'Vũ Quang Huy' };
/** Người quyết định của bản giả lập (AC-08), chủ chứng thư ký số. */
export const MOCK_DECIDER = { id: `${ID}0209`, name: 'Ngô Thanh Tâm' };
/** Số quyết định đã dùng trong năm 2027 của đơn vị. */
const USED_DECISION_NOS = ['198-QĐ/ĐUK'];
const MAX_EXCELLENT_SHARE = 0.2;
const SIGN_PER_POLL = 12;

interface Seed {
  name: string;
  title: string;
  unit: { id: string; name: string };
  score: number;
  self: RatingLevel;
  appraised: RatingLevel;
  proposed: RatingLevel;
  head?: boolean;
}

const SEEDS: Seed[] = [
  {
    name: 'Đỗ Quang Vinh',
    title: 'Trưởng phòng Cơ sở đảng',
    unit: CO_SO,
    score: 92.5,
    self: 'HTXSNV',
    appraised: 'HTXSNV',
    proposed: 'HTXSNV',
  },
  {
    name: 'Lê Thu Hà',
    title: 'Phó Trưởng phòng Tổng hợp',
    unit: TONG_HOP,
    score: 86.4,
    self: 'HTTNV',
    appraised: 'HTTNV',
    proposed: 'HTTNV',
  },
  {
    name: 'Vũ Ngọc Sơn',
    title: 'Phó Trưởng phòng Cán bộ',
    unit: CAN_BO,
    score: 88,
    self: 'HTTNV',
    appraised: 'HTTNV',
    proposed: 'HTTNV',
  },
  {
    name: 'Nguyễn Hải Yến',
    title: 'Chuyên viên',
    unit: TONG_HOP,
    score: 82,
    self: 'HTTNV',
    appraised: 'HTTNV',
    proposed: 'HTTNV',
  },
  {
    name: 'Phạm Đức Long',
    title: 'Chuyên viên chính',
    unit: CAN_BO,
    score: 78.9,
    self: 'HTTNV',
    appraised: 'HTNV',
    proposed: 'HTNV',
  },
  {
    name: 'Hoàng Thị Mai',
    title: 'Chuyên viên',
    unit: CO_SO,
    score: 64.3,
    self: 'HTNV',
    appraised: 'HTNV',
    proposed: 'HTNV',
  },
  {
    name: 'Bùi Văn Khánh',
    title: 'Chuyên viên',
    unit: TONG_HOP,
    score: 57.8,
    self: 'HTNV',
    appraised: 'KHTNV',
    proposed: 'KHTNV',
  },
  {
    name: 'Phan Văn Thành',
    title: 'Trưởng ban (người đứng đầu)',
    unit: BAN,
    score: 84.6,
    self: 'HTTNV',
    appraised: 'HTTNV',
    proposed: 'HTTNV',
    head: true,
  },
];

const FAMILY = ['Nguyễn', 'Trần', 'Lê', 'Phạm', 'Hoàng', 'Vũ', 'Đặng', 'Bùi', 'Đỗ', 'Hồ'];
const MIDDLE = ['Văn', 'Thị', 'Minh', 'Đức', 'Thu', 'Quốc', 'Ngọc', 'Hữu'];
const GIVEN = [
  'An',
  'Bình',
  'Châu',
  'Dũng',
  'Giang',
  'Hải',
  'Hương',
  'Khoa',
  'Lan',
  'Linh',
  'Nam',
  'Phúc',
  'Quân',
  'Thảo',
  'Trang',
  'Tuấn',
  'Uyên',
  'Việt',
  'Xuân',
  'Yên',
];
const UNITS = [TONG_HOP, CO_SO, CAN_BO];

/** 40 hồ sơ còn lại: 8 xuất sắc, 26 tốt, 5 hoàn thành, 1 không hoàn thành; điểm theo khoảng của mức. */
function generated(): Seed[] {
  const levels: RatingLevel[] = [
    ...Array<RatingLevel>(8).fill('HTXSNV'),
    ...Array<RatingLevel>(26).fill('HTTNV'),
    ...Array<RatingLevel>(5).fill('HTNV'),
    'KHTNV',
  ];
  const base: Record<RatingLevel, number> = { HTXSNV: 90, HTTNV: 80, HTNV: 62, KHTNV: 52 };
  const span: Record<RatingLevel, number> = { HTXSNV: 6, HTTNV: 9.5, HTNV: 17, KHTNV: 7 };
  return levels.map((level, i) => ({
    name: `${FAMILY[i % FAMILY.length]} ${MIDDLE[(i * 3) % MIDDLE.length]} ${GIVEN[(i * 7) % GIVEN.length]}`,
    title: i % 4 === 0 ? 'Chuyên viên chính' : 'Chuyên viên',
    unit: UNITS[i % UNITS.length],
    score: Math.round((base[level] + (((i * 37) % 100) / 100) * span[level]) * 10) / 10,
    self: level,
    appraised: level,
    proposed: level,
  }));
}

/** Điểm của hồ sơ chờ quyết định (dòng danh sách đọc thay cho ảnh chụp điểm). */
export const DECISION_SCORES = new Map<string, { total: number; auto: RatingLevel }>();

function trail(s: Seed, person: { id: string; name: string }): AppraisalRatingProposal[] {
  const at = (d: string) => `2027-01-${d}T09:00:00+07:00`;
  const base = {
    isCurrent: true,
    isBulkDecision: false,
    differsFromPrev: false,
    explanationRequired: false,
  };
  const appraisedDiffers = s.appraised !== s.self;
  const proposedDiffers = s.proposed !== s.appraised;
  return [
    {
      ...base,
      id: crypto.randomUUID(),
      levelKind: 'TU_DE_XUAT',
      ratingCode: s.self,
      actor: person,
      actorRole: 'staff',
      proposedAt: at('04'),
      signedAt: at('04'),
    },
    {
      ...base,
      id: crypto.randomUUID(),
      levelKind: 'THAM_DINH',
      ratingCode: s.appraised,
      actor: MOCK_APPRAISER,
      actorRole: 'appraiser',
      differsFromPrev: appraisedDiffers,
      explanationRequired: appraisedDiffers,
      ...(appraisedDiffers
        ? {
            reason: 'Kết quả nghiệm thu một số nhiệm vụ thấp hơn mức cán bộ tự nhận về chất lượng.',
          }
        : {}),
      proposedAt: at('07'),
      signedAt: at('07'),
    },
    {
      ...base,
      id: crypto.randomUUID(),
      levelKind: 'DE_XUAT',
      ratingCode: s.proposed,
      actor: MANAGER,
      actorRole: 'manager',
      differsFromPrev: proposedDiffers,
      explanationRequired: proposedDiffers,
      proposedAt: at('09'),
    },
  ];
}

/** 48 hồ sơ chờ quyết định, sinh mới mỗi lần nạp lại dữ liệu giả lập. */
export function decisionQueue(): AppraisalProfile[] {
  DECISION_SCORES.clear();
  return [...SEEDS, ...generated()].map((s, i) => {
    const id = `${ID}${(0xd01 + i).toString(16).padStart(4, '0')}`;
    const person = { id: `${ID}${(0x301 + i).toString(16).padStart(4, '0')}`, name: s.name };
    DECISION_SCORES.set(id, { total: s.score, auto: s.appraised });
    return {
      id,
      person,
      jobTitle: s.title,
      orgUnit: s.unit,
      period: Q4,
      periodKind: 'QUY',
      flow: 'DANG',
      availableFlows: ['DANG'],
      status: 'CHO_QUYET_DINH',
      isHead: !!s.head,
      isLeadership: !!s.head || s.title.startsWith('Trưởng') || s.title.startsWith('Phó'),
      policyVersion: 'Đảng v2.0',
      finalRatingCode: null,
      noSelfAssessment: false,
      submittedAt: '2027-01-04T09:00:00+07:00',
      appraiser: MOCK_APPRAISER,
      generalCriteria: [],
      ratingTrail: trail(s, person),
      anomalyHints: [],
      rowVersion: 9,
    } satisfies AppraisalProfile;
  });
}

interface BatchRun {
  batch: AppraisalBatchDecision;
  job: Job;
  input: AppraisalBatchDecisionInput;
  pending: string[];
  fail: Set<string>;
}

const runs = new Map<string, BatchRun>();
const usedNos = new Set<string>(USED_DECISION_NOS);

export function resetBatchDecisions(): void {
  runs.clear();
  usedNos.clear();
  USED_DECISION_NOS.forEach((n) => usedNos.add(n));
}

const MSG_09_13 = (share: number) =>
  `Tỷ lệ Hoàn thành xuất sắc nhiệm vụ của danh sách là ${Math.round(share * 100)}%, vượt giới hạn ` +
  `${MAX_EXCELLENT_SHARE * 100}% của khối. Đề nghị tổ chức phiên hiệu chỉnh trước khi ký.`;
const MSG_09_14 =
  'Hồ sơ của người đứng đầu chỉ được quyết định sau khi các đơn vị trực thuộc đã có mức (QĐ khoản 14.3.3).';

const current = (p: AppraisalProfile, kind: AppraisalRatingProposal['levelKind']) =>
  p.ratingTrail.find((r) => r.levelKind === kind && r.isCurrent)?.ratingCode ?? null;

/** Tuyến `/appraisals/batch-decisions` dùng chung kho hồ sơ của giả lập MOD-04. */
export function batchDecisionRoutes(deps: {
  store: () => AppraisalProfile[];
  stepUpRequired: () => MockResponse;
  pageQuery: () => URLSearchParams;
}): MockRoute[] {
  function create(req: MockRequest): MockResponse {
    const body = (req.body ?? {}) as Partial<AppraisalBatchDecisionInput>;
    const items = body.items ?? [];
    const errors: { field: string; message: string }[] = [];
    if (!body.periodId || !body.flow)
      errors.push({ field: 'periodId', message: 'Thiếu kỳ hoặc luồng.' });
    if (!body.decisionNo?.trim())
      errors.push({ field: 'decisionNo', message: 'Nhập số quyết định.' });
    if (!body.decisionDate)
      errors.push({ field: 'decisionDate', message: 'Nhập ngày quyết định.' });
    if (!items.length || items.length > 1000)
      errors.push({ field: 'items', message: 'Chọn từ 1 đến 1.000 hồ sơ.' });
    if (errors.length) return problem(422, 'Thông tin quyết định chưa đủ', { errors });
    const year = body.decisionDate!.slice(0, 4);
    if (usedNos.has(`${year}|${body.decisionNo!.trim()}`) || usedNos.has(body.decisionNo!.trim()))
      return problem(409, 'Số quyết định đã được dùng trong năm', {
        code: 'APPRAISAL-409-DECISION-NO-DUPLICATE',
        detail: `Số quyết định ${body.decisionNo} đã được dùng trong năm ${year}.`,
      });
    const all = deps.store();
    const conflicts: { field: string; message: string }[] = [];
    const reasons: { field: string; message: string }[] = [];
    const chosen: AppraisalProfile[] = [];
    items.forEach((it, i) => {
      const p = all.find((x) => x.id === it.profileId);
      if (!p || p.status !== 'CHO_QUYET_DINH' || p.rowVersion !== it.rowVersion) {
        conflicts.push({
          field: `items[${i}].rowVersion`,
          message: `Hồ sơ của đồng chí ${p?.person.name ?? 'không xác định'} đã thay đổi.`,
        });
        return;
      }
      chosen.push(p);
      const proposed = current(p, 'DE_XUAT') ?? current(p, 'THAM_DINH');
      const reason = it.reason?.trim() ?? '';
      if (proposed && it.ratingCode !== proposed && (reason.length < 20 || reason.length > 2000))
        reasons.push({
          field: `items[${i}].reason`,
          message: `Ghi lý do đổi mức cho hồ sơ của đồng chí ${p.person.name}, từ 20 đến 2.000 ký tự.`,
        });
    });
    if (conflicts.length)
      return problem(409, 'Một số hồ sơ đã được cập nhật, vui lòng tải lại danh sách', {
        code: 'APPRAISAL-409-VERSION-CONFLICT',
        errors: conflicts,
      });
    if (reasons.length)
      return problem(422, 'Thiếu lý do đổi mức', {
        code: 'APPRAISAL-422-REASON-REQUIRED',
        errors: reasons,
      });
    const ids = new Set(chosen.map((p) => p.id));
    const head = chosen.find((p) => p.isHead);
    if (
      head &&
      all.some(
        (p) =>
          p.status === 'CHO_QUYET_DINH' &&
          !p.isHead &&
          !ids.has(p.id) &&
          p.period.id === head.period.id &&
          p.flow === head.flow,
      )
    )
      return problem(422, MSG_09_14, { code: 'APPRAISAL-422-HEAD-TOO-EARLY' });
    const share = items.filter((i) => i.ratingCode === 'HTXSNV').length / items.length;
    if (share > MAX_EXCELLENT_SHARE && !body.calibrationAcknowledged)
      return problem(422, MSG_09_13(share), { code: 'APPRAISAL-422-CALIBRATION-REQUIRED' });
    if (!mockSession.recentMfa()) return deps.stepUpRequired();

    usedNos.add(`${year}|${body.decisionNo!.trim()}`);
    const batchId = crypto.randomUUID();
    const jobId = crypto.randomUUID();
    const failing =
      mockSession.esignDown() || deps.pageQuery().get('mock-esign') === 'loi'
        ? items.slice(-2).map((i) => i.profileId)
        : [];
    const batch: AppraisalBatchDecision = {
      id: batchId,
      period: chosen[0].period,
      flow: body.flow!,
      decisionNo: body.decisionNo!.trim(),
      decisionDate: body.decisionDate!,
      decider: MOCK_DECIDER,
      profileCount: items.length,
      jobId,
      status: 'CHO_KY',
      signedCount: 0,
      failedCount: 0,
      failedItems: [],
      rowVersion: 1,
    };
    const job: Job = {
      id: jobId,
      type: 'ESIGN_BATCH',
      status: 'CHO',
      progress: 0,
      message: 'Đã gửi yêu cầu ký lô tới ứng dụng ký số',
      resultLocation: `/api/v1/appraisals/batch-decisions/${batchId}`,
    };
    runs.set(jobId, {
      batch,
      job,
      input: body as AppraisalBatchDecisionInput,
      pending: items.map((i) => i.profileId),
      fail: new Set(failing),
    });
    return {
      status: 202,
      body: job,
      headers: { Location: `/api/v1/appraisals/batch-jobs/${jobId}` },
    };
  }

  /** Ký thêm một phần lô: hồ sơ ký xong nhận mức `QUYET_DINH`, sang `DA_KY_SO` (EVT-18, EVT-12). */
  function advance(run: BatchRun): void {
    const b = run.batch;
    if (b.status !== 'CHO_KY') return;
    const now = new Date().toISOString();
    const take = run.pending.splice(0, SIGN_PER_POLL);
    for (const id of take) {
      const p = deps.store().find((x) => x.id === id)!;
      if (run.fail.has(id)) {
        b.failedCount = (b.failedCount ?? 0) + 1;
        b.failedItems = [
          ...(b.failedItems ?? []),
          {
            id,
            status: 503,
            problem: { type: 'about:blank', title: 'Dịch vụ ký số không phản hồi', status: 503 },
          },
        ];
        continue;
      }
      const item = run.input.items.find((i) => i.profileId === id)!;
      const proposed = current(p, 'DE_XUAT');
      p.ratingTrail = [
        ...p.ratingTrail,
        {
          id: crypto.randomUUID(),
          levelKind: 'QUYET_DINH',
          ratingCode: item.ratingCode,
          actor: MOCK_DECIDER,
          actorRole: 'decider',
          isCurrent: true,
          differsFromPrev: item.ratingCode !== proposed,
          explanationRequired: item.ratingCode !== proposed,
          isBulkDecision: true,
          batchDecisionId: b.id,
          ...(item.reason ? { reason: item.reason } : {}),
          proposedAt: now,
          signedAt: now,
        },
      ];
      p.status = 'DA_KY_SO';
      p.finalRatingCode = item.ratingCode;
      p.decidedAt = now;
      p.decider = MOCK_DECIDER;
      p.rowVersion++;
      b.signedCount = (b.signedCount ?? 0) + 1;
    }
    if (!run.pending.length) b.status = b.failedCount ? 'LOI_MOT_PHAN' : 'DA_KY';
    b.rowVersion++;
  }

  function jobOf(run: BatchRun): Job {
    const b = run.batch;
    const done = (b.signedCount ?? 0) + (b.failedCount ?? 0);
    const status: Job['status'] =
      b.status === 'HUY' || (b.status === 'LOI_MOT_PHAN' && run.pending.length)
        ? 'DA_HUY'
        : b.status === 'CHO_KY'
          ? 'DANG_CHAY'
          : 'HOAN_TAT';
    return {
      ...run.job,
      status,
      progress: Math.round((done / b.profileCount) * 100),
      message: `Đã ký ${b.signedCount ?? 0}/${b.profileCount} hồ sơ`,
      updatedAt: new Date().toISOString(),
    };
  }

  const byBatch = (id: string) => [...runs.values()].find((r) => r.batch.id === id);
  const etag = (b: AppraisalBatchDecision) => ({ ETag: `"${b.rowVersion}"` });

  return [
    { method: 'POST', pattern: '/api/v1/appraisals/batch-decisions', handle: create },
    {
      method: 'GET',
      pattern: '/api/v1/appraisals/batch-jobs/:id',
      handle: (req) => {
        const run = runs.get(req.pathParams['id']);
        if (!run) return problem(404, 'Không tìm thấy tác vụ ký lô');
        advance(run);
        run.job = jobOf(run);
        return ok(run.job);
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/appraisals/batch-decisions/:id',
      handle: (req) => {
        const run = byBatch(req.pathParams['id']);
        return run
          ? ok(structuredClone(run.batch), etag(run.batch))
          : problem(404, 'Không tìm thấy lô quyết định');
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/appraisals/batch-decisions/:id/cancel',
      handle: (req) => {
        const run = byBatch(req.pathParams['id']);
        if (!run) return problem(404, 'Không tìm thấy lô quyết định');
        const b = run.batch;
        const match = req.headers.get('If-Match');
        if (!match) return problem(428, 'Thiếu If-Match');
        if (b.status === 'DA_KY')
          return problem(409, 'Lô đã ký xong, không huỷ được', {
            code: 'APPRAISAL-409-BATCH-COMPLETED',
          });
        if (match.replace(/"/g, '') !== String(b.rowVersion))
          return problem(409, 'Lô đã được cập nhật, vui lòng tải lại', {
            code: 'APPRAISAL-409-VERSION-CONFLICT',
          });
        if (b.status === 'CHO_KY') {
          b.status = b.signedCount ? 'LOI_MOT_PHAN' : 'HUY';
          b.rowVersion++;
          run.job = jobOf(run);
        }
        return ok(structuredClone(b), etag(b));
      },
    },
  ];
}
