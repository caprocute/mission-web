import { PeriodRef } from '../../lib/models/task';
import {
  AssignCheckItem,
  AssignCheckResult,
  AssignRequest,
  BulkAssignRequest,
  PersonWorkload,
  SimilarTask,
  TaskDraft,
} from '../../lib/models/task-assign';
import {
  MockModule,
  MockRequest,
  MockResponse,
  ok,
  problem,
  resolveRelativeDates,
} from '../mock-router';
import { ORG_ME, ORG_PERSONS, ORG_UNITS, WEEK_LABELS, WEEKLY_THRESHOLD } from '../org/org.mock';
import { approvedPlanItems } from '../plans/plans.mock';
import fixture from './tasks.json';
import { workingDaysBetween } from './tasks.mock';

/**
 * Giả lập phần giao việc của `/api/v1/tasks` (QT-05, SCR-0501, WEB-028): soạn, lưu nháp, kiểm tra
 * tức thì của MOD-02, giao, giao đồng loạt. Bản nháp giữ riêng với danh sách "Việc của tôi"
 * vì nhiệm vụ giao cho người khác không thuộc danh sách đó.
 */

/** Ngưỡng độ tương đồng của BR-05-06. */
export const SIMILARITY_THRESHOLD = 0.7;

type StoredSimilar = Omit<SimilarTask, 'similarity'>;

let drafts: TaskDraft[] | null = null;
let seq = 0;

const period = (): PeriodRef => resolveRelativeDates(structuredClone(fixture.period));
const todayIso = () => new Date().toISOString().slice(0, 10);
const me = () => ORG_PERSONS.find((p) => p.id === ORG_ME)!;
const person = (id?: string) => ORG_PERSONS.find((p) => p.id === id);

function store(): TaskDraft[] {
  drafts ??= resolveRelativeDates(structuredClone(fixture.drafts) as unknown as TaskDraft[]).map(
    (d) => ({ ...context(), ...d }),
  );
  return drafts;
}

/** Trường do máy chủ điền theo ngữ cảnh đăng nhập. */
function context(): Pick<TaskDraft, 'periodId' | 'unitName' | 'assignerId' | 'assignerName'> {
  const m = me();
  return { periodId: period().id, unitName: m.unitName, assignerId: m.id, assignerName: m.name };
}

function blankDraft(): TaskDraft {
  return {
    ...context(),
    status: 'NHAP',
    source: 'KE_HOACH',
    title: '',
    taskGroup: 'THUONG_XUYEN',
    priority: 'TRUNG_BINH',
    coAssignees: [],
    acceptorId: ORG_ME,
    milestones: [],
    productName: '',
    quantity: null,
    unit: '',
    evidenceRequired: '',
    qualityCriteria: '',
  };
}

const tokens = (s: string) =>
  s
    .toLowerCase()
    .replace(/[.,;:()"'·]/g, ' ')
    .split(/\s+/)
    .filter(Boolean);

/** Độ tương đồng Dice trên âm tiết; máy chủ thật dùng tìm kiếm văn bản và vector (suy luận). */
export function similarity(a: string, b: string): number {
  const x = tokens(a);
  const y = new Map<string, number>();
  for (const t of tokens(b)) y.set(t, (y.get(t) ?? 0) + 1);
  let common = 0;
  for (const t of x) {
    const n = y.get(t) ?? 0;
    if (n > 0) {
      common++;
      y.set(t, n - 1);
    }
  }
  const total = x.length + tokens(b).length;
  return total ? Math.round(((2 * common) / total) * 100) / 100 : 0;
}

function findSimilar(d: TaskDraft): SimilarTask[] {
  if (tokens(d.title).length < 3) return [];
  const others = resolveRelativeDates(structuredClone(fixture.unitTasks) as StoredSimilar[]);
  return others
    .map((t) => ({ ...t, similarity: similarity(d.title, t.title) }))
    .filter((t) => t.similarity >= SIMILARITY_THRESHOLD && t.id !== d.id)
    .sort((a, b) => b.similarity - a.similarity);
}

function workload(personId?: string): PersonWorkload | undefined {
  const p = person(personId);
  if (!p) return undefined;
  return {
    personId: p.id,
    personName: p.name,
    leadCount: p.workload.leadCount,
    coCount: p.workload.coCount,
    weeklyThreshold: WEEKLY_THRESHOLD,
    weeks: p.workload.weeks.map((count, i) => ({ label: WEEK_LABELS[i], count })),
  };
}

const coPersonIds = (d: TaskDraft) =>
  d.coAssignees.filter((c) => c.kind === 'PERSON').map((c) => c.id);

/** Danh sách kiểm tra trước khi giao: cùng một hàm cho `assign-check` và cho `assign`. */
export function check(d: TaskDraft): AssignCheckResult {
  const items: AssignCheckItem[] = [];
  const add = (rule: string, level: AssignCheckItem['level'], passed: boolean, message: string) =>
    items.push({ rule, level, passed, message });
  const p = period();
  const lead = person(d.leadPersonId);

  add(
    'BR-05-01',
    'CHAN',
    !!lead && !coPersonIds(d).includes(lead.id),
    lead
      ? coPersonIds(d).includes(lead.id)
        ? 'Mỗi nhiệm vụ chỉ có đúng một người chủ trì.'
        : 'Có đúng một chủ trì'
      : 'Chưa chọn người chủ trì',
  );
  if (d.source === 'KE_HOACH') {
    const item = approvedPlanItems().find((i) => i.id === d.planItemId);
    add(
      'BR-05-04',
      'CHAN',
      !!item,
      item
        ? `Thuộc kế hoạch đã duyệt (phiên bản ${item.version})`
        : 'Chọn dòng của kế hoạch đã duyệt',
    );
  } else {
    const hasBasis = d.source === 'VAN_BAN' ? !!d.sourceDoc?.number.trim() : !!d.basis?.trim();
    add(
      'BR-05-09',
      'CHAN',
      hasBasis,
      hasBasis
        ? 'Có căn cứ giao việc'
        : 'Nhiệm vụ ngoài kế hoạch phải ghi căn cứ (văn bản hoặc chỉ đạo)',
    );
  }
  const acceptorOk =
    !!d.acceptorId && d.acceptorId !== d.leadPersonId && !coPersonIds(d).includes(d.acceptorId);
  add(
    'BR-05-02',
    'CHAN',
    acceptorOk,
    !d.acceptorId
      ? 'Chưa chọn người nghiệm thu'
      : acceptorOk
        ? 'Người nghiệm thu khác người thực hiện'
        : 'Người nghiệm thu phải khác người thực hiện nhiệm vụ.',
  );
  const dueOk = !!d.dueDate && d.dueDate >= todayIso() && d.dueDate <= p.endDate;
  add(
    'BR-05-03',
    'CHAN',
    dueOk,
    dueOk ? `Hạn nằm trong kỳ ${p.label}` : `Hạn hoàn thành phải từ hôm nay đến hết kỳ ${p.label}`,
  );
  const productOk =
    !!d.productName.trim() &&
    (d.quantity ?? 0) > 0 &&
    !!d.unit.trim() &&
    !!d.evidenceRequired.trim() &&
    !!d.qualityCriteria.trim();
  add(
    'BR-05-03',
    'CHAN',
    productOk,
    productOk
      ? 'Đủ sản phẩm, số lượng, minh chứng, tiêu chuẩn'
      : 'Thiếu sản phẩm, số lượng, đơn vị tính, minh chứng hoặc tiêu chuẩn chất lượng',
  );
  const similar = findSimilar(d);
  for (const s of similar) {
    add(
      'BR-05-06',
      'CANH_BAO',
      false,
      `Nhiệm vụ gần giống ${s.code} (độ tương đồng ${s.similarity.toFixed(2).replace('.', ',')}). Vui lòng gộp hoặc ghi lý do tạo mới.`,
    );
  }
  const w = workload(d.leadPersonId);
  const heavy = w?.weeks.find((x) => x.count > w.weeklyThreshold);
  if (w && heavy) {
    add(
      'BR-05-11',
      'CANH_BAO',
      false,
      `Đồng chí ${w.personName} đang chủ trì ${heavy.count} việc trong ${heavy.label.toLowerCase()}. Cân nhắc phân công lại.`,
    );
  }
  return {
    items,
    similar,
    workingDays: d.dueDate ? workingDaysBetween(todayIso(), d.dueDate) : undefined,
    workload: w,
  };
}

function sanitize(body: unknown): TaskDraft {
  const b = (body ?? {}) as Partial<TaskDraft>;
  return {
    ...blankDraft(),
    ...b,
    ...context(),
    coAssignees: b.coAssignees ?? [],
    milestones: b.milestones ?? [],
  } as TaskDraft;
}

const etag = (d: TaskDraft) => ({ ETag: `"${d.rowVersion}"` });
const findDraft = (id: string) => store().find((d) => d.id === id);
const notFound = () =>
  problem(404, 'Không tìm thấy nhiệm vụ', { detail: 'Nhiệm vụ không tồn tại hoặc đã bị xoá.' });

function ifMatch(d: TaskDraft, req: MockRequest): MockResponse | null {
  const v = req.headers.get('If-Match');
  if (!v) return problem(428, 'Thiếu thông tin phiên bản dữ liệu. Vui lòng tải lại trang.');
  return v.replace(/"/g, '') === String(d.rowVersion)
    ? null
    : problem(409, 'Nhiệm vụ vừa được người khác cập nhật. Vui lòng tải lại.');
}

function validateBasic(d: TaskDraft): MockResponse | null {
  if (!d.title.trim() || d.title.length > 500)
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'title', message: 'Tên nhiệm vụ bắt buộc, tối đa 500 ký tự' }],
    });
  return null;
}

function create(req: MockRequest): MockResponse {
  const d = sanitize(req.body);
  const bad = validateBasic(d);
  if (bad) return bad;
  seq++;
  const saved: TaskDraft = {
    ...d,
    id: crypto.randomUUID(),
    code: `NV-${d.periodId}-${String(300 + seq).padStart(4, '0')}`,
    rowVersion: 0,
    status: 'NHAP',
  };
  store().push(saved);
  return {
    status: 201,
    body: saved,
    headers: { Location: `/api/v1/tasks/${saved.id}`, ...etag(saved) },
  };
}

function update(req: MockRequest): MockResponse {
  const cur = findDraft(req.pathParams['id']);
  if (!cur) return notFound();
  const conflict = ifMatch(cur, req);
  if (conflict) return conflict;
  if (cur.status !== 'NHAP')
    return problem(409, 'Nhiệm vụ đã giao, không sửa ở màn hình giao việc');
  const d = sanitize(req.body);
  const bad = validateBasic(d);
  if (bad) return bad;
  Object.assign(cur, d, {
    id: cur.id,
    code: cur.code,
    status: 'NHAP',
    rowVersion: cur.rowVersion! + 1,
  });
  return ok(structuredClone(cur), etag(cur));
}

function remove(req: MockRequest): MockResponse {
  const cur = findDraft(req.pathParams['id']);
  if (!cur) return notFound();
  const conflict = ifMatch(cur, req);
  if (conflict) return conflict;
  if (cur.status !== 'NHAP') return problem(409, 'Chỉ huỷ được nhiệm vụ nháp');
  drafts = store().filter((d) => d !== cur);
  return { status: 204 };
}

/** Giao việc: máy chủ chạy lại toàn bộ kiểm tra; lỗi chặn trả 422 kèm MSG-05-01, MSG-05-02. */
function assign(req: MockRequest): MockResponse {
  const cur = findDraft(req.pathParams['id']);
  if (!cur) return notFound();
  const conflict = ifMatch(cur, req);
  if (conflict) return conflict;
  if (cur.status !== 'NHAP') return problem(409, 'Nhiệm vụ đã được giao');
  const body = (req.body ?? {}) as AssignRequest;
  const result = check(cur);
  const errors = result.items
    .filter((i) => i.level === 'CHAN' && !i.passed)
    .map((i) => ({ field: i.rule, message: i.message }));
  if (result.similar.length && !body.similarReason?.trim()) {
    errors.push({
      field: 'BR-05-06',
      message: 'Có nhiệm vụ gần giống: ghi lý do tạo mới hoặc gộp vào nhiệm vụ cũ.',
    });
  }
  if (errors.length) return problem(422, 'Chưa giao được nhiệm vụ', { errors });
  cur.status = 'DA_GIAO';
  cur.rowVersion = cur.rowVersion! + 1;
  return ok(
    { id: cur.id, code: cur.code, status: cur.status, leadName: person(cur.leadPersonId)!.name },
    etag(cur),
  );
}

function bulkAssign(req: MockRequest): MockResponse {
  const body = (req.body ?? {}) as Partial<BulkAssignRequest>;
  const ids = body.unitIds ?? [];
  const valid = ORG_UNITS.filter((u) => u.scope === 'children').map((u) => u.id);
  if (!ids.length || ids.some((id) => !valid.includes(id))) {
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'unitIds', message: 'Chọn ít nhất một đơn vị cấp dưới' }],
    });
  }
  const d = sanitize(body.draft);
  const bad = validateBasic(d);
  if (bad) return bad;
  return { status: 202, body: null, headers: { Location: `/api/v1/jobs/${crypto.randomUUID()}` } };
}

/**
 * Tạo nhiệm vụ `NHAP` cho tuyến khác của MOD-02 (tạo từ văn bản, WEB-029); máy chủ điền ngữ cảnh,
 * mã và phiên bản như `POST /api/v1/tasks`.
 */
export function addDraft(d: Partial<TaskDraft>): TaskDraft {
  seq++;
  const saved: TaskDraft = {
    ...blankDraft(),
    ...d,
    ...context(),
    id: crypto.randomUUID(),
    code: `NV-${period().id}-${String(300 + seq).padStart(4, '0')}`,
    rowVersion: 0,
    status: 'NHAP',
  };
  store().push(saved);
  return saved;
}

/** Huỷ nhiệm vụ nháp khi gợi ý tạo ra nó bị bỏ (WEB-029). */
export function cancelDraft(id: string): void {
  const cur = findDraft(id);
  if (cur?.status === 'NHAP') cur.status = 'HUY';
}

/** Giao một nhiệm vụ nháp đã lưu (giao theo lô): cùng kiểm tra như `POST /tasks/{id}/assign`. */
export function assignStored(
  id: string,
  similarReason?: string,
): { draft?: TaskDraft; errors: string[] } {
  const cur = findDraft(id);
  if (!cur) return { errors: ['Không tìm thấy nhiệm vụ'] };
  if (cur.status !== 'NHAP') return { draft: cur, errors: ['Nhiệm vụ đã được giao'] };
  const result = check(cur);
  const errors = result.items.filter((i) => i.level === 'CHAN' && !i.passed).map((i) => i.message);
  if (result.similar.length && !similarReason?.trim())
    errors.push('Có nhiệm vụ gần giống: ghi lý do tạo mới hoặc gộp vào nhiệm vụ cũ.');
  if (!errors.length) {
    cur.status = 'DA_GIAO';
    cur.rowVersion = cur.rowVersion! + 1;
  }
  return { draft: cur, errors };
}

/** `GET /api/v1/tasks/{id}/draft` (`getTaskDraft`). */
export function draftView(id: string): MockResponse | null {
  const d = findDraft(id);
  return d ? ok({ period: period(), draft: structuredClone(d) }, etag(d)) : null;
}

export const ASSIGN_MOCK: Pick<MockModule, 'routes' | 'reset'> = {
  reset: () => {
    drafts = null;
    seq = 0;
  },
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/tasks/drafts/new',
      handle: () => ok({ period: period(), draft: blankDraft() }),
    },
    {
      method: 'POST',
      pattern: '/api/v1/tasks/assign-check',
      handle: (r) => ok(check(sanitize(r.body))),
    },
    { method: 'POST', pattern: '/api/v1/tasks/bulk-assign', handle: bulkAssign },
    { method: 'POST', pattern: '/api/v1/tasks', handle: create },
    { method: 'PUT', pattern: '/api/v1/tasks/:id', handle: update },
    { method: 'DELETE', pattern: '/api/v1/tasks/:id', handle: remove },
    { method: 'POST', pattern: '/api/v1/tasks/:id/assign', handle: assign },
  ],
};
