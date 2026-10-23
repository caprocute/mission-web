import {
  OrgChangeOrder,
  OrgChangeRequest,
  OrgTree,
  OrgTreeNode,
  OrgUnitAssignment,
  OrgUnitDetail,
  OrgUnitInput,
  OrgUnitSearchHit,
  OrgUnitVersion,
} from '../../lib/models/org-tree';
import { startMockJob } from '../mock-jobs';
import { MockModule, MockRequest, MockResponse, ok, problem } from '../mock-router';
import {
  AssignmentRow,
  SUBJECT_GROUPS,
  UnitVersion,
  generatedAssignments,
  seedOrgTree,
} from './org-tree.fixture';

/**
 * Giả lập cây tổ chức có lịch sử SCR-0103 (SVC-04 org-service, `/api/v1/org/units/*`) theo hợp đồng
 * `contracts/openapi/modules/org.yaml`: cây theo hai trục thời gian (`asOf`, `recordedAsOf`) có độ
 * sâu `depth`, cây con `/subtree`, tìm đơn vị kèm tổ tiên, chi tiết, lịch sử phiên bản, phân công;
 * sửa tạo phiên bản mới kèm `If-Match` (BR-01-06), thay đổi tổ chức theo quyết định (BR-01-07,
 * MSG-01-09), nhập Excel 202 + `Location: /api/v1/jobs/{id}` (tên tệp chứa `loi` cho job thất bại có
 * lỗi từng dòng ở `problem.errors`). Giả lập không đọc nội dung tệp Excel.
 */

let units: UnitVersion[] = [];
let assignments: AssignmentRow[] = [];
let rowVersions = new Map<string, number>();

function reset() {
  const s = seedOrgTree();
  units = s.units;
  assignments = s.assignments;
  rowVersions = new Map();
}
reset();

const today = () => new Date().toISOString().slice(0, 10);
const nowIso = () => new Date().toISOString();

interface View {
  asOf: string | null;
  /** Thời điểm cắt trên trục ghi nhận (ms). */
  cut: number;
  recorded: string | null;
}

/** `recordedAsOf` là `date-time`; vẫn nhận ngày trơn (cuối ngày giờ Việt Nam) cho lời gọi cũ. */
const parseRecorded = (rec: string) =>
  /^\d{4}-\d{2}-\d{2}$/.test(rec) ? Date.parse(`${rec}T23:59:59.999+07:00`) : Date.parse(rec);

function view(r: MockRequest): View | MockResponse {
  const asOf = r.params.get('asOf');
  const rec = r.params.get('recordedAsOf');
  const recMs = rec ? parseRecorded(rec) : null;
  const futureAsOf = !!asOf && asOf > today();
  const futureRec = recMs !== null && (Number.isNaN(recMs) || recMs > Date.now() + 86_400_000);
  if (futureAsOf || futureRec) {
    return problem(422, 'Ngày xem không được sau hôm nay.', {
      code: 'ORG-422-VALIDATION',
      errors: [
        {
          field: futureAsOf ? 'asOf' : 'recordedAsOf',
          message: 'Ngày xem không được sau hôm nay.',
        },
      ],
    });
  }
  return { asOf, cut: recMs ?? Date.now(), recorded: rec };
}
const isResponse = (x: View | MockResponse): x is MockResponse => 'status' in x;
const day = (v: View) => v.asOf ?? today();

const recordedAt = (u: { recordedFrom: string; recordedTo?: string | null }, cut: number) =>
  Date.parse(u.recordedFrom) <= cut && (!u.recordedTo || Date.parse(u.recordedTo) > cut);
const validAt = (u: { validFrom: string; validTo?: string | null }, d: string) =>
  u.validFrom <= d && (!u.validTo || d < u.validTo);

/** Phiên bản có hiệu lực tại `asOf` (mặc định hôm nay), đã ghi nhận tại thời điểm cắt. */
function pick(id: string, v: View): UnitVersion | undefined {
  return units.find((u) => u.id === id && recordedAt(u, v.cut) && validAt(u, day(v)));
}

function visible(v: View): UnitVersion[] {
  const ids = [...new Set(units.map((u) => u.id))];
  return ids.map((id) => pick(id, v)).filter((u): u is UnitVersion => !!u);
}

function activeAssignments(unitId: string, v: View): AssignmentRow[] {
  const unit = pick(unitId, v);
  const d = day(v);
  if (!unit) return [];
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

/** Nút cây kèm `levels` tầng con; hết tầng thì `children` rỗng và client nạp lười bằng `/subtree`. */
function node(u: UnitVersion, all: UnitVersion[], levels: number): OrgTreeNode {
  const kids = all.filter((c) => c.parentId === u.id);
  return {
    id: u.id,
    code: u.code,
    name: u.name,
    unitType: u.unitType,
    hasChildren: kids.length > 0,
    children: levels > 0 ? kids.map((c) => node(c, all, levels - 1)) : [],
  };
}

function depthOf(r: MockRequest, fallback: number): number {
  const d = Number(r.params.get('depth') ?? fallback);
  return Number.isInteger(d) && d >= 1 && d <= 6 ? d : fallback;
}

const treeBody = (v: View, roots: OrgTreeNode[]): OrgTree => ({
  asOf: day(v),
  recordedAsOf: v.recorded ? new Date(v.cut).toISOString() : nowIso(),
  readOnly: !!v.asOf || !!v.recorded,
  roots,
});

function tree(r: MockRequest): MockResponse {
  const v = view(r);
  if (isResponse(v)) return v;
  const all = visible(v);
  const depth = depthOf(r, 3);
  const roots = all.filter((u) => !u.parentId).map((u) => node(u, all, depth - 1));
  return ok(treeBody(v, roots));
}

function subtree(r: MockRequest): MockResponse {
  const v = view(r);
  if (isResponse(v)) return v;
  const root = pick(r.pathParams['id'], v);
  if (!root) return problem(404, 'Không tìm thấy đơn vị tại thời điểm hỏi.');
  return ok(treeBody(v, [node(root, visible(v), depthOf(r, 2))]));
}

const fold = (s: string) =>
  s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase();

function search(r: MockRequest): MockResponse {
  const v = view(r);
  if (isResponse(v)) return v;
  const q = fold(r.params.get('q') ?? '').trim();
  if (q.length < 2) {
    return problem(400, 'Nhập ít nhất 2 ký tự để tìm đơn vị.', {
      errors: [{ field: 'q', message: 'Nhập ít nhất 2 ký tự để tìm đơn vị.' }],
    });
  }
  const all = visible(v);
  const byId = new Map(all.map((u) => [u.id, u]));
  const items: OrgUnitSearchHit[] = all
    .filter((u) => fold(u.name).includes(q) || fold(u.code).includes(q))
    .slice(0, Math.min(Number(r.params.get('limit') ?? 20), 200))
    .map((u) => {
      const ancestors: UnitVersion[] = [];
      for (let p = u.parentId; p && byId.get(p); p = byId.get(p)!.parentId) {
        ancestors.unshift(byId.get(p)!);
      }
      return {
        id: u.id,
        code: u.code,
        name: u.name,
        unitType: u.unitType,
        ancestors: ancestors.map((a) => ({ id: a.id, code: a.code, name: a.name })),
      };
    });
  return ok({ items });
}

const groupName = (id: string) => SUBJECT_GROUPS.find((g) => g.id === id)?.name;

/** Tên đơn vị cha tại ngày `d`, dùng cho `parentName`. */
function parentName(parentId: string | null, d: string, cut: number): string | undefined {
  if (!parentId) return undefined;
  const p =
    units.find((u) => u.id === parentId && recordedAt(u, cut) && validAt(u, d)) ??
    units.filter((u) => u.id === parentId).at(-1);
  return p?.name;
}

function detail(id: string, v: View): OrgUnitDetail | null {
  const u = pick(id, v);
  if (!u) return null;
  return {
    id: u.id,
    versionId: u.versionId,
    code: u.code,
    nationalCode: u.nationalCode,
    name: u.name,
    shortName: u.shortName,
    level: u.level,
    unitType: u.unitType,
    subjectGroupId: u.subjectGroupId,
    subjectGroupName: groupName(u.subjectGroupId),
    flows: u.flows,
    ...(u.parentId
      ? { parentId: u.parentId, parentName: parentName(u.parentId, day(v), v.cut) }
      : {}),
    validFrom: u.validFrom,
    validTo: u.validTo,
    predecessorIds: u.predecessorIds,
    recordedFrom: u.recordedFrom,
    recordedByName: u.recordedBy,
    assignmentCount: activeAssignments(u.id, v).filter((a) => a.validFrom <= day(v)).length,
    rowVersion: rowVersions.get(u.id) ?? 0,
  };
}

function unit(r: MockRequest): MockResponse {
  const v = view(r);
  if (isResponse(v)) return v;
  const d = detail(r.pathParams['id'], v);
  return d
    ? { status: 200, body: d, headers: { ETag: `"${d.rowVersion}"` } }
    : problem(404, 'Không tìm thấy đơn vị tại thời điểm hỏi.');
}

/** Trang theo con trỏ đơn giản của giả lập: con trỏ là vị trí bắt đầu. */
function paged<T>(r: MockRequest, rows: T[]): MockResponse {
  const limit = Math.min(Math.max(Number(r.params.get('limit') ?? 20), 1), 200);
  const start = Number(r.params.get('cursor') ?? 0) || 0;
  const items = rows.slice(start, start + limit);
  const next = start + limit < rows.length ? String(start + limit) : null;
  return ok({ items, nextCursor: next });
}

function unitHistory(r: MockRequest): MockResponse {
  const v = view(r);
  if (isResponse(v)) return v;
  const id = r.pathParams['id'];
  if (!units.some((u) => u.id === id)) return problem(404, 'Không tìm thấy đơn vị.');
  const rows: OrgUnitVersion[] = units
    .filter((u) => u.id === id && recordedAt(u, v.cut))
    .sort((a, b) => (a.validFrom < b.validFrom ? 1 : -1))
    .map((u) => ({
      versionId: u.versionId,
      code: u.code,
      name: u.name,
      parentName: parentName(u.parentId, u.validFrom, v.cut),
      subjectGroupName: groupName(u.subjectGroupId),
      validFrom: u.validFrom,
      validTo: u.validTo,
      recordedFrom: u.recordedFirst ?? u.recordedFrom,
      recordedTo: null,
      recordedByName: u.recordedBy,
      changeType: u.changeType,
      changeOrder: u.changeOrder,
      changedFields: u.changedFields,
    }));
  return paged(r, rows);
}

function unitAssignments(r: MockRequest): MockResponse {
  const v = view(r);
  if (isResponse(v)) return v;
  if (!pick(r.pathParams['id'], v)) {
    return problem(404, 'Không tìm thấy đơn vị tại thời điểm hỏi.');
  }
  // phân công đặc biệt trước, phân công sinh tự động sau
  const rows: OrgUnitAssignment[] = activeAssignments(r.pathParams['id'], v)
    .sort((a, b) => Number(a.id.includes('-as-')) - Number(b.id.includes('-as-')))
    .map((a) => ({
      id: a.id,
      personId: a.personId,
      personName: a.personName,
      jobTitleName: a.jobTitleName,
      kind: a.kind,
      branch: a.branch,
      validFrom: a.validFrom,
      validTo: a.validTo,
    }));
  return paged(r, rows);
}

const fieldError = (title: string, field: string, code = 'ORG-422-VALIDATION') =>
  problem(422, title, { code, errors: [{ field, message: title }] });

const MSG_01_09 = 'Sáp nhập cần khai báo ít nhất một đơn vị tiền thân.';

/** Phiên bản đang ghi nhận (recorded_to = vô cực) hiệu lực tại ngày `d`. */
const currentAt = (id: string, d: string) =>
  units.find((u) => u.id === id && !u.recordedTo && validAt(u, d));

const ACTOR = 'Hoàng Đức Dũng';

/**
 * Ghi thay đổi theo hai trục thời gian, không ghi đè (BR-01-06): đóng trục ghi nhận của phiên bản
 * cũ, ghi lại đoạn trước `eff` và đoạn mới từ `eff` (`patch = 'END'` là kết thúc hiệu lực).
 */
function split(id: string, eff: string, patch: Partial<UnitVersion> | 'END') {
  const cur = currentAt(id, eff);
  if (!cur) return false;
  const now = nowIso();
  cur.recordedTo = now;
  const base = { ...cur, recordedFrom: now, recordedTo: null, recordedBy: ACTOR };
  if (cur.validFrom < eff) {
    units.push({
      ...base,
      versionId: crypto.randomUUID(),
      validTo: eff,
      recordedFirst: cur.recordedFirst ?? cur.recordedFrom,
    });
  }
  if (patch !== 'END') {
    units.push({
      ...base,
      changeType: undefined,
      changeOrder: undefined,
      changedFields: undefined,
      recordedFirst: undefined,
      ...patch,
      versionId: crypto.randomUUID(),
      validFrom: eff,
    });
  }
  rowVersions.set(id, (rowVersions.get(id) ?? 0) + 1);
  return true;
}

const activeChildren = (id: string, d: string) => [
  ...new Set(
    units.filter((u) => u.parentId === id && !u.recordedTo && validAt(u, d)).map((u) => u.id),
  ),
];

function moveAssignments(fromIds: string[], toId: string, eff: string): number {
  const cut: View = { asOf: eff, cut: Date.now(), recorded: null };
  let n = 0;
  for (const fromId of fromIds) {
    for (const a of activeAssignments(fromId, cut)) {
      assignments.push({
        ...a,
        id: crypto.randomUUID(),
        unitId: toId,
        validFrom: eff,
        recordedFrom: nowIso(),
      });
      n++;
    }
  }
  return n;
}

/** Kiểm thân `OrgUnitInput` (trường bắt buộc của hợp đồng, BR-01-05). */
function checkInput(body: Partial<OrgUnitInput>, prefix = ''): MockResponse | null {
  const f = (name: string) => `${prefix}${name}`;
  if (!body.code?.trim()) return fieldError('Nhập mã đơn vị.', f('code'));
  if (body.code.length > 30) return fieldError('Mã đơn vị tối đa 30 ký tự.', f('code'));
  if (!body.name?.trim()) return fieldError('Tên đơn vị không được trống.', f('name'));
  if (body.name.length > 255) return fieldError('Tên đơn vị tối đa 255 ký tự.', f('name'));
  if (!body.level) return fieldError('Chọn cấp đơn vị.', f('level'));
  if (!body.unitType) return fieldError('Chọn loại đơn vị.', f('unitType'));
  if (!SUBJECT_GROUPS.some((g) => g.id === body.subjectGroupId)) {
    return fieldError(
      'Đơn vị phải thuộc đúng một khối đối tượng theo Phụ lục 2A.',
      f('subjectGroupId'),
      'ORG-422-SUBJECT-GROUP',
    );
  }
  if (!body.flows?.length) return fieldError('Chọn ít nhất một luồng đánh giá.', f('flows'));
  if (!body.validFrom) return fieldError('Nhập ngày hiệu lực.', f('validFrom'));
  return null;
}

const CONTENT_FIELDS = [
  'code',
  'nationalCode',
  'name',
  'shortName',
  'level',
  'unitType',
  'subjectGroupId',
  'flows',
] as const;

function update(r: MockRequest): MockResponse {
  const id = r.pathParams['id'];
  const body = (r.body ?? {}) as Partial<OrgUnitInput>;
  const ifMatch = r.headers.get('If-Match');
  if (!ifMatch) {
    return problem(428, 'Yêu cầu cập nhật phải kèm If-Match.', {
      code: 'COMMON-428-PRECONDITION-REQUIRED',
    });
  }
  if (!units.some((u) => u.id === id && !u.recordedTo)) {
    return problem(404, 'Không tìm thấy đơn vị.');
  }
  if (ifMatch.replace(/^W\//, '').replace(/"/g, '') !== String(rowVersions.get(id) ?? 0)) {
    return problem(409, 'Đơn vị đã được người khác thay đổi. Vui lòng tải lại.', {
      code: 'ORG-409-VERSION-CONFLICT',
    });
  }
  const invalid = checkInput(body);
  if (invalid) return invalid;
  const input = body as OrgUnitInput;
  const cur = currentAt(id, input.validFrom);
  if (!cur) {
    return fieldError('Đơn vị chưa có hiệu lực tại ngày bắt đầu của thay đổi.', 'validFrom');
  }
  if (cur.syncedFrom) {
    return problem(
      409,
      'Đơn vị được đồng bộ từ CSDL CBCCVC; đồng chí sửa tại nguồn rồi đồng bộ lại.',
      { code: 'ORG-409-SYNCED' },
    );
  }
  if (units.some((u) => u.id !== id && !u.recordedTo && u.code === input.code.trim())) {
    return fieldError('Mã đơn vị đã được dùng cho đơn vị khác.', 'code', 'ORG-409-DUPLICATE');
  }
  const next: Partial<UnitVersion> = {
    code: input.code.trim(),
    nationalCode: input.nationalCode?.trim() || undefined,
    name: input.name.trim(),
    shortName: input.shortName?.trim() || undefined,
    level: input.level as UnitVersion['level'],
    unitType: input.unitType,
    subjectGroupId: input.subjectGroupId,
    flows: [...input.flows],
    ...(input.parentId ? { parentId: input.parentId } : {}),
  };
  const changed = [...CONTENT_FIELDS, 'parentId' as const].filter(
    (k) => k in next && JSON.stringify(next[k]) !== JSON.stringify(cur[k]),
  );
  split(id, input.validFrom, { ...next, changedFields: changed });
  const d = detail(id, { asOf: input.validFrom, cut: Date.now(), recorded: null })!;
  return { status: 200, body: d, headers: { ETag: `"${d.rowVersion}"` } };
}

function change(r: MockRequest): MockResponse {
  const id = r.pathParams['id'];
  const body = (r.body ?? {}) as Partial<OrgChangeRequest>;
  const type = body.changeType;
  const eff = body.effectiveOn;
  if (!type) return fieldError('Chọn loại thay đổi.', 'changeType');
  if (!eff) return fieldError('Nhập ngày hiệu lực.', 'effectiveOn');
  if (!body.orderNo?.trim()) return fieldError('Nhập số quyết định.', 'orderNo');
  if (!body.orderDate) return fieldError('Nhập ngày ban hành quyết định.', 'orderDate');
  if (body.orderDate > today()) {
    return fieldError('Ngày ban hành không được sau hôm nay.', 'orderDate');
  }
  const related = body.relatedUnitIds ?? [];
  const newUnits = body.newUnits ?? [];
  if (related.includes(id)) {
    return fieldError('Đơn vị liên quan không được trùng đơn vị đang thay đổi.', 'relatedUnitIds');
  }
  if ((type === 'SAP_NHAP' || type === 'HOP_NHAT') && !related.length) {
    return fieldError(MSG_01_09, 'relatedUnitIds', 'ORG-422-PREDECESSOR-REQUIRED');
  }
  if (type === 'THANH_LAP' && newUnits.length < 1) {
    return fieldError('Thành lập cần ít nhất một đơn vị mới.', 'newUnits');
  }
  if (type === 'HOP_NHAT' && newUnits.length !== 1) {
    return fieldError('Hợp nhất tạo đúng một đơn vị mới.', 'newUnits');
  }
  if (type === 'CHIA_TACH' && newUnits.length + related.length < 1) {
    return fieldError('Chia tách cần ít nhất một đơn vị kế thừa.', 'newUnits');
  }
  if (type === 'DOI_TEN' && !body.newName?.trim()) {
    return fieldError('Đổi tên cần nhập tên mới.', 'newName');
  }
  if ((type === 'SAP_NHAP' || type === 'GIAI_THE') && newUnits.length) {
    return fieldError('Loại thay đổi này không tạo đơn vị mới.', 'newUnits');
  }
  const cur = currentAt(id, eff);
  if (!cur) {
    return fieldError('Đơn vị chưa có hiệu lực tại ngày hiệu lực của quyết định.', 'effectiveOn');
  }
  for (const rel of related) {
    if (!currentAt(rel, eff)) {
      return fieldError(
        'Đơn vị liên quan chưa có hiệu lực tại ngày hiệu lực của quyết định.',
        'relatedUnitIds',
      );
    }
  }
  const codes = new Set(units.filter((u) => !u.recordedTo).map((u) => u.code));
  for (const [i, nu] of newUnits.entries()) {
    const invalid = checkInput({ ...nu, validFrom: nu.validFrom ?? eff }, `newUnits.${i}.`);
    if (invalid) return invalid;
    if (codes.has(nu.code.trim())) {
      return fieldError('Mã đơn vị đã được dùng cho đơn vị khác.', `newUnits.${i}.code`);
    }
    codes.add(nu.code.trim());
  }
  if (type === 'DOI_TEN' && eff <= cur.validFrom) {
    return fieldError('Ngày đổi tên phải sau hiệu lực hiện có.', 'effectiveOn');
  }
  if (type === 'GIAI_THE' && activeChildren(id, eff).length) {
    return fieldError(
      'Không thể giải thể đơn vị còn đơn vị cấp dưới đang hiệu lực.',
      'id',
      'ORG-422-HAS-ACTIVE-CHILDREN',
    );
  }

  const orderId = crypto.randomUUID();
  const order = { id: orderId, orderNo: body.orderNo.trim(), orderDate: body.orderDate };
  const stamp = { changeType: type, changeOrder: order };
  const created = newUnits.map((nu) => {
    const nid = crypto.randomUUID();
    const parentId = nu.parentId ?? (type === 'THANH_LAP' ? id : null);
    units.push({
      versionId: crypto.randomUUID(),
      id: nid,
      code: nu.code.trim(),
      nationalCode: nu.nationalCode?.trim() || undefined,
      name: nu.name.trim(),
      shortName: nu.shortName?.trim() || undefined,
      level: nu.level as UnitVersion['level'],
      unitType: nu.unitType,
      subjectGroupId: nu.subjectGroupId,
      flows: [...nu.flows],
      parentId,
      predecessorIds: type === 'HOP_NHAT' ? [id, ...related] : type === 'CHIA_TACH' ? [id] : [],
      validFrom: nu.validFrom && nu.validFrom > eff ? nu.validFrom : eff,
      validTo: nu.validTo ?? null,
      recordedFrom: nowIso(),
      recordedTo: null,
      recordedBy: ACTOR,
      ...stamp,
    });
    return nid;
  });
  const affected = new Set<string>(created);
  const end = (ids: string[], heir: string | undefined) => {
    for (const p of ids) {
      if (heir) for (const c of activeChildren(p, eff)) split(c, eff, { parentId: heir });
      split(p, eff, 'END');
      affected.add(p);
    }
  };
  switch (type) {
    case 'THANH_LAP':
      break;
    case 'DOI_TEN':
      split(id, eff, { name: body.newName!.trim(), ...stamp, changedFields: ['name'] });
      affected.add(id);
      break;
    case 'SAP_NHAP':
      split(id, eff, {
        predecessorIds: [...cur.predecessorIds, ...related],
        ...stamp,
        changedFields: ['predecessorIds'],
      });
      affected.add(id);
      end(related, id);
      break;
    case 'HOP_NHAT':
      end([id, ...related], created[0]);
      break;
    case 'CHIA_TACH':
      end([id], created[0] ?? related[0]);
      related.forEach((x) => affected.add(x));
      break;
    case 'GIAI_THE':
      end([id], undefined);
      break;
    default:
      return problem(400, 'Loại thay đổi không hợp lệ.');
  }
  if (body.moveAssignmentsTo) {
    const dest =
      type === 'HOP_NHAT' && body.moveAssignmentsTo === id && created.length
        ? created[0]
        : body.moveAssignmentsTo;
    const sources =
      type === 'SAP_NHAP'
        ? related
        : type === 'HOP_NHAT'
          ? [id, ...related]
          : [id, ...related].filter((u) => u !== dest);
    moveAssignments(sources, dest, eff);
  }
  // Hợp đồng: thay đổi có hiệu lực tương lai giữ ở `NHAP` tới ngày hiệu lực.
  const applied = eff <= today();
  const res: OrgChangeOrder = {
    id: orderId,
    changeType: type,
    orderNo: order.orderNo,
    effectiveOn: eff,
    status: applied ? 'DA_AP_DUNG' : 'NHAP',
    ...(applied ? { appliedAt: nowIso() } : {}),
    affectedUnitIds: [...affected],
  };
  return { status: 201, body: res };
}

/** Lỗi từng dòng của tệp tên chứa `loi` (BR-01-05, BR-01-07), cùng dạng `problem.errors` máy chủ. */
const IMPORT_ERRORS = [
  {
    field: 'rows.12.subjectGroup',
    message: 'Dòng 12, cột Khối đối tượng: Thiếu khối đối tượng theo Phụ lục 2A.',
    rule: 'BR-01-05',
  },
  {
    field: 'rows.27.parentCode',
    message: 'Dòng 27, cột Đơn vị cấp trên: Sáp nhập thiếu đơn vị tiền thân.',
    rule: 'BR-01-07',
  },
];

function startImport(r: MockRequest): MockResponse {
  const file = r.body instanceof FormData ? (r.body.get('file') as File | null) : null;
  if (!file) return fieldError('Chọn tệp Excel theo mẫu.', 'file');
  if (!/\.xlsx$/i.test(file.name)) return fieldError('Tệp phải là Excel .xlsx theo mẫu.', 'file');
  const bad = /loi/i.test(file.name);
  return startMockJob(
    'IMPORT',
    () =>
      bad
        ? {
            status: 'THAT_BAI',
            message: `Tệp có ${IMPORT_ERRORS.length} dòng lỗi, chưa nhập đơn vị nào.`,
            problem: {
              type: 'https://okrkpi.vn/problems/import-invalid',
              title: 'Tệp nhập chưa hợp lệ',
              status: 422,
              code: 'ORG-422-IMPORT-INVALID',
              detail: `Tệp có ${IMPORT_ERRORS.length} dòng lỗi, chưa nhập đơn vị nào.`,
              errors: IMPORT_ERRORS,
            },
          }
        : { message: 'Đã nhập 37 đơn vị.', resultLocation: '/api/v1/org/units/tree' },
    2,
    { message: 'Đang kiểm tra dữ liệu…', updatedAt: nowIso() },
  );
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
    { method: 'GET', pattern: '/api/v1/org/units/:id', handle: unit },
    { method: 'PUT', pattern: '/api/v1/org/units/:id', handle: update },
    { method: 'GET', pattern: '/api/v1/org/units/:id/subtree', handle: subtree },
    { method: 'GET', pattern: '/api/v1/org/units/:id/history', handle: unitHistory },
    { method: 'GET', pattern: '/api/v1/org/units/:id/assignments', handle: unitAssignments },
    { method: 'POST', pattern: '/api/v1/org/units/:id/changes', handle: change },
  ],
};
