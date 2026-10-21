/**
 * Logic thuần của SCR-1505 (BS-01): khai báo tham số mẫu ↔ JSON Schema `param_schema` (sinh biểu mẫu
 * tham số ở SCR-1504), đọc hình vẽ BPMNDI để vẽ sơ đồ SVG. Máy chủ kiểm lại khi lưu.
 */

export type ParamType = 'integer' | 'boolean' | 'string' | 'choice';

export interface ParamDef {
  key: string;
  title: string;
  type: ParamType;
  /** Giá trị mặc định dạng chuỗi như người dùng nhập. */
  def: string;
  min: string;
  max: string;
  /** Danh sách lựa chọn (kiểu `choice`), mỗi dòng một giá trị. */
  options: string;
}

export const PARAM_TYPES: { value: ParamType; label: string }[] = [
  { value: 'integer', label: 'Số nguyên' },
  { value: 'boolean', label: 'Có hoặc không' },
  { value: 'string', label: 'Chữ' },
  { value: 'choice', label: 'Chọn trong danh sách' },
];

interface JsonProp {
  type?: string;
  title?: string;
  default?: unknown;
  minimum?: number;
  maximum?: number;
  enum?: unknown[];
}

const str = (v: unknown) => (v === undefined || v === null ? '' : String(v));

export function paramsFromSchema(schema: unknown): ParamDef[] {
  const props = ((schema as { properties?: Record<string, JsonProp> })?.properties ?? {}) as Record<
    string,
    JsonProp
  >;
  return Object.entries(props).map(([key, p]) => ({
    key,
    title: p.title ?? '',
    type: p.enum ? 'choice' : p.type === 'integer' || p.type === 'boolean' ? p.type : 'string',
    def: str(p.default),
    min: str(p.minimum),
    max: str(p.maximum),
    options: (p.enum ?? []).map(str).join('\n'),
  }));
}

const optionList = (p: ParamDef) =>
  p.options
    .split('\n')
    .map((s) => s.trim())
    .filter(Boolean);

export function schemaFromParams(params: ParamDef[]): Record<string, unknown> {
  const properties: Record<string, JsonProp> = {};
  for (const p of params) {
    const prop: JsonProp = { title: p.title };
    if (p.type === 'integer') {
      prop.type = 'integer';
      if (p.min !== '') prop.minimum = Number(p.min);
      if (p.max !== '') prop.maximum = Number(p.max);
      if (p.def !== '') prop.default = Number(p.def);
    } else if (p.type === 'boolean') {
      prop.type = 'boolean';
      prop.default = p.def === 'true';
    } else {
      prop.type = 'string';
      if (p.type === 'choice') prop.enum = optionList(p);
      if (p.def !== '') prop.default = p.def;
    }
    properties[p.key] = prop;
  }
  return { type: 'object', properties };
}

const KEY = /^[a-z][A-Za-z0-9]{1,39}$/;

/** Lỗi khai báo tham số theo khoá tham số (rỗng là hợp lệ). */
export function paramErrors(params: ParamDef[]): Record<string, string> {
  const errs: Record<string, string> = {};
  const seen = new Set<string>();
  params.forEach((p, i) => {
    const id = `${i}`;
    const int = (s: string) => s === '' || /^-?\d+$/.test(s);
    if (!KEY.test(p.key))
      errs[id] = 'Tên kỹ thuật bắt đầu bằng chữ thường, chỉ gồm chữ và số, tối đa 40 ký tự.';
    else if (seen.has(p.key)) errs[id] = 'Tên kỹ thuật bị trùng.';
    else if (!p.title.trim()) errs[id] = 'Cần nhập tên hiển thị cho đơn vị.';
    else if (p.type === 'integer' && (!int(p.min) || !int(p.max) || !int(p.def)))
      errs[id] = 'Giá trị mặc định, nhỏ nhất, lớn nhất phải là số nguyên.';
    else if (p.type === 'integer' && p.min !== '' && p.max !== '' && Number(p.min) > Number(p.max))
      errs[id] = 'Giá trị nhỏ nhất lớn hơn giá trị lớn nhất.';
    else if (
      p.type === 'integer' &&
      p.def !== '' &&
      ((p.min !== '' && Number(p.def) < Number(p.min)) ||
        (p.max !== '' && Number(p.def) > Number(p.max)))
    )
      errs[id] = 'Giá trị mặc định nằm ngoài miền cho phép.';
    else if (p.type === 'choice' && optionList(p).length < 2)
      errs[id] = 'Cần ít nhất hai lựa chọn, mỗi dòng một lựa chọn.';
    else if (p.type === 'choice' && p.def !== '' && !optionList(p).includes(p.def))
      errs[id] = 'Giá trị mặc định phải là một trong các lựa chọn.';
    seen.add(p.key);
  });
  return errs;
}

export const newParam = (n: number): ParamDef => ({
  key: `thamSo${n}`,
  title: '',
  type: 'integer',
  def: '',
  min: '',
  max: '',
  options: '',
});

export interface DiagramShape {
  id: string;
  kind: string;
  name: string;
  x: number;
  y: number;
  w: number;
  h: number;
}

export interface DiagramEdge {
  id: string;
  name: string;
  points: string;
}

export interface Diagram {
  shapes: DiagramShape[];
  edges: DiagramEdge[];
  width: number;
  height: number;
}

const num = (e: Element, a: string) => Number(e.getAttribute(a) ?? 0);

/** Đọc hình vẽ BPMNDI (toạ độ phần tử, đường nối) để vẽ SVG; tệp không có hình vẽ trả sơ đồ rỗng. */
export function readDiagram(xml: string): Diagram {
  const doc = new DOMParser().parseFromString(xml ?? '', 'application/xml');
  const empty: Diagram = { shapes: [], edges: [], width: 0, height: 0 };
  if (doc.getElementsByTagName('parsererror').length) return empty;
  const byId = new Map<string, Element>();
  for (const e of Array.from(doc.getElementsByTagName('*'))) {
    const id = e.getAttribute('id');
    if (id && e.namespaceURI?.endsWith('/MODEL')) byId.set(id, e);
  }
  const shapes: DiagramShape[] = [];
  const edges: DiagramEdge[] = [];
  let width = 0;
  let height = 0;
  for (const s of Array.from(doc.getElementsByTagNameNS('*', 'BPMNShape'))) {
    const el = byId.get(s.getAttribute('bpmnElement') ?? '');
    const b = s.getElementsByTagNameNS('*', 'Bounds')[0];
    if (!el || !b) continue;
    const shape = {
      id: el.getAttribute('id')!,
      kind: el.localName,
      name: el.getAttribute('name') ?? '',
      x: num(b, 'x'),
      y: num(b, 'y'),
      w: num(b, 'width'),
      h: num(b, 'height'),
    };
    shapes.push(shape);
    width = Math.max(width, shape.x + shape.w);
    height = Math.max(height, shape.y + shape.h);
  }
  for (const e of Array.from(doc.getElementsByTagNameNS('*', 'BPMNEdge'))) {
    const el = byId.get(e.getAttribute('bpmnElement') ?? '');
    const pts = Array.from(e.getElementsByTagNameNS('*', 'waypoint')).map(
      (w) => `${num(w, 'x')},${num(w, 'y')}`,
    );
    if (!el || pts.length < 2) continue;
    edges.push({
      id: el.getAttribute('id')!,
      name: el.getAttribute('name') ?? '',
      points: pts.join(' '),
    });
  }
  return { shapes, edges, width: width + 40, height: height + 40 };
}

/** Tên loại phần tử cho người đọc (bảng chú thích và trình đọc màn hình). */
export const KIND_LABEL: Record<string, string> = {
  startEvent: 'Bắt đầu',
  endEvent: 'Kết thúc',
  userTask: 'Bước người xử lý',
  serviceTask: 'Bước máy xử lý',
  exclusiveGateway: 'Rẽ nhánh theo điều kiện',
  parallelGateway: 'Chia nhánh song song',
  callActivity: 'Gọi quy trình con',
  intermediateCatchEvent: 'Chờ hẹn giờ',
  boundaryEvent: 'Hẹn giờ quá hạn',
};

export type WfBlock = 'ky-so' | 'ky-lo' | 'leo-thang';

/** Khối dựng sẵn của nền tảng (BS-01 bảng màu). */
export const WF_BLOCKS: { id: WfBlock; label: string }[] = [
  { id: 'ky-so', label: 'Bước ký số' },
  { id: 'ky-lo', label: 'Ký lô' },
  { id: 'leo-thang', label: 'Leo thang quá hạn' },
];

const BPMN_NS = 'http://www.omg.org/spec/BPMN/20100524/MODEL';
const DI_NS = 'http://www.omg.org/spec/BPMN/20100524/DI';
const DC_NS = 'http://www.omg.org/spec/DD/20100524/DC';
const DD_NS = 'http://www.omg.org/spec/DD/20100524/DI';
const FLOWABLE_NS = 'http://flowable.org/bpmn';

/**
 * Chèn một khối dựng sẵn vào cuối nhánh chính (trước sự kiện kết thúc đầu tiên) hoặc, với "Leo thang
 * quá hạn", gắn hẹn giờ vào bước người xử lý cuối. Trả `null` khi tệp không có chỗ chèn.
 */
export function insertBlock(xml: string, block: WfBlock): string | null {
  const doc = new DOMParser().parseFromString(xml, 'application/xml');
  if (doc.getElementsByTagName('parsererror').length) return null;
  const proc = doc.getElementsByTagNameNS(BPMN_NS, 'process')[0];
  const plane = doc.getElementsByTagNameNS(DI_NS, 'BPMNPlane')[0];
  if (!proc || !plane) return null;
  const used = new Set(Array.from(doc.getElementsByTagName('*')).map((e) => e.getAttribute('id')));
  const id = (base: string) => {
    let i = 1;
    while (used.has(`${base}${i}`)) i++;
    used.add(`${base}${i}`);
    return `${base}${i}`;
  };
  const el = (tag: string, attrs: Record<string, string>, ns = BPMN_NS) => {
    const e = doc.createElementNS(ns, tag);
    for (const [k, v] of Object.entries(attrs)) {
      if (k.startsWith('flowable:')) e.setAttributeNS(FLOWABLE_NS, k, v);
      else e.setAttribute(k, v);
    }
    return e;
  };
  const boundsOf = (elementId: string) => {
    const s = Array.from(plane.getElementsByTagNameNS(DI_NS, 'BPMNShape')).find(
      (x) => x.getAttribute('bpmnElement') === elementId,
    );
    return s?.getElementsByTagNameNS(DC_NS, 'Bounds')[0] ?? null;
  };
  const addShape = (elementId: string, x: number, y: number, w: number, h: number) => {
    const s = el('bpmndi:BPMNShape', { id: `${elementId}_di`, bpmnElement: elementId }, DI_NS);
    s.appendChild(el('dc:Bounds', { x: `${x}`, y: `${y}`, width: `${w}`, height: `${h}` }, DC_NS));
    plane.appendChild(s);
  };
  const addFlow = (from: string, to: string, pts: [number, number][]) => {
    const fid = id('f_');
    proc.appendChild(el('sequenceFlow', { id: fid, sourceRef: from, targetRef: to }));
    const e = el('bpmndi:BPMNEdge', { id: `${fid}_di`, bpmnElement: fid }, DI_NS);
    for (const [x, y] of pts) e.appendChild(el('di:waypoint', { x: `${x}`, y: `${y}` }, DD_NS));
    plane.appendChild(e);
  };
  const n = (b: Element, a: string) => Number(b.getAttribute(a));

  if (block === 'leo-thang') {
    const tasks = Array.from(proc.getElementsByTagNameNS(BPMN_NS, 'userTask'));
    const last = tasks[tasks.length - 1];
    const lb = last && boundsOf(last.getAttribute('id')!);
    if (!last || !lb) return null;
    const timerId = id('quaHan');
    const notifyId = id('leoThang');
    const endId = id('ketThucLeoThang');
    const timer = el('boundaryEvent', {
      id: timerId,
      name: 'Quá hạn',
      attachedToRef: last.getAttribute('id')!,
      cancelActivity: 'false',
    });
    const def = el('timerEventDefinition', {});
    const dur = el('timeDuration', {});
    dur.textContent = 'P2D';
    def.appendChild(dur);
    timer.appendChild(def);
    proc.appendChild(timer);
    proc.appendChild(
      el('serviceTask', {
        id: notifyId,
        name: 'Báo cấp trên',
        'flowable:type': 'external-worker',
        'flowable:topic': 'leo-thang',
      }),
    );
    proc.appendChild(el('endEvent', { id: endId, name: 'Đã báo' }));
    const tx = n(lb, 'x') + n(lb, 'width') / 2 - 18;
    const ty = n(lb, 'y') + n(lb, 'height') - 18;
    addShape(timerId, tx, ty, 36, 36);
    addShape(notifyId, tx - 42, ty + 90, 120, 72);
    addShape(endId, tx + 128, ty + 108, 36, 36);
    addFlow(timerId, notifyId, [
      [tx + 18, ty + 36],
      [tx + 18, ty + 90],
    ]);
    addFlow(notifyId, endId, [
      [tx + 78, ty + 126],
      [tx + 128, ty + 126],
    ]);
  } else {
    const ends = Array.from(proc.getElementsByTagNameNS(BPMN_NS, 'endEvent'));
    const end = ends[0];
    const eb = end && boundsOf(end.getAttribute('id')!);
    if (!end || !eb) return null;
    const endId = end.getAttribute('id')!;
    const nodeId = id(block === 'ky-so' ? 'kySo' : 'kyLo');
    const task = el('serviceTask', {
      id: nodeId,
      name: block === 'ky-so' ? 'Bước ký số' : 'Ký lô',
      'flowable:type': 'external-worker',
      'flowable:topic': block,
    });
    if (block === 'ky-lo') {
      const mi = el('multiInstanceLoopCharacteristics', { isSequential: 'false' });
      const card = el('loopCardinality', {});
      card.textContent = '20';
      mi.appendChild(card);
      task.appendChild(mi);
    }
    proc.insertBefore(task, end);
    const x = n(eb, 'x');
    const cy = n(eb, 'y') + n(eb, 'height') / 2;
    addShape(nodeId, x, cy - 36, 120, 72);
    eb.setAttribute('x', `${x + 170}`);
    // Nối lại các đường đang vào sự kiện kết thúc sang khối mới.
    for (const f of Array.from(proc.getElementsByTagNameNS(BPMN_NS, 'sequenceFlow'))) {
      if (f.getAttribute('targetRef') !== endId) continue;
      f.setAttribute('targetRef', nodeId);
    }
    addFlow(nodeId, endId, [
      [x + 120, cy],
      [x + 170, cy],
    ]);
  }
  return new XMLSerializer().serializeToString(doc);
}
