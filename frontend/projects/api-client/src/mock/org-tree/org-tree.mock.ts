import { Job } from '../../lib/models/job';
import {
  OrgChangeInput,
  OrgHistoryEntry,
  OrgTreeNode,
  OrgUnitDetail,
  OrgUnitMatch,
  OrgUnitRef,
  OrgUnitUpdate,
  UnitAssignment,
} from '../../lib/models/org-tree';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import {
  AssignmentRow,
  HistoryRow,
  LOCKED_PERIOD,
  SUBJECT_GROUPS,
  UnitVersion,
  generatedAssignments,
  seedOrgTree,
} from './org-tree.fixture';

/**
 * Giả lập cây tổ chức có lịch sử SCR-0103 (SVC-04 org-service, `/api/v1/org/units/*`): đọc theo
 * hai trục thời gian (`asOf`, `recordedAsOf`), nạp lười theo nhánh, tìm đơn vị, chi tiết, lịch sử,
 * phân công; sửa tạo phiên bản mới (BR-01-06), thay đổi tổ chức (BR-01-07, MSG-01-09, MSG-01-10,
 * MSG-01-11), nhập Excel 202 + job (tên tệp chứa `loi` cho báo cáo có dòng lỗi). Giả lập không đọc
 * nội dung tệp Excel; máy chủ thật kiểm từng dòng và phát EVT-14.
 */

let units: UnitVersion[] = [];
let assignments: AssignmentRow[] = [];
let history: HistoryRow[] = [];
let rowVersions = new Map<string, number>();
let imports = new Map<string, { job: Job; polls: number; errors: string[][] }>();

function reset() {
  const s = seedOrgTree();
  units = s.units;
  assignments = s.assignments;
  history = s.history;
  rowVersions = new Map();
  imports = new Map();
}
reset();

const today = () => new Date().toISOString().slice(0, 10);
const nowIso = () => new Date().toISOString();

interface View {
  asOf: string | null;
  cut: number;
}

function view(r: MockRequest): View | MockResponse {
  const asOf = r.params.get('asOf');
  const rec = r.params.get('recordedAsOf');
  if ((asOf && asOf > today()) || (rec && rec > today())) {
    return problem(422, 'Ngày xem không được sau hôm nay.', {
      errors: [
        {
          field: asOf && asOf > today() ? 'asOf' : 'recordedAsOf',
          message: 'Ngày xem không được sau hôm nay.',
        },
      ],
    } as never);
  }
  return { asOf, cut: rec ? Date.parse(`${rec}T23:59:59.999+07:00`) : Date.now() };
}
const isResponse = (x: View | MockResponse): x is MockResponse => 'status' in x;

const recordedAt = (u: { recordedFrom: string; recordedTo?: string | null }, cut: number) =>
  Date.parse(u.recordedFrom) <= cut && (!u.recordedTo || Date.parse(u.recordedTo) > cut);
const validAt = (u: { validFrom: string; validTo: string | null }, d: string) =>
  u.validFrom <= d && (!u.validTo || d < u.validTo);

/**
 * Phiên bản hiển thị của đơn vị. Có `asOf`: phiên bản hiệu lực tại ngày đó. "Hiện tại": phiên bản
 * hiệu lực hôm nay, không có thì phiên bản cuối đã kết thúc (hiện mờ) hoặc phiên bản tương lai.
 */
function pick(id: string, v: View): UnitVersion | undefined {
  const known = units.filter((u) => u.id === id && recordedAt(u, v.cut));
  if (v.asOf) return known.find((u) => validAt(u, v.asOf!));
  const d = today();
  return (
    known.find((u) => validAt(u, d)) ??
    known.filter((u) => u.validFrom <= d).sort((a, b) => (a.validFrom < b.validFrom ? 1 : -1))[0] ??
    known.sort((a, b) => (a.validFrom < b.validFrom ? -1 : 1))[0]
  );
}

function visible(v: View): UnitVersion[] {
  const ids = [...new Set(units.map((u) => u.id))];
  return ids.map((id) => pick(id, v)).filter((u): u is UnitVersion => !!u);
}

function activeAssignments(unitId: string, v: View): AssignmentRow[] {
  const unit = pick(unitId, v);
  const d = v.asOf ?? today();
  if (!unit || !validAt(unit, d)) return [];
  const rows = [
    ...generatedAssignments(
      unitId,
      units
        .filter((u) => u.id === unitId)
        .reduce((m, u) => (u.validFrom < m ? u.validFrom : m), unit.validFrom),
    ),
    ...assignments.filter((a) => a.unitId === unitId && recordedAt(a, v.cut)),
  ];
  return rows.filter((a) => !a.validTo || a.validTo > d);
}

function node(u: UnitVersion, all: UnitVersion[], v: View): OrgTreeNode {
  return {
    id: u.id,
    code: u.code,
    name: u.name,
    parentId: u.parentId,
    unitType: u.unitType,
    childCount: all.filter((c) => c.parentId === u.id).length,
    assignmentCount: activeAssignments(u.id, v).filter((a) => a.validFrom <= (v.asOf ?? today()))
      .length,
    validFrom: u.validFrom,
    validTo: u.validTo,
    mergedOn: u.mergedOn,
  };
}

/** Đơn vị còn hiệu lực xếp trước, đơn vị đã kết thúc xếp sau (giữ thứ tự fixture trong mỗi nhóm). */
const endedLast = (a: UnitVersion, b: UnitVersion) => Number(!!a.validTo) - Number(!!b.validTo);

function tree(r: MockRequest): MockResponse {
  const v = view(r);
  if (isResponse(v)) return v;
  const all = visible(v);
  const parent = r.params.get('parent');
  const items = all
    .filter((u) => u.parentId === (parent || null))
    .sort(endedLast)
    .map((u) => node(u, all, v));
  const active = all.filter((u) => validAt(u, v.asOf ?? today()));
  return ok({
    items,
    stats: {
      unitCount: active.length,
      subjectGroupCount: new Set(active.map((u) => u.subjectGroupId)).size,
      positionCount: Math.round(active.length * 1.25),
    },
  });
}

const fold = (s: string) =>
  s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase();

function search(r: MockRequest): MockResponse {
  const v = view(r);
  if (isResponse(v)) return v;
  const q = fold(r.params.get('q') ?? '').trim();
  if (!q) return ok({ items: [] });
  const all = visible(v);
  const byId = new Map(all.map((u) => [u.id, u]));
  const items: OrgUnitMatch[] = all
    .filter((u) => fold(u.name).includes(q) || fold(u.code).includes(q))
    .sort(endedLast)
    .slice(0, Number(r.params.get('limit') ?? 50))
    .map((u) => {
      const ancestors: UnitVersion[] = [];
      for (let p = u.parentId; p && byId.get(p); p = byId.get(p)!.parentId) {
        ancestors.unshift(byId.get(p)!);
      }
      return {
        ...node(u, all, v),
        ancestorIds: ancestors.map((a) => a.id),
        path: ancestors.map((a) => a.name).join(' / '),
      };
    });
  return ok({ items });
}

function ref(id: string, v: View): OrgUnitRef | null {
  const u = pick(id, v) ?? units.filter((x) => x.id === id).at(-1);
  return u ? { id: u.id, code: u.code, name: u.name } : null;
}

function detail(id: string, v: View): OrgUnitDetail | null {
  const u = pick(id, v);
  if (!u) return null;
  const group = SUBJECT_GROUPS.find((g) => g.id === u.subjectGroupId)!;
  return {
    id: u.id,
    versionId: u.versionId,
    code: u.code,
    externalCode: u.externalCode,
    name: u.name,
    shortName: u.shortName,
    level: u.level,
    unitType: u.unitType,
    subjectGroupId: u.subjectGroupId,
    subjectGroup: group,
    flows: u.flows,
    validFrom: u.validFrom,
    validTo: u.validTo,
    recordedFrom: u.recordedFrom,
    recordedBy: u.recordedBy,
    parent: u.parentId ? ref(u.parentId, v) : null,
    predecessors: u.predecessorIds.map((p) => ref(p, v)).filter((x): x is OrgUnitRef => !!x),
    syncedFrom: u.syncedFrom,
    rowVersion: rowVersions.get(u.id) ?? 0,
  };
}

function unit(r: MockRequest): MockResponse {
  const v = view(r);
  if (isResponse(v)) return v;
  const d = detail(r.pathParams['id'], v);
  return d ? ok(d) : problem(404, 'Đơn vị không tồn tại tại thời điểm đang xem.');
}

function unitHistory(r: MockRequest): MockResponse {
  const v = view(r);
  if (isResponse(v)) return v;
  const items: OrgHistoryEntry[] = history
    .filter((h) => h.unitId === r.pathParams['id'] && Date.parse(h.recordedAt) <= v.cut)
    .sort((a, b) => (a.effectiveOn < b.effectiveOn ? -1 : 1))
    .map(({ unitId: _u, ...h }) => {
      void _u;
      return h;
    });
  return ok({ items });
}

function unitAssignments(r: MockRequest): MockResponse {
  const v = view(r);
  if (isResponse(v)) return v;
  // phân công đặc biệt trước, phân công sinh tự động sau
  const rows = activeAssignments(r.pathParams['id'], v).sort(
    (a, b) => Number(a.id.includes('-as-')) - Number(b.id.includes('-as-')),
  );
  const items: UnitAssignment[] = rows
    .slice(0, Number(r.params.get('limit') ?? 200))
    .map(({ unitId: _u, recordedFrom: _r, ...a }) => {
      void _u;
      void _r;
      return a;
    });
  return ok({ items });
}

const fieldError = (title: string, field: string) =>
  problem(422, title, { errors: [{ field, message: title }] } as never);

const MSG_01_09 = 'Sáp nhập cần khai báo ít nhất một đơn vị tiền thân.';
const MSG_01_10 = (period: string) =>
  `Thay đổi có hiệu lực trước ngày khoá của kỳ ${period}. Kết quả kỳ đã khoá sẽ không tự tính lại; nếu cần, đề nghị mở lại kỳ.`;
const MSG_01_11 = (n: number, order: string) =>
  `Đã chuyển ${n} phân công sang đơn vị mới theo quyết định ${order}.`;

const lockedWarning = (eff: string) =>
  eff <= LOCKED_PERIOD.lockedThrough ? MSG_01_10(LOCKED_PERIOD.name) : undefined;

/** Phiên bản đang ghi nhận (recorded_to = vô cực) hiệu lực tại ngày `d`. */
const currentAt = (id: string, d: string) =>
  units.find((u) => u.id === id && !u.recordedTo && validAt(u, d));

/**
 * Ghi thay đổi theo hai trục thời gian, không ghi đè (BR-01-06): đóng trục ghi nhận của phiên bản
 * cũ, ghi lại đoạn trước `eff` và đoạn mới từ `eff` (`patch = 'END'` là kết thúc hiệu lực).
 */
function split(id: string, eff: string, patch: Partial<UnitVersion> | 'END', by: string) {
  const cur = currentAt(id, eff);
  if (!cur) return false;
  const now = nowIso();
  cur.recordedTo = now;
  const base = { ...cur, recordedFrom: now, recordedTo: null, recordedBy: by };
  if (cur.validFrom < eff) {
    units.push({ ...base, versionId: crypto.randomUUID(), validTo: eff });
  }
  if (patch !== 'END') {
    units.push({ ...base, ...patch, versionId: crypto.randomUUID(), validFrom: eff });
  }
  rowVersions.set(id, (rowVersions.get(id) ?? 0) + 1);
  return true;
}

const ACTOR = 'Hoàng Đức Dũng';

function addHistory(unitId: string, h: Omit<HistoryRow, 'id' | 'unitId' | 'recordedAt'>) {
  history.push({ ...h, id: crypto.randomUUID(), unitId, recordedAt: nowIso() });
}

const activeChildren = (id: string, d: string) => [
  ...new Set(
    units.filter((u) => u.parentId === id && !u.recordedTo && validAt(u, d)).map((u) => u.id),
  ),
];

function moveAssignments(fromId: string, toId: string, eff: string): number {
  const cut: View = { asOf: eff, cut: Date.now() };
  const rows = activeAssignments(fromId, cut);
  for (const a of rows) {
    assignments.push({
      ...a,
      id: crypto.randomUUID(),
      unitId: toId,
      validFrom: eff,
      recordedFrom: nowIso(),
    });
  }
  return rows.length;
}

function update(r: MockRequest): MockResponse {
  const id = r.pathParams['id'];
  const body = r.body as OrgUnitUpdate;
  const cur = currentAt(id, body.validFrom ?? today()) ?? currentAt(id, today());
  if (!cur) return problem(404, 'Đơn vị không còn hiệu lực.');
  if (r.headers.get('If-Match')?.replace(/"/g, '') !== String(rowVersions.get(id) ?? 0)) {
    return problem(409, 'Đơn vị đã được người khác thay đổi. Vui lòng tải lại.', {
      code: 'ORG-409-CONFLICT',
    });
  }
  if (cur.syncedFrom) {
    return problem(
      409,
      'Đơn vị được đồng bộ từ CSDL CBCCVC; đồng chí sửa tại nguồn rồi đồng bộ lại.',
      {
        code: 'ORG-409-SYNCED',
      },
    );
  }
  if (!body.name?.trim()) return fieldError('Tên đơn vị không được trống.', 'name');
  if (body.name.length > 255) return fieldError('Tên đơn vị tối đa 255 ký tự.', 'name');
  if (!SUBJECT_GROUPS.some((g) => g.id === body.subjectGroupId)) {
    return fieldError(
      // BR-01-05
      'Đơn vị phải thuộc đúng một khối đối tượng theo Phụ lục 2A.',
      'subjectGroupId',
    );
  }
  if (!body.flows?.length) return fieldError('Chọn ít nhất một luồng đánh giá.', 'flows');
  if (!body.validFrom || body.validFrom < cur.validFrom) {
    return fieldError(
      'Ngày hiệu lực không được trước hiệu lực của phiên bản hiện có.',
      'validFrom',
    );
  }
  const { validFrom, ...fields } = body;
  split(id, validFrom, { ...fields, name: fields.name.trim() }, ACTOR);
  addHistory(id, {
    kind: 'SUA',
    effectiveOn: validFrom,
    title: 'Sửa thông tin',
    detail: `Cập nhật thông tin: ${body.name.trim()}`,
  });
  const d = detail(id, { asOf: null, cut: Date.now() })!;
  return ok({ ...d, warning: lockedWarning(validFrom) });
}

function change(r: MockRequest): MockResponse {
  const id = r.pathParams['id'];
  const body = r.body as OrgChangeInput;
  const eff = body.effectiveOn;
  if (!eff) return fieldError('Nhập ngày hiệu lực.', 'effectiveOn');
  if (!body.orderNo?.trim()) return fieldError('Nhập văn bản căn cứ.', 'orderNo');
  const cur = currentAt(id, eff);
  if (!cur) return fieldError('Đơn vị không còn hiệu lực tại ngày này.', 'effectiveOn');
  const names = (body.names ?? []).map((n) => n.trim()).filter(Boolean);
  const preds = (body.predecessorIds ?? []).filter((p) => p !== id);
  const order = body.orderNo.trim();
  const newUnit = (name: string, parentId: string | null, predecessorIds: string[]) => {
    const nid = `u-${crypto.randomUUID().slice(0, 8)}`;
    units.push({
      ...cur,
      versionId: crypto.randomUUID(),
      id: nid,
      code: `${cur.code}-${nid.slice(2, 6).toUpperCase()}`,
      externalCode: undefined,
      syncedFrom: undefined,
      name,
      shortName: undefined,
      parentId,
      predecessorIds,
      mergedOn: predecessorIds.length ? eff : undefined,
      validFrom: eff,
      validTo: null,
      recordedFrom: nowIso(),
      recordedTo: null,
      recordedBy: ACTOR,
    });
    return nid;
  };
  let target = id;
  let moved = 0;
  switch (body.kind) {
    case 'DOI_TEN':
      if (!names[0]) return fieldError('Nhập tên mới.', 'names');
      if (eff <= cur.validFrom)
        return fieldError('Ngày đổi tên phải sau hiệu lực hiện có.', 'effectiveOn');
      split(id, eff, { name: names[0] }, ACTOR);
      addHistory(id, {
        kind: 'DOI_TEN',
        effectiveOn: eff,
        title: 'Đổi tên',
        detail: `"${cur.name}" → "${names[0]}"`,
        orderNo: order,
      });
      break;
    case 'THANH_LAP':
      if (!names[0]) return fieldError('Nhập tên đơn vị thành lập.', 'names');
      target = newUnit(names[0], id, []);
      addHistory(target, {
        kind: 'THANH_LAP',
        effectiveOn: eff,
        title: 'Thành lập',
        detail: `Thành lập ${names[0]} trực thuộc ${cur.name}`,
        orderNo: order,
      });
      addHistory(id, {
        kind: 'THANH_LAP',
        effectiveOn: eff,
        title: 'Thành lập đơn vị trực thuộc',
        detail: names[0],
        orderNo: order,
      });
      break;
    case 'SAP_NHAP':
    case 'HOP_NHAT': {
      if (!preds.length) return fieldError(MSG_01_09, 'predecessorIds');
      if (body.kind === 'HOP_NHAT' && !names[0])
        return fieldError('Nhập tên đơn vị hợp nhất.', 'names');
      const sources = body.kind === 'SAP_NHAP' ? preds : [id, ...preds];
      for (const p of sources) {
        if (!currentAt(p, eff))
          return fieldError('Đơn vị tiền thân không còn hiệu lực tại ngày này.', 'predecessorIds');
      }
      if (body.kind === 'SAP_NHAP') {
        split(id, eff, { predecessorIds: [...cur.predecessorIds, ...preds], mergedOn: eff }, ACTOR);
      } else {
        target = newUnit(names[0], cur.parentId, sources);
      }
      for (const p of sources) {
        moved += moveAssignments(p, target, eff);
        for (const c of activeChildren(p, eff)) split(c, eff, { parentId: target }, ACTOR);
        split(p, eff, 'END', ACTOR);
        addHistory(p, {
          kind: body.kind,
          effectiveOn: eff,
          title: 'Kết thúc do ' + (body.kind === 'SAP_NHAP' ? 'sáp nhập' : 'hợp nhất'),
          detail: `Chuyển vào ${body.kind === 'SAP_NHAP' ? cur.name : names[0]}`,
          orderNo: order,
        });
      }
      addHistory(target, {
        kind: body.kind,
        effectiveOn: eff,
        title: body.kind === 'SAP_NHAP' ? 'Sáp nhập' : 'Hợp nhất',
        detail: `${sources.map((p) => ref(p, { asOf: null, cut: Date.now() })?.name).join(', ')}; ${moved} phân công được chuyển theo`,
        orderNo: order,
      });
      break;
    }
    case 'CHIA_TACH': {
      if (names.length < 2)
        return fieldError('Chia tách cần ít nhất hai tên đơn vị mới, mỗi dòng một tên.', 'names');
      const created = names.map((n) => newUnit(n, cur.parentId, [id]));
      target = created[0];
      moved = moveAssignments(id, target, eff);
      for (const c of activeChildren(id, eff)) split(c, eff, { parentId: target }, ACTOR);
      split(id, eff, 'END', ACTOR);
      addHistory(id, {
        kind: 'CHIA_TACH',
        effectiveOn: eff,
        title: 'Chia tách',
        detail: `Thành ${names.join(', ')}`,
        orderNo: order,
      });
      for (const c of created)
        addHistory(c, {
          kind: 'CHIA_TACH',
          effectiveOn: eff,
          title: 'Thành lập do chia tách',
          detail: `Từ ${cur.name}`,
          orderNo: order,
        });
      break;
    }
    case 'GIAI_THE':
      if (activeChildren(id, eff).length) {
        return problem(
          409,
          'Đơn vị còn đơn vị trực thuộc. Chuyển hoặc giải thể các đơn vị đó trước.',
          { code: 'ORG-409-HAS-CHILDREN' },
        );
      }
      split(id, eff, 'END', ACTOR);
      addHistory(id, {
        kind: 'GIAI_THE',
        effectiveOn: eff,
        title: 'Giải thể',
        detail: cur.name,
        orderNo: order,
      });
      break;
    default:
      return problem(400, 'Loại thay đổi không hợp lệ.');
  }
  return {
    status: 201,
    body: {
      unitId: target,
      movedAssignments: moved,
      message: moved ? MSG_01_11(moved, order) : undefined,
      warning: lockedWarning(eff),
    },
  };
}

function startImport(r: MockRequest): MockResponse {
  const file = r.body instanceof FormData ? (r.body.get('file') as File | null) : null;
  if (!file) return fieldError('Chọn tệp Excel theo mẫu.', 'file');
  if (!/\.xlsx$/i.test(file.name)) return fieldError('Tệp phải là Excel .xlsx theo mẫu.', 'file');
  const id = crypto.randomUUID();
  const errors = /loi/i.test(file.name)
    ? [
        // BR-01-05, BR-01-07
        ['12', 'TN-X-200', 'Thiếu khối đối tượng theo Phụ lục 2A'],
        ['27', 'TN-X-215', 'Sáp nhập thiếu đơn vị tiền thân'],
      ]
    : [];
  imports.set(id, {
    job: { id, type: 'ORG_IMPORT', status: 'CHO', progress: 0 },
    polls: 0,
    errors,
  });
  return { status: 202, body: null, headers: { Location: `/api/v1/org/imports/${id}` } };
}

function importJob(r: MockRequest): MockResponse {
  const e = imports.get(r.pathParams['id']);
  if (!e) return problem(404, 'Không tìm thấy yêu cầu nhập tệp.');
  e.polls++;
  const id = e.job.id;
  e.job =
    e.polls >= 2
      ? {
          id,
          type: 'ORG_IMPORT',
          status: 'HOAN_TAT',
          progress: 100,
          message: e.errors.length
            ? `Đã nhập 37 đơn vị; ${e.errors.length} dòng lỗi chưa nhập, xem báo cáo lỗi.`
            : 'Đã nhập 37 đơn vị, không có dòng lỗi.',
          resultLocation: e.errors.length ? `/api/v1/org/imports/${id}/report` : undefined,
        }
      : {
          id,
          type: 'ORG_IMPORT',
          status: 'DANG_CHAY',
          progress: 40,
          message: 'Đang kiểm tra từng dòng dữ liệu…',
        };
  return ok(e.job);
}

function importReport(r: MockRequest): MockResponse {
  const e = imports.get(r.pathParams['id']);
  if (!e?.errors.length) return problem(404, 'Không có báo cáo lỗi.');
  const csv = [['Dòng', 'Mã đơn vị', 'Lỗi'], ...e.errors].map((l) => l.join(',')).join('\r\n');
  return {
    status: 200,
    body: new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }),
    headers: { 'Content-Type': 'text/csv; charset=utf-8' },
  };
}

export const ORG_TREE_MOCK: MockModule = {
  name: 'org/units (cây tổ chức có lịch sử)',
  reset,
  routes: [
    { method: 'GET', pattern: '/api/v1/org/units/tree', handle: tree },
    { method: 'GET', pattern: '/api/v1/org/units/search', handle: search },
    {
      method: 'GET',
      pattern: '/api/v1/org/subject-groups',
      handle: () => ok({ items: SUBJECT_GROUPS }),
    },
    { method: 'POST', pattern: '/api/v1/org/imports', handle: startImport },
    { method: 'GET', pattern: '/api/v1/org/imports/:id', handle: importJob },
    { method: 'GET', pattern: '/api/v1/org/imports/:id/report', handle: importReport },
    { method: 'GET', pattern: '/api/v1/org/units/:id', handle: unit },
    { method: 'PUT', pattern: '/api/v1/org/units/:id', handle: update },
    { method: 'GET', pattern: '/api/v1/org/units/:id/history', handle: unitHistory },
    { method: 'GET', pattern: '/api/v1/org/units/:id/assignments', handle: unitAssignments },
    { method: 'POST', pattern: '/api/v1/org/units/:id/changes', handle: change },
  ],
};
