import {
  PlanCheckItem,
  PlanCheckResult,
  PlanItem,
  PlanItemRef,
  PlanSummary,
  PlanVersion,
  PlanWorkspace,
} from '../../lib/models/plan';
import { TaskGroup } from '../../lib/models/task';
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
import { workingDaysBetween } from '../tasks/tasks.mock';
import fixture from './plans.json';

/**
 * Giả lập `/api/v1/plans` (QT-04, MOD-02): dòng kế hoạch đã duyệt cho SCR-0501 (WEB-028) và lập kế hoạch
 * quý SCR-0402 (WEB-026): xem phiên bản, kiểm tra tức thì, lưu nháp (If-Match), sao chép quý trước,
 * gợi ý từ OKR, gửi duyệt, tạo điều chỉnh. Máy chủ là nơi chặn BR-04-02 … BR-04-06, BR-04-10.
 */

/** Dòng kế hoạch đã duyệt, ngày tương đối đã quy đổi. */
export const approvedPlanItems = (): PlanItemRef[] =>
  resolveRelativeDates(structuredClone(fixture.items) as PlanItemRef[]);

type StoredPlan = Omit<
  PlanWorkspace,
  'version' | 'versions' | 'workingDaysLeft' | 'groupWeights' | 'adjustmentsUsed' | 'adjustmentsMax'
> & {
  versions: PlanVersion[];
};

let plans: StoredPlan[] | null = null;
let seq = 0;

const todayIso = () => new Date().toISOString().slice(0, 10);
const catalogItem = (id?: string) => catalogFixture.items.find((i) => i.id === id);
const keyResults = () => okrFixture.keyResults;

/** Dòng 5–7 của phiên bản 2 Quý IV là các dòng đã duyệt dùng cho giao việc (`items`). */
function fromApproved(r: PlanItemRef): PlanItem {
  const ci = catalogItem(r.catalogItemId);
  return {
    id: r.id,
    itemNo: r.lineNo,
    title: r.title,
    taskGroup: r.taskGroup,
    keyResultId: r.keyResultId,
    leadUnitId: 'u-btc',
    coUnitIds: [],
    catalogItemId: r.catalogItemId,
    productName: r.productName,
    plannedQuantity: r.quantity,
    unit: r.unit,
    dueDate: r.dueDate,
    standardScore: ci?.baseScore ?? null,
  };
}

function withCatalog(i: PlanItem): PlanItem {
  const ci = catalogItem(i.catalogItemId);
  return {
    ...i,
    catalogItemCode: ci?.code,
    difficulty: ci?.difficulty,
    // BR-04-05: điểm của dòng lấy từ danh mục; việc ngoài danh mục để cấp duyệt chấm.
    standardScore: ci ? ci.baseScore : (i.standardScore ?? null),
  };
}

function store(): StoredPlan[] {
  if (plans) return plans;
  const raw = resolveRelativeDates(structuredClone(fixture.plans)) as unknown as StoredPlan[];
  plans = raw.map((p) => {
    const versions = p.versions.map((v) => ({ ...v }));
    const q4v2 = versions.find((v) => v.id === 'pv-q4-2');
    if (q4v2) q4v2.items = [...q4v2.items, ...approvedPlanItems().map(fromApproved)];
    for (const v of versions) {
      if ((v.items as unknown) === '@v2-without-tc-dv-06' && q4v2) {
        v.items = q4v2.items
          .filter((i) => i.catalogItemId !== 'ci-tc-dv-06')
          .map((i, n) => ({ ...i, id: i.id.replace('-v2-', '-v1-'), itemNo: n + 1 }));
      }
      v.items = v.items.map(withCatalog);
    }
    if (q4v2) {
      const v1 = versions.find((v) => v.id === 'pv-q4-1')!;
      q4v2.items = q4v2.items.map((i) => ({
        ...i,
        originItemId: v1.items.find((o) => o.title === i.title)?.id,
      }));
    }
    // phiên bản 1 đã duyệt có mã băm như lúc ký
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

function workspace(p: StoredPlan, v: PlanVersion): PlanWorkspace {
  return structuredClone({
    ...p,
    workingDaysLeft: workingDaysBetween(todayIso(), p.approvalDeadline),
    groupWeights: fixture.groupWeights,
    adjustmentsUsed: latest(p).versionNo - 1,
    adjustmentsMax: fixture.adjustmentsMax,
    versions: p.versions.map(({ id, versionNo, status, isCurrent }) => ({
      id,
      versionNo,
      status,
      isCurrent,
    })),
    version: v,
  });
}

const etag = (v: PlanVersion) => ({ ETag: `"${v.rowVersion}"` });
const editable = (v: PlanVersion) => v.status === 'NHAP' || v.status === 'TRA_LAI';

function locate(req: MockRequest): { p: StoredPlan; v: PlanVersion } | MockResponse {
  const p = store().find((x) => x.id === req.pathParams['id']);
  if (!p) return problem(404, 'Không tìm thấy kế hoạch');
  const no = req.pathParams['v'] ? Number(req.pathParams['v']) : latest(p).versionNo;
  const v = p.versions.find((x) => x.versionNo === no);
  return v ? { p, v } : problem(404, 'Không tìm thấy phiên bản kế hoạch');
}

function ifMatch(v: PlanVersion, req: MockRequest): MockResponse | null {
  const h = req.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu If-Match');
  return h.replace(/"/g, '') === String(v.rowVersion)
    ? null
    : problem(409, 'Kế hoạch vừa được người khác cập nhật. Vui lòng tải lại.');
}

const GROUP_LABEL: Record<TaskGroup, string> = {
  THUONG_XUYEN: 'thường xuyên',
  THEN_CHOT: 'then chốt',
};

/** Danh sách kiểm tra kế hoạch (SCR-0402 vùng 4): cùng một hàm cho `check` và `submit`. */
export function checkPlan(
  p: Pick<StoredPlan, 'periodStart' | 'periodEnd'>,
  items: PlanItem[],
): PlanCheckResult {
  const out: PlanCheckItem[] = [];
  const add = (
    rule: string,
    level: PlanCheckItem['level'],
    passed: boolean,
    message: string,
    itemNo?: number,
  ) => out.push({ rule, level, passed, message, itemNo });
  const vague = catalogFixture.vagueWords;

  const leadIssues = items.filter(
    (i) =>
      (!i.leadUnitId && !i.leadPersonId) || (i.leadUnitId && i.coUnitIds.includes(i.leadUnitId)),
  );
  if (!leadIssues.length) add('BR-04-02', 'CHAN', true, 'Mỗi việc có đúng 1 chủ trì');

  const krs = keyResults();
  const linked = new Set(items.map((i) => i.keyResultId).filter(Boolean));
  const missingKr = krs.filter((k) => !linked.has(k.id));
  add(
    'BR-04-07',
    'CANH_BAO',
    true,
    `${krs.length - missingKr.length}/${krs.length} KR có nhiệm vụ liên kết`,
  );

  const groupTotals: Record<TaskGroup, number> = { THUONG_XUYEN: 0, THEN_CHOT: 0 };
  const warnings: PlanCheckItem[] = [];
  for (const i of items) {
    const n = i.itemNo;
    groupTotals[i.taskGroup] += i.standardScore ?? 0;
    if (!i.title.trim() || i.title.length > 500)
      add('BR-04-04', 'CHAN', false, `Dòng ${n}: nội dung công việc bắt buộc, tối đa 500 ký tự`, n);
    for (const x of leadIssues.filter((l) => l === i)) {
      if (!x.leadUnitId && !x.leadPersonId)
        add('BR-04-02', 'CHAN', false, `Dòng ${n} chưa có chủ trì.`, n);
      else
        add(
          'BR-04-02',
          'CHAN',
          false,
          `Dòng ${n} có 2 đơn vị chủ trì. Mỗi việc chỉ có đúng một chủ trì.`,
          n,
        );
    }
    const missing: string[] = [];
    if (!i.productName.trim()) missing.push('sản phẩm');
    if (!(i.plannedQuantity && i.plannedQuantity > 0)) missing.push('số lượng');
    if (!i.unit.trim()) missing.push('đơn vị tính');
    const dueOk =
      !!i.recurrence || (!!i.dueDate && i.dueDate >= p.periodStart && i.dueDate <= p.periodEnd);
    if (!i.recurrence && !i.dueDate) missing.push('hạn');
    const words = vague.filter((w) => i.title.toLowerCase().includes(w));
    if (words.length && missing.length) {
      add(
        'BR-04-03',
        'CHAN',
        false,
        `KPI mơ hồ ở dòng ${n}: "${words.join('", "')}". Vui lòng ghi rõ sản phẩm, đơn vị tính và hạn.`,
        n,
      );
    } else if (missing.length) {
      add('BR-04-04', 'CHAN', false, `Dòng ${n} thiếu ${missing.join(', ')}.`, n);
    }
    if (i.dueDate && !dueOk) add('BR-04-04', 'CHAN', false, `Dòng ${n}: hạn phải nằm trong kỳ.`, n);
    if (i.standardScore == null)
      warnings.push({
        rule: 'BR-04-05',
        level: 'CANH_BAO',
        passed: false,
        message: `Dòng ${n} chưa có điểm (việc ngoài danh mục)`,
        itemNo: n,
      });
  }
  for (const g of ['THUONG_XUYEN', 'THEN_CHOT'] as TaskGroup[]) {
    const max = fixture.groupWeights[g];
    if (groupTotals[g] > max)
      add(
        'BR-04-06',
        'CHAN',
        false,
        `Tổng điểm nhóm ${GROUP_LABEL[g]} là ${groupTotals[g]}, vượt điểm tối đa ${max}.`,
      );
  }
  out.push(...warnings);
  for (const k of missingKr) add('BR-04-07', 'CANH_BAO', false, `${k.code} chưa có nhiệm vụ`);
  return {
    items: out,
    groupTotals,
    keyResultsWithoutItems: missingKr.map((k) => k.id),
    keyResultCount: krs.length,
  };
}

/** Máy chủ chuẩn hoá dòng: đánh lại số thứ tự, điểm theo danh mục, gắn mã dòng mới. */
function sanitize(items: unknown): PlanItem[] {
  return ((items ?? []) as Partial<PlanItem>[]).map((i, n) =>
    withCatalog({
      id: i.id || `pi-new-${++seq}`,
      itemNo: n + 1,
      title: i.title ?? '',
      taskGroup: i.taskGroup ?? 'THUONG_XUYEN',
      keyResultId: i.keyResultId || undefined,
      leadUnitId: i.leadUnitId || undefined,
      leadPersonId: i.leadPersonId || undefined,
      coUnitIds: i.coUnitIds ?? [],
      catalogItemId: i.catalogItemId || undefined,
      productName: i.productName ?? '',
      plannedQuantity: i.plannedQuantity ?? null,
      unit: i.unit ?? '',
      dueDate: i.recurrence ? undefined : i.dueDate || undefined,
      recurrence: i.recurrence || undefined,
      // việc ngoài danh mục: điểm do cấp duyệt chấm, chỉ giữ điểm đã chấm ở phiên bản trước
      standardScore: i.originItemId ? (i.standardScore ?? null) : null,
      originItemId: i.originItemId,
      changeReason: i.changeReason,
    }),
  );
}

function save(req: MockRequest): MockResponse {
  const hit = locate(req);
  if ('status' in hit) return hit;
  const { v } = hit;
  const conflict = ifMatch(v, req);
  if (conflict) return conflict;
  if (!editable(v)) return problem(409, 'Phiên bản đã gửi duyệt hoặc đã duyệt, không sửa được');
  const items = sanitize((req.body as { items?: unknown })?.items);
  const bad = items.find((i) => i.title.length > 500);
  if (bad)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [
        { field: `items[${bad.itemNo - 1}].title`, message: 'Nội dung công việc tối đa 500 ký tự' },
      ],
    });
  v.items = items;
  v.rowVersion++;
  return ok(structuredClone(v), etag(v));
}

function copyFrom(req: MockRequest): MockResponse {
  const hit = locate(req);
  if ('status' in hit) return hit;
  const { p, v } = hit;
  const conflict = ifMatch(v, req);
  if (conflict) return conflict;
  if (v.versionNo !== 1 || v.items.length || !editable(v))
    return problem(409, 'Chỉ sao chép vào phiên bản 1 còn trống');
  const prev = store()
    .filter((x) => x.periodEnd < p.periodStart)
    .sort((a, b) => b.periodEnd.localeCompare(a.periodEnd))[0];
  const src = prev?.versions.find((x) => x.isCurrent);
  if (!src) return problem(404, 'Không có kế hoạch đã duyệt của quý trước');
  const shift = (d?: string) => {
    if (!d) return undefined;
    const t = new Date(d + 'T00:00:00Z').getTime() + 91 * 86_400_000;
    return new Date(t).toISOString().slice(0, 10);
  };
  // dòng thường xuyên và dòng then chốt chưa tới hạn (suy luận "chưa hoàn thành"), hạn dịch một quý
  const picked = src.items.filter(
    (i) => i.taskGroup === 'THUONG_XUYEN' || (i.dueDate ?? '') >= todayIso(),
  );
  v.items = sanitize(
    picked.map((i) => ({ ...i, id: '', originItemId: undefined, dueDate: shift(i.dueDate) })),
  );
  v.rowVersion++;
  return ok(structuredClone(v), etag(v));
}

function suggestions(req: MockRequest): MockResponse {
  const hit = locate(req);
  if ('status' in hit) return hit;
  const linked = new Set(hit.v.items.map((i) => i.keyResultId));
  const items = keyResults()
    .filter((k) => !linked.has(k.id))
    .map((k, n) =>
      withCatalog({
        id: '',
        itemNo: n + 1,
        title: `Triển khai ${k.code}: ${k.title}`,
        taskGroup: 'THEN_CHOT',
        keyResultId: k.id,
        leadUnitId: hit.p.unitId,
        coUnitIds: [],
        productName: '',
        plannedQuantity: null,
        unit: '',
        standardScore: null,
      }),
    );
  return ok({ items, nextCursor: null });
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
  if (v.versionNo > 1 && !v.changeReason?.trim())
    return problem(422, 'Thiếu lý do điều chỉnh', {
      errors: [{ field: 'changeReason', message: 'Ghi lý do điều chỉnh' }],
    });
  const errors = checkPlan(p, v.items)
    .items.filter((i) => i.level === 'CHAN' && !i.passed)
    .map((i) => ({ field: i.itemNo ? `items[${i.itemNo - 1}]` : i.rule, message: i.message }));
  if (errors.length) return problem(422, 'Kế hoạch còn lỗi chặn gửi duyệt', { errors });
  Object.assign(v, {
    status: 'CHO_DUYET',
    submittedAt: new Date().toISOString(),
    contentHash: contentHash(v.items),
    rowVersion: v.rowVersion + 1,
  });
  return ok(structuredClone(v), etag(v));
}

function createAdjustment(req: MockRequest): MockResponse {
  const p = store().find((x) => x.id === req.pathParams['id']);
  if (!p) return problem(404, 'Không tìm thấy kế hoạch');
  const last = latest(p);
  const current = p.versions.find((x) => x.isCurrent);
  if (!current || last.status !== 'DA_DUYET')
    return problem(409, 'Chỉ tạo điều chỉnh từ phiên bản đã duyệt mới nhất');
  const used = last.versionNo - 1;
  if (used >= fixture.adjustmentsMax)
    return problem(409, `Kế hoạch đã điều chỉnh ${used} lần trong quý, đạt giới hạn.`, {
      code: 'PLAN-409-ADJUSTMENT-LIMIT',
    });
  const reason = (req.body as { changeReason?: string })?.changeReason?.trim();
  if (!reason)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'changeReason', message: 'Ghi lý do điều chỉnh' }],
    });
  const next: PlanVersion = {
    id: `pv-${p.id}-${last.versionNo + 1}`,
    versionNo: last.versionNo + 1,
    status: 'NHAP',
    rowVersion: 0,
    isCurrent: false,
    changeReason: reason,
    items: current.items.map((i) => ({
      ...i,
      id: `${i.id}-n${last.versionNo + 1}`,
      originItemId: i.id,
    })),
  };
  p.versions.push(next);
  return { status: 201, body: structuredClone(next), headers: etag(next) };
}

export const PLANS_MOCK: MockModule = {
  name: 'plans',
  reset: () => {
    plans = null;
    seq = 0;
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
      handle: () => {
        const items: PlanSummary[] = [...store()]
          .sort((a, b) => b.periodStart.localeCompare(a.periodStart))
          .map((p) => ({
            id: p.id,
            periodId: p.periodId,
            periodLabel: p.periodLabel,
            latestVersionNo: latest(p).versionNo,
            latestStatus: latest(p).status,
          }));
        return ok({ items, nextCursor: null });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/plans/:id',
      handle: (r) => {
        const hit = locate(r);
        return 'status' in hit ? hit : ok(workspace(hit.p, hit.v), etag(hit.v));
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/plans/:id/versions/:v',
      handle: (r) => {
        const hit = locate(r);
        return 'status' in hit ? hit : ok(workspace(hit.p, hit.v), etag(hit.v));
      },
    },
    {
      method: 'POST',
      pattern: '/api/v1/plans/:id/versions/:v/check',
      handle: (r) => {
        const hit = locate(r);
        if ('status' in hit) return hit;
        return ok(checkPlan(hit.p, sanitize((r.body as { items?: unknown })?.items)));
      },
    },
    { method: 'PUT', pattern: '/api/v1/plans/:id/versions/:v', handle: save },
    { method: 'POST', pattern: '/api/v1/plans/:id/versions/:v/copy-from', handle: copyFrom },
    { method: 'POST', pattern: '/api/v1/plans/:id/versions/:v/submit', handle: submit },
    { method: 'GET', pattern: '/api/v1/plans/:id/suggestions', handle: suggestions },
    { method: 'POST', pattern: '/api/v1/plans/:id/versions', handle: createAdjustment },
  ],
};
