import { HttpParams } from '@angular/common/http';
import { MAX_PAGE_LIMIT } from '../../lib/paging';
import {
  MyTasksQuickFilter,
  PeriodRef,
  ProgressBatchItem,
  ProgressBatchResult,
  ProgressUpdate,
  TaskDetail,
  TaskRegistration,
  TaskStatus,
  TaskSummary,
} from '../../lib/models/task';
import { CatalogItem } from '../../lib/models/catalog';
import {
  MockModule,
  MockRequest,
  MockResponse,
  ok,
  problem,
  resolveRelativeDates,
} from '../mock-router';
import catalogFixture from '../catalog/catalog.json';
import fixture from './tasks.json';

/**
 * Giả lập API `/api/v1/tasks` của MOD-02 (mission-core) cho QT-05, QT-06:
 * SCR-0503 "Việc của tôi", SCR-0601 "Chi tiết nhiệm vụ", widget `myTasksToday` (SCR-0002).
 * Mô phỏng hành vi quan trọng của máy chủ: kiểm `If-Match` (409), kiểm dữ liệu (422),
 * tăng `rowVersion` sau khi ghi, chuyển `DA_GIAO` → `DANG_THUC_HIEN` (baseline mục 9.1),
 * BR-06-02 (giảm tiến độ phải có ghi chú), BR-06-03 (chỉ chủ trì đổi phần trăm),
 * số ngày làm việc còn lại và cờ quá hạn tính theo lịch (giả lập: nghỉ thứ Bảy, Chủ nhật).
 */
type StoredTask = Omit<TaskDetail, 'overdue' | 'workingDaysLeft' | 'openBlockerCount'>;

/** Số ngày làm việc còn lại để một nhiệm vụ được xếp "Sắp hạn" (tham số Policy Pack, giả định 3). */
export const SAP_HAN_WORKING_DAYS = 3;
const OPEN: TaskStatus[] = ['DA_GIAO', 'DANG_THUC_HIEN'];

let db: StoredTask[] | null = null;
let period: PeriodRef | null = null;

function tasks(): StoredTask[] {
  db ??= resolveRelativeDates(structuredClone(fixture.tasks) as StoredTask[]);
  return db;
}

function currentPeriod(): PeriodRef {
  period ??= resolveRelativeDates(structuredClone(fixture.period));
  return period;
}

const utcDay = (iso: string) => Date.UTC(+iso.slice(0, 4), +iso.slice(5, 7) - 1, +iso.slice(8, 10));

/** Số ngày làm việc từ hôm nay tới `due` (âm nếu đã qua), bỏ thứ Bảy và Chủ nhật. */
export function workingDaysBetween(todayIso: string, dueIso: string): number {
  const a = utcDay(todayIso);
  const b = utcDay(dueIso);
  const sign = b >= a ? 1 : -1;
  let n = 0;
  for (let d = a; d !== b; d += sign * 86_400_000) {
    const next = d + sign * 86_400_000;
    const wd = new Date(sign > 0 ? next : d).getUTCDay();
    if (wd !== 0 && wd !== 6) n++;
  }
  return sign * n;
}

const todayIso = () => new Date().toISOString().slice(0, 10);

function derive(t: StoredTask): TaskDetail {
  const workingDaysLeft = workingDaysBetween(todayIso(), t.dueDate);
  // cờ quá hạn: đã qua hạn mà chưa CHO_NGHIEM_THU hoặc HOAN_THANH (QT-06 bước 8)
  const overdue =
    utcDay(t.dueDate) < utcDay(todayIso()) && (OPEN.includes(t.status) || t.status === 'TAM_DUNG');
  return {
    ...t,
    overdue,
    workingDaysLeft,
    openBlockerCount: t.blockers.filter((b) => !b.resolved).length,
  };
}

function toSummary(t: StoredTask): TaskSummary {
  const d = derive(t);
  return {
    id: d.id,
    code: d.code,
    title: d.title,
    status: d.status,
    overdue: d.overdue,
    dueDate: d.dueDate,
    progress: d.progress,
    role: d.role,
    assignerName: d.assignerName,
    unitName: d.unitName,
    periodId: d.periodId,
    rowVersion: d.rowVersion,
    taskGroup: d.taskGroup,
    source: d.source,
    keyResultId: d.keyResultId,
    keyResultTitle: d.keyResultTitle,
    plannedQuantity: d.plannedQuantity,
    doneQuantity: d.doneQuantity,
    unit: d.unit,
    openBlockerCount: d.openBlockerCount,
    workingDaysLeft: d.workingDaysLeft,
  };
}

const etag = (t: StoredTask) => ({ ETag: `"${t.rowVersion}"` });
const find = (id: string) => tasks().find((x) => x.id === id);

function notFound(id: string) {
  return problem(404, 'Không tìm thấy nhiệm vụ', { detail: `Không có nhiệm vụ ${id}` });
}

export function matchesQuickFilter(t: TaskSummary, f: MyTasksQuickFilter): boolean {
  switch (f) {
    case 'CHAM':
      return t.overdue;
    case 'SAP_HAN':
      return OPEN.includes(t.status) && !t.overdue && t.workingDaysLeft <= SAP_HAN_WORKING_DAYS;
    case 'CHO_NGHIEM_THU':
      return t.status === 'CHO_NGHIEM_THU';
    case 'HOAN_THANH':
      return t.status === 'HOAN_THANH';
    default:
      return true;
  }
}

const QUICK_FILTERS: MyTasksQuickFilter[] = [
  'TAT_CA',
  'CHAM',
  'SAP_HAN',
  'CHO_NGHIEM_THU',
  'HOAN_THANH',
];

function myTasks(params: HttpParams): MockResponse {
  const limit = Math.min(Number(params.get('limit') ?? 20) || 20, MAX_PAGE_LIMIT);
  const offset = Number(params.get('cursor') ?? 0) || 0;
  const status = params.get('status')?.split(',').filter(Boolean) as TaskStatus[] | undefined;
  const q = params.get('q')?.toLowerCase();
  const p = currentPeriod();
  // nhiệm vụ NHAP chưa hiển thị với người thực hiện, trừ nhiệm vụ do chính họ đăng ký (QT-05)
  let base = tasks()
    .filter((t) => t.status !== 'NHAP' || t.source === 'DANG_KY')
    .map(toSummary);
  const periodId = params.get('period');
  if (periodId) base = base.filter((t) => t.periodId === periodId);
  if (params.get('role')) base = base.filter((t) => t.role === params.get('role'));
  if (params.get('group')) base = base.filter((t) => t.taskGroup === params.get('group'));
  if (params.get('kr')) base = base.filter((t) => t.keyResultId === params.get('kr'));
  if (status?.length) base = base.filter((t) => status.includes(t.status));
  if (params.get('overdue') === 'true') base = base.filter((t) => t.overdue);
  if (q)
    base = base.filter(
      (t) => t.title.toLowerCase().includes(q) || t.code.toLowerCase().includes(q),
    );

  const counts = Object.fromEntries(
    QUICK_FILTERS.map((f) => [f, base.filter((t) => matchesQuickFilter(t, f)).length]),
  ) as Record<MyTasksQuickFilter, number>;
  const filter = (params.get('filter') ?? 'TAT_CA') as MyTasksQuickFilter;
  const items = base
    .filter((t) => matchesQuickFilter(t, filter))
    .sort((a, b) => a.dueDate.localeCompare(b.dueDate) || a.code.localeCompare(b.code));
  const keyResults = [
    ...new Map(
      tasks()
        .filter((t) => t.keyResultId)
        .map((t) => [
          t.keyResultId!,
          { id: t.keyResultId!, title: t.keyResultTitle ?? t.keyResultId! },
        ]),
    ).values(),
  ];
  const page = items.slice(offset, offset + limit);
  const next = offset + limit < items.length ? String(offset + limit) : null;
  return ok({ items: page, nextCursor: next, period: p, counts, keyResults });
}

function checkIfMatch(t: StoredTask, ifMatch: string | null): MockResponse | null {
  if (ifMatch && ifMatch !== `"${t.rowVersion}"`) {
    return problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      detail: `If-Match ${ifMatch} khác phiên bản hiện tại "${t.rowVersion}"`,
    });
  }
  return null;
}

/** Ghi một lần cập nhật tiến độ; dùng chung cho cập nhật đơn lẻ và hàng loạt. */
function applyProgress(
  t: StoredTask,
  body: Partial<ProgressUpdate>,
  ifMatch: string | null,
): MockResponse {
  const conflict = checkIfMatch(t, ifMatch);
  if (conflict) return conflict;
  if (!OPEN.includes(t.status)) {
    return problem(422, 'Nhiệm vụ không ở trạng thái cho phép cập nhật tiến độ', {
      detail: `Trạng thái hiện tại ${t.status}`,
    });
  }
  const percent = body.percent;
  if (typeof percent !== 'number' || !Number.isInteger(percent) || percent < 0 || percent > 100) {
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'percent', message: 'Tiến độ phải là số nguyên từ 0 đến 100' }],
    });
  }
  if (t.role === 'PHOI_HOP' && percent !== t.progress) {
    return problem(
      403,
      'Chỉ người chủ trì được đổi phần trăm tiến độ; người phối hợp chỉ ghi nhật ký',
      {
        detail: 'BR-06-03',
      },
    );
  }
  const note = (body.note ?? '').trim();
  if (percent < t.progress && !note) {
    return problem(422, 'Tiến độ mới thấp hơn lần trước. Vui lòng ghi chú lý do.', {
      code: 'MSG-06-01',
      errors: [
        { field: 'note', message: 'Tiến độ mới thấp hơn lần trước. Vui lòng ghi chú lý do.' },
      ],
    });
  }
  t.progress = percent;
  t.rowVersion += 1;
  if (t.status === 'DA_GIAO') t.status = 'DANG_THUC_HIEN';
  t.progressLog.unshift({ at: new Date().toISOString(), percent, note, by: 'Tôi' });
  if (body.blocker)
    t.blockers.push({ id: crypto.randomUUID(), content: body.blocker, resolved: false });
  return ok(derive(structuredClone(t)), etag(t));
}

function updateProgress(req: MockRequest): MockResponse {
  const t = find(req.pathParams['id']);
  if (!t) return notFound(req.pathParams['id']);
  return applyProgress(t, (req.body ?? {}) as Partial<ProgressUpdate>, req.headers.get('If-Match'));
}

function accept(req: MockRequest): MockResponse {
  const t = find(req.pathParams['id']);
  if (!t) return notFound(req.pathParams['id']);
  const conflict = checkIfMatch(t, req.headers.get('If-Match'));
  if (conflict) return conflict;
  if (t.role !== 'CHU_TRI') return problem(403, 'Chỉ người chủ trì được nhận việc');
  if (t.status !== 'DA_GIAO') {
    return problem(422, 'Nhiệm vụ không ở trạng thái Đã giao', {
      detail: `Trạng thái hiện tại ${t.status}`,
    });
  }
  t.status = 'DANG_THUC_HIEN';
  t.rowVersion += 1;
  return ok(derive(structuredClone(t)), etag(t));
}

function progressBatch(req: MockRequest): MockResponse {
  const items = ((req.body ?? {}) as { items?: ProgressBatchItem[] }).items ?? [];
  if (!items.length || items.length > MAX_PAGE_LIMIT) {
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'items', message: `Cần từ 1 đến ${MAX_PAGE_LIMIT} nhiệm vụ` }],
    });
  }
  const results: ProgressBatchResult[] = items.map((it) => {
    const t = find(it.taskId);
    const res = t ? applyProgress(t, it, `"${it.rowVersion}"`) : notFound(it.taskId);
    return res.status < 300
      ? { taskId: it.taskId, status: res.status, task: toSummary(res.body as TaskDetail) }
      : {
          taskId: it.taskId,
          status: res.status,
          problem: res.body as { title: string; code?: string },
        };
  });
  return ok({ results });
}

function register(req: MockRequest): MockResponse {
  const body = (req.body ?? {}) as Partial<TaskRegistration>;
  const item = (catalogFixture.items as CatalogItem[]).find((c) => c.id === body.catalogItemId);
  const p = currentPeriod();
  const errors: { field: string; message: string }[] = [];
  if (!item)
    errors.push({ field: 'catalogItemId', message: 'Chọn một mục trong danh mục áp dụng' });
  if (typeof body.quantity !== 'number' || !(body.quantity > 0)) {
    errors.push({ field: 'quantity', message: 'Số lượng phải lớn hơn 0' });
  }
  const due = body.proposedDueDate ?? '';
  if (!/^\d{4}-\d{2}-\d{2}$/.test(due) || due < todayIso() || due > p.endDate) {
    errors.push({
      field: 'proposedDueDate',
      message: 'Hạn đề xuất phải nằm trong kỳ, từ hôm nay trở đi',
    });
  }
  if (errors.length || !item) return problem(422, 'Dữ liệu không hợp lệ', { errors });
  const n = tasks().length + 200;
  const t: StoredTask = {
    id: crypto.randomUUID(),
    code: `NV-${p.id}-${String(n).padStart(4, '0')}`,
    title: item.name,
    status: 'NHAP',
    dueDate: due,
    progress: 0,
    role: 'CHU_TRI',
    assignerName: 'Chờ người quản lý duyệt',
    unitName: 'Ban Tổ chức',
    periodId: p.id,
    rowVersion: 0,
    taskGroup: item.taskGroup,
    source: 'DANG_KY',
    plannedQuantity: body.quantity!,
    doneQuantity: 0,
    unit: item.unit,
    description: body.note ?? '',
    coAssignees: [],
    progressLog: [],
    blockers: [],
  };
  tasks().push(t);
  return {
    status: 201,
    body: toSummary(t),
    headers: { Location: `/api/v1/tasks/${t.id}`, ...etag(t) },
  };
}

export const TASKS_MOCK: MockModule = {
  name: 'tasks',
  reset: () => {
    db = null;
    period = null;
  },
  routes: [
    { method: 'GET', pattern: '/api/v1/tasks/mine', handle: (r) => myTasks(r.params) },
    { method: 'POST', pattern: '/api/v1/tasks/progress-batch', handle: progressBatch },
    { method: 'POST', pattern: '/api/v1/tasks/registrations', handle: register },
    {
      method: 'POST',
      pattern: '/api/v1/tasks/export',
      handle: () => ({
        status: 202,
        body: null,
        headers: { Location: `/api/v1/jobs/${crypto.randomUUID()}` },
      }),
    },
    {
      method: 'GET',
      pattern: '/api/v1/tasks/:id',
      handle: (r) => {
        const t = find(r.pathParams['id']);
        return t ? ok(derive(structuredClone(t)), etag(t)) : notFound(r.pathParams['id']);
      },
    },
    { method: 'POST', pattern: '/api/v1/tasks/:id/progress', handle: updateProgress },
    { method: 'POST', pattern: '/api/v1/tasks/:id/accept', handle: accept },
  ],
};
