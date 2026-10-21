import { WorkflowInboxItem, WorkflowInstanceSummary } from '../../lib/models/workflow-instance';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { activeTemplateBpmn } from '../workflow-templates/workflow-templates.mock';

/**
 * Giả lập giám sát tiến trình theo mẫu (SVC-06, QT-15, BS-01 SCR-1506): danh sách đọc từ bảng chiếu,
 * tiến trình kẹt, chuyển người xử lý (người nhận phải thuộc nhóm ứng viên), gia hạn bước, sơ đồ và lịch
 * sử. Mỗi thao tác ghi sổ cái; không có API sửa biến hay nhảy bước.
 */

type Inst = { -readonly [K in keyof WorkflowInstanceSummary]: WorkflowInstanceSummary[K] } & {
  rowVersion: number;
  completed: string[];
  active: string;
  log: { at: string; action: string; actorName: string; activityName: string; comment?: string }[];
};

/** Người giữ vai trò còn hiệu lực theo bảng phân quyền giả lập (khớp `role-admin.fixture.ts`). */
const ELIGIBLE: Record<string, string[]> = {
  appraiser: ['p-binh'],
  leader: ['p-dung'],
  decider: ['p-chau'],
  manager: ['p-ha'],
};

const out = (body: unknown, headers?: Record<string, string>) => ok(structuredClone(body), headers);
const hdr = (r: number) => ({ ETag: `"${r}"` });
/** Mốc tính theo ngày so với hôm nay để tiến trình kẹt luôn quá hạn khi chạy thử. */
const day = (d: number, h = 8) => {
  const t = new Date();
  t.setUTCDate(t.getUTCDate() + d);
  t.setUTCHours(h - 7, 0, 0, 0);
  return t.toISOString();
};
const DAY_MS = 86_400_000;

/** Kẹt: quá hạn hoặc chưa có người xử lý (suy luận theo mô tả hợp đồng). */
function restuck(i: Inst) {
  const late = Math.floor((Date.now() - new Date(i.dueAt!).getTime()) / DAY_MS);
  i.stuck = late > 0 || !i.assigneeId;
  i.stuckReason = !i.stuck
    ? undefined
    : late > 0
      ? `Quá hạn ${late} ngày${i.assigneeId ? '.' : ', chưa có người xử lý.'}`
      : 'Chưa có người xử lý trong đơn vị ứng viên.';
}

function inst(
  n: number,
  title: string,
  template: 'duyet-ho-so-danh-gia' | 'giao-nhiem-vu-nghiem-thu',
  active: string,
  activity: string,
  role: string,
  assigneeId: string | null,
  startedDay: number,
  dueDay: number,
  extra: Partial<Inst> = {},
): Inst {
  const businessId = `0192f3d5-6e7f-7091-a2b3-${String(n).padStart(12, '0')}`;
  const businessType = template === 'duyet-ho-so-danh-gia' ? 'APPRAISAL_PROFILE' : 'TASK';
  const order =
    template === 'duyet-ho-so-danh-gia'
      ? ['batDau', 'tuDanhGia', 'capTrenNhanXet', 'xetDuyet', 'kySo']
      : ['batDau', 'nopKetQua', 'nghiemThu', 'datYeuCau'];
  return {
    businessKey: `${businessType}:${businessId}`,
    businessType,
    businessId,
    title,
    templateCode: template,
    templateVersion: template === 'duyet-ho-so-danh-gia' ? 3 : 1,
    currentActivityName: activity,
    currentTaskId: `9a1b2c3d-8b1e-11f1-9c5a-${String(n).padStart(12, '0')}`,
    assigneeId,
    candidateRole: role,
    startedAt: day(startedDay),
    dueAt: day(dueDay, 17),
    stuck: false,
    cellCode: 'CELL-HN',
    rowVersion: 1,
    completed: order.slice(0, order.indexOf(active)),
    active,
    log: [
      {
        at: day(startedDay),
        action: 'TAO_TIEN_TRINH',
        actorName: 'Hệ thống',
        activityName: 'Bắt đầu',
      },
    ],
    ...extra,
  };
}

function seed(): Inst[] {
  const list = [
    inst(
      812,
      'Hồ sơ đánh giá năm 2026 – Trần Minh Hoàng',
      'duyet-ho-so-danh-gia',
      'capTrenNhanXet',
      'Cấp trên nhận xét',
      'appraiser',
      null,
      -20,
      -3,
    ),
    inst(
      813,
      'Hồ sơ đánh giá năm 2026 – Nguyễn Thị Hạnh',
      'duyet-ho-so-danh-gia',
      'capTrenNhanXet',
      'Cấp trên nhận xét',
      'appraiser',
      'p-binh',
      -10,
      9,
    ),
    inst(
      814,
      'Hồ sơ đánh giá năm 2026 – Phạm Quốc Khánh',
      'duyet-ho-so-danh-gia',
      'xetDuyet',
      'Đồng ý kết quả?',
      'leader',
      'p-dung',
      -18,
      -4,
    ),
    inst(
      901,
      'Nhiệm vụ: Báo cáo sơ kết công tác cải cách hành chính',
      'giao-nhiem-vu-nghiem-thu',
      'nghiemThu',
      'Nghiệm thu',
      'manager',
      'p-ha',
      -5,
      20,
    ),
    inst(
      902,
      'Nhiệm vụ: Rà soát quy chế làm việc của cơ quan',
      'giao-nhiem-vu-nghiem-thu',
      'nopKetQua',
      'Nộp kết quả nhiệm vụ',
      'manager',
      'p-ha',
      -3,
      25,
      { cellCode: 'CELL-G1' },
    ),
  ];
  list.forEach(restuck);
  return list;
}

let items = seed();

const byTask = (r: MockRequest) => items.find((i) => i.currentTaskId === r.pathParams['taskId']);
const byKey = (r: MockRequest) =>
  items.find((i) => i.businessKey === decodeURIComponent(r.pathParams['businessKey']));
const notFound = () => problem(404, 'Không tìm thấy tiến trình');
const summary = (i: Inst): WorkflowInstanceSummary => {
  const { rowVersion: _r, completed: _c, active: _a, log: _l, ...rest } = i;
  void _r;
  void _c;
  void _a;
  void _l;
  return rest;
};

function inbox(i: Inst): WorkflowInboxItem {
  return {
    taskId: i.currentTaskId!,
    title: i.title ?? '',
    businessType: i.businessType,
    businessId: i.businessId,
    businessKey: i.businessKey,
    templateCode: i.templateCode,
    assigneeId: i.assigneeId ?? null,
    candidateRole: i.candidateRole ?? null,
    dueAt: i.dueAt ?? null,
    createdAt: i.startedAt,
    overdue: i.stuck,
    rowVersion: i.rowVersion,
  };
}

function guard(r: MockRequest): MockResponse | null {
  if (!r.headers.get('If-Match')) return problem(428, 'Thiếu điều kiện phiên bản khi ghi');
  const reason = String((r.body as { reason?: string })?.reason ?? '').trim();
  if (reason.length < 10) {
    return problem(422, 'Cần nêu lý do (ít nhất 10 ký tự) để ghi sổ cái.', {
      errors: [{ field: 'reason', message: 'Cần nêu lý do, ít nhất 10 ký tự.' }],
    });
  }
  return null;
}

function reassign(r: MockRequest): MockResponse {
  const i = byTask(r);
  if (!i) return notFound();
  const bad = guard(r);
  if (bad) return bad;
  const to = (r.body as { assigneeId: string }).assigneeId;
  if (!(ELIGIBLE[i.candidateRole ?? ''] ?? []).includes(to) || to === i.assigneeId) {
    return problem(422, 'Người nhận không thuộc nhóm người được xử lý bước này.', {
      code: 'WORKFLOW-422-ASSIGNEE-NOT-ELIGIBLE',
    });
  }
  i.assigneeId = to;
  restuck(i);
  i.rowVersion++;
  i.log.push({
    at: new Date().toISOString(),
    action: 'CHUYEN_NGUOI_XU_LY',
    actorName: 'Nguyễn Văn Minh',
    activityName: i.currentActivityName ?? '',
    comment: (r.body as { reason: string }).reason,
  });
  return out(inbox(i), hdr(i.rowVersion));
}

function extend(r: MockRequest): MockResponse {
  const i = byTask(r);
  if (!i) return notFound();
  const bad = guard(r);
  if (bad) return bad;
  const due = (r.body as { dueAt: string }).dueAt;
  if (!due || new Date(due).getTime() <= new Date(i.dueAt!).getTime()) {
    return problem(422, 'Hạn mới phải sau hạn hiện tại.', {
      errors: [{ field: 'dueAt', message: 'Hạn mới phải sau hạn hiện tại.' }],
    });
  }
  i.dueAt = due;
  restuck(i);
  i.rowVersion++;
  i.log.push({
    at: new Date().toISOString(),
    action: 'GIA_HAN',
    actorName: 'Nguyễn Văn Minh',
    activityName: i.currentActivityName ?? '',
    comment: (r.body as { reason: string }).reason,
  });
  return out(inbox(i), hdr(i.rowVersion));
}

export const WORKFLOW_INSTANCES_MOCK: MockModule = {
  name: 'workflow-instances',
  reset: () => {
    items = seed();
  },
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/workflow/instances',
      handle: (r) => {
        const t = r.params.get('template');
        const stuck = r.params.get('stuck') === 'true';
        const cell = r.params.get('cell');
        const list = items
          .filter(
            (i) =>
              (!t || i.templateCode === t) && (!stuck || i.stuck) && (!cell || i.cellCode === cell),
          )
          .map(summary);
        return out({ items: list, nextCursor: null });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/workflow/instances/:businessKey/diagram',
      handle: (r) => {
        const i = byKey(r);
        if (!i) return notFound();
        return out({
          businessKey: i.businessKey,
          templateCode: i.templateCode,
          templateVersion: i.templateVersion,
          bpmnXml: activeTemplateBpmn(i.templateCode),
          completedActivityIds: i.completed,
          activeActivityIds: [i.active],
        });
      },
    },
    {
      method: 'GET',
      pattern: '/api/v1/workflow/instances/:businessKey/history',
      handle: (r) => {
        const i = byKey(r);
        if (!i) return notFound();
        return out({
          businessKey: i.businessKey,
          businessType: i.businessType,
          templateCode: i.templateCode,
          templateVersion: i.templateVersion,
          status: 'DANG_CHAY',
          entries: i.log,
        });
      },
    },
    { method: 'POST', pattern: '/api/v1/workflow/tasks/:taskId/reassign', handle: reassign },
    { method: 'POST', pattern: '/api/v1/workflow/tasks/:taskId/extend', handle: extend },
  ],
};
