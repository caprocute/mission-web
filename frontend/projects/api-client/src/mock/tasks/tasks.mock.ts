import { HttpParams } from '@angular/common/http';
import { MAX_PAGE_LIMIT } from '../../lib/paging';
import {
  BlockerReport,
  CHANGE_REQUEST_STATUS_LABEL,
  ChangeRequest,
  ChangeRequestDraft,
  ChangeRequestPreview,
  DelayRisk,
  MyTasksQuickFilter,
  PeriodRef,
  ProgressBatchItem,
  ProgressBatchResult,
  ProgressUpdate,
  TASK_STATUS_LABEL,
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
import { MOCK_ME } from '../mock-session';
import catalogFixture from '../catalog/catalog.json';
import fixture from './tasks.json';
import { ASSIGN_MOCK, draftView } from './assign.mock';

/**
 * Giả lập API `/api/v1/tasks` của MOD-02 (mission-core) cho QT-05, QT-06:
 * SCR-0503 "Việc của tôi", SCR-0601 "Chi tiết nhiệm vụ", widget `myTasksToday` (SCR-0002).
 * Mô phỏng hành vi quan trọng của máy chủ: kiểm `If-Match` (409), kiểm dữ liệu (422),
 * tăng `rowVersion` sau khi ghi, chuyển `DA_GIAO` → `DANG_THUC_HIEN` (baseline mục 9.1),
 * BR-06-02 (giảm tiến độ phải có ghi chú), BR-06-03 (chỉ chủ trì đổi phần trăm),
 * số ngày làm việc còn lại và cờ quá hạn tính theo lịch (giả lập: nghỉ thứ Bảy, Chủ nhật),
 * điểm nghẽn, đề nghị gia hạn/điều chỉnh (BR-06-06 đến BR-06-09), lịch sử trạng thái (WEB-031).
 */
type StoredTask = Omit<
  TaskDetail,
  | 'overdue'
  | 'workingDaysLeft'
  | 'openBlockerCount'
  | 'delayRisk'
  | 'extensionsUsed'
  | 'extensionsMax'
>;

/** Số lần gia hạn tối đa mỗi quý trước khi phải chuyển cấp trên (BR-06-06, tham số Policy Pack). */
export const MAX_EXTENSIONS = 2;
/** Gia hạn quá số ngày làm việc này phải chuyển cấp trên duyệt (BR-06-06). */
export const EXTENSION_ESCALATE_DAYS = 10;

/** Số ngày làm việc còn lại để một nhiệm vụ được xếp "Sắp hạn" (tham số Policy Pack, giả định 3). */
export const SAP_HAN_WORKING_DAYS = 3;
const OPEN: TaskStatus[] = ['DA_GIAO', 'DANG_THUC_HIEN'];

let db: StoredTask[] | null = null;
let period: PeriodRef | null = null;

/** Điền giá trị mặc định cho trường chi tiết mà fixture không ghi (máy chủ thật luôn trả đủ). */
function normalize(
  t: Partial<StoredTask> & Pick<StoredTask, 'id' | 'status' | 'dueDate' | 'assignerName'>,
): StoredTask {
  const n = t as StoredTask;
  n.acceptorName ??= t.assignerName;
  n.scoreDueDate ??= t.dueDate;
  n.products ??= {
    confirmed: n.doneQuantity,
    pendingReview: t.status === 'CHO_NGHIEM_THU' ? n.plannedQuantity - n.doneQuantity : 0,
    required: n.plannedQuantity,
  };
  n.changeRequests ??= [];
  n.stateLog ??=
    t.status === 'NHAP'
      ? []
      : [
          {
            at: `${t.dueDate}T00:00:00Z`,
            from: 'NHAP',
            to: 'DA_GIAO',
            transition: 'GIAO',
            actorName: t.assignerName,
          },
        ];
  for (const l of n.progressLog) {
    l.byRole ??= n.role;
    l.fromPercent ??= l.percent;
  }
  return n;
}

function tasks(): StoredTask[] {
  db ??= resolveRelativeDates(structuredClone(fixture.tasks) as unknown as StoredTask[]).map(
    normalize,
  );
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
    openBlockerCount: t.blockers.filter((b) => b.status === 'MO' || b.status === 'DANG_XU_LY')
      .length,
    delayRisk: OPEN.includes(t.status) ? delayRisk(t, workingDaysLeft) : undefined,
    extensionsUsed: t.changeRequests.filter((c) => c.type === 'GIA_HAN' && c.status === 'DA_DUYET')
      .length,
    extensionsMax: MAX_EXTENSIONS,
  };
}

/**
 * Dự báo trễ hạn giả lập SVC-15 (QT-06 bước 2): nhịp = phần trăm tăng bình quân mỗi ngày làm việc
 * từ khi giao; số ngày còn cần = phần còn lại / nhịp; so với số ngày làm việc còn lại.
 */
function delayRisk(t: StoredTask, left: number): DelayRisk {
  const start = t.assignedAt?.slice(0, 10) ?? t.stateLog[0]?.at.slice(0, 10) ?? todayIso();
  const elapsed = Math.max(1, -workingDaysBetween(todayIso(), start));
  const pace = t.progress / elapsed;
  const needed = pace > 0 ? Math.ceil((100 - t.progress) / pace) : 99;
  const level = needed > left + 2 ? 'CAO' : needed > left ? 'VUA' : 'THAP';
  return {
    level,
    neededWorkingDays: needed,
    explanation:
      pace > 0
        ? `Tiến độ bình quân ${pace.toFixed(1).replace('.', ',')}% mỗi ngày làm việc; cần khoảng ${needed} ngày làm việc, còn ${Math.max(0, left)} ngày.`
        : 'Chưa có cập nhật tiến độ để dự báo.',
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

function notFound(_id: string) {
  return problem(404, 'Không tìm thấy nhiệm vụ', {
    detail: 'Nhiệm vụ không tồn tại hoặc đã bị xoá.',
  });
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
      // If-Match của yêu cầu khác phiên bản hiện tại
      detail: 'Nhiệm vụ đã có thay đổi sau lần tải trước.',
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
      detail: `Trạng thái hiện tại: ${TASK_STATUS_LABEL[t.status]}.`,
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
      // BR-06-03
      {},
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
  const fromPercent = t.progress;
  t.progress = percent;
  t.rowVersion += 1;
  if (t.status === 'DA_GIAO') transition(t, 'DANG_THUC_HIEN', 'NHAN_VIEC');
  t.progressLog.unshift({
    at: new Date().toISOString(),
    percent,
    fromPercent,
    note,
    by: MOCK_ME.name,
    byRole: t.role,
    channel: 'WEB',
  });
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
      detail: `Trạng thái hiện tại: ${TASK_STATUS_LABEL[t.status]}.`,
    });
  }
  transition(t, 'DANG_THUC_HIEN', 'NHAN_VIEC');
  t.rowVersion += 1;
  return ok(derive(structuredClone(t)), etag(t));
}

/** Chuyển trạng thái và ghi `task.task_state_log` cùng lúc như máy trạng thái của MOD-02. */
function transition(t: StoredTask, to: TaskStatus, name: string, reason?: string): void {
  t.stateLog.push({
    at: new Date().toISOString(),
    from: t.status,
    to,
    transition: name,
    actorName: MOCK_ME.name,
    reason,
  });
  t.status = to;
}

const isAssignee = (_t: StoredTask) => true; // danh sách giả lập chỉ gồm nhiệm vụ của người dùng hiện tại

function reportBlocker(req: MockRequest): MockResponse {
  const t = find(req.pathParams['id']);
  if (!t) return notFound(req.pathParams['id']);
  if (!isAssignee(t)) return problem(403, 'Chỉ người được giao được báo điểm nghẽn');
  if (!OPEN.includes(t.status) && t.status !== 'TAM_DUNG') {
    return problem(422, 'Nhiệm vụ không ở trạng thái cho phép báo điểm nghẽn');
  }
  const b = (req.body ?? {}) as Partial<BlockerReport>;
  const content = (b.content ?? '').trim();
  const errors: { field: string; message: string }[] = [];
  if (!content || content.length > 500)
    errors.push({ field: 'content', message: 'Nội dung bắt buộc, tối đa 500 ký tự' });
  if (!b.causeCategory) errors.push({ field: 'causeCategory', message: 'Chọn nhóm nguyên nhân' });
  if (!b.causeClaimed)
    errors.push({ field: 'causeClaimed', message: 'Chọn nguyên nhân khách quan hoặc chủ quan' });
  if (!b.impactLevel) errors.push({ field: 'impactLevel', message: 'Chọn mức ảnh hưởng' });
  if (errors.length) return problem(422, 'Dữ liệu không hợp lệ', { errors });
  t.blockers.push({
    id: crypto.randomUUID(),
    code: `BN-${t.code.slice(-4)}-${String(t.blockers.length + 1).padStart(2, '0')}`,
    content,
    causeCategory: b.causeCategory!,
    causeClaimed: b.causeClaimed!,
    impactLevel: b.impactLevel!,
    resolveBy: b.resolveBy,
    status: 'MO',
    createdAt: new Date().toISOString(),
  });
  t.rowVersion += 1;
  return { status: 201, body: derive(structuredClone(t)), headers: etag(t) };
}

/** Hệ số tiến độ C = 1 − 0,05 × số ngày làm việc chậm, không nhỏ hơn 0 (BR-06-01). */
export const coefficientC = (delayDays: number) =>
  Math.max(0, Math.round((1 - 0.05 * Math.max(0, delayDays)) * 100) / 100);

function preview(t: StoredTask, d: Partial<ChangeRequestDraft>): ChangeRequestPreview {
  const newDue = d.type === 'GIA_HAN' && d.newDueDate ? d.newDueDate : t.dueDate;
  const extra = Math.max(0, workingDaysBetween(t.dueDate, newDue));
  const used = t.changeRequests.filter(
    (c) => c.type === 'GIA_HAN' && c.status === 'DA_DUYET',
  ).length;
  return {
    extraWorkingDays: extra,
    objective: { scoreDueDate: newDue, c: 1 },
    subjective: { scoreDueDate: t.scoreDueDate, delayDays: extra, c: coefficientC(extra) },
    overThreshold:
      d.type === 'GIA_HAN' && (used >= MAX_EXTENSIONS || extra > EXTENSION_ESCALATE_DAYS),
    escalateToName: 'Trưởng ban Tổ chức',
    lateSubmission: utcDay(todayIso()) > utcDay(t.dueDate),
    cancelBlocked: d.type === 'HUY' && t.products.confirmed > 0,
  };
}

function validateDraft(t: StoredTask, d: Partial<ChangeRequestDraft>): MockResponse | null {
  const errors: { field: string; message: string }[] = [];
  const types = ['GIA_HAN', 'DOI_SO_LUONG', 'DOI_SAN_PHAM', 'TAM_DUNG', 'HUY'];
  if (!d.type || !types.includes(d.type))
    errors.push({ field: 'type', message: 'Chọn loại đề nghị' });
  if (d.type === 'GIA_HAN') {
    const p = currentPeriod();
    if (!d.newDueDate || d.newDueDate <= t.dueDate || d.newDueDate > p.endDate) {
      errors.push({ field: 'newDueDate', message: 'Hạn mới phải sau hạn hiện tại và trong kỳ' });
    }
  }
  if (d.type === 'DOI_SO_LUONG' && !(typeof d.newQuantity === 'number' && d.newQuantity > 0)) {
    errors.push({ field: 'newQuantity', message: 'Số lượng mới phải lớn hơn 0' });
  }
  if (d.type === 'DOI_SAN_PHAM' && !d.newProduct?.trim())
    errors.push({ field: 'newProduct', message: 'Nhập sản phẩm mới' });
  if (d.causeClaimed !== 'KHACH_QUAN' && d.causeClaimed !== 'CHU_QUAN') {
    errors.push({ field: 'causeClaimed', message: 'Chọn nguyên nhân' });
  }
  const reason = (d.reason ?? '').trim();
  if (reason.length < 20 || reason.length > 2000) {
    errors.push({ field: 'reason', message: 'Lý do chi tiết từ 20 đến 2.000 ký tự' });
  }
  if (d.blockerId && !t.blockers.some((b) => b.id === d.blockerId)) {
    errors.push({ field: 'blockerId', message: 'Điểm nghẽn không thuộc nhiệm vụ này' });
  }
  return errors.length ? problem(422, 'Dữ liệu không hợp lệ', { errors }) : null;
}

function previewRoute(req: MockRequest): MockResponse {
  const t = find(req.pathParams['id']);
  if (!t) return notFound(req.pathParams['id']);
  return ok(preview(t, (req.body ?? {}) as Partial<ChangeRequestDraft>));
}

function submitChangeRequest(req: MockRequest): MockResponse {
  const t = find(req.pathParams['id']);
  if (!t) return notFound(req.pathParams['id']);
  if (t.role !== 'CHU_TRI') return problem(403, 'Chỉ người chủ trì được gửi đề nghị');
  if (!OPEN.includes(t.status) && t.status !== 'TAM_DUNG') {
    return problem(422, 'Nhiệm vụ không còn mở nên không gửi được đề nghị');
  }
  const d = (req.body ?? {}) as Partial<ChangeRequestDraft>;
  const invalid = validateDraft(t, d);
  if (invalid) return invalid;
  const pv = preview(t, d);
  if (pv.cancelBlocked) {
    return problem(422, 'Không thể huỷ nhiệm vụ đã có sản phẩm được nghiệm thu.', {
      code: 'MSG-06-09',
    });
  }
  if (t.changeRequests.some((c) => c.type === d.type && c.status === 'CHO_DUYET')) {
    return problem(409, 'Đã có đề nghị cùng loại đang chờ duyệt', {
      detail: 'Rút đề nghị cũ trước khi gửi đề nghị mới',
    });
  }
  const cr: ChangeRequest = {
    id: crypto.randomUUID(),
    type: d.type!,
    status: 'CHO_DUYET',
    newDueDate: d.type === 'GIA_HAN' ? d.newDueDate : undefined,
    newQuantity: d.type === 'DOI_SO_LUONG' ? d.newQuantity : undefined,
    newProduct: d.type === 'DOI_SAN_PHAM' ? d.newProduct : undefined,
    causeClaimed: d.causeClaimed!,
    blockerId: d.blockerId,
    reason: d.reason!.trim(),
    // người duyệt là người giao việc; vượt ngưỡng chuyển cấp trên (BR-06-06, BR-06-08)
    approverName: pv.overThreshold ? pv.escalateToName! : t.assignerName,
    escalated: pv.overThreshold,
    submittedAt: new Date().toISOString(),
  };
  t.changeRequests.unshift(cr);
  t.rowVersion += 1;
  return { status: 201, body: derive(structuredClone(t)), headers: etag(t) };
}

function withdraw(req: MockRequest): MockResponse {
  const t = find(req.pathParams['id']);
  if (!t) return notFound(req.pathParams['id']);
  const cr = t.changeRequests.find((c) => c.id === req.pathParams['cid']);
  if (!cr) return problem(404, 'Không tìm thấy đề nghị');
  if (cr.status !== 'CHO_DUYET') {
    return problem(409, 'Đề nghị đã được xử lý nên không rút lại được', {
      detail: `Trạng thái hiện tại: ${CHANGE_REQUEST_STATUS_LABEL[cr.status]}.`,
    });
  }
  cr.status = 'DA_RUT';
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
    taskGroup: item.defaultGroup,
    source: 'DANG_KY',
    plannedQuantity: body.quantity!,
    doneQuantity: 0,
    unit: item.unit,
    description: body.note ?? '',
    catalogItemCode: item.code,
    acceptorName: 'Chờ phân công',
    scoreDueDate: due,
    products: { confirmed: 0, pendingReview: 0, required: body.quantity! },
    coAssignees: [],
    progressLog: [],
    blockers: [],
    changeRequests: [],
    stateLog: [],
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
    ASSIGN_MOCK.reset();
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
    {
      method: 'GET',
      pattern: '/api/v1/tasks/:id/draft',
      handle: (r) => draftView(r.pathParams['id']) ?? notFound(r.pathParams['id']),
    },
    { method: 'POST', pattern: '/api/v1/tasks/:id/progress', handle: updateProgress },
    { method: 'POST', pattern: '/api/v1/tasks/:id/accept', handle: accept },
    { method: 'POST', pattern: '/api/v1/tasks/:id/blockers', handle: reportBlocker },
    { method: 'POST', pattern: '/api/v1/tasks/:id/change-requests/preview', handle: previewRoute },
    { method: 'POST', pattern: '/api/v1/tasks/:id/change-requests', handle: submitChangeRequest },
    {
      method: 'POST',
      pattern: '/api/v1/tasks/:id/change-requests/:cid/withdraw',
      handle: withdraw,
    },
    ...ASSIGN_MOCK.routes,
  ],
};
