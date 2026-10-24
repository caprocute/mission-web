import { lintBpmn } from '@vt/api-client/domains';
import { buildBpmn } from '../../../../../api-client/src/mock/workflow-templates/workflow-templates.mock';
import {
  addNode,
  connect,
  connectError,
  moveNode,
  readGraph,
  readProps,
  removeElement,
  setProps,
} from './wf-graph';
import { insertBlock, readDiagram } from './wf-model';

/** WEB-061: thao tác của trình soạn kéo thả giữ tệp BPMN hợp lệ và giữ hình vẽ khớp với phần tử. */
const base = () =>
  buildBpmn('mau', 'Mẫu thử', [
    { id: 'tuDanhGia', name: 'Tự đánh giá', kind: 'user' },
    { id: 'kySo', name: 'Bước ký số', kind: 'worker', attrs: 'flowable:topic="ky-so"' },
  ]);

describe('wf-graph', () => {
  it('đọc phần tử và đường nối kèm toạ độ', () => {
    const g = readGraph(base())!;
    expect(g.nodes.map((n) => n.kind)).toEqual([
      'startEvent',
      'userTask',
      'serviceTask',
      'endEvent',
    ]);
    expect(g.flows.length).toBe(3);
    expect(readGraph('<khong-hop-le')).toBeNull();
  });

  it('thêm phần tử từ bảng phần tử với hình vẽ và mặc định đạt bộ luật', () => {
    let xml = base();
    for (const kind of ['serviceTask', 'parallelGateway', 'intermediateCatchEvent'] as const) {
      const r = addNode(xml, kind, 100, 300)!;
      xml = r.xml;
      expect(readDiagram(xml).shapes.some((s) => s.id === r.id && s.y === 300)).toBe(true);
    }
    expect(lintBpmn(xml)).toEqual([]);
    expect(readProps(xml, 'cho1')!.duration).toBe('P1D');
  });

  it('nối hai bước, chặn nối sai và vẽ đường gấp khúc', () => {
    let xml = base();
    const added = addNode(xml, 'userTask', 300, 300)!;
    xml = added.xml;
    const g = readGraph(xml)!;
    expect(connectError(g, 'ketThuc', added.id)).toContain('kết thúc');
    expect(connectError(g, 'tuDanhGia', 'kySo')).toContain('đã được nối');
    expect(connectError(g, added.id, 'batDau')).not.toBeNull();
    const r = connect(xml, 'tuDanhGia', added.id)!;
    const edge = readDiagram(r.xml).edges.find((e) => e.id === r.id)!;
    expect(edge.points.split(' ').length).toBe(4);
    expect(connect(r.xml, 'tuDanhGia', added.id)).toBeNull();
  });

  it('dời bước kéo theo hẹn giờ biên và vẽ lại đường nối', () => {
    const xml = insertBlock(base(), 'leo-thang')!;
    const timer = readGraph(xml)!.nodes.find((n) => n.kind === 'boundaryEvent')!;
    const moved = moveNode(xml, 'tuDanhGia', 250, 200)!;
    const g = readGraph(moved)!;
    const t = g.nodes.find((n) => n.id === 'tuDanhGia')!;
    expect([t.x, t.y]).toEqual([250, 200]);
    const t2 = g.nodes.find((n) => n.id === timer.id)!;
    expect(t2.y - timer.y).toBe(200 - 104);
    const inFlow = readDiagram(moved).edges.find((e) => e.id === 'f_batDau')!;
    expect(inFlow.points.endsWith(`250,236`)).toBe(true);
  });

  it('xoá bước kéo theo đường nối, hẹn giờ biên và hình vẽ', () => {
    const xml = insertBlock(base(), 'leo-thang')!;
    const out = removeElement(xml, 'tuDanhGia')!;
    const g = readGraph(out)!;
    expect(g.nodes.some((n) => n.id === 'tuDanhGia' || n.kind === 'boundaryEvent')).toBe(false);
    expect(g.flows.every((f) => f.source !== 'tuDanhGia' && f.target !== 'tuDanhGia')).toBe(true);
    expect(out).not.toContain('bpmnElement="tuDanhGia"');
  });

  it('sửa thuộc tính theo loại phần tử', () => {
    let xml = setProps(base(), 'tuDanhGia', { name: 'Cán bộ tự đánh giá', role: 'can-bo' })!;
    xml = setProps(xml, 'kySo', { topic: 'ky-so-2', role: 'bo-qua' })!;
    expect(readProps(xml, 'tuDanhGia')).toMatchObject({
      name: 'Cán bộ tự đánh giá',
      role: 'can-bo',
    });
    expect(readProps(xml, 'kySo')).toMatchObject({ topic: 'ky-so-2', role: '' });
    xml = setProps(xml, 'f_batDau', { condition: '${diem >= 50}' })!;
    expect(readProps(xml, 'f_batDau')!.condition).toBe('${diem >= 50}');
    xml = setProps(xml, 'f_batDau', { condition: '' })!;
    expect(xml).not.toContain('conditionExpression');
    expect(lintBpmn(xml)).toEqual([]);
  });
});
