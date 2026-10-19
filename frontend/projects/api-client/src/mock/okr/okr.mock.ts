import { Job } from '../../lib/models/job';
import {
  AiOkrReview,
  Confidence,
  KeyResult,
  Objective,
  ObjectiveDetail,
  ObjectiveDraft,
  OkrBasis,
  OkrComposeContext,
  OkrPeerLink,
  OkrPeriod,
  OkrHistoryEntry,
  OkrPolicy,
  OkrReviewCheck,
  OkrReviewDecisionBody,
  OkrReviewObjective,
  OkrReviewQueue,
  OkrReviewSet,
  OkrTree,
} from '../../lib/models/okr';
import { krProgress, objectiveProgressPct } from '../../lib/okr-progress';
import { MockModule, MockRequest, ok, problem } from '../mock-router';
import { checkinRoutes, myKeyResults, resetCheckins } from './okr-checkin.mock';
import fixture from './okr.json';

type KrFixture = Omit<KeyResult, 'progress'>;
type ObjectiveFixture = Omit<Objective, 'progressPct' | 'keyResults' | 'tier'> & {
  tier: number;
  keyResults: KrFixture[];
};

/** Đơn vị có cây lớn sinh tự động, để thử ảo hoá và hiệu năng của SCR-0201. */
export const OKR_LARGE_UNIT_ID = 'u-duk';

const periods = fixture.periods as OkrPeriod[];
const basisLibrary = fixture.compose.basisLibrary as OkrBasis[];

/** Dữ liệu ghi được (mục tiêu theo kỳ, bản nháp chi tiết), đưa về fixture bằng `reset()`. */
let objectivesByPeriod: Record<string, ObjectiveFixture[]> | null = null;
let drafts: Map<string, ObjectiveDetail> | null = null;
const objectivesState = () =>
  (objectivesByPeriod ??= structuredClone(fixture.objectives) as unknown as Record<
    string,
    ObjectiveFixture[]
  >);
type StaticSet = Omit<
  OkrReviewSet,
  | 'periodId'
  | 'versionNo'
  | 'objectiveCount'
  | 'krCount'
  | 'summary'
  | 'blockingCount'
  | 'warningCount'
>;
/** Bộ OKR chờ duyệt của đơn vị khác theo kỳ, và lịch sử duyệt ghi thêm (WEB-023). */
let reviewSets: Record<string, StaticSet[]> | null = null;
const reviewLog = new Map<string, OkrHistoryEntry[]>();
const reviewState = () =>
  (reviewSets ??= structuredClone(fixture.reviews.sets) as unknown as Record<string, StaticSet[]>);
const draftsState = () =>
  (drafts ??= new Map(
    Object.entries(structuredClone(fixture.drafts) as unknown as Record<string, ObjectiveDetail>),
  ));

function withProgress(o: ObjectiveFixture): Objective {
  const keyResults = o.keyResults.map((k) => ({ ...k, progress: krProgress(k) }));
  return {
    ...o,
    tier: o.tier as Objective['tier'],
    keyResults,
    progressPct: objectiveProgressPct(keyResults),
  };
}

/** Cây lớn: 3 mục tiêu tầng 1, mỗi mục tiêu 12 phòng, mỗi phòng 8 cá nhân (≈ 330 nút, ≈ 900 KR). */
function largeObjectives(): Objective[] {
  const conf: Confidence[] = ['CAO', 'TRUNG_BINH', 'THAP'];
  const out: Objective[] = [];
  const kr = (objId: string, code: string, i: number, n: number): KeyResult => {
    const target = 10 + ((i * 7) % 40);
    const current = (i * 13) % (target + 5);
    return {
      id: `${objId}-kr${n}`,
      objectiveId: objId,
      code,
      title: `Kết quả then chốt ${code}: hoàn thành ${target} sản phẩm đúng hạn`,
      unit: 'sản phẩm',
      baselineValue: 0,
      targetValue: target,
      currentValue: current,
      weight: 1 / 3,
      progress: krProgress({ baselineValue: 0, targetValue: target, currentValue: current }),
      dueDate: '2026-12-31',
      verifierName: 'Trưởng đơn vị',
      dataSource: null,
      valueNote: null,
      confidence: conf[i % 3],
      riskFlag: i % 11 === 0 ? 'RUI_RO' : 'BINH_THUONG',
      lastCheckinAt: '2026-10-16T09:00:00+07:00',
      ownerName: `Cán bộ ${i}`,
      viewerIsOwner: false,
      latestBlocker: null,
      linkedWork: [],
      linkedTaskCount: i % 5,
    };
  };
  const push = (
    id: string,
    code: string,
    title: string,
    tier: 1 | 2 | 3,
    unit: string,
    person: string | null,
    parent: string | null,
    seed: number,
  ) => {
    const keyResults = [1, 2, 3].map((n) => kr(id, `KR ${code.slice(1)}.${n}`, seed + n, n));
    out.push({
      id,
      code,
      title,
      tier,
      ownerType: person ? 'CA_NHAN' : 'TAP_THE',
      ownerUnitName: unit,
      ownerPersonName: person,
      parentObjectiveId: parent,
      status: 'DA_DUYET',
      progressPct: objectiveProgressPct(keyResults),
      approvedAt: '2026-10-08T09:00:00+07:00',
      versionNo: 1,
      overLimitReason: null,
      keyResults,
    });
  };
  for (let a = 1; a <= 3; a++) {
    push(`L${a}`, `O${a}`, `Mục tiêu ${a} của Đảng uỷ khối`, 1, 'Đảng uỷ khối', null, null, a);
    for (let b = 1; b <= 12; b++) {
      const unit = `Đảng uỷ cơ sở số ${(a - 1) * 12 + b}`;
      push(`L${a}-${b}`, `O${a}.${b}`, `Mục tiêu của ${unit}`, 2, unit, null, `L${a}`, a * 100 + b);
      for (let c = 1; c <= 8; c++) {
        push(
          `L${a}-${b}-${c}`,
          `O${a}.${b}.${c}`,
          `Mục tiêu cá nhân ${c} thuộc ${unit}`,
          3,
          unit,
          `Đảng viên ${a}.${b}.${c}`,
          `L${a}-${b}`,
          a * 1000 + b * 10 + c,
        );
      }
    }
  }
  return out;
}

function buildTree(req: MockRequest) {
  const periodId = req.params.get('period') ?? fixture.currentPeriodId;
  const period = periods.find((p) => p.id === periodId);
  if (!period) return problem(404, 'Không tìm thấy kỳ đánh giá', { detail: `Kỳ ${periodId}` });
  const unitId = req.params.get('unit') ?? fixture.unit.id;
  const large = unitId === OKR_LARGE_UNIT_ID;
  if (!large && unitId !== fixture.unit.id) {
    const reviewed = reviewUnitTree(unitId, period);
    return reviewed
      ? ok(reviewed)
      : problem(403, 'Đồng chí không có quyền xem mục tiêu của đơn vị này');
  }
  const byPeriod = objectivesState();
  const objectives = large
    ? period.id === fixture.currentPeriodId
      ? largeObjectives()
      : []
    : (byPeriod[period.id] ?? []).map(withProgress);
  const tree: OkrTree = {
    period,
    periods,
    unit: large ? { id: OKR_LARGE_UNIT_ID, name: 'Đảng uỷ khối' } : fixture.unit,
    viewerOwnUnit: !large,
    bases: fixture.bases as OkrTree['bases'],
    parent: large ? null : (fixture.parent as OkrTree['parent']),
    recommendedMax: fixture.recommendedMax,
    objectives,
    peerLinks: large ? [] : ((fixture.peerLinks as Record<string, OkrPeerLink[]>)[period.id] ?? []),
  };
  return ok(tree);
}

/** Máy chủ cấp mã và id cho KR mới; giữ id cũ của KR đã có. */
function withKrIds(d: ObjectiveDraft, n: number): ObjectiveDraft['keyResults'] {
  return d.keyResults.map((k, i) => ({
    ...k,
    id: k.id ?? `kr-${crypto.randomUUID().slice(0, 8)}`,
    code: `KR ${n}.${i + 1}`,
  }));
}

/** Cập nhật cây SCR-0201 theo bản nháp vừa lưu. */
function syncTree(d: ObjectiveDetail) {
  const list = (objectivesState()[d.periodId] ??= []);
  const verifierName = (id: string | null) =>
    fixture.compose.verifiers.find((v) => v.id === id)?.title ?? '';
  const node: ObjectiveFixture = {
    id: d.id,
    code: d.code,
    title: d.title,
    tier: 1,
    ownerType: d.ownerType,
    ownerUnitName: d.ownerUnitName,
    ownerPersonName: d.ownerType === 'CA_NHAN' ? d.authorName : null,
    parentObjectiveId: d.parentObjectiveId,
    status: d.status,
    approvedAt: null,
    versionNo: d.versionNo,
    overLimitReason: d.overLimitReason,
    keyResults: d.keyResults.map((k) => ({
      id: k.id!,
      objectiveId: d.id,
      code: k.code,
      title: k.title,
      unit: k.unit,
      baselineValue: k.baselineValue,
      targetValue: k.targetValue,
      currentValue: null,
      weight: k.weight,
      dueDate: k.dueDate ?? '',
      verifierName: verifierName(k.verifierId),
      dataSource: null,
      valueNote: null,
      confidence: null,
      riskFlag: 'BINH_THUONG',
      lastCheckinAt: null,
      ownerName: d.authorName,
      viewerIsOwner: true,
      latestBlocker: null,
      linkedWork: [],
      linkedTaskCount: 0,
    })),
  };
  const i = list.findIndex((o) => o.id === d.id);
  if (i >= 0) list[i] = node;
  else list.push(node);
}

/** Kiểm tra lại ở máy chủ khi trình (rút gọn từ Policy Pack): những quy tắc chặn chính. */
function serverCheck(d: ObjectiveDetail): string[] {
  const errs: string[] = [];
  const policy = fixture.compose.policy as OkrPolicy;
  if (!d.title.trim()) errs.push('Tên mục tiêu không được trống.');
  if (d.basisIds.length === 0) errs.push('Mục tiêu phải có ít nhất một căn cứ.');
  if (!d.parentObjectiveId) errs.push('Mục tiêu phải liên kết mục tiêu cấp trên.');
  const parent = fixture.compose.parentOptions.find((p) => p.id === d.parentObjectiveId);
  if (parent && parent.status !== 'DA_DUYET') {
    errs.push(
      `Mục tiêu cấp trên ${parent.code} chưa được duyệt. Đồng chí có thể soạn nhưng chưa trình được.`,
    );
  }
  const total = Math.round(d.keyResults.reduce((s, k) => s + k.weight, 0) * 100);
  if (total !== 100) errs.push(`Tổng trọng số KR là ${total}%, cần bằng 100%.`);
  for (const k of d.keyResults) {
    const t = k.title.toLocaleLowerCase('vi');
    const vague = policy.vagueWords.find((w) => t.includes(w));
    if (
      !k.unit ||
      k.targetValue === null ||
      k.baselineValue === null ||
      !k.dueDate ||
      !k.verifierId
    ) {
      errs.push(
        vague
          ? `KPI mơ hồ: "${vague}" không đo được. Vui lòng bổ sung chỉ tiêu và đơn vị đo.`
          : `${k.code} thiếu thông tin bắt buộc.`,
      );
    }
    if (k.verifierId === fixture.compose.me.id)
      errs.push(`${k.code}: người xác nhận phải khác chủ sở hữu KR.`);
  }
  const list = objectivesState()[d.periodId] ?? [];
  const count = list.filter((o) => o.tier === 1).length;
  if (count > policy.recommendedMax && !d.overLimitReason?.trim()) {
    errs.push(`Vui lòng ghi lý do vượt ${policy.recommendedMax} mục tiêu.`);
  }
  return errs;
}

function composeContext(req: MockRequest) {
  const periodId = req.params.get('period') ?? fixture.currentPeriodId;
  const period = periods.find((p) => p.id === periodId);
  if (!period) return problem(404, 'Không tìm thấy kỳ đánh giá');
  const self = req.params.get('objective');
  const existing = (objectivesState()[period.id] ?? []).filter(
    (o) => o.tier === 1 && o.id !== self,
  ).length;
  const c = fixture.compose;
  const ctx: OkrComposeContext = {
    period,
    periods: periods.filter((p) => p.status === 'MO'),
    unit: fixture.unit,
    isTopLevel: c.isTopLevel,
    bases: basisLibrary,
    parentOptions: c.parentOptions.map((o) => ({
      ...(o as OkrComposeContext['parentOptions'][number]),
      periodId: period.id,
    })),
    existingCount: existing,
    verifiers: c.verifiers,
    me: c.me,
    aiEnabled: req.params.get('ai') !== 'off',
    policy: c.policy as OkrPolicy,
  };
  return ok(ctx);
}

/**
 * Cây chỉ đọc của đơn vị cấp dưới có bộ OKR trong hàng chờ (SCR-0203 "Xem cây"): người duyệt `leader`
 * xem được cây cấp dưới. Trạng thái lấy theo quyết định đã ghi (còn trong hàng chờ là `CHO_DUYET`).
 */
function reviewUnitTree(unitId: string, period: OkrPeriod): OkrTree | null {
  const sets = (fixture.reviews.sets as unknown as Record<string, StaticSet[]>)[period.id] ?? [];
  const set = sets.find((x) => x.unit.id === unitId);
  if (!set) return null;
  const status = (id: string): Objective['status'] => {
    const last = reviewLog.get(id)?.[0];
    return last ? (last.action === 'DUYET' ? 'DA_DUYET' : 'NHAP') : 'CHO_DUYET';
  };
  const objectives: Objective[] = set.objectives.map((o) => ({
    id: o.id,
    code: o.code,
    title: o.title,
    tier: 1,
    ownerType: 'TAP_THE',
    ownerUnitName: set.unit.name,
    ownerPersonName: null,
    parentObjectiveId: null,
    status: status(o.id),
    progressPct: 0,
    approvedAt: null,
    versionNo: o.versionNo,
    overLimitReason: null,
    keyResults: o.keyResults.map((k) => ({
      id: k.id,
      objectiveId: o.id,
      code: k.code,
      title: k.title,
      unit: '',
      baselineValue: null,
      targetValue: null,
      currentValue: null,
      weight: k.weight,
      progress: 0,
      dueDate: period.endDate,
      verifierName: '',
      dataSource: null,
      valueNote: k.targetText,
      confidence: null,
      riskFlag: 'BINH_THUONG',
      lastCheckinAt: null,
      ownerName: set.submittedBy,
      viewerIsOwner: false,
      latestBlocker: null,
      linkedWork: [],
      linkedTaskCount: 0,
    })),
  }));
  return {
    period,
    periods,
    unit: set.unit,
    viewerOwnUnit: false,
    bases: [],
    parent: null,
    recommendedMax: fixture.recommendedMax,
    objectives,
    peerLinks: [],
  };
}

const REVIEW_OK: OkrReviewCheck = { tone: 'DAT', label: 'Đạt' };

function dueDateOf(periodId: string): string {
  const dues = fixture.reviews.dueDates as Record<string, string>;
  return dues[periodId] ?? periods.find((p) => p.id === periodId)!.startDate;
}

/** Bộ OKR của Ban Tổ chức (đơn vị của fixture): mục tiêu tầng 1 đang `CHO_DUYET`, kiểm tra lại từ Policy Pack. */
function ownUnitSet(periodId: string): StaticSet | null {
  const policy = fixture.compose.policy as OkrPolicy;
  const list = (objectivesState()[periodId] ?? []).filter(
    (o) => o.tier === 1 && o.status === 'CHO_DUYET',
  );
  if (!list.length) return null;
  const parentCode = (id: string | null) =>
    fixture.compose.parentOptions.find((p) => p.id === id)?.code ?? null;
  const objectives: OkrReviewObjective[] = list.map((o) => {
    const d = draftsState().get(o.id);
    return {
      id: o.id,
      code: o.code,
      title: o.title,
      parentCode: parentCode(o.parentObjectiveId),
      versionNo: o.versionNo,
      rowVersion: d?.rowVersion ?? 1,
      check:
        (d?.basisIds.length ?? 1) > 0
          ? { tone: 'DAT', label: 'Có căn cứ' }
          : { tone: 'LOI', label: 'Thiếu căn cứ' },
      keyResults: o.keyResults.map((k) => {
        const t = k.title.toLocaleLowerCase('vi');
        const check: OkrReviewCheck = policy.vagueWords.some((w) => t.includes(w))
          ? { tone: 'CANH_BAO', label: 'KR mơ hồ' }
          : policy.taskVerbs.some((v) => t.startsWith(v + ' '))
            ? { tone: 'CANH_BAO', label: 'Giống nhiệm vụ' }
            : { tone: 'DAT', label: 'Đo được' };
        return {
          id: k.id,
          code: k.code,
          title: k.title,
          targetText:
            k.targetValue === null ? '—' : `${k.targetValue}${k.unit === '%' ? '%' : ' ' + k.unit}`,
          weight: k.weight,
          link: null,
          check,
        };
      }),
    };
  });
  const author = draftsState().get(list[0].id)?.authorName ?? fixture.compose.me.name;
  return {
    id: `rs-${fixture.unit.id}`,
    unit: { id: fixture.unit.id, name: fixture.unit.name },
    submittedAt: draftsState().get(list[0].id)?.updatedAt ?? new Date().toISOString(),
    submittedBy: author,
    viewerIsAuthor: author === fixture.reviews.viewer.name,
    objectives,
  };
}

/** Bổ sung số đếm và nhãn tổng hợp của hàng chờ ("Đạt", "n cảnh báo", "Vượt 3 O", "KR mơ hồ"). */
function summarize(s: StaticSet, periodId: string): OkrReviewSet {
  const checks = s.objectives.flatMap((o) => [o.check, ...o.keyResults.map((k) => k.check)]);
  const blocking = checks.filter((c) => c.tone === 'LOI').length;
  const over = s.objectives.length > fixture.compose.policy.recommendedMax;
  const warnings = checks.filter((c) => c.tone === 'CANH_BAO').length + (over ? 1 : 0);
  const vague = checks.some((c) => c.label === 'KR mơ hồ');
  const summary: OkrReviewCheck = blocking
    ? { tone: 'LOI', label: `${blocking} lỗi` }
    : over
      ? { tone: 'CANH_BAO', label: `Vượt ${fixture.compose.policy.recommendedMax} O` }
      : vague
        ? { tone: 'CANH_BAO', label: 'KR mơ hồ' }
        : warnings
          ? { tone: 'CANH_BAO', label: `${warnings} cảnh báo` }
          : REVIEW_OK;
  return {
    ...s,
    periodId,
    versionNo: Math.max(...s.objectives.map((o) => o.versionNo)),
    objectiveCount: s.objectives.length,
    krCount: s.objectives.reduce((n, o) => n + o.keyResults.length, 0),
    summary,
    blockingCount: blocking,
    warningCount: warnings,
  };
}

function reviewQueue(req: MockRequest) {
  const periodId = req.params.get('period') ?? fixture.currentPeriodId;
  const period = periods.find((p) => p.id === periodId);
  if (!period) return problem(404, 'Không tìm thấy kỳ đánh giá');
  const own = ownUnitSet(periodId);
  const sets = [...(own ? [own] : []), ...(reviewState()[periodId] ?? [])]
    .filter((x) => x.objectives.length > 0)
    .map((x) => summarize(x, periodId))
    .sort((a, b) => a.submittedAt.localeCompare(b.submittedAt));
  const queue: OkrReviewQueue = {
    period,
    periods,
    dueDate: dueDateOf(periodId),
    reviewer: fixture.reviews.reviewer,
    workflow: fixture.reviews.workflow,
    items: sets,
  };
  return ok(queue);
}

/** Thực hiện duyệt hoặc trả lại một mục tiêu; dùng chung cho thao tác đơn và lô. */
function applyDecision(
  id: string,
  rowVersion: number | null,
  decision: 'DUYET' | 'TRA_LAI',
  body: OkrReviewDecisionBody,
): { status: number; title?: string; code?: string } {
  const viewer = fixture.reviews.viewer;
  const entry = (versionNo: number): OkrHistoryEntry => ({
    at: new Date().toISOString(),
    action: decision,
    versionNo,
    status: decision === 'DUYET' ? 'DA_DUYET' : 'NHAP',
    actor: viewer,
    ...(body.comment ? { comment: body.comment } : {}),
  });
  // Bộ của đơn vị khác
  for (const list of Object.values(reviewState())) {
    for (const set of list) {
      const i = set.objectives.findIndex((o) => o.id === id);
      if (i < 0) continue;
      const o = set.objectives[i];
      if (set.viewerIsAuthor && decision === 'DUYET') {
        return {
          status: 403,
          title: 'Đồng chí là người soạn bộ OKR này nên không thể phê duyệt.',
          code: 'OKR-403-SELF-APPROVAL',
        };
      }
      if (rowVersion !== null && rowVersion !== o.rowVersion) {
        return {
          status: 409,
          title: 'Mục tiêu vừa được cập nhật, vui lòng tải lại.',
          code: 'OKR-409-VERSION-CONFLICT',
        };
      }
      set.objectives.splice(i, 1);
      reviewLog.set(id, [entry(o.versionNo), ...(reviewLog.get(id) ?? [])]);
      return { status: 200 };
    }
  }
  // Mục tiêu của Ban Tổ chức (soạn ở SCR-0202)
  const d = draftsState().get(id);
  if (!d) return { status: 404, title: 'Không tìm thấy mục tiêu' };
  if (d.status !== 'CHO_DUYET') {
    return {
      status: 409,
      title: 'Mục tiêu không ở trạng thái chờ duyệt',
      code: 'OKR-409-INVALID-STATE',
    };
  }
  if (decision === 'DUYET' && d.authorName === viewer.name) {
    return {
      status: 403,
      title: 'Đồng chí là người soạn bộ OKR này nên không thể phê duyệt.',
      code: 'OKR-403-SELF-APPROVAL',
    };
  }
  if (rowVersion !== null && rowVersion !== d.rowVersion) {
    return {
      status: 409,
      title: 'Mục tiêu vừa được cập nhật, vui lòng tải lại.',
      code: 'OKR-409-VERSION-CONFLICT',
    };
  }
  const next: ObjectiveDetail = {
    ...d,
    status: decision === 'DUYET' ? 'DA_DUYET' : 'NHAP',
    rowVersion: d.rowVersion + 1,
  };
  draftsState().set(id, next);
  syncTree(next);
  if (decision === 'DUYET') {
    const node = objectivesState()[d.periodId]?.find((o) => o.id === id);
    if (node) node.approvedAt = new Date().toISOString();
  }
  reviewLog.set(id, [entry(d.versionNo), ...(reviewLog.get(id) ?? [])]);
  return { status: 200 };
}

function decide(req: MockRequest, decision: 'DUYET' | 'TRA_LAI') {
  const ifMatch = req.headers.get('If-Match');
  if (!ifMatch) return problem(428, 'Thiếu If-Match');
  const body = (req.body ?? {}) as OkrReviewDecisionBody;
  if (decision === 'TRA_LAI' && !body.comment?.trim()) {
    return problem(422, 'Vui lòng nhập ý kiến khi trả lại.', { code: 'OKR-422-COMMENT-REQUIRED' });
  }
  const r = applyDecision(req.pathParams['id'], Number(ifMatch.replace(/"/g, '')), decision, body);
  if (r.status !== 200) return problem(r.status, r.title ?? 'Lỗi', r.code ? { code: r.code } : {});
  return ok({ id: req.pathParams['id'], status: decision === 'DUYET' ? 'DA_DUYET' : 'NHAP' });
}

/** Lịch sử gốc: lần lưu nháp, lần trình của mục tiêu (trước các quyết định ghi trong `reviewLog`). */
function baseHistory(id: string): OkrHistoryEntry[] | null {
  for (const list of Object.values(
    fixture.reviews.sets as unknown as Record<string, StaticSet[]>,
  )) {
    const set = list.find((x) => x.objectives.some((o) => o.id === id));
    if (set) {
      const actor = { id: `p-${set.unit.id}`, name: set.submittedBy };
      return [
        { at: set.submittedAt, action: 'TRINH', versionNo: 1, status: 'CHO_DUYET', actor },
        { at: set.submittedAt, action: 'LUU_NHAP', versionNo: 1, status: 'NHAP', actor },
      ];
    }
  }
  const d = draftsState().get(id);
  if (!d) return null;
  const actor = { id: fixture.compose.me.id, name: d.authorName };
  const saved: OkrHistoryEntry = {
    at: d.updatedAt,
    action: 'LUU_NHAP',
    versionNo: d.versionNo,
    status: 'NHAP',
    actor,
    comment: `Bản nháp ${d.draftNo}`,
    aiAssisted: d.keyResults.some((k) => k.contentSource !== 'HUMAN'),
  };
  return d.status === 'NHAP'
    ? [saved]
    : [
        { at: d.updatedAt, action: 'TRINH', versionNo: d.versionNo, status: 'CHO_DUYET', actor },
        saved,
      ];
}

const exportJobs = new Map<string, Job>();

/**
 * Giả lập `/api/v1/okr`: KR để liên kết nhiệm vụ (WEB-028), cây mục tiêu, lịch sử check-in và
 * xuất mẫu (WEB-021, SCR-0201); check-in KR (WEB-024, SCR-0301) ở `okr-checkin.mock.ts`.
 */
export const OKR_MOCK: MockModule = {
  name: 'okr',
  reset: () => {
    exportJobs.clear();
    objectivesByPeriod = null;
    drafts = null;
    reviewSets = null;
    reviewLog.clear();
    resetCheckins();
  },
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/okr/key-results',
      handle: (req) =>
        req.params.get('owner') === 'me'
          ? myKeyResults(req, objectivesState())
          : ok({ items: fixture.keyResults, nextCursor: null }),
    },
    {
      method: 'GET',
      pattern: '/api/v1/okr/objectives',
      handle: (req) =>
        req.params.get('view') === 'tree'
          ? buildTree(req)
          : problem(400, 'Chế độ xem chưa hỗ trợ trong dữ liệu giả lập'),
    },
    ...checkinRoutes(objectivesState),
    {
      method: 'GET',
      pattern: '/api/v1/okr/compose-context',
      handle: (req) => composeContext(req),
    },
    {
      method: 'GET',
      pattern: '/api/v1/okr/objectives/:id',
      handle: (req) => {
        const id = req.pathParams['id'];
        // Mục tiêu của đơn vị cấp trên: ngoài phạm vi soạn của người dùng (ABAC ở máy chủ)
        if (fixture.compose.parentOptions.some((p) => p.id === id)) {
          return problem(403, 'Đồng chí không có quyền sửa mục tiêu của đơn vị khác.', {
            code: 'OKR-403-SCOPE',
          });
        }
        const d = draftsState().get(id);
        return d ? ok(d, { ETag: `"${d.rowVersion}"` }) : problem(404, 'Không tìm thấy mục tiêu');
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/okr/objectives',
      handle: (req) => {
        const body = req.body as ObjectiveDraft;
        const period = periods.find((p) => p.id === body.periodId);
        if (!period || period.status === 'DA_KHOA' || period.status === 'DANG_KHOA') {
          return problem(409, 'Kỳ đã khoá, không tạo được mục tiêu', {
            code: 'OKR-409-PERIOD-LOCKED',
          });
        }
        const list = (objectivesState()[period.id] ??= []);
        const n = list.filter((o) => o.tier === 1).length + 1;
        const id = `o-new-${crypto.randomUUID().slice(0, 8)}`;
        const detail: ObjectiveDetail = {
          ...body,
          id,
          code: `O${n}`,
          status: 'NHAP',
          versionNo: 1,
          rowVersion: 1,
          ownerUnitName: fixture.unit.name,
          authorName: fixture.compose.me.name,
          updatedAt: new Date().toISOString(),
          draftNo: 1,
          keyResults: withKrIds(body, n),
        };
        draftsState().set(id, detail);
        syncTree(detail);
        return {
          status: 201,
          body: detail,
          headers: { ETag: '"1"', Location: `/api/v1/okr/objectives/${id}` },
        };
      },
    },
    {
      method: 'PUT',
      pattern: '/api/v1/okr/objectives/:id',
      handle: (req) => {
        const cur = draftsState().get(req.pathParams['id']);
        if (!cur) return problem(404, 'Không tìm thấy mục tiêu');
        const ifMatch = req.headers.get('If-Match');
        if (!ifMatch) return problem(428, 'Thiếu If-Match');
        if (ifMatch.replace(/"/g, '') !== String(cur.rowVersion)) {
          return problem(409, 'Mục tiêu vừa được người khác cập nhật. Vui lòng tải lại.');
        }
        if (cur.status !== 'NHAP') {
          return problem(409, 'Mục tiêu đã trình hoặc đã duyệt không sửa tại chỗ (BR-02-10)', {
            code: 'OKR-409-NOT-DRAFT',
          });
        }
        const body = req.body as ObjectiveDraft;
        const n = Number(cur.code.slice(1)) || 1;
        const next: ObjectiveDetail = {
          ...cur,
          ...body,
          id: cur.id,
          keyResults: withKrIds(body, n),
          rowVersion: cur.rowVersion + 1,
          draftNo: cur.draftNo + 1,
          updatedAt: new Date().toISOString(),
        };
        draftsState().set(cur.id, next);
        syncTree(next);
        return ok(next, { ETag: `"${next.rowVersion}"` });
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/okr/objectives/:id/submit',
      handle: (req) => {
        const cur = draftsState().get(req.pathParams['id']);
        if (!cur) return problem(404, 'Không tìm thấy mục tiêu');
        if (cur.status !== 'NHAP')
          return problem(409, 'Mục tiêu không ở trạng thái nháp', { code: 'OKR-409-NOT-DRAFT' });
        const errors = serverCheck(cur);
        if (errors.length) {
          return problem(422, 'Bộ OKR còn lỗi chặn trình duyệt', {
            code: 'OKR-422-RULES',
            errors: errors.map((message) => ({ field: '', message })),
          });
        }
        const next: ObjectiveDetail = {
          ...cur,
          status: 'CHO_DUYET',
          rowVersion: cur.rowVersion + 1,
        };
        draftsState().set(cur.id, next);
        syncTree(next);
        const period = periods.find((p) => p.id === cur.periodId)!;
        return ok({
          status: 'CHO_DUYET',
          reviewerName: `${fixture.compose.reviewer.title} ${fixture.compose.reviewer.name}`,
          dueDate: dueDateOf(period.id),
        });
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/ai/okr/review',
      handle: (req) => {
        const body = req.body as { keyResults: { code: string; title: string }[] };
        const out: AiOkrReview = { suggestions: [] };
        for (const k of body.keyResults ?? []) {
          const t = k.title.toLocaleLowerCase('vi');
          const hit = fixture.aiReview.find((a) => t.includes(a.match));
          if (hit) {
            out.suggestions.push({
              id: `${k.code}-${hit.match}`,
              krCode: k.code,
              issue: hit.issue,
              rewrites: hit.rewrites,
              sources: hit.sources,
            });
          }
        }
        return ok(out);
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/okr/reviews',
      handle: (req) => reviewQueue(req),
    },
    {
      method: 'POST',
      pattern: '/api/v1/okr/objectives/:id/approve',
      handle: (req) => decide(req, 'DUYET'),
    },
    {
      method: 'POST',
      pattern: '/api/v1/okr/objectives/:id/return',
      handle: (req) => decide(req, 'TRA_LAI'),
    },
    {
      method: 'POST',
      pattern: '/api/v1/okr/objectives/approve-batch',
      handle: (req) => {
        const body = req.body as OkrReviewDecisionBody & {
          items: { id: string; rowVersion: number }[];
        };
        if (!body.items?.length || body.items.length > 200) {
          return problem(422, 'Danh sách duyệt hàng loạt phải có từ 1 đến 200 mục');
        }
        const results = body.items.map((it) => {
          const r = applyDecision(it.id, it.rowVersion, 'DUYET', body);
          return { id: it.id, status: r.status };
        });
        const okCount = results.filter((r) => r.status === 200).length;
        const id = `batch-${crypto.randomUUID().slice(0, 8)}`;
        exportJobs.set(id, {
          id,
          type: 'OKR_APPROVE_BATCH',
          status: 'HOAN_TAT',
          progress: 100,
          message: `Đã phê duyệt ${okCount}/${results.length} mục tiêu`,
          resultLocation: `/api/v1/okr/exports/${id}/result`,
        });
        return { status: 202, body: null, headers: { Location: `/api/v1/okr/exports/${id}` } };
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/okr/objectives/:id/history',
      handle: (req) => {
        const id = req.pathParams['id'];
        const base = baseHistory(id);
        if (!base) return problem(404, 'Không tìm thấy mục tiêu');
        const items = [...(reviewLog.get(id) ?? []), ...base];
        return ok({ items, nextCursor: null });
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/okr/objectives/export',
      handle: () => {
        const id = crypto.randomUUID();
        exportJobs.set(id, {
          id,
          type: 'EXPORT',
          status: 'HOAN_TAT',
          progress: 100,
          message: 'Đã sinh tệp theo mẫu báo cáo OKR',
          resultLocation: `/api/v1/okr/exports/${id}/file`,
        });
        return { status: 202, body: null, headers: { Location: `/api/v1/okr/exports/${id}` } };
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/okr/exports/:id',
      handle: (req) => {
        const job = exportJobs.get(req.pathParams['id']);
        return job ? ok(job) : problem(404, 'Không tìm thấy tác vụ xuất mẫu');
      },
    },
  ],
};
