import { WorkflowTemplate, WorkflowTemplateRollout } from '../../lib/models/workflow-template';
import { lintBpmn } from '../../lib/workflow/wf-lint';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import { MOCK_ME, mockSession } from '../mock-session';

/**
 * Giả lập thư viện mẫu luồng của nền tảng (SVC-06, QT-15, BS-01 SCR-1505): vòng đời NHAP →
 * DAT_KIEM_THU → CHO_PHE_DUYET → HIEU_LUC, linter chạy lại ở máy chủ khi lưu, phê duyệt bốn mắt có
 * xác thực nhiều lớp và saga triển khai cuốn chiếu theo cell (CELL-NB trước, CELL-HCM, CELL-DTW sau cùng).
 */

type Tpl = { -readonly [K in keyof WorkflowTemplate]: WorkflowTemplate[K] } & { authorId: string };

const uuid = (n: number) => `0192f5f7-8091-72a3-b4c5-${String(n).padStart(12, '0')}`;
const out = (body: unknown, headers?: Record<string, string>) => ok(structuredClone(body), headers);
const etag = (rowVersion: number) => ({ ETag: `"${rowVersion}"` });
const notFound = () => problem(404, 'Không tìm thấy mẫu quy trình');
const OTHER_AUTHOR = 'u-wf-author-2';
/** Thứ tự cuốn chiếu TL-04 mục 11.2: thí điểm nội bộ, cell nhỏ trước, CELL-HCM sau cùng. */
const CELLS = [
  'CELL-NB',
  'CELL-G4',
  'CELL-G1',
  'CELL-G2',
  'CELL-G3',
  'CELL-SH',
  'CELL-HN',
  'CELL-DTW',
  'CELL-HCM',
];

export type WfStepKind = 'user' | 'worker' | 'xor' | 'call';
export interface WfStep {
  id: string;
  name: string;
  kind: WfStepKind;
  /** Thuộc tính thêm vào thẻ, ví dụ `flowable:topic="ky-so"`. */
  attrs?: string;
  /** Nội dung con, ví dụ vòng lặp đa thể hiện. */
  inner?: string;
}

const TAG: Record<WfStepKind, string> = {
  user: 'userTask',
  worker: 'serviceTask',
  xor: 'exclusiveGateway',
  call: 'callActivity',
};

/**
 * Dựng BPMN 2.0 thẳng hàng kèm toạ độ BPMNDI từ danh sách bước; cổng rẽ nhánh có thêm nhánh "Trả lại"
 * kết thúc riêng. Dùng cho dữ liệu giả lập và kiểm thử.
 */
export function buildBpmn(processId: string, name: string, steps: WfStep[]): string {
  const nodes: { id: string; tag: string; xml: string; w: number; h: number }[] = [
    {
      id: 'batDau',
      tag: 'startEvent',
      xml: '<startEvent id="batDau" name="Bắt đầu"/>',
      w: 36,
      h: 36,
    },
  ];
  for (const s of steps) {
    const tag = TAG[s.kind];
    const attrs = `${s.kind === 'worker' ? ' flowable:type="external-worker"' : ''}${s.attrs ? ' ' + s.attrs : ''}`;
    const xml = s.inner
      ? `<${tag} id="${s.id}" name="${s.name}"${attrs}>${s.inner}</${tag}>`
      : `<${tag} id="${s.id}" name="${s.name}"${attrs}/>`;
    nodes.push({
      id: s.id,
      tag,
      xml,
      w: s.kind === 'xor' ? 50 : 120,
      h: s.kind === 'xor' ? 50 : 72,
    });
  }
  nodes.push({
    id: 'ketThuc',
    tag: 'endEvent',
    xml: '<endEvent id="ketThuc" name="Hoàn tất"/>',
    w: 36,
    h: 36,
  });
  const flows: string[] = [];
  const shapes: string[] = [];
  const edges: string[] = [];
  const cy = 140;
  let x = 40;
  const pos = new Map<string, { x: number; y: number; w: number; h: number }>();
  for (const n of nodes) {
    const p = { x, y: cy - n.h / 2, w: n.w, h: n.h };
    pos.set(n.id, p);
    shapes.push(
      `<bpmndi:BPMNShape id="${n.id}_di" bpmnElement="${n.id}"><dc:Bounds x="${p.x}" y="${p.y}" width="${p.w}" height="${p.h}"/></bpmndi:BPMNShape>`,
    );
    x += n.w + 50;
  }
  const edge = (id: string, a: string, b: string, label = '') => {
    flows.push(
      `<sequenceFlow id="${id}" sourceRef="${a}" targetRef="${b}"${label ? ` name="${label}"` : ''}/>`,
    );
    const pa = pos.get(a)!;
    const pb = pos.get(b)!;
    const pts =
      pb.y > pa.y + pa.h
        ? [
            [pa.x + pa.w / 2, pa.y + pa.h],
            [pa.x + pa.w / 2, pb.y + pb.h / 2],
            [pb.x, pb.y + pb.h / 2],
          ]
        : [
            [pa.x + pa.w, cy],
            [pb.x, cy],
          ];
    edges.push(
      `<bpmndi:BPMNEdge id="${id}_di" bpmnElement="${id}">${pts.map(([px, py]) => `<di:waypoint x="${px}" y="${py}"/>`).join('')}</bpmndi:BPMNEdge>`,
    );
  };
  const extra: string[] = [];
  nodes.forEach((n, i) => {
    const next = nodes[i + 1];
    if (!next) return;
    edge(`f_${n.id}`, n.id, next.id, n.tag === 'exclusiveGateway' ? 'Đồng ý' : '');
    if (n.tag === 'exclusiveGateway') {
      const endId = `traLai_${n.id}`;
      const p = pos.get(n.id)!;
      pos.set(endId, { x: p.x + 120, y: cy + 90, w: 36, h: 36 });
      extra.push(`<endEvent id="${endId}" name="Trả lại"/>`);
      shapes.push(
        `<bpmndi:BPMNShape id="${endId}_di" bpmnElement="${endId}"><dc:Bounds x="${p.x + 120}" y="${cy + 90}" width="36" height="36"/></bpmndi:BPMNShape>`,
      );
      edge(`f_${endId}`, n.id, endId, 'Trả lại');
    }
  });
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    // ui-text-ok: không gian tên XML của tệp sơ đồ, không hiện lên màn hình
    '<definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:flowable="http://flowable.org/bpmn" targetNamespace="https://okrkpi.vn/wf">',
    `<process id="${processId}" name="${name}" isExecutable="true">`,
    ...nodes.map((n) => n.xml),
    ...extra,
    ...flows,
    '</process>',
    `<bpmndi:BPMNDiagram id="${processId}_dg"><bpmndi:BPMNPlane id="${processId}_pl" bpmnElement="${processId}">`,
    ...shapes,
    ...edges,
    '</bpmndi:BPMNPlane></bpmndi:BPMNDiagram>',
    '</definitions>',
  ].join('\n');
}

const MI_KY_LO =
  '<multiInstanceLoopCharacteristics isSequential="false"><loopCardinality>20</loopCardinality></multiInstanceLoopCharacteristics>';

const DUYET_STEPS: WfStep[] = [
  { id: 'tuDanhGia', name: 'Cá nhân tự đánh giá', kind: 'user' },
  { id: 'capTrenNhanXet', name: 'Cấp trên nhận xét', kind: 'user' },
  { id: 'xetDuyet', name: 'Đồng ý kết quả?', kind: 'xor' },
  { id: 'kySo', name: 'Bước ký số', kind: 'worker', attrs: 'flowable:topic="ky-so"' },
];

const DUYET_V4: WfStep[] = [
  ...DUYET_STEPS.slice(0, 2),
  { id: 'hoiDongXet', name: 'Hội đồng xem xét', kind: 'user' },
  ...DUYET_STEPS.slice(2),
];

const NGHIEM_THU_STEPS: WfStep[] = [
  { id: 'nopKetQua', name: 'Nộp kết quả nhiệm vụ', kind: 'user' },
  { id: 'nghiemThu', name: 'Nghiệm thu', kind: 'user' },
  { id: 'datYeuCau', name: 'Đạt yêu cầu?', kind: 'xor' },
  { id: 'kyLo', name: 'Ký lô', kind: 'worker', attrs: 'flowable:topic="ky-lo"', inner: MI_KY_LO },
];

const PARAMS_DUYET = {
  type: 'object',
  properties: {
    approvalLevels: {
      type: 'integer',
      title: 'Số cấp duyệt',
      default: 2,
      minimum: 1,
      maximum: 4,
    },
    remindAfterDays: {
      type: 'integer',
      title: 'Nhắc việc sau (ngày)',
      default: 2,
      minimum: 1,
      maximum: 10,
    },
    requireCouncil: { type: 'boolean', title: 'Có hội đồng xem xét', default: false },
  },
};

const PARAMS_NGHIEM_THU = {
  type: 'object',
  properties: {
    acceptanceMode: {
      type: 'string',
      title: 'Cách nghiệm thu',
      default: 'MOT_CAP',
      enum: ['MOT_CAP', 'HAI_CAP'],
    },
    batchSize: {
      type: 'integer',
      title: 'Số hồ sơ mỗi lô ký',
      default: 20,
      minimum: 1,
      maximum: 20,
    },
  },
};

interface State {
  templates: Tpl[];
  rollouts: Map<string, WorkflowTemplateRollout[]>;
  /** Mẫu đang chạy test hoặc đang phát hành: số lần đọc còn lại trước khi đổi trạng thái. */
  pending: Map<string, { left: number; to: 'TESTED' | 'PUBLISHED' }>;
}

const sha = (n: number) => n.toString(16).padStart(64, 'a');

function tpl(
  n: number,
  code: string,
  versionNo: number,
  status: Tpl['status'],
  bpmnXml: string,
  paramSchema: object,
  extra: Partial<Tpl> = {},
): Tpl {
  return {
    id: uuid(n),
    templateCode: code,
    versionNo,
    status,
    bpmnXml,
    bpmnSha256: sha(n),
    paramSchema: paramSchema as never,
    tenantBindingCount: 0,
    testCount: 0,
    updatedAt: '2026-10-02T14:00:00+07:00',
    rowVersion: 1,
    authorId: OTHER_AUTHOR,
    ...extra,
  };
}

function seed(): State {
  const done = (cell: string, i: number): WorkflowTemplateRollout => ({
    cellCode: cell,
    status: 'DA_TRIEN_KHAI',
    flowableDeploymentId: String(20001 + i),
    deployedAt: '2026-10-02T22:10:00+07:00',
  });
  const templates = [
    tpl(
      1,
      'duyet-ho-so-danh-gia',
      3,
      'HIEU_LUC',
      buildBpmn('duyetHoSo', 'Duyệt hồ sơ đánh giá', DUYET_STEPS),
      PARAMS_DUYET,
      {
        tenantBindingCount: 37,
        testCount: 24,
        testSuiteRef: '4e1d2c3b',
        flowableDeploymentId: '20001',
        approvedBy: uuid(909),
        rowVersion: 6,
      },
    ),
    tpl(
      2,
      'duyet-ho-so-danh-gia',
      4,
      'NHAP',
      buildBpmn('duyetHoSo', 'Duyệt hồ sơ đánh giá', DUYET_V4),
      PARAMS_DUYET,
      {
        bpmnSha256: sha(2),
        testCount: 24,
        updatedAt: '2026-10-19T11:00:00+07:00',
        authorId: MOCK_ME.id,
      },
    ),
    tpl(
      3,
      'giao-nhiem-vu-nghiem-thu',
      1,
      'HIEU_LUC',
      buildBpmn('nghiemThu', 'Giao nhiệm vụ và nghiệm thu', NGHIEM_THU_STEPS.slice(0, 3)),
      PARAMS_NGHIEM_THU,
      {
        tenantBindingCount: 52,
        testCount: 18,
        flowableDeploymentId: '20011',
        rowVersion: 4,
      },
    ),
    tpl(
      4,
      'giao-nhiem-vu-nghiem-thu',
      2,
      'CHO_PHE_DUYET',
      buildBpmn('nghiemThu', 'Giao nhiệm vụ và nghiệm thu', NGHIEM_THU_STEPS),
      PARAMS_NGHIEM_THU,
      {
        testCount: 21,
        testSuiteRef: '7c8d9e0f',
        mergeRequestUrl: 'https://git.okrkpi.vn/platform/wf-templates/-/merge_requests/128',
        updatedAt: '2026-10-20T15:00:00+07:00',
        rowVersion: 3,
      },
    ),
  ];
  return {
    templates,
    rollouts: new Map([
      [uuid(1), CELLS.map(done)],
      [uuid(3), CELLS.map(done)],
    ]),
    pending: new Map(),
  };
}

let state = seed();

const find = (r: MockRequest) => state.templates.find((t) => t.id === r.pathParams['id']);
const view = (t: Tpl): WorkflowTemplate => {
  const { authorId: _a, ...rest } = t;
  void _a;
  return rest;
};
const summary = (t: Tpl) => {
  const { bpmnXml: _x, paramSchema: _p, authorId: _a, ...rest } = t;
  void _x;
  void _p;
  void _a;
  return rest;
};

/** Đổi trạng thái sau vài lần đọc, như máy chủ hoàn tất test hoặc phát hành ở nền. */
function settle(t: Tpl) {
  const p = state.pending.get(t.id);
  if (!p) return;
  if (--p.left > 0) return;
  state.pending.delete(t.id);
  t.rowVersion++;
  if (p.to === 'TESTED') {
    t.status = 'DAT_KIEM_THU';
    t.testSuiteRef = 'a1b2c3d4';
  } else {
    t.status = 'HIEU_LUC';
    t.approvedBy = uuid(909);
    t.flowableDeploymentId = '30011';
    state.rollouts.set(
      t.id,
      CELLS.map((cellCode) => ({
        cellCode,
        status: 'CHO',
        flowableDeploymentId: null,
        deployedAt: null,
      })),
    );
  }
}

function ifMatch(r: MockRequest, rowVersion: number): MockResponse | null {
  const h = r.headers.get('If-Match');
  if (!h) return problem(428, 'Thiếu điều kiện phiên bản khi ghi');
  if (h.replace(/"/g, '') !== String(rowVersion)) {
    return problem(409, 'Mẫu đã được người khác cập nhật. Vui lòng tải lại.', {
      code: 'WORKFLOW-409-VERSION-CONFLICT',
    });
  }
  return null;
}

/** Máy chủ chạy lại cùng bộ luật khi lưu và là nơi quyết định (BS-01). */
function lintProblem(xml: string): MockResponse | null {
  const issues = lintBpmn(xml);
  if (!issues.length) return null;
  return problem(422, 'Sơ đồ chưa đạt quy tắc của mẫu quy trình.', {
    code: 'WORKFLOW-422-LINT',
    errors: issues.map((i) => ({
      field: i.elementId ? `bpmnXml.${i.elementId}` : 'bpmnXml',
      message: i.message,
      rule: i.rule,
    })),
  });
}

function job(type: string): MockResponse {
  const id = uuid(5000 + Math.floor(Math.random() * 9000));
  return {
    status: 202,
    body: { id, type, status: 'DANG_CHAY', progress: 10 },
    headers: { Location: `/api/v1/jobs/${id}` },
  };
}

function create(r: MockRequest): MockResponse {
  const body = r.body as {
    templateCode: string;
    basedOnTemplateId?: string;
    bpmnXml: string;
    paramSchema: object;
  };
  if (state.templates.some((t) => t.templateCode === body.templateCode && t.status === 'NHAP')) {
    return problem(409, 'Mẫu này đang có một bản nháp khác.', {
      code: 'WORKFLOW-409-DRAFT-EXISTS',
    });
  }
  const bad = lintProblem(body.bpmnXml);
  if (bad) return bad;
  const base = state.templates.find((t) => t.id === body.basedOnTemplateId);
  const versionNo =
    Math.max(
      0,
      ...state.templates
        .filter((t) => t.templateCode === body.templateCode)
        .map((t) => t.versionNo),
    ) + 1;
  const t = tpl(
    100 + state.templates.length,
    body.templateCode,
    versionNo,
    'NHAP',
    body.bpmnXml,
    body.paramSchema,
    {
      testCount: base?.testCount ?? 0,
      updatedAt: new Date().toISOString(),
      authorId: MOCK_ME.id,
      rowVersion: 0,
    },
  );
  state.templates.push(t);
  return { status: 201, body: view(structuredClone(t)), headers: etag(0) };
}

function save(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return notFound();
  const blocked = ifMatch(r, t.rowVersion);
  if (blocked) return blocked;
  if (t.status !== 'NHAP' && t.status !== 'DAT_KIEM_THU') {
    return problem(409, 'Phiên bản này không còn sửa được; hãy tạo phiên bản mới.', {
      code: 'WORKFLOW-409-TEMPLATE-IMMUTABLE',
    });
  }
  const body = r.body as { bpmnXml: string; paramSchema: object };
  const bad = lintProblem(body.bpmnXml);
  if (bad) return bad;
  t.bpmnXml = body.bpmnXml;
  t.paramSchema = body.paramSchema as never;
  t.status = 'NHAP';
  t.testSuiteRef = undefined;
  t.bpmnSha256 = sha(t.rowVersion + 500);
  t.updatedAt = new Date().toISOString();
  t.rowVersion++;
  return out(view(t), etag(t.rowVersion));
}

function runTests(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return notFound();
  if (t.status !== 'NHAP' && t.status !== 'DAT_KIEM_THU') {
    return problem(409, 'Chỉ chạy kiểm thử cho bản nháp.', {
      code: 'WORKFLOW-409-TEMPLATE-IMMUTABLE',
    });
  }
  state.pending.set(t.id, { left: 2, to: 'TESTED' });
  return job('TEST_RUN');
}

function submit(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return notFound();
  const blocked = ifMatch(r, t.rowVersion);
  if (blocked) return blocked;
  if (t.status !== 'DAT_KIEM_THU') {
    return problem(409, 'Mẫu cần đạt kiểm thử trước khi đề nghị phát hành.', {
      code: 'WORKFLOW-409-NOT-TESTED',
    });
  }
  t.status = 'CHO_PHE_DUYET';
  t.mergeRequestUrl = 'https://git.okrkpi.vn/platform/wf-templates/-/merge_requests/131';
  t.rowVersion++;
  return out(view(t), etag(t.rowVersion));
}

function approve(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return notFound();
  const blocked = ifMatch(r, t.rowVersion);
  if (blocked) return blocked;
  if (t.status !== 'CHO_PHE_DUYET') {
    return problem(409, 'Mẫu không ở trạng thái chờ phê duyệt.', {
      code: 'WORKFLOW-409-NOT-PENDING',
    });
  }
  if (t.authorId === MOCK_ME.id) {
    return problem(403, 'Người phê duyệt phải khác người soạn mẫu.', {
      code: 'WORKFLOW-403-SAME-AUTHOR',
    });
  }
  if (!mockSession.recentMfa()) {
    const res = problem(401, 'Cần xác thực nhiều lớp trước khi phê duyệt phát hành.', {
      type: 'https://datatracker.ietf.org/doc/html/rfc9470',
      code: 'insufficient_user_authentication',
    });
    res.headers = {
      ...res.headers,
      // ui-text-ok: tiêu đề HTTP theo RFC 9470, không hiện lên màn hình
      'WWW-Authenticate': 'Bearer error="insufficient_user_authentication", acr_values="aal2"',
    };
    return res;
  }
  state.pending.set(t.id, { left: 2, to: 'PUBLISHED' });
  return job('ROLLOUT');
}

/** Mỗi lần đọc, saga triển khai thêm một cell theo thứ tự cuốn chiếu. */
function rollouts(r: MockRequest): MockResponse {
  const t = find(r);
  if (!t) return notFound();
  const list = state.rollouts.get(t.id) ?? [];
  const next = list.find((x) => x.status === 'CHO');
  if (next) {
    next.status = 'DA_TRIEN_KHAI';
    next.flowableDeploymentId = String(30011 + list.indexOf(next));
    next.deployedAt = new Date().toISOString();
  }
  return out({ items: list });
}

export const WORKFLOW_TEMPLATES_MOCK: MockModule = {
  name: 'workflow-templates',
  reset: () => {
    state = seed();
  },
  routes: [
    {
      method: 'GET',
      pattern: '/api/v1/workflow/templates',
      handle: (r) => {
        const status = r.params.get('status');
        state.templates.forEach(settle);
        const items = state.templates.filter((t) => !status || t.status === status).map(summary);
        return out({ items, nextCursor: null });
      },
    },
    { method: 'POST', pattern: '/api/v1/workflow/templates', handle: create },
    {
      method: 'GET',
      pattern: '/api/v1/workflow/templates/:id',
      handle: (r) => {
        const t = find(r);
        if (!t) return notFound();
        settle(t);
        return out(view(t), etag(t.rowVersion));
      },
    },
    { method: 'PUT', pattern: '/api/v1/workflow/templates/:id', handle: save },
    { method: 'POST', pattern: '/api/v1/workflow/templates/:id/test', handle: runTests },
    { method: 'POST', pattern: '/api/v1/workflow/templates/:id/submit', handle: submit },
    { method: 'POST', pattern: '/api/v1/workflow/templates/:id/approve', handle: approve },
    { method: 'GET', pattern: '/api/v1/workflow/templates/:id/rollouts', handle: rollouts },
  ],
};
