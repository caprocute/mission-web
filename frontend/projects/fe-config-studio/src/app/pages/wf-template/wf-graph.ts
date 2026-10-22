/**
 * Thao tác đồ thị trên tệp BPMN 2.0 cho trình soạn kéo thả (SCR-1505, BS-01 "bảng phần tử được phép").
 * Tệp XML luôn là nguồn sự thật: mỗi thao tác đọc tệp, sửa đúng phần tử và hình vẽ BPMNDI liên quan
 * rồi trả lại tệp mới, nên phần trình soạn không hiểu (điều kiện, vòng lặp, thuộc tính mở rộng) được
 * giữ nguyên. Tệp chỉ dùng DOMParser/XMLSerializer, không sinh mã lúc chạy.
 */

const BPMN_NS = 'http://www.omg.org/spec/BPMN/20100524/MODEL';
const DI_NS = 'http://www.omg.org/spec/BPMN/20100524/DI';
const DC_NS = 'http://www.omg.org/spec/DD/20100524/DC';
const DD_NS = 'http://www.omg.org/spec/DD/20100524/DI';
const FLOWABLE_NS = 'http://flowable.org/bpmn';

export type WfNodeKind =
  | 'userTask'
  | 'serviceTask'
  | 'exclusiveGateway'
  | 'parallelGateway'
  | 'intermediateCatchEvent'
  | 'callActivity'
  | 'endEvent';

/** Bảng phần tử kéo vào sơ đồ; khối dựng sẵn vẫn đi qua `insertBlock`. */
export const WF_PALETTE: { kind: WfNodeKind; label: string }[] = [
  { kind: 'userTask', label: 'Bước người xử lý' },
  { kind: 'serviceTask', label: 'Bước máy xử lý' },
  { kind: 'exclusiveGateway', label: 'Rẽ nhánh theo điều kiện' },
  { kind: 'parallelGateway', label: 'Chia nhánh song song' },
  { kind: 'intermediateCatchEvent', label: 'Chờ hẹn giờ' },
  { kind: 'callActivity', label: 'Gọi quy trình con' },
  { kind: 'endEvent', label: 'Kết thúc' },
];

const SIZE: Record<string, [number, number]> = {
  exclusiveGateway: [50, 50],
  parallelGateway: [50, 50],
  startEvent: [36, 36],
  endEvent: [36, 36],
  intermediateCatchEvent: [36, 36],
  boundaryEvent: [36, 36],
};
export const sizeOf = (kind: string): [number, number] => SIZE[kind] ?? [120, 72];

const ID_BASE: Record<WfNodeKind, string> = {
  userTask: 'buoc',
  serviceTask: 'may',
  exclusiveGateway: 'reNhanh',
  parallelGateway: 'song',
  intermediateCatchEvent: 'cho',
  callActivity: 'goi',
  endEvent: 'ketThuc',
};

export interface GraphNode {
  id: string;
  kind: string;
  name: string;
  x: number;
  y: number;
  w: number;
  h: number;
  /** Bước mà hẹn giờ biên gắn vào. */
  attachedTo?: string;
}

export interface GraphFlow {
  id: string;
  source: string;
  target: string;
  name: string;
}

export interface Graph {
  nodes: GraphNode[];
  flows: GraphFlow[];
}

/** Thuộc tính sửa được trên bảng thuộc tính; trường rỗng nghĩa là bỏ thuộc tính. */
export interface WfProps {
  name: string;
  /** Nhóm người xử lý của bước người (vai trò). */
  role: string;
  /** Hàng đợi việc của bước máy. */
  topic: string;
  /** Thời hạn hẹn giờ ISO 8601, ví dụ P2D. */
  duration: string;
  /** Mã mẫu được gọi. */
  called: string;
  /** Điều kiện của đường nối ra từ cổng rẽ nhánh. */
  condition: string;
}

interface Ctx {
  doc: Document;
  proc: Element;
  plane: Element;
}

function open(xml: string): Ctx | null {
  const doc = new DOMParser().parseFromString(xml ?? '', 'application/xml');
  if (doc.getElementsByTagName('parsererror').length) return null;
  const proc = doc.getElementsByTagNameNS(BPMN_NS, 'process')[0];
  const plane = doc.getElementsByTagNameNS(DI_NS, 'BPMNPlane')[0];
  return proc && plane ? { doc, proc, plane } : null;
}

const save = (c: Ctx) => new XMLSerializer().serializeToString(c.doc);
const kids = (e: Element) => Array.from(e.children);
const byId = (c: Ctx, id: string) =>
  kids(c.proc).find((e) => e.getAttribute('id') === id && e.namespaceURI === BPMN_NS) ?? null;
const diOf = (c: Ctx, id: string) =>
  kids(c.plane).find((e) => e.getAttribute('bpmnElement') === id) ?? null;
const boundsOf = (c: Ctx, id: string) =>
  diOf(c, id)?.getElementsByTagNameNS(DC_NS, 'Bounds')[0] ?? null;
const n = (e: Element, a: string) => Number(e.getAttribute(a) ?? 0);

function make(c: Ctx, tag: string, attrs: Record<string, string>, ns = BPMN_NS): Element {
  const e = c.doc.createElementNS(ns, tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k.startsWith('flowable:')) e.setAttributeNS(FLOWABLE_NS, k, v);
    else e.setAttribute(k, v);
  }
  return e;
}

function freshId(c: Ctx, base: string): string {
  const used = new Set(
    Array.from(c.doc.getElementsByTagName('*')).map((e) => e.getAttribute('id')),
  );
  let i = 1;
  while (used.has(`${base}${i}`)) i++;
  return `${base}${i}`;
}

/** Đọc các phần tử có hình vẽ và các đường nối giữa chúng. */
export function readGraph(xml: string): Graph | null {
  const c = open(xml);
  if (!c) return null;
  const nodes: GraphNode[] = [];
  const flows: GraphFlow[] = [];
  for (const e of kids(c.proc)) {
    const id = e.getAttribute('id') ?? '';
    if (e.localName === 'sequenceFlow') {
      flows.push({
        id,
        source: e.getAttribute('sourceRef') ?? '',
        target: e.getAttribute('targetRef') ?? '',
        name: e.getAttribute('name') ?? '',
      });
      continue;
    }
    const b = boundsOf(c, id);
    if (!b) continue;
    nodes.push({
      id,
      kind: e.localName,
      name: e.getAttribute('name') ?? '',
      x: n(b, 'x'),
      y: n(b, 'y'),
      w: n(b, 'width'),
      h: n(b, 'height'),
      attachedTo: e.getAttribute('attachedToRef') ?? undefined,
    });
  }
  const ids = new Set(nodes.map((x) => x.id));
  return { nodes, flows: flows.filter((f) => ids.has(f.source) && ids.has(f.target)) };
}

/** Đường gấp khúc vuông góc từ cạnh phải nguồn (đáy với hẹn giờ biên) sang cạnh trái đích. */
function route(c: Ctx, source: string, target: string): [number, number][] {
  const a = boundsOf(c, source);
  const b = boundsOf(c, target);
  if (!a || !b) return [];
  const ty = n(b, 'y') + n(b, 'height') / 2;
  const tx = n(b, 'x');
  if (byId(c, source)?.localName === 'boundaryEvent') {
    const sx = n(a, 'x') + n(a, 'width') / 2;
    return [
      [sx, n(a, 'y') + n(a, 'height')],
      [sx, ty],
      [tx, ty],
    ];
  }
  const sx = n(a, 'x') + n(a, 'width');
  const sy = n(a, 'y') + n(a, 'height') / 2;
  if (sy === ty)
    return [
      [sx, sy],
      [tx, ty],
    ];
  const mx = Math.round((sx + tx) / 2);
  return [
    [sx, sy],
    [mx, sy],
    [mx, ty],
    [tx, ty],
  ];
}

function setWaypoints(c: Ctx, flowId: string, source: string, target: string) {
  let di = diOf(c, flowId);
  if (!di) {
    di = make(c, 'bpmndi:BPMNEdge', { id: `${flowId}_di`, bpmnElement: flowId }, DI_NS);
    c.plane.appendChild(di);
  }
  for (const w of Array.from(di.getElementsByTagNameNS(DD_NS, 'waypoint'))) w.remove();
  for (const [x, y] of route(c, source, target))
    di.appendChild(make(c, 'di:waypoint', { x: `${x}`, y: `${y}` }, DD_NS));
}

function rerouteAround(c: Ctx, ids: Set<string>) {
  for (const f of kids(c.proc).filter((e) => e.localName === 'sequenceFlow')) {
    const s = f.getAttribute('sourceRef') ?? '';
    const t = f.getAttribute('targetRef') ?? '';
    if (ids.has(s) || ids.has(t)) setWaypoints(c, f.getAttribute('id')!, s, t);
  }
}

/** Thêm một phần tử của bảng phần tử tại toạ độ (góc trên trái) cho trước. */
export function addNode(
  xml: string,
  kind: WfNodeKind,
  x: number,
  y: number,
): { xml: string; id: string } | null {
  const c = open(xml);
  if (!c) return null;
  const id = freshId(c, ID_BASE[kind]);
  const label = WF_PALETTE.find((p) => p.kind === kind)!.label;
  const attrs: Record<string, string> = { id, name: label };
  if (kind === 'serviceTask') attrs['flowable:type'] = 'external-worker';
  const el = make(c, kind, attrs);
  if (kind === 'intermediateCatchEvent') {
    const def = make(c, 'timerEventDefinition', {});
    const dur = make(c, 'timeDuration', {});
    dur.textContent = 'P1D';
    def.appendChild(dur);
    el.appendChild(def);
  }
  // Phần tử luồng đứng trước các đường nối để tệp dễ đọc.
  const firstFlow = kids(c.proc).find((e) => e.localName === 'sequenceFlow') ?? null;
  c.proc.insertBefore(el, firstFlow);
  const [w, h] = sizeOf(kind);
  const shape = make(c, 'bpmndi:BPMNShape', { id: `${id}_di`, bpmnElement: id }, DI_NS);
  shape.appendChild(
    make(
      c,
      'dc:Bounds',
      { x: `${Math.round(x)}`, y: `${Math.round(y)}`, width: `${w}`, height: `${h}` },
      DC_NS,
    ),
  );
  c.plane.appendChild(shape);
  return { xml: save(c), id };
}

/** Dời phần tử (cùng hẹn giờ biên gắn trên nó) và vẽ lại các đường nối liên quan. */
export function moveNode(xml: string, id: string, x: number, y: number): string | null {
  const c = open(xml);
  const b = c && boundsOf(c, id);
  if (!c || !b) return null;
  const dx = Math.round(x) - n(b, 'x');
  const dy = Math.round(y) - n(b, 'y');
  if (!dx && !dy) return xml;
  const moved = new Set([id]);
  for (const e of kids(c.proc))
    if (e.localName === 'boundaryEvent' && e.getAttribute('attachedToRef') === id)
      moved.add(e.getAttribute('id')!);
  for (const m of moved) {
    const mb = boundsOf(c, m);
    if (!mb) continue;
    mb.setAttribute('x', `${n(mb, 'x') + dx}`);
    mb.setAttribute('y', `${n(mb, 'y') + dy}`);
  }
  rerouteAround(c, moved);
  return save(c);
}

/** Lý do không cho nối, hoặc `null` khi nối được. */
export function connectError(graph: Graph, source: string, target: string): string | null {
  const s = graph.nodes.find((x) => x.id === source);
  const t = graph.nodes.find((x) => x.id === target);
  if (!s || !t) return 'Không tìm thấy bước cần nối.';
  if (source === target) return 'Không nối một bước với chính nó.';
  if (s.kind === 'endEvent') return 'Bước kết thúc không có đường ra.';
  if (t.kind === 'startEvent' || t.kind === 'boundaryEvent')
    return 'Không nối vào điểm bắt đầu hoặc hẹn giờ gắn trên bước.';
  if (graph.flows.some((f) => f.source === source && f.target === target))
    return 'Hai bước này đã được nối.';
  return null;
}

export function connect(
  xml: string,
  source: string,
  target: string,
): { xml: string; id: string } | null {
  const g = readGraph(xml);
  const c = open(xml);
  if (!g || !c || connectError(g, source, target)) return null;
  const id = freshId(c, 'f_');
  c.proc.appendChild(make(c, 'sequenceFlow', { id, sourceRef: source, targetRef: target }));
  setWaypoints(c, id, source, target);
  return { xml: save(c), id };
}

/** Xoá một bước (kèm đường nối và hẹn giờ biên của nó) hoặc một đường nối. */
export function removeElement(xml: string, id: string): string | null {
  const c = open(xml);
  const el = c && byId(c, id);
  if (!c || !el) return null;
  const gone = new Set([id]);
  if (el.localName !== 'sequenceFlow') {
    for (const e of kids(c.proc))
      if (e.localName === 'boundaryEvent' && e.getAttribute('attachedToRef') === id)
        gone.add(e.getAttribute('id')!);
    for (const f of kids(c.proc).filter((e) => e.localName === 'sequenceFlow'))
      if (
        gone.has(f.getAttribute('sourceRef') ?? '') ||
        gone.has(f.getAttribute('targetRef') ?? '')
      )
        gone.add(f.getAttribute('id')!);
  }
  for (const g of gone) {
    byId(c, g)?.remove();
    diOf(c, g)?.remove();
  }
  return save(c);
}

const child = (e: Element, local: string) =>
  Array.from(e.getElementsByTagNameNS(BPMN_NS, local))[0] ?? null;
const flowableAttr = (e: Element, name: string) => e.getAttributeNS(FLOWABLE_NS, name) ?? '';

export function readProps(xml: string, id: string): (WfProps & { kind: string }) | null {
  const c = open(xml);
  const el = c && byId(c, id);
  if (!el) return null;
  return {
    kind: el.localName,
    name: el.getAttribute('name') ?? '',
    role: flowableAttr(el, 'candidateGroups'),
    topic: flowableAttr(el, 'topic'),
    duration: child(el, 'timeDuration')?.textContent?.trim() ?? '',
    called: el.getAttribute('calledElement') ?? '',
    condition: child(el, 'conditionExpression')?.textContent?.trim() ?? '',
  };
}

/** Ghi thuộc tính; chỉ các trường hợp lệ với loại phần tử được ghi. */
export function setProps(xml: string, id: string, p: Partial<WfProps>): string | null {
  const c = open(xml);
  const el = c && byId(c, id);
  if (!c || !el) return null;
  const attr = (name: string, v: string | undefined, ns: string | null = null) => {
    if (v === undefined) return;
    const val = v.trim();
    if (ns) {
      if (val) el.setAttributeNS(ns, `flowable:${name}`, val);
      else el.removeAttributeNS(ns, name);
    } else if (val) el.setAttribute(name, val);
    else el.removeAttribute(name);
  };
  const kind = el.localName;
  attr('name', p.name);
  if (kind === 'userTask') attr('candidateGroups', p.role, FLOWABLE_NS);
  if (kind === 'serviceTask') attr('topic', p.topic, FLOWABLE_NS);
  if (kind === 'callActivity') attr('calledElement', p.called);
  if (p.duration !== undefined) {
    const dur = child(el, 'timeDuration');
    if (dur) dur.textContent = p.duration.trim();
  }
  if (kind === 'sequenceFlow' && p.condition !== undefined) {
    let cond = child(el, 'conditionExpression');
    const val = p.condition.trim();
    if (!val) cond?.remove();
    else {
      if (!cond) {
        cond = make(c, 'conditionExpression', {});
        cond.setAttributeNS(
          'http://www.w3.org/2001/XMLSchema-instance',
          'xsi:type',
          'tFormalExpression',
        );
        el.appendChild(cond);
      }
      cond.textContent = val;
    }
  }
  return save(c);
}
