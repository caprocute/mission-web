import { Job } from '../../lib/models/job';
import {
  Plan,
  PlanChangeType,
  PlanCheck,
  PlanDiff,
  PlanDiffLine,
  PlanItem,
  PlanItemInput,
  PlanGroupWeights,
  PlanItemRef,
  PlanRemovedItem,
  PlanSignRequest,
  PlanVersion,
  PlanVersionStatus,
  PlanVersionSummary,
  SignMethod,
} from '../../lib/models/plan';
import { PeriodRef, TaskGroup } from '../../lib/models/task';
import {
  MockModule,
  MockRequest,
  MockResponse,
  ok,
  problem,
  resolveRelativeDates,
} from '../mock-router';
import catalogFixture from '../catalog/catalog.json';
import okrFixture from '../okr/okr.json';
import orgFixture from '../org/org.json';
import { MOCK_ME, mockSession } from '../mock-session';
import { workingDaysBetween } from '../tasks/working-days';
import { mockPeriodInfo } from '../periods/periods.mock';
import fixture from './plans.json';

/**
 * Giả lập `/api/v1/plans` (QT-04, MOD-02) theo hợp đồng `tasks.yaml` (WEB-065): dòng kế hoạch đã duyệt
 * cho SCR-0501 (WEB-028) và lập kế hoạch quý SCR-0402 (WEB-026): danh sách kế hoạch, phiên bản, kiểm tra
 * tức thì, lưu nháp (If-Match), sao chép quý trước, gợi ý từ OKR, gửi duyệt, tạo điều chỉnh; phê duyệt
 * SCR-0403 (WEB-027): so sánh phiên bản, trả lại, duyệt và ký số (202 Job, yêu cầu ký của esign-service).
 * Máy chủ là nơi chặn BR-04-02 … BR-04-06, BR-04-08, BR-04-10 và MFA bước lên.
 */

/** Dòng kế hoạch đã duyệt, ngày tương đối đã quy đổi. */
export const approvedPlanItems = (): PlanItemRef[] =>
  resolveRelativeDates(structuredClone(fixture.items) as PlanItemRef[]);

/** Phiên bản trong kho giả lập: ngoài trường của hợp đồng còn người trình, mốc thời gian, dòng đã bỏ. */
interface StoredVersion {
  versionNo: number;
  status: PlanVersionStatus;
  rowVersion: number;
  items: PlanItem[];
  contentHash?: string | null;
  reviewComment?: string | null;
  signRequestId?: string;
  submittedAt?: string;
  submittedById?: string;
  submittedByName?: string;
  submittedByTitle?: string;
  approvedAt?: string;
  approvedByName?: string;
  /** Lý do chung của lần điều chỉnh (`task.plan_version.change_reason`). */
  changeReason?: string | null;
  /** Dòng của phiên bản trước bị bỏ, kèm lý do (BR-04-10). */
  removedItems?: PlanRemovedItem[];
}
interface StoredPlan {
  id: string;
  code: string;
  period: PeriodRef;
  approvalDeadline: string;
  unitId: string;
  unitName: string;
  effectiveVersionNo?: number;
  versions: StoredVersion[];
}
type StoredSign = PlanSignRequest & {
  planId: string;
  versionNo: number;
  method: SignMethod;
  comment?: string;
  polls: number;
};

let plans: StoredPlan[] | null = null;
let signs: StoredSign[] = [];
let seq = 0;

const todayIso = () => new Date().toISOString().slice(0, 10);
const catalogItem = (id?: string) => catalogFixture.items.find((i) => i.id === id);
const keyResults = () => okrFixture.keyResults;
const unitRef = (id: string) => ({
  id,
  name: orgFixture.units.find((u) => u.id === id)?.name ?? '',
});
const groupWeights = (): PlanGroupWeights => ({ ...fixture.groupWeights }) as PlanGroupWeights;

/** Dòng 5–7 của phiên bản 2 Quý IV là các dòng đã duyệt dùng cho giao việc (`items`). */
function fromApproved(r: PlanItemRef, unit: { id: string; name: string }): PlanItem {
  const ci = catalogItem(r.catalogItemId);
  return {
    id: r.id,
    lineNo: r.lineNo,
    title: r.title,
    taskGroup: r.taskGroup,
    keyResultId: r.keyResultId,
    leadAssignee: { kind: 'UNIT', ...unit },
    coAssignees: [],
    catalogItemId: r.catalogItemId,
    productName: r.productName,
    quantity: r.quantity,
    unit: r.unit,
    dueDate: r.dueDate,
    standardScore: ci?.baseScore ?? null,
  };
}

function withCatalog(i: PlanItem): PlanItem {
  const ci = catalogItem(i.catalogItemId);
  // BR-04-05: điểm của dòng lấy từ danh mục; việc ngoài danh mục để cấp duyệt chấm.
  const standardScore = ci ? ci.baseScore : (i.standardScore ?? null);
  return { ...i, catalogItemCode: ci?.code, standardScore, pendingScore: standardScore == null };
}

const v1Id = (id: string) => (id.includes('-v2-') ? id.replace('-v2-', '-v1-') : `${id}-v1`);

function store(): StoredPlan[] {
  if (plans) return plans;
  const raw = resolveRelativeDates(structuredClone(fixture.plans)) as unknown as StoredPlan[];
  plans = raw.map((p) => {
    const versions = p.versions.map((v) => ({ ...v }));
    const q4v2 = p.id === 'plan-2026-q4' ? versions.find((v) => v.versionNo === 2) : undefined;
    if (q4v2)
      q4v2.items = [
        ...q4v2.items,
        ...approvedPlanItems().map((r) => fromApproved(r, unitRef(p.unitId))),
      ].sort((a, b) => a.lineNo - b.lineNo);
    // Hình 7.14: phiên bản 1 = phiên bản 2 bỏ dòng thêm, số lượng dòng sửa như cũ, cộng dòng đã bỏ
    const adj = resolveRelativeDates(structuredClone(fixture.q4Adjustment));
    for (const v of versions) {
      if ((v.items as unknown) === '@v2-without-tc-dv-06' && q4v2) {
        v.items = [
          ...q4v2.items
            .filter((i) => i.catalogItemId !== adj.addedCatalogItemId)
            .map((i) => ({
              ...i,
              id: v1Id(i.id!),
              quantity: i.id === adj.changedItemId ? adj.quantityBefore : i.quantity,
              changeReason: undefined,
            })),
          adj.removedItem as PlanItem,
        ].map((i, n) => ({ ...i, lineNo: n + 1 }));
      }
      v.items = v.items.map(withCatalog);
    }
    if (q4v2) {
      const v1 = versions.find((v) => v.versionNo === 1)!;
      q4v2.items = q4v2.items.map((i) => ({
        ...i,
        originItemId: v1.items.find((o) => o.id === v1Id(i.id!))?.id,
        changeReason: i.catalogItemId === adj.addedCatalogItemId ? adj.addedReason : i.changeReason,
      }));
    }
    // phiên bản đã gửi duyệt có mã băm như lúc gửi
    for (const v of versions) if (v.status !== 'NHAP') v.contentHash ??= contentHash(v.items);
    return { ...p, versions };
  });
  return plans;
}

/** Mã băm 256 bit của nội dung các dòng (giả lập; máy chủ dùng SHA-256 trên dạng chuẩn hoá). */
function contentHash(items: PlanItem[]): string {
  const s = JSON.stringify(items.map(({ id: _id, ...rest }) => rest));
  let out = '';
  for (let seed = 0; seed < 8; seed++) {
    let h = 0x811c9dc5 ^ (seed * 0x9e3779b1);
    for (let i = 0; i < s.length; i++) h = Math.imul(h ^ s.charCodeAt(i), 0x01000193);
    out += (h >>> 0).toString(16).padStart(8, '0');
  }
  return out;
}

const latest = (p: StoredPlan) => p.versions[p.versions.length - 1];
const effective = (p: StoredPlan) => p.versions.find((v) => v.versionNo === p.effectiveVersionNo);

/** Kế hoạch của kỳ liền trước có phiên bản đã duyệt (nguồn "Sao chép từ {kỳ}", `Plan.previousPeriod`). */
function previousPlan(p: StoredPlan): StoredPlan | undefined {
  return store()
    .filter((x) => x.period.endDate < p.period.startDate && effective(x))
    .sort((a, b) => b.period.endDate.localeCompare(a.period.endDate))[0];
}

/** Dòng bị bỏ kèm lý do trong thân `PlanVersionInput`; chỉ có nghĩa ở phiên bản điều chỉnh. */
function removedOf(body: unknown, v: StoredVersion): PlanRemovedItem[] | undefined {
  if (v.versionNo === 1) return undefined;
  const list = (body as { removedItems?: Partial<PlanRemovedItem>[] })?.removedItems ?? [];
  return list
    .filter((r) => r.originItemId)
    .map((r) => ({ originItemId: r.originItemId!, changeReason: (r.changeReason ?? '').trim() }));
}

function summaryOf(p: StoredPlan, v: StoredVersion): PlanVersionSummary {
  return {
    planId: p.id,
    versionNo: v.versionNo,
    status: v.status,
    unitName: p.unitName,
    periodLabel: p.period.label,
    itemCount: v.items.length,
    submittedAt: v.submittedAt,
    submittedByName: v.submittedByName,
    submittedByTitle: v.submittedByTitle,
    approvedAt: v.approvedAt,
    approvedByName: v.approvedByName,
  };
}

function planOf(p: StoredPlan): Plan {
  return structuredClone({
    id: p.id,
    code: p.code,
    period: p.period,
    unitId: p.unitId,
    unitName: p.unitName,
    currentVersion: summaryOf(p, latest(p)),
    effectiveVersionNo: p.effectiveVersionNo ?? null,
    previousPeriod: previousPlan(p)?.period ?? null,
    approvalDeadline: p.approvalDeadline,
    adjustmentsUsed: latest(p).versionNo - 1,
    adjustmentsMax: fixture.adjustmentsMax,
    rowVersion: latest(p).rowVersion,
  });
}

/** Phiên bản theo lược đồ `PlanVersion`: kèm tỷ trọng nhóm và vi phạm còn lại của các dòng. */
function versionOf(p: StoredPlan, v: StoredVersion): PlanVersion {
  return structuredClone({
    planId: p.id,
    versionNo: v.versionNo,
    status: v.status,
    period: p.period,
    unitName: p.unitName,
    approvalDeadline: p.approvalDeadline,
    workingDaysToDeadline: workingDaysBetween(todayIso(), p.approvalDeadline),
    contentHash: v.contentHash ?? null,
    reviewComment: v.reviewComment ?? null,
    items: v.items,
    groupWeights: groupWeights(),
    // Phiên bản đã gửi duyệt đã qua kiểm tra lúc gửi; BR-04-11, BR-04-12 lúc duyệt cần dữ liệu minh chứng
    // và nhiệm vụ đang làm, giả lập không có vi phạm.
    checks: editable(v) ? checkPlan(p, v.items, baseItems(p, v), v.removedItems) : [],
    changeReason: v.changeReason ?? null,
    removedItems: v.removedItems ?? [],
    signRequestId: v.signRequestId,
    rowVersion: v.rowVersion,
  });
}

const etag = (v: { rowVersion: number }) => ({ ETag: `"${v.rowVersion}"` });
const editable = (v: StoredVersion) => v.status === 'NHAP' || v.status === 'TRA_LAI';

function locate(req: MockRequest): { p: StoredPlan; v: StoredVersion } | MockResponse {
  const p = store().find((x) => x.id === req.pathParams['id']);
  if (!p) return problem(404, 'Không tìm thấy kế hoạch');
  const no = Number(req.pathParams['v']);
  const v = p.versions.find((x) => x.versionNo === no);
  return v ? { p, v } : problem(404, 'Không tìm thấy phiên bản kế hoạch');
}

function ifMatch(v: StoredVersion, req: MockRequest): MockResponse | null {
  const h = req.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu thông tin phiên bản dữ liệu. Vui lòng tải lại trang.');
  return h.replace(/"/g, '') === String(v.rowVersion)
    ? null
    : problem(409, 'Kế hoạch vừa được người khác cập nhật. Vui lòng tải lại.');
}

const GROUP_LABEL: Record<TaskGroup, string> = {
  THUONG_XUYEN: 'thường xuyên',
  THEN_CHOT: 'then chốt',
};

/** Dòng của phiên bản hiệu lực mà phiên bản điều chỉnh `v` dựa vào (BR-04-10). */
function baseItems(p: StoredPlan, v: StoredVersion): PlanItem[] | undefined {
  if (v.versionNo === 1) return undefined;
  return [...p.versions].reverse().find((x) => x.versionNo < v.versionNo && x.status === 'DA_DUYET')
    ?.items;
}

const FIELDS = [
  'title',
  'taskGroup',
  'keyResultId',
  'leadAssignee',
  'coAssignees',
  'catalogItemId',
  'productName',
  'quantity',
  'unit',
  'dueDate',
  'recurrence',
  'standardScore',
] as const;

const same = (a: unknown, b: unknown) => {
  const norm = (x: unknown) =>
    JSON.stringify(x ?? null) === '[]' ? 'null' : JSON.stringify(x ?? null);
  return norm(a) === norm(b);
};
const changedFields = (before: PlanItem, after: PlanItem) =>
  FIELDS.filter((f) => !same(before[f], after[f]));

/**
 * "Kiểm tra kế hoạch" (SCR-0402 vùng 4): vi phạm còn lại, cùng một hàm cho `check`, `submit` và
 * `PlanVersion.checks`. `base` là dòng của phiên bản đã duyệt khi đang điều chỉnh (BR-04-10).
 */
export function checkPlan(
  p: Pick<StoredPlan, 'period'>,
  items: PlanItem[],
  base?: PlanItem[],
  removed: PlanRemovedItem[] = [],
): PlanCheck[] {
  const out: PlanCheck[] = [];
  const add = (rule: string, level: PlanCheck['level'], message: string, lineNo?: number) =>
    out.push(lineNo ? { rule, level, message, lineNo } : { rule, level, message });
  const warnings: PlanCheck[] = [];
  const vague = catalogFixture.vagueWords;
  const { startDate, endDate } = p.period;
  const totals: Record<TaskGroup, number> = { THUONG_XUYEN: 0, THEN_CHOT: 0 };

  for (const i of items) {
    const n = i.lineNo;
    totals[i.taskGroup] += i.standardScore ?? 0;
    if (!i.title.trim() || i.title.length > 500)
      add('BR-04-04', 'CHAN', `Dòng ${n}: nội dung công việc bắt buộc, tối đa 500 ký tự`, n);
    if (!i.leadAssignee?.id) add('BR-04-02', 'CHAN', `Dòng ${n} chưa có chủ trì.`, n);
    else if ((i.coAssignees ?? []).some((c) => c.id === i.leadAssignee.id))
      add(
        'BR-04-02',
        'CHAN',
        `Dòng ${n} có 2 đơn vị chủ trì. Mỗi việc chỉ có đúng một chủ trì.`,
        n,
      );
    const missing: string[] = [];
    if (!i.productName.trim()) missing.push('sản phẩm');
    if (!(i.quantity > 0)) missing.push('số lượng');
    if (!i.unit?.trim()) missing.push('đơn vị tính');
    if (!i.dueDate) missing.push('hạn');
    const words = vague.filter((w) => i.title.toLowerCase().includes(w));
    if (words.length && missing.length) {
      add(
        'BR-04-03',
        'CHAN',
        `KPI mơ hồ ở dòng ${n}: "${words.join('", "')}". Vui lòng ghi rõ sản phẩm, đơn vị tính và hạn.`,
        n,
      );
    } else if (missing.length) {
      add('BR-04-04', 'CHAN', `Dòng ${n} thiếu ${missing.join(', ')}.`, n);
    }
    if (i.dueDate && (i.dueDate < startDate || i.dueDate > endDate))
      add('BR-04-04', 'CHAN', `Dòng ${n}: hạn phải nằm trong kỳ.`, n);
    if (base) {
      const before = base.find((b) => b.id === i.originItemId);
      const changed = !before || changedFields(before, i).length > 0;
      if (changed && !i.changeReason?.trim())
        add('BR-04-10', 'CHAN', `Dòng ${n} thay đổi so với phiên bản đã duyệt, cần ghi lý do.`, n);
    }
    if (i.standardScore == null)
      warnings.push({
        rule: 'BR-04-05',
        level: 'CANH_BAO',
        lineNo: n,
        message: `Dòng ${n} chưa có điểm (việc ngoài danh mục)`,
      });
  }
  // dòng của phiên bản đã duyệt bị bỏ mà không ghi lý do (BR-04-10, SCR-0403 trường 3)
  const kept = new Set(items.map((i) => i.originItemId));
  for (const b of base ?? []) {
    if (kept.has(b.id)) continue;
    if (!removed.find((r) => r.originItemId === b.id)?.changeReason?.trim())
      add('BR-04-10', 'CHAN', `Dòng "${b.title}" bị bỏ so với phiên bản đã duyệt, cần ghi lý do.`);
  }
  for (const g of ['THUONG_XUYEN', 'THEN_CHOT'] as TaskGroup[]) {
    const max = fixture.groupWeights[g];
    if (totals[g] > max)
      add(
        'BR-04-06',
        'CHAN',
        `Tổng điểm nhóm ${GROUP_LABEL[g]} là ${totals[g]}, vượt điểm tối đa ${max}.`,
      );
  }
  out.push(...warnings);
  const linked = new Set(items.map((i) => i.keyResultId).filter(Boolean));
  for (const k of keyResults().filter((k) => !linked.has(k.id)))
    add('BR-04-07', 'CANH_BAO', `${k.code} chưa có nhiệm vụ`);
  return out;
}

/** Máy chủ chuẩn hoá dòng: đánh lại số thứ tự, điểm theo danh mục, gắn mã dòng mới. */
function sanitize(items: unknown, period: PeriodRef): PlanItem[] {
  return ((items ?? []) as Partial<PlanItemInput>[]).map((i, n) =>
    withCatalog({
      id: i.id || `pi-new-${++seq}`,
      lineNo: n + 1,
      title: i.title ?? '',
      taskGroup: i.taskGroup ?? 'THUONG_XUYEN',
      keyResultId: i.keyResultId || undefined,
      leadAssignee: i.leadAssignee ?? { kind: 'UNIT', id: '', name: '' },
      coAssignees: i.coAssignees ?? [],
      catalogItemId: i.catalogItemId || undefined,
      productName: i.productName ?? '',
      quantity: Number(i.quantity) || 0,
      unit: i.unit ?? '',
      dueDate: i.dueDate || period.endDate,
      recurrence: i.recurrence || undefined,
      // việc ngoài danh mục: điểm do cấp duyệt chấm, chỉ giữ điểm đã chấm ở phiên bản trước
      standardScore: i.originItemId ? (i.standardScore ?? null) : null,
      originItemId: i.originItemId,
      changeReason: i.changeReason || undefined,
    }),
  );
}

function save(req: MockRequest): MockResponse {
  const hit = locate(req);
  if ('status' in hit) return hit;
  const { p, v } = hit;
  const conflict = ifMatch(v, req);
  if (conflict) return conflict;
  if (!editable(v)) return problem(409, 'Phiên bản đã gửi duyệt hoặc đã duyệt, không sửa được');
  const items = sanitize((req.body as { items?: unknown })?.items, p.period);
  const bad = items.find((i) => i.title.length > 500);
  if (bad)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [
        { field: `items[${bad.lineNo - 1}].title`, message: 'Nội dung công việc tối đa 500 ký tự' },
      ],
    });
  v.items = items;
  v.removedItems = removedOf(req.body, v);
  v.rowVersion++;
  return ok(versionOf(p, v), etag(v));
}

function copyFrom(req: MockRequest): MockResponse {
  const hit = locate(req);
  if ('status' in hit) return hit;
  const { p, v } = hit;
  const conflict = ifMatch(v, req);
  if (conflict) return conflict;
  if (v.versionNo !== 1 || v.items.length || !editable(v))
    return problem(409, 'Chỉ sao chép vào phiên bản 1 còn trống');
  const prev = previousPlan(p);
  const src = prev && effective(prev);
  if (!src) return problem(404, 'Không có kế hoạch đã duyệt của quý trước');
  const shift = (d: string) => {
    const t = new Date(d + 'T00:00:00Z').getTime() + 91 * 86_400_000;
    return new Date(t).toISOString().slice(0, 10);
  };
  // dòng thường xuyên và dòng then chốt chưa tới hạn (suy luận "chưa hoàn thành"), hạn dịch một quý
  const picked = src.items.filter((i) => i.taskGroup === 'THUONG_XUYEN' || i.dueDate >= todayIso());
  v.items = sanitize(
    picked.map((i) => ({ ...i, id: '', originItemId: undefined, dueDate: shift(i.dueDate) })),
    p.period,
  );
  v.rowVersion++;
  return ok(versionOf(p, v), etag(v));
}

function suggestions(req: MockRequest): MockResponse {
  const p = store().find((x) => x.id === req.pathParams['id']);
  if (!p) return problem(404, 'Không tìm thấy kế hoạch');
  const linked = new Set(latest(p).items.map((i) => i.keyResultId));
  const items: PlanItemInput[] = keyResults()
    .filter((k) => !linked.has(k.id))
    .map((k) => ({
      title: `Triển khai ${k.code}: ${k.title}`,
      taskGroup: 'THEN_CHOT',
      keyResultId: k.id,
      leadAssignee: { kind: 'UNIT', ...unitRef(p.unitId) },
      coAssignees: [],
      productName: '',
      quantity: 0,
      unit: '',
      dueDate: p.period.endDate,
      standardScore: null,
    }));
  return ok({ items });
}

function submit(req: MockRequest): MockResponse {
  const hit = locate(req);
  if ('status' in hit) return hit;
  const { p, v } = hit;
  const conflict = ifMatch(v, req);
  if (conflict) return conflict;
  if (!editable(v)) return problem(409, 'Phiên bản đã gửi duyệt');
  if (!v.items.length)
    return problem(422, 'Kế hoạch chưa có dòng nào', {
      errors: [{ field: 'items', message: 'Thêm ít nhất một công việc' }],
    });
  const errors = checkPlan(p, v.items, baseItems(p, v), v.removedItems)
    .filter((i) => i.level === 'CHAN')
    .map((i) => ({ field: i.lineNo ? `items[${i.lineNo - 1}]` : i.rule, message: i.message }));
  if (errors.length) return problem(422, 'Kế hoạch còn lỗi chặn gửi duyệt', { errors });
  Object.assign(v, {
    status: 'CHO_DUYET',
    submittedAt: new Date().toISOString(),
    submittedById: MOCK_ME.id,
    submittedByName: MOCK_ME.name,
    contentHash: contentHash(v.items),
    rowVersion: v.rowVersion + 1,
  });
  return ok(versionOf(p, v), etag(v));
}

function createAdjustment(req: MockRequest): MockResponse {
  const p = store().find((x) => x.id === req.pathParams['id']);
  if (!p) return problem(404, 'Không tìm thấy kế hoạch');
  const last = latest(p);
  const current = effective(p);
  if (!current || last.status !== 'DA_DUYET')
    return problem(409, 'Chỉ tạo điều chỉnh từ phiên bản đã duyệt mới nhất');
  const changeReason = ((req.body as { changeReason?: string })?.changeReason ?? '').trim();
  if (!changeReason)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'changeReason', message: 'Nhập lý do điều chỉnh' }],
    });
  const used = last.versionNo - 1;
  if (used >= fixture.adjustmentsMax)
    return problem(409, `Kế hoạch đã điều chỉnh ${used} lần trong quý, đạt giới hạn.`, {
      code: 'PLAN-409-ADJUSTMENT-LIMIT',
    });
  const next: StoredVersion = {
    versionNo: last.versionNo + 1,
    status: 'NHAP',
    rowVersion: 0,
    changeReason,
    removedItems: [],
    items: current.items.map((i) => ({
      ...i,
      id: `${i.id}-n${last.versionNo + 1}`,
      originItemId: i.id,
      changeReason: undefined,
    })),
  };
  p.versions.push(next);
  return { status: 201, body: versionOf(p, next), headers: etag(next) };
}

/** So sánh theo `originItemId` (TL-02 SCR-0403 trường 2): thêm, sửa, bỏ, không đổi. */
function diff(req: MockRequest): MockResponse {
  const hit = locate(req);
  if ('status' in hit) return hit;
  const { p, v } = hit;
  const raw = req.params.get('base');
  if (raw == null || raw === '') return problem(400, 'Thiếu phiên bản gốc để so sánh');
  const baseNo = Number(raw);
  // base=0: so với kế hoạch rỗng, mọi dòng là THEM (phiên bản đầu)
  const base =
    baseNo === 0
      ? { versionNo: 0, items: [] as PlanItem[] }
      : p.versions.find((x) => x.versionNo === baseNo && x.versionNo !== v.versionNo);
  if (!base) return problem(404, 'Không tìm thấy phiên bản so sánh');
  const lines: PlanDiffLine[] = v.items.map((after) => {
    const before = base.items.find((b) => b.id === after.originItemId);
    if (!before) return { change: 'THEM', after, reason: after.changeReason };
    const fields = changedFields(before, after);
    const change: PlanChangeType = fields.length ? 'SUA' : 'KHONG_DOI';
    return { change, before, after, changedFields: [...fields], reason: after.changeReason };
  });
  const kept = new Set(v.items.map((i) => i.originItemId));
  for (const before of base.items) {
    if (kept.has(before.id)) continue;
    const reason = v.removedItems?.find((r) => r.originItemId === before.id)?.changeReason;
    lines.push({ change: 'BO', before, reason });
  }
  const count = (c: PlanChangeType) => lines.filter((r) => r.change === c).length;
  const body: PlanDiff = {
    baseVersionNo: base.versionNo,
    versionNo: v.versionNo,
    added: count('THEM'),
    modified: count('SUA'),
    removed: count('BO'),
    groupWeightChanged: false,
    adjustmentNo: v.versionNo - 1,
    adjustmentsMax: fixture.adjustmentsMax,
    lines: req.params.get('mode') === 'all' ? lines : lines.filter((l) => l.change !== 'KHONG_DOI'),
  };
  return ok(structuredClone(body));
}

function pending(req: MockRequest): { p: StoredPlan; v: StoredVersion } | MockResponse {
  const hit = locate(req);
  if ('status' in hit) return hit;
  const conflict = ifMatch(hit.v, req);
  if (conflict) return conflict;
  if (hit.v.status !== 'CHO_DUYET') return problem(409, 'Phiên bản không ở trạng thái chờ duyệt');
  return hit;
}

function returnPlan(req: MockRequest): MockResponse {
  const hit = pending(req);
  if ('status' in hit) return hit;
  const comment = ((req.body as { comment?: string })?.comment ?? '').trim();
  if (!comment || comment.length > 2000)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'comment', message: 'Ghi ý kiến khi trả lại (tối đa 2.000 ký tự)' }],
    });
  Object.assign(hit.v, {
    status: 'TRA_LAI',
    reviewComment: comment,
    rowVersion: hit.v.rowVersion + 1,
  });
  return ok(versionOf(hit.p, hit.v), etag(hit.v));
}

/** "Duyệt và ký số": BR-04-08, MFA trong 15 phút, rồi tạo yêu cầu ký 120 giây (MSG-04-09), trả 202 Job. */
function approve(req: MockRequest): MockResponse {
  const hit = pending(req);
  if ('status' in hit) return hit;
  const { p, v } = hit;
  if (v.submittedById === MOCK_ME.id)
    return problem(403, 'Người duyệt không được là người lập kế hoạch.', {
      code: 'TASK-403-SAME-AUTHOR',
      // BR-04-08
      detail: 'Kế hoạch này do đồng chí lập và gửi duyệt nên đồng chí không duyệt được.',
    });
  if (!mockSession.recentMfa()) {
    const r = problem(401, 'Cần xác thực nhiều lớp trước khi ký số', {
      type: 'https://datatracker.ietf.org/doc/html/rfc9470',
      code: 'insufficient_user_authentication',
    });
    r.headers = {
      ...r.headers,
      'WWW-Authenticate':
        // ui-text-ok: tiêu đề HTTP theo RFC 9470, không hiện lên màn hình
        'Bearer error="insufficient_user_authentication", acr_values="aal2", max_age=900',
    };
    return r;
  }
  const body = req.body as { signMethod?: SignMethod; comment?: string };
  if (body?.signMethod !== 'MYSIGN')
    return problem(422, 'Chứng thư số chưa liên kết', {
      errors: [
        { field: 'signMethod', message: 'Phương thức ký này chưa liên kết chứng thư hợp lệ' },
      ],
    });
  const sign: StoredSign = {
    id: `0192a1e5-0000-7000-8fff-${String(++seq).padStart(12, '0')}`,
    status: mockSession.esignDown() ? 'LOI' : 'CHO_XAC_NHAN',
    expiresAt: new Date(Date.now() + 120_000).toISOString(),
    completedAt: null,
    method: body.signMethod,
    planId: p.id,
    versionNo: v.versionNo,
    comment: body.comment?.trim() || undefined,
    polls: 0,
  };
  signs.push(sign);
  v.signRequestId = sign.id;
  const job: Job = {
    id: `job-plan-approve-${seq}`,
    type: 'ESIGN_PLAN_APPROVAL',
    status: sign.status === 'LOI' ? 'THAT_BAI' : 'DANG_CHAY',
    message: 'Chờ người duyệt xác nhận ký trên MySign',
  };
  return {
    status: 202,
    body: job,
    headers: { Location: `/api/v1/jobs/${job.id}`, 'Retry-After': '2' },
  };
}

/**
 * Lập kế hoạch (`createPlan`): kỳ phải đang mở, mỗi đơn vị một kế hoạch mỗi kỳ (409 `TASK-409-PLAN-EXISTS`);
 * bỏ trống đơn vị thì lấy đơn vị của phân công đang chọn (giả lập: đơn vị của kế hoạch mẫu).
 */
function createPlan(req: MockRequest): MockResponse {
  const body = (req.body ?? {}) as { periodId?: string; unitId?: string };
  if (!body.periodId)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'periodId', message: 'Chọn kỳ kế hoạch' }],
    });
  const info = mockPeriodInfo(body.periodId);
  if (!info) return problem(404, 'Không tìm thấy kỳ');
  if (info.status !== 'MO') return problem(409, 'Kỳ đã đóng, không lập được kế hoạch mới');
  const unit = unitRef(body.unitId ?? fixture.plans[0].unitId);
  if (store().some((p) => p.period.id === info.ref.id && p.unitId === unit.id))
    return problem(409, 'Đơn vị đã có kế hoạch cho kỳ này', { code: 'TASK-409-PLAN-EXISTS' });
  const deadline = new Date(`${info.ref.startDate}T00:00:00Z`);
  deadline.setUTCDate(deadline.getUTCDate() + 10);
  const p: StoredPlan = {
    id: `plan-${info.ref.id}-${unit.id}`,
    code: `KH-${info.ref.id.toUpperCase()}`,
    period: info.ref,
    approvalDeadline: deadline.toISOString().slice(0, 10),
    unitId: unit.id,
    unitName: unit.name,
    versions: [{ versionNo: 1, status: 'NHAP', rowVersion: 1, items: [] }],
  };
  store().push(p);
  return { status: 201, body: planOf(p), headers: { Location: `/api/v1/plans/${p.id}` } };
}

/** Trạng thái yêu cầu ký: lần hỏi thứ hai coi như người duyệt đã xác nhận trên MySign (EVT-18, EVT-03). */
function signStatus(req: MockRequest): MockResponse {
  const s = signs.find((x) => x.id === req.pathParams['id']);
  if (!s) return problem(404, 'Không tìm thấy yêu cầu ký');
  if (s.status === 'CHO_XAC_NHAN' && ++s.polls >= 2) {
    const p = store().find((x) => x.id === s.planId)!;
    const v = p.versions.find((x) => x.versionNo === s.versionNo)!;
    const now = new Date().toISOString();
    p.effectiveVersionNo = v.versionNo;
    Object.assign(v, {
      status: 'DA_DUYET',
      approvedAt: now,
      approvedByName: MOCK_ME.name,
      reviewComment: s.comment,
      rowVersion: v.rowVersion + 1,
    });
    Object.assign(s, { status: 'DA_KY', completedAt: now });
  }
  const { id, status, expiresAt, completedAt } = s;
  return ok({ id, status, expiresAt, completedAt });
}

export const PLANS_MOCK: MockModule = {
  name: 'plans',
  reset: () => {
    plans = null;
    signs = [];
    seq = 0;
    mockSession.reset();
  },
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/plans/items',
      handle: () => ok({ items: approvedPlanItems(), nextCursor: null }),
    },
    {
      method: 'GET',
      pattern: '/api/v1/plans',
      handle: (r) => {
        const status = r.params.get('status');
        const items = [...store()]
          .sort((a, b) => b.period.startDate.localeCompare(a.period.startDate))
          .filter((p) => !r.params.get('period') || p.period.id === r.params.get('period'))
          .filter((p) => !r.params.get('unit') || p.unitId === r.params.get('unit'))
          .filter((p) => !status || latest(p).status === status)
          .map(planOf);
        return ok({ items, nextCursor: null });
      },
    },
    { method: 'POST', pattern: '/api/v1/plans', handle: createPlan },
    {
      method: 'GET',
      pattern: '/api/v1/plans/:id/versions',
      handle: (r) => {
        const p = store().find((x) => x.id === r.pathParams['id']);
        if (!p) return problem(404, 'Không tìm thấy kế hoạch');
        return ok({ items: [...p.versions].reverse().map((v) => summaryOf(p, v)) });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/plans/:id/versions/:v',
      handle: (r) => {
        const hit = locate(r);
        return 'status' in hit ? hit : ok(versionOf(hit.p, hit.v), etag(hit.v));
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/plans/:id/versions/:v/check',
      handle: (r) => {
        const hit = locate(r);
        if ('status' in hit) return hit;
        const items = sanitize((r.body as { items?: unknown })?.items, hit.p.period);
        const removed = removedOf(r.body, hit.v);
        return ok({ checks: checkPlan(hit.p, items, baseItems(hit.p, hit.v), removed) });
      },
    },
    { method: 'PUT', pattern: '/api/v1/plans/:id/versions/:v', handle: save },
    { method: 'POST', pattern: '/api/v1/plans/:id/versions/:v/copy-from', handle: copyFrom },
    { method: 'POST', pattern: '/api/v1/plans/:id/versions/:v/submit', handle: submit },
    { method: 'GET', pattern: '/api/v1/plans/:id/suggestions', handle: suggestions },
    { method: 'POST', pattern: '/api/v1/plans/:id/versions', handle: createAdjustment },
    { method: 'GET', pattern: '/api/v1/plans/:id/versions/:v/diff', handle: diff },
    { method: 'POST', pattern: '/api/v1/plans/:id/versions/:v/return', handle: returnPlan },
    { method: 'POST', pattern: '/api/v1/plans/:id/versions/:v/approve', handle: approve },
    { method: 'GET', pattern: '/api/v1/esign/requests/:id', handle: signStatus },
  ],
};
