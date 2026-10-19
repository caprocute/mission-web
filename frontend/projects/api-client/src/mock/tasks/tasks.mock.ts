import { HttpParams } from '@angular/common/http';
import { MAX_PAGE_LIMIT } from '../../lib/paging';
import { ProgressUpdate, TaskDetail, TaskStatus, TaskSummary } from '../../lib/models/task';
import { MockModule, MockRequest, ok, problem, resolveRelativeDates } from '../mock-router';
import fixture from './tasks.json';

/**
 * Giả lập API `/api/v1/tasks` của MOD-02 (mission-core) cho QT-05, QT-06:
 * SCR-0503 "Việc của tôi", SCR-0601 "Chi tiết nhiệm vụ", widget `myTasksToday` (SCR-0002).
 * Mô phỏng hành vi quan trọng của máy chủ: kiểm `If-Match` (409), kiểm dữ liệu (422),
 * tăng `rowVersion` sau khi ghi, chuyển `DA_GIAO` → `DANG_THUC_HIEN` (baseline mục 9.1).
 */
let db: TaskDetail[] | null = null;

function tasks(): TaskDetail[] {
  db ??= resolveRelativeDates(structuredClone(fixture.tasks) as TaskDetail[]);
  return db;
}

function toSummary(t: TaskDetail): TaskSummary {
  const {
    id,
    code,
    title,
    status,
    overdue,
    dueDate,
    progress,
    role,
    assignerName,
    unitName,
    periodId,
    rowVersion,
  } = t;
  return {
    id,
    code,
    title,
    status,
    overdue,
    dueDate,
    progress,
    role,
    assignerName,
    unitName,
    periodId,
    rowVersion,
  };
}

const etag = (t: TaskDetail) => ({ ETag: `"${t.rowVersion}"` });

function notFound(id: string) {
  return problem(404, 'Không tìm thấy nhiệm vụ', { detail: `Không có nhiệm vụ ${id}` });
}

function myTasks(params: HttpParams) {
  const limit = Math.min(Number(params.get('limit') ?? 50) || 50, MAX_PAGE_LIMIT);
  const offset = Number(params.get('cursor') ?? 0) || 0;
  const status = params.get('status')?.split(',').filter(Boolean) as TaskStatus[] | undefined;
  const q = params.get('q')?.toLowerCase();
  let items = tasks();
  if (status?.length) items = items.filter((t) => status.includes(t.status));
  if (params.get('overdue') === 'true') items = items.filter((t) => t.overdue);
  if (q)
    items = items.filter(
      (t) => t.title.toLowerCase().includes(q) || t.code.toLowerCase().includes(q),
    );
  const page = items.slice(offset, offset + limit);
  const next = offset + limit < items.length ? String(offset + limit) : null;
  return ok({ items: page.map(toSummary), nextCursor: next });
}

function updateProgress(req: MockRequest) {
  const t = tasks().find((x) => x.id === req.pathParams['id']);
  if (!t) return notFound(req.pathParams['id']);
  const ifMatch = req.headers.get('If-Match');
  if (ifMatch && ifMatch !== `"${t.rowVersion}"`) {
    return problem(409, 'Dữ liệu đã được người khác cập nhật, vui lòng tải lại', {
      detail: `If-Match ${ifMatch} khác phiên bản hiện tại "${t.rowVersion}"`,
    });
  }
  const body = (req.body ?? {}) as Partial<ProgressUpdate>;
  const percent = body.percent;
  if (typeof percent !== 'number' || !Number.isInteger(percent) || percent < 0 || percent > 100) {
    return problem(422, 'Dữ liệu không hợp lệ', {
      errors: [{ field: 'percent', message: 'Tiến độ phải là số nguyên từ 0 đến 100' }],
    });
  }
  t.progress = percent;
  t.rowVersion += 1;
  if (t.status === 'DA_GIAO') t.status = 'DANG_THUC_HIEN';
  t.progressLog.unshift({
    at: new Date().toISOString(),
    percent,
    note: body.note ?? '',
    by: 'Tôi',
  });
  if (body.blocker)
    t.blockers.push({ id: crypto.randomUUID(), content: body.blocker, resolved: false });
  return ok(structuredClone(t), etag(t));
}

export const TASKS_MOCK: MockModule = {
  name: 'tasks',
  reset: () => (db = null),
  routes: [
    { method: 'GET', pattern: '/api/v1/tasks/mine', handle: (r) => myTasks(r.params) },
    {
      method: 'GET',
      pattern: '/api/v1/tasks/:id',
      handle: (r) => {
        const t = tasks().find((x) => x.id === r.pathParams['id']);
        return t ? ok(structuredClone(t), etag(t)) : notFound(r.pathParams['id']);
      },
    },
    { method: 'POST', pattern: '/api/v1/tasks/:id/progress', handle: updateProgress },
  ],
};
