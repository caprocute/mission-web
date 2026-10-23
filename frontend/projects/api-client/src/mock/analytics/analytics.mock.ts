import type {
  AnalyticsColumn,
  AnalyticsDashboard,
  AnalyticsDashboardInput,
  AnalyticsFilter,
  AnalyticsQuery,
  AnalyticsQueryResult,
  DashboardShareRequest,
  TraceChain,
  TraceObjectType,
} from '../../lib/models/analytics';
import type { TaskSummary } from '../../lib/models/task';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { MOCK_ME } from '../mock-session';
import { unitQueue } from '../tasks/tasks.mock';
import {
  CURRENT_PERIOD,
  HOANG_SCORE_ID,
  NOT_MY_ASSIGNMENT,
  PERIODS,
  SEMANTIC_MODEL,
  STALE_PERIOD,
  SUMMARY_SOURCES,
  UNITS,
  UNIT_MEASURES,
  UnitMeasure,
  dashboards as dashboardFixtures,
  directiveTasks,
  genericChain,
  hoangChain,
  people,
  unitValues,
  weeklySummary,
} from './analytics.fixture';

/**
 * Giả lập SVC-15 analytics (QT-16, WEB-047, WEB-048) theo `platform.yaml`: một bộ máy truy vấn nhỏ trên
 * dữ liệu mẫu thay cho Cube (chỉ chỉ số, chiều của lớp ngữ nghĩa, tối đa 2 chiều, kết hợp sai trả 422 với
 * MSG-16-04, so với kỳ trước thêm cột `.previous`), dashboard tự dựng (`config.dashboard_def`: trùng tên
 * 409, chỉ người tạo được sửa 403, `If-Match` 428/412), chuỗi truy vết có mắt xích bị che (MSG-16-06),
 * tóm tắt tuần của SVC-14, việc cần chỉ đạo cùng nhắc, giao lại của MOD-02 (`/tasks`).
 */

let boards: AnalyticsDashboard[] = dashboardFixtures();
let tasks: TaskSummary[] = directiveTasks();
let seq = 0;

const etag = (rowVersion: number) => ({ ETag: `"${rowVersion}"` });
const accepted = (kind: string): MockResponse => ({
  status: 202,
  body: { id: `job-${kind}-${++seq}`, type: 'EXPORT', status: 'CHO' },
  headers: { Location: `/api/v1/jobs/job-${kind}-${seq}`, 'Retry-After': '2' },
});

function ifMatch(x: { rowVersion: number }, req: MockRequest): MockResponse | null {
  const h = req.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu thông tin phiên bản dữ liệu; vui lòng tải lại trang.');
  if (h.replace(/"/g, '') !== String(x.rowVersion))
    return problem(412, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      code: 'PRECONDITION_FAILED',
    });
  return null;
}

const invalid = (field: string, message: string, extra: Record<string, unknown> = {}) =>
  problem(422, 'Dữ liệu không hợp lệ', {
    code: 'COMMON-422-VALIDATION',
    errors: [{ field, message }],
    ...extra,
  });

const measureOf = (n: string) => SEMANTIC_MODEL.measures.find((m) => m.name === n);
const dimensionOf = (n: string) => SEMANTIC_MODEL.dimensions.find((d) => d.name === n);
const periodLabel = (id: string) => PERIODS.find((p) => p.id === id)?.label ?? id;
const previousOf = (id: string) => PERIODS.find((p) => p.id === id)?.previous ?? null;

type Fact = Record<string, string | number>;
const PERSON_DIMS = new Set(['person.name', 'score_snapshot.id', 'rating.level']);
const WEEKS = 6;

/** Bản ghi chi tiết của một kỳ theo hạt cần cho các chiều được hỏi (đơn vị, tuần, loại nhiệm vụ, cán bộ). */
function facts(periodId: string, dims: string[]): Fact[] {
  const out: Fact[] = [];
  const label = periodLabel(periodId);
  if (dims.some((d) => PERSON_DIMS.has(d))) {
    for (const p of people(periodId)) {
      const u = UNITS.find((x) => x.id === p.unitId)!;
      out.push({
        'org_unit.id': u.id,
        'org_unit.name': u.name,
        'flow.name': u.flow,
        'subject_group.name': u.group,
        'period.label': label,
        'person.name': p.name,
        'score_snapshot.id': p.scoreId,
        'rating.level': p.rating,
        'appraisal.diem_kpi_tb': p.score,
        'task.ty_le_dung_han': p.onTime,
        'appraisal.phan_bo_muc': 1,
      });
    }
    return out;
  }
  const weeks = dims.includes('time.week') ? WEEKS : 0;
  const types = dims.includes('task_type.name') ? ['Then chốt', 'Thường xuyên'] : [null];
  for (const u of UNITS) {
    const v = unitValues(periodId, u.id);
    if (!v) continue;
    const base: Fact = {
      'org_unit.id': u.id,
      'org_unit.name': u.name,
      'flow.name': u.flow,
      'subject_group.name': u.group,
      'period.label': label,
      // số cán bộ theo mức để phân bố mức khi không hỏi chiều cán bộ
      'appraisal.phan_bo_muc': people(periodId).filter((p) => p.unitId === u.id).length,
    };
    for (const t of types) {
      const split = t === null ? 1 : t === 'Then chốt' ? 0.4 : 0.6;
      const rows = weeks ? Array.from({ length: weeks }, (_, i) => i + 1) : [0];
      for (const w of rows) {
        const f: Fact = { ...base };
        if (t) f['task_type.name'] = t;
        if (w) f['time.week'] = `Tuần ${w}`;
        for (const m of Object.keys(UNIT_MEASURES) as UnitMeasure[]) {
          let x = v[m];
          if (UNIT_MEASURES[m].agg === 'sum') x = Math.round(x * split);
          else if (t === 'Then chốt' && m === 'task.ty_le_dung_han') x -= 4;
          if (w && UNIT_MEASURES[m].progressive) x = Math.round(x * (0.55 + (0.45 * w) / WEEKS));
          f[m] = x;
        }
        out.push(f);
      }
    }
  }
  return out;
}

function matches(f: Fact, filters: AnalyticsFilter[]): boolean {
  return filters.every((x) => {
    const v = f[x.member];
    const vals = x.values.map(String);
    switch (x.operator) {
      case 'equals':
      case 'in':
        return vals.includes(String(v));
      case 'notEquals':
        return !vals.includes(String(v));
      case 'gte':
        return Number(v) >= Number(x.values[0]);
      case 'lte':
        return Number(v) <= Number(x.values[0]);
    }
  });
}

const aggOf = (m: string) =>
  m in UNIT_MEASURES
    ? UNIT_MEASURES[m as UnitMeasure].agg
    : m === 'appraisal.phan_bo_muc'
      ? 'sum'
      : 'avg';

function aggregate(rows: Fact[], m: string): number | null {
  const xs = rows.map((r) => r[m]).filter((x): x is number => typeof x === 'number');
  if (!xs.length) return null;
  const s = xs.reduce((a, b) => a + b, 0);
  return aggOf(m) === 'sum' ? s : Math.round((s / xs.length) * 10) / 10;
}

/** Kiểm truy vấn theo lớp ngữ nghĩa (BR-16-02); trả lỗi 422 hoặc null. */
function validate(q: Partial<AnalyticsQuery>): MockResponse | null {
  if (!q.measures?.length) return invalid('measures', 'Chọn ít nhất một chỉ số.');
  if ((q.dimensions?.length ?? 0) > 2) return invalid('dimensions', 'Mỗi truy vấn tối đa 2 chiều.');
  for (const m of q.measures)
    if (!measureOf(m)) return invalid('measures', 'Chỉ số không có trong danh mục chỉ số chuẩn.');
  for (const name of [...(q.dimensions ?? []), ...(q.filters ?? []).map((f) => f.member)]) {
    const d = dimensionOf(name);
    if (!d) return invalid('dimensions', 'Chiều không có trong danh mục chiều chuẩn.');
    const bad = q.measures.find((m) => !d.supportedMeasures?.includes(m));
    if (bad)
      return problem(422, 'Kết hợp chỉ số và chiều không hợp lệ', {
        code: 'ANALYTICS-422-INVALID-COMBINATION',
        msg: 'MSG-16-04',
        detail: `Chỉ số ${measureOf(bad)!.title} không hỗ trợ chiều ${d.title}.`,
      });
  }
  return null;
}

function run(q: AnalyticsQuery): AnalyticsQueryResult {
  const periodId = q.periodId ?? CURRENT_PERIOD;
  const dims = q.dimensions ?? [];
  const filters = [...(q.filters ?? [])];
  if (q.unitId) filters.push({ member: 'org_unit.id', operator: 'equals', values: [q.unitId] });
  const prevId = q.compareToPreviousPeriod ? previousOf(periodId) : null;
  const cur = facts(periodId, dims).filter((f) => matches(f, filters));
  const prev = q.compareToPreviousPeriod
    ? (prevId ? facts(prevId, dims) : []).filter((f) => matches(f, filters))
    : [];
  const key = (f: Fact) => dims.map((d) => String(f[d])).join('\u0001');
  const groups = new Map<string, Fact[]>();
  for (const f of cur) groups.set(key(f), [...(groups.get(key(f)) ?? []), f]);
  if (!dims.length && !groups.size) groups.set('', []);
  const columns: AnalyticsColumn[] = [
    ...dims.map((d) => {
      const x = dimensionOf(d)!;
      return {
        name: d,
        title: x.title,
        type: x.type === 'time' ? 'time' : 'string',
      } as AnalyticsColumn;
    }),
    ...q.measures.flatMap((m) => {
      const x = measureOf(m)!;
      const col = {
        name: m,
        title: x.title,
        type: x.type === 'count' ? 'count' : 'number',
        ...(x.unit ? { unit: x.unit } : {}),
      } as AnalyticsColumn;
      return q.compareToPreviousPeriod
        ? [col, { ...col, name: `${m}.previous`, title: `${x.title} kỳ trước` }]
        : [col];
    }),
  ];
  const rows = [...groups.entries()].map(([k, fs]) => {
    const pfs = prev.filter((p) => key(p) === k);
    return [
      ...dims.map((d) => fs[0]?.[d] ?? null),
      ...q.measures.flatMap((m) =>
        q.compareToPreviousPeriod ? [aggregate(fs, m), aggregate(pfs, m)] : [aggregate(fs, m)],
      ),
    ];
  });
  const stale = periodId === STALE_PERIOD;
  return {
    semanticModelVersion: SEMANTIC_MODEL.version,
    dataAsOf: new Date(Date.now() - (stale ? 42 : 3) * 60_000).toISOString(),
    stale,
    columns,
    rows: rows.slice(0, Math.min(q.limit ?? 50, 200)),
  };
}

function query(req: MockRequest): MockResponse {
  const q = (req.body ?? {}) as AnalyticsQuery;
  return validate(q) ?? ok(run(q));
}

// ── Dashboard ──
const summary = (d: AnalyticsDashboard) => ({
  id: d.id,
  title: d.title,
  ownerUserId: d.ownerUserId,
  shareScope: d.shareScope,
  widgetCount: d.layout.length,
  updatedAt: d.updatedAt,
});
const visible = (d: AnalyticsDashboard) => d.ownerUserId === MOCK_ME.id || d.shareScope !== 'RIENG';

function listBoards(req: MockRequest): MockResponse {
  const mine = req.params.get('owner') === 'me';
  const items = boards
    .filter((d) => (mine ? d.ownerUserId === MOCK_ME.id : visible(d)))
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
    .map(summary);
  return ok({ items, nextCursor: null });
}

function findBoard(req: MockRequest): AnalyticsDashboard | MockResponse {
  const d = boards.find((x) => x.id === req.pathParams['id']);
  return d && visible(d) ? d : problem(404, 'Không tìm thấy dashboard');
}
const isRes = (x: AnalyticsDashboard | MockResponse): x is MockResponse => !('layout' in x);

function checkInput(b: Partial<AnalyticsDashboardInput>, selfId?: string): MockResponse | null {
  const title = (b.title ?? '').trim();
  if (!title || title.length > 100) return invalid('title', 'Tên dashboard từ 1 đến 100 ký tự.');
  if (!b.layout?.length) return invalid('layout', 'Dashboard cần ít nhất một biểu đồ.');
  if (b.layout.length > 20) return invalid('layout', 'Mỗi dashboard tối đa 20 biểu đồ.');
  for (const w of b.layout) {
    const bad = validate({ measures: [w.measure], dimensions: w.dimensions, filters: w.filters });
    if (bad) return bad;
  }
  if (
    boards.some((d) => d.ownerUserId === MOCK_ME.id && d.id !== selfId && d.title.trim() === title)
  )
    return problem(409, 'Trùng tên dashboard', {
      code: 'ANALYTICS-409-DUPLICATE-TITLE',
      msg: 'MSG-16-04',
      detail: 'Đồng chí đã có dashboard cùng tên; vui lòng đặt tên khác.',
    });
  return null;
}

function apply(d: AnalyticsDashboard, b: AnalyticsDashboardInput) {
  d.title = b.title.trim();
  d.layout = structuredClone(b.layout);
  d.measures = [...new Set(b.layout.map((w) => w.measure))];
  d.dimensions = [...new Set(b.layout.flatMap((w) => w.dimensions ?? []))];
  d.semanticModelVersion = SEMANTIC_MODEL.version;
  d.updatedAt = new Date().toISOString();
}

function withData(d: AnalyticsDashboard, req: MockRequest): AnalyticsDashboard {
  const out = structuredClone(d);
  if (req.params.get('includeData') === 'false') return out;
  const periodId = req.params.get('period') ?? CURRENT_PERIOD;
  const unitId = req.params.get('unit');
  const results = d.layout.map((w) => ({
    w,
    r: run({
      measures: [w.measure],
      dimensions: w.dimensions ?? [],
      filters: w.filters ?? [],
      periodId,
      ...(unitId ? { unitId } : {}),
    }),
  }));
  out.data = {
    dataAsOf: results[0]?.r.dataAsOf ?? new Date().toISOString(),
    stale: results.some((x) => x.r.stale),
    widgets: results.map(({ w, r }) => ({ widgetId: w.id, columns: r.columns, rows: r.rows })),
  };
  return out;
}

function createBoard(req: MockRequest): MockResponse {
  const b = (req.body ?? {}) as AnalyticsDashboardInput;
  const bad = checkInput(b);
  if (bad) return bad;
  const d: AnalyticsDashboard = {
    id: `db-moi-${++seq}`,
    title: '',
    ownerUserId: MOCK_ME.id,
    measures: [],
    dimensions: [],
    layout: [],
    shareScope: 'RIENG',
    sharedUnitIds: [],
    rowVersion: 0,
    updatedAt: '',
  };
  apply(d, b);
  boards = [...boards, d];
  return {
    status: 201,
    body: structuredClone(d),
    headers: { ...etag(d.rowVersion), Location: `/api/v1/analytics/dashboards/${d.id}` },
  };
}

const notOwner = () =>
  problem(403, 'Không có quyền sửa dashboard', {
    code: 'ANALYTICS-403-NOT-OWNER',
    detail: 'Chỉ người tạo dashboard được sửa hoặc chia sẻ dashboard này.',
  });

function saveBoard(req: MockRequest): MockResponse {
  const d = findBoard(req);
  if (isRes(d)) return d;
  if (d.ownerUserId !== MOCK_ME.id) return notOwner();
  const pre = ifMatch(d, req);
  if (pre) return pre;
  const b = (req.body ?? {}) as AnalyticsDashboardInput;
  const bad = checkInput(b, d.id);
  if (bad) return bad;
  apply(d, b);
  d.rowVersion++;
  return ok(structuredClone(d), etag(d.rowVersion));
}

function shareBoard(req: MockRequest): MockResponse {
  const d = findBoard(req);
  if (isRes(d)) return d;
  if (d.ownerUserId !== MOCK_ME.id) return notOwner();
  const pre = ifMatch(d, req);
  if (pre) return pre;
  const b = (req.body ?? {}) as Partial<DashboardShareRequest>;
  if (!b.shareScope || !['RIENG', 'DON_VI', 'TENANT'].includes(b.shareScope))
    return invalid('shareScope', 'Chọn phạm vi chia sẻ.');
  if (b.shareScope === 'DON_VI' && !b.sharedUnitIds?.length)
    return invalid('sharedUnitIds', 'Chọn ít nhất một đơn vị được xem.');
  d.shareScope = b.shareScope;
  d.sharedUnitIds = b.shareScope === 'DON_VI' ? [...(b.sharedUnitIds ?? [])] : [];
  d.rowVersion++;
  d.updatedAt = new Date().toISOString();
  return ok(structuredClone(d), etag(d.rowVersion));
}

// ── Chuỗi truy vết ──
function chainFor(type: TraceObjectType, id: string): TraceChain | null {
  const all = [CURRENT_PERIOD, 'p-2026q3', 'p-2026q2'];
  const candidates = [
    hoangChain(),
    ...all.flatMap((pid) =>
      people(pid)
        .filter((p) => p.scoreId !== HOANG_SCORE_ID)
        .map((p) => genericChain(p, UNITS.find((u) => u.id === p.unitId)!.name, periodLabel(pid))),
    ),
  ];
  const links = candidates.find((c) => c.some((l) => l.objectType === type && l.id === id));
  if (!links) return null;
  const focus = links.find((l) => l.objectType === type && l.id === id)!;
  if (focus.masked) return null;
  return {
    focus: { objectType: type, id },
    links,
    ...(type === 'MINH_CHUNG'
      ? {
          ledgerRef: `ledger:cell-dtw:${new Date().toISOString().slice(0, 10)}:${String(184_200 + ++seq).padStart(9, '0')}`,
        }
      : {}),
  };
}

function trace(req: MockRequest): MockResponse {
  const type = req.pathParams['objectType'] as TraceObjectType;
  const id = req.pathParams['id'];
  const c = chainFor(type, id);
  if (c) return ok(c);
  return problem(404, 'Không tìm thấy đối tượng trong chuỗi truy vết');
}

// ── Việc cần chỉ đạo (MOD-02) ──
function unitTasks(req: MockRequest): MockResponse {
  if (req.params.has('source') || req.params.has('pendingChangeRequest')) {
    return unitQueue(req.params);
  }
  const overdue = req.params.get('overdue') === 'true';
  const limit = Math.min(Number(req.params.get('limit') ?? 20), 200);
  const items = tasks
    .filter((t) => !overdue || t.overdue)
    .sort((a, b) => a.workingDaysLeft - b.workingDaysLeft)
    .slice(0, limit);
  return ok({ items: structuredClone(items), nextCursor: null });
}

const findTask = (req: MockRequest) => tasks.find((t) => t.id === req.pathParams['id']);

function remind(req: MockRequest): MockResponse {
  const t = findTask(req);
  if (!t) return problem(404, 'Không tìm thấy nhiệm vụ');
  return { status: 202, body: null };
}

function reassign(req: MockRequest): MockResponse {
  const t = findTask(req);
  if (!t) return problem(404, 'Không tìm thấy nhiệm vụ');
  const pre = ifMatch(t, req);
  if (pre) return pre;
  const b = (req.body ?? {}) as { leadPersonId?: string; reason?: string };
  if (!b.leadPersonId) return invalid('leadPersonId', 'Chọn người chủ trì mới.');
  if (!(b.reason ?? '').trim()) return invalid('reason', 'Nhập lý do giao lại.');
  if (t.id === NOT_MY_ASSIGNMENT)
    return problem(403, 'Không có quyền giao lại', {
      code: 'TASK-403-NOT-ASSIGNER',
      msg: 'MSG-16-06',
      detail: `Đồng chí không có quyền giao lại nhiệm vụ này thuộc ${t.unitName}; nhiệm vụ do đồng chí ${t.assignerName} giao.`,
    });
  t.rowVersion++;
  return ok({ ...structuredClone(t), description: '' }, etag(t.rowVersion));
}

// ── Tóm tắt tuần (SVC-14) ──
function suggestions(req: MockRequest): MockResponse {
  if (req.params.get('kind') !== 'TOM_TAT_TUAN') return ok({ items: [], nextCursor: null });
  const period = req.params.get('period') ?? CURRENT_PERIOD;
  return ok({ items: period === CURRENT_PERIOD ? [weeklySummary()] : [], nextCursor: null });
}

export const ANALYTICS_MOCK: MockModule = {
  name: 'analytics',
  routes: [
    { method: 'POST', pattern: '/api/v1/analytics/query', handle: query },
    {
      method: 'GET',
      pattern: '/api/v1/analytics/semantic-model',
      handle: () => ok(structuredClone(SEMANTIC_MODEL)),
    },
    { method: 'GET', pattern: '/api/v1/analytics/dashboards', handle: listBoards },
    { method: 'POST', pattern: '/api/v1/analytics/dashboards', handle: createBoard },
    {
      method: 'GET',
      pattern: '/api/v1/analytics/dashboards/:id',
      handle: (req) => {
        const d = findBoard(req);
        return isRes(d) ? d : ok(withData(d, req), etag(d.rowVersion));
      },
    },
    { method: 'PUT', pattern: '/api/v1/analytics/dashboards/:id', handle: saveBoard },
    { method: 'POST', pattern: '/api/v1/analytics/dashboards/:id/shares', handle: shareBoard },
    { method: 'POST', pattern: '/api/v1/analytics/reports', handle: () => accepted('report') },
    { method: 'GET', pattern: '/api/v1/analytics/trace/:objectType/:id', handle: trace },
    { method: 'POST', pattern: '/api/v1/analytics/trace/exports', handle: () => accepted('trace') },
    { method: 'GET', pattern: '/api/v1/ai/suggestions', handle: suggestions },
    {
      method: 'GET',
      pattern: '/api/v1/ai/suggestions/:id/sources',
      handle: (req) =>
        req.pathParams['id'] === SUMMARY_SOURCES.suggestionId
          ? ok(structuredClone(SUMMARY_SOURCES))
          : problem(404, 'Không tìm thấy gợi ý'),
    },
    { method: 'GET', pattern: '/api/v1/tasks', handle: unitTasks },
    { method: 'POST', pattern: '/api/v1/tasks/:id/reminders', handle: remind },
    { method: 'POST', pattern: '/api/v1/tasks/:id/reassign', handle: reassign },
  ],
  reset: () => {
    boards = dashboardFixtures();
    tasks = directiveTasks();
    seq = 0;
  },
};
